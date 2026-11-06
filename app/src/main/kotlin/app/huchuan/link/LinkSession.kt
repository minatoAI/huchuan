package app.huchuan.link

import android.content.Context
import android.util.Log
import app.huchuan.core.link.LinkDefaults
import app.huchuan.core.link.LinkEvents
import app.huchuan.core.link.LinkState
import app.huchuan.core.link.LinkTimeouts
import app.huchuan.core.link.ShareEndpoint
import app.huchuan.core.link.SignalClient
import app.huchuan.core.m1.DeviceIdentity
import app.huchuan.kernel.M1Runtime
import java.io.File
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 一条分享的在线会话：信令（SignalClient）+ 发送端（LinkSender）。
 *
 * 一个访客一个 PeerConnection；传完（浏览器 verify 通过）就发 bye{done} 结束这条信令，
 * 800 ms 后重开一条新的 —— Worker 的房间在分享者发 bye 时会关掉分享者自己的 socket，
 * 不重开的话链接会一直显示「对方暂时离线」（spike §3 的教训：先连 WS，再让链接可用）。
 */
internal class LinkSession(
    private val controller: LinkShareController,
    private val context: Context,
    private val record: StoredShare,
    private val identity: DeviceIdentity,
    private val file: File,
    private val sha256: String,
) : LinkEvents() {

    private val endpoint = ShareEndpoint(
        record.baseUrl,
        record.shareId,
        record.token,
        record.expiresAt,
        "",
        "",
        LinkDefaults.MAX_GUESTS,
    )

    private val lock = Any()
    private val timer = ScheduledThreadPoolExecutor(1) { job ->
        Thread(job, "huchuan-link-session").apply { isDaemon = true }
    }

    @Volatile
    private var closed = false

    @Volatile
    private var signal: SignalClient? = null

    @Volatile
    private var sender: LinkSender? = null

    private var restartPending = false
    private var restarts = 0
    private var delivered = false

    fun start() {
        if (closed) return
        update(LinkLive(LinkPhase.CONNECTING))
        openSignal()
    }

    fun close() {
        if (closed) return
        closed = true
        synchronized(lock) { restartPending = false }
        timer.shutdownNow()
        closeSender()
        val current = signal
        signal = null
        try {
            current?.close()
        } catch (_: Throwable) {
            /* 已关 */
        }
    }

    // ------------------------------------------------------------------ 信令事件

    override fun incoming(shareId: String, visitorId: String, epPub: String) {
        if (closed) return
        Log.i(
            M1Runtime.TAG,
            "event=link_incoming share=" + record.shareId.take(8) + " visitor=" + visitorId.take(8),
        )
        controller.bumpVisit(record.shareId)
        update(LinkLive(LinkPhase.INCOMING, visitorId = visitorId))
        closeSender()
        val client = signal ?: return
        val created = LinkSender(
            context = context,
            shareId = record.shareId,
            file = file,
            fileName = record.fileName,
            mime = record.mime,
            sha256 = sha256,
            signal = client,
            callbacks = SenderCallbacks(),
        )
        sender = created
        created.start()
    }

    override fun answer(sdp: String) {
        sender?.onAnswer(sdp)
    }

    override fun ice(candidate: String) {
        if (candidate.isNotEmpty()) sender?.onIce(candidate)
    }

    override fun failure(code: String) {
        if (closed) return
        Log.i(M1Runtime.TAG, "event=link_session_failure share=" + record.shareId.take(8) + " code=" + code)
        if (code == "revoked") {
            update(LinkLive(LinkPhase.REVOKED, failure = code))
            controller.onServerRevoked(record.shareId)
            return
        }
        update(LinkLive(LinkPhase.FAILED, failure = code))
        scheduleRestart(nextDelay())
    }

    override fun stateChanged(from: LinkState, to: LinkState) {
        if (closed) return
        if (to == LinkState.SIGNALING && (from == LinkState.CONNECTED || from == LinkState.TRANSFERRING)) {
            // 分享者的房间还活着：访客走了，回到等下一个访客，不重连自己（core §3.11）。
            closeSender()
            update(LinkLive(LinkPhase.WAITING))
            return
        }
        if (to == LinkState.SIGNALING && from == LinkState.IDLE) {
            update(LinkLive(LinkPhase.CONNECTING))
        }
    }

    override fun log(event: String, fields: Map<String, String>) {
        val extra = if (fields.isEmpty()) {
            ""
        } else {
            fields.entries.joinToString(" ") { it.key + "=" + it.value }
        }
        Log.i(M1Runtime.TAG, "event=" + event + " share=" + record.shareId.take(8) + " " + extra)
        // 分享者的 WS 打开 = 链接现在可以被取件；core 没有单独的 onOpen 事件，从日志口进。
        if (event == "link_open") {
            val phase = controller.liveOf(record.shareId).phase
            if (phase == LinkPhase.CONNECTING || phase == LinkPhase.FAILED) {
                update(LinkLive(LinkPhase.WAITING))
            }
        }
    }

    // ------------------------------------------------------------------ 发送端回调

    private inner class SenderCallbacks : LinkSender.Callbacks {

        override fun onOpen() {
            if (closed) return
            update(LinkLive(LinkPhase.SENDING, percent = 0))
        }

        override fun onProgress(sentBytes: Long, totalBytes: Long) {
            if (closed) return
            val percent = if (totalBytes <= 0L) 0 else ((sentBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
            update(LinkLive(LinkPhase.SENDING, percent = percent))
        }

        override fun onVerified(ok: Boolean, detail: String) {
            if (closed) return
            if (!ok) {
                Log.i(
                    M1Runtime.TAG,
                    "event=link_verify_failed share=" + record.shareId.take(8) + " detail=" + detail,
                )
                update(LinkLive(LinkPhase.FAILED, failure = "verify"))
                signal?.abort("verify")
                scheduleRestart(nextDelay())
                return
            }
            delivered = true
            restarts = 0
            update(LinkLive(LinkPhase.SENT, percent = 100))
            Log.i(M1Runtime.TAG, "event=link_delivered share=" + record.shareId.take(8))
            signal?.finish()
            scheduleRestart(RESTART_AFTER_DONE_MS)
        }

        override fun onFailed(code: String) {
            if (closed) return
            update(LinkLive(LinkPhase.FAILED, failure = code))
            signal?.abort(code)
            scheduleRestart(nextDelay())
        }

        override fun onChannelClosed() {
            if (closed) return
            // 通道断了但信令可能还挂着：这条会话收尾，换一条新信令继续等下一个访客。
            // 这里不直接 closeSender()：本回调在 WebRTC 线程上，关 DataChannel 交给
            // 重启定时器（restart -> closeSender）去做，避免在观察者回调里反注册。
            update(LinkLive(LinkPhase.WAITING))
            signal?.abort("peer-left")
            scheduleRestart(nextDelay())
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun openSignal() {
        if (closed) return
        // 分享者要挂着等访客，不能用 core 默认的 15 s 建链预算（那是「访客连上后多久必须配对成功」）。
        // 这里把预算设成分享剩余有效期：等待期不误判 FAILED；建链阶段的超时由发送端的
        // 通道 watchdog（LinkSender）与访客页自己的 25 s 兜底。
        val budget = (record.expiresAt - System.currentTimeMillis())
            .coerceIn(60_000L, LinkDefaults.TTL_MAX_SEC * 1000L)
        val timeouts = LinkTimeouts(signalBudgetMs = budget)
        val client = try {
            SignalClient.forSharer(endpoint, identity, OkHttpTransportFactory, this, timeouts)
        } catch (t: Throwable) {
            Log.w(M1Runtime.TAG, "event=link_session_bad share=" + record.shareId.take(8) + " error=" + t)
            update(LinkLive(LinkPhase.FAILED, failure = "bad-endpoint"))
            return
        }
        signal = client
        if (!client.start()) {
            update(LinkLive(LinkPhase.FAILED, failure = "closed"))
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (closed) return
        synchronized(lock) {
            if (restartPending) return
            restartPending = true
        }
        try {
            timer.schedule(
                {
                    restart()
                },
                delayMs.coerceAtLeast(1L),
                TimeUnit.MILLISECONDS,
            )
        } catch (_: Throwable) {
            synchronized(lock) { restartPending = false }
        }
    }

    private fun restart() {
        if (closed) return
        synchronized(lock) { restartPending = false }
        if (record.revoked) return
        restarts += 1
        closeSender()
        val old = signal
        signal = null
        try {
            old?.close()
        } catch (_: Throwable) {
            /* 已关 */
        }
        update(LinkLive(LinkPhase.CONNECTING))
        openSignal()
    }

    private fun nextDelay(): Long {
        val shift = restarts.coerceIn(0, 4)
        return (2_000L shl shift).coerceAtMost(30_000L)
    }

    private fun closeSender() {
        val current = sender
        sender = null
        try {
            current?.close()
        } catch (_: Throwable) {
            /* 已关 */
        }
    }

    private fun update(live: LinkLive) {
        controller.updateLive(record.shareId, live)
    }

    companion object {
        private const val RESTART_AFTER_DONE_MS = 800L
    }
}
