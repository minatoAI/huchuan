package app.huchuan.core.link

import app.huchuan.core.m1.B32
import app.huchuan.core.m1.DeviceIdentity
import app.huchuan.core.m1.Domains
import app.huchuan.core.m1.J
import app.huchuan.core.m1.Json
import app.huchuan.core.m1.hexToBytes
import app.huchuan.core.m1.jobj
import app.huchuan.core.m1.jstr
import app.huchuan.core.m1.toHex
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 链接分享的信令客户端（worker/src/signal.js + room.js，link-share.md §5）。
 *
 * 这一层只管「信令房间」与「状态机」，不实现 WebRTC：
 *   - 分享者：带签名查询串连 /signal?role=sharer，等 share-incoming，发 offer，
 *     收 answer；DataChannel 由平台适配层建立。
 *   - 访客：带 ep 连 /signal?role=guest，发 share-join，收 joined + offer，
 *     验签后把 offer 交给平台适配层，由它生成 answer 再调 sendAnswer。
 *   - 掉线：Worker 不重放 share-incoming（spike §4），访客必须重新 join。本类在
 *     peer-left / 异常断线后按 1/2/4/8/16 s 退避重开连接并重发 share-join；分享者同样
 *     重连，否则访客回来也找不到房间。
 *
 * 线程模型：所有状态跳转与消息处理都在一条 huchuan-link 线程上（Transport 回调只负责投递），
 * 因此 sendOffer / sendAnswer 可以安全地在事件回调里直接调用，不会和网络线程抢状态。
 *
 * 日志口径（§7）：不发 token 全文、不发 SDP、不发访客地址；token 只打前 4 字符。
 */
class SignalClient @JvmOverloads constructor(
    private val role: LinkRole,
    private val share: ShareEndpoint,
    private val identity: DeviceIdentity?,
    private val transportFactory: TransportFactory,
    private val events: LinkEvents,
    private val timeouts: LinkTimeouts,
    private val autoReconnect: Boolean = true,
) {

    init {
        require(share.shareId.matches(Regex("^[0-9a-f]{32}$"))) { "shareId must be 32 lowercase hex chars" }
        require(LinkProtocol.validToken(share.token)) { "token must be 26 Crockford B32 chars" }
        if (role == LinkRole.SHARER) {
            require(identity != null) { "sharer needs a device identity" }
        } else {
            require(share.sharerPubHex.matches(Regex("^[0-9a-fA-F]{128}$"))) { "guest needs sharer pub (128 hex)" }
            require(share.sharerDeviceId.isNotEmpty()) { "guest needs sharer deviceId" }
        }
    }

    @Volatile
    var state: LinkState = LinkState.IDLE
        private set

    @Volatile
    var failureReason: String = ""
        private set

    @Volatile
    var reconnectAttempts: Int = 0
        private set

    @Volatile
    var incomingVisitorId: String = ""
        private set

    @Volatile
    var offerSdp: String = ""
        private set

    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val stateLock = Object()
    private val inLoop = ThreadLocal.withInitial { false }
    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "huchuan-link-" + share.shareId.take(6)).apply { isDaemon = true }
    }

    private val guestEp: String = LinkProtocol.newOpaqueId(12)

    private var generation = 0
    private var transport: Transport? = null
    private var transportOpen = false
    private var offerReceived = false
    private var everConnected = false
    private var reconnectPending = false
    private var signalTimeout: ScheduledFuture<*>? = null
    private var transferTimeout: ScheduledFuture<*>? = null
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var closeFuture: ScheduledFuture<*>? = null

    /** 开始连接/加入房间。幂等；返回 false 表示客户端已经关闭。 */
    fun start(): Boolean {
        if (closed.get()) return false
        if (!started.compareAndSet(false, true)) return true
        events.log(
            "link_start",
            mapOf(
                "role" to roleName(),
                "share" to share.shareId.take(8),
                "guest_limit" to share.guestLimit.toString(),
            ),
        )
        submit { beginAttempt(0) }
        return true
    }

    fun isTerminal(): Boolean = state == LinkState.DONE || state == LinkState.FAILED

    /**
     * 发 offer（仅分享者）。dtlsFp 从 SDP 的 a=fingerprint: 行提取，
     * 签名域 huchuan/share1/v1 = [token, dtlsFp, expiresAt]（§5-6）。
     * 没有指纹、连接未打开或角色不对时返回 false，不抛异常。
     */
    fun sendOffer(sdp: String): Boolean = callOnLoop(false) {
        if (closed.get() || isTerminal() || role != LinkRole.SHARER) return@callOnLoop false
        val id = identity ?: return@callOnLoop false
        val dtlsFp = LinkProtocol.fingerprintOfSdp(sdp)
        if (dtlsFp.isEmpty()) {
            events.log("link_offer_no_fingerprint", emptyMap())
            return@callOnLoop false
        }
        val sig = id.sign(Domains.LINK_SHARE, LinkProtocol.offerFields(share.token, dtlsFp, share.expiresAt))
        val frame = jobj(
            "t" to jstr("offer"),
            "sdp" to jstr(sdp),
            "sig" to jstr(sig),
        )
        if (!sendFrame(frame)) return@callOnLoop false
        events.log(
            "link_send",
            mapOf(
                "t" to "offer",
                "sdp_bytes" to sdp.toByteArray(Charsets.UTF_8).size.toString(),
                "fp_len" to dtlsFp.length.toString(),
                "sig_len" to sig.length.toString(),
            ),
        )
        true
    }

    /** 发 answer（仅访客）。发送成功即视为 SDP 交换完成，状态进入 CONNECTED。 */
    fun sendAnswer(sdp: String): Boolean = callOnLoop(false) {
        if (closed.get() || isTerminal() || role != LinkRole.GUEST) return@callOnLoop false
        if (!offerReceived) {
            events.log("link_answer_without_offer", emptyMap())
            return@callOnLoop false
        }
        val frame = jobj("t" to jstr("answer"), "sdp" to jstr(sdp))
        if (!sendFrame(frame)) return@callOnLoop false
        events.log("link_send", mapOf("t" to "answer", "sdp_bytes" to sdp.toByteArray(Charsets.UTF_8).size.toString()))
        markConnected("answer-sent")
        true
    }

    /** 转发一条 ICE candidate（非 trickle 设计下它是 SDP 之后补的候选）。 */
    fun sendIce(candidate: String): Boolean = callOnLoop(false) {
        if (closed.get() || isTerminal()) return@callOnLoop false
        val frame = jobj("t" to jstr("ice"), "candidate" to jstr(candidate))
        if (!sendFrame(frame)) return@callOnLoop false
        events.log("link_send", mapOf("t" to "ice", "bytes" to candidate.length.toString()))
        true
    }

    fun sendBye(code: String): Boolean = callOnLoop(false) {
        if (closed.get() || isTerminal()) return@callOnLoop false
        val frame = jobj("t" to jstr("bye"), "code" to jstr(code))
        if (!sendFrame(frame)) return@callOnLoop false
        events.log("link_send", mapOf("t" to "bye", "code" to code))
        true
    }

    /**
     * 平台适配层在 DataChannel 打开时调用：CONNECTED → TRANSFERRING，并启动 90 s
     * 无进展超时（§5-10）。传输过程中收到字节/ack 时调 [noteProgress] 续期。
     */
    fun markTransferring() {
        submit {
            if (closed.get() || isTerminal()) return@submit
            if (state != LinkState.CONNECTED) {
                events.log("link_transfer_ignored", mapOf("state" to state.name.lowercase()))
                return@submit
            }
            setState(LinkState.TRANSFERRING)
            scheduleTransferTimeout()
            events.log("link_transferring", mapOf("idle_ms" to timeouts.transferIdleMs.toString()))
        }
    }

    /** 传输有进展（收到/发出一个块或 ack）：把 90 s 空闲预算重新计时。 */
    fun noteProgress() {
        submit {
            if (state == LinkState.TRANSFERRING && !closed.get()) scheduleTransferTimeout()
        }
    }

    /** 正常结束：发 bye{done}，状态置 DONE，稍后关闭连接。 */
    fun finish() {
        submit {
            if (closed.get() || isTerminal()) return@submit
            val frame = jobj("t" to jstr("bye"), "code" to jstr("done"))
            sendFrame(frame)
            events.log("link_send", mapOf("t" to "bye", "code" to "done"))
            setState(LinkState.DONE)
            cancelTimers()
            scheduleTransportClose()
        }
    }

    /** 平台侧失败（例如 DataChannel 打不开）时直接收尾；不抛异常。 */
    fun abort(code: String) {
        submit { fail(code) }
    }

    /** 关闭客户端。不是终态跳转：调用方在断言/退出时用它释放线程与连接。 */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelTimers()
        val current = transport
        transport = null
        transportOpen = false
        try { current?.close(1000, "close") } catch (_: Throwable) {}
        events.log("link_close", mapOf("role" to roleName(), "state" to state.name.lowercase()))
        executor.shutdownNow()
        synchronized(stateLock) { stateLock.notifyAll() }
    }

    /** 阻塞等待某个状态（测试/宿主自检用）；超时返回 false。 */
    fun awaitState(target: LinkState, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        synchronized(stateLock) {
            while (state != target) {
                if (closed.get()) return state == target
                val left = deadline - System.nanoTime()
                if (left <= 0L) return state == target
                try {
                    stateLock.wait(left / 1_000_000L, (left % 1_000_000L).toInt())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return state == target
                }
            }
        }
        return true
    }

    /** 等 DONE/FAILED 任一终态；超时返回当前是否终态。 */
    fun awaitTerminal(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        synchronized(stateLock) {
            while (!isTerminal()) {
                if (closed.get()) return isTerminal()
                val left = deadline - System.nanoTime()
                if (left <= 0L) return isTerminal()
                try {
                    stateLock.wait(left / 1_000_000L, (left % 1_000_000L).toInt())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return isTerminal()
                }
            }
        }
        return true
    }

    /* ---------------------------------------------------------------- 状态机（单线程） */

    private fun beginAttempt(attempt: Int) {
        if (closed.get() || isTerminal()) return
        reconnectPending = false
        generation += 1
        val gen = generation
        transportOpen = false
        offerReceived = false
        offerSdp = ""
        setState(LinkState.SIGNALING)
        events.log(
            "link_connecting",
            mapOf(
                "role" to roleName(),
                "attempt" to attempt.toString(),
                "share" to share.shareId.take(8),
                "budget_ms" to timeouts.signalBudgetMs.toString(),
            ),
        )
        scheduleSignalTimeout(gen)

        val url = try {
            buildUrl()
        } catch (e: Throwable) {
            fail("bad-url")
            return
        }
        val created = try {
            transportFactory.create()
        } catch (e: Throwable) {
            fail("no-transport")
            return
        }
        transport = created
        try {
            created.open(url, listener(gen))
        } catch (e: Throwable) {
            handleClosed(gen, -1, "open:" + (e.message ?: e.toString()))
        }
    }

    private fun listener(gen: Int): Transport.Listener = object : Transport.Listener {
        override fun onOpen() = submit { handleOpen(gen) }

        override fun onMessage(text: String) = submit { handleMessage(gen, text) }

        override fun onClosed(code: Int, reason: String) = submit { handleClosed(gen, code, reason) }
    }

    private fun handleOpen(gen: Int) {
        if (gen != generation || closed.get() || isTerminal()) return
        transportOpen = true
        events.log("link_open", mapOf("role" to roleName(), "share" to share.shareId.take(8)))
        if (role == LinkRole.GUEST) {
            val epPub = identity?.pub64?.toHex() ?: ""
            val frame = jobj(
                "t" to jstr("share-join"),
                "token" to jstr(share.token),
                "epPub" to jstr(epPub),
            )
            if (!sendFrame(frame)) {
                events.log("link_send_failed", mapOf("t" to "share-join"))
                fail("send-failed")
                return
            }
            events.log("link_send", mapOf("t" to "share-join", "token_masked" to LinkProtocol.mask(share.token)))
        }
    }

    private fun handleMessage(gen: Int, text: String) {
        if (gen != generation || closed.get() || isTerminal()) return
        val msg = try {
            Json.parse(text)
        } catch (_: Throwable) {
            events.log("link_bad_frame", mapOf("bytes" to text.length.toString()))
            return
        }
        val type = msg.str("t") ?: return
        events.log("link_recv", mapOf("t" to type))
        when (type) {
            "joined" -> {
                val visitor = msg.str("visitorId") ?: ""
                incomingVisitorId = visitor
                events.log(
                    "link_joined",
                    mapOf("share" to (msg.str("shareId") ?: "").take(8), "visitor" to visitor.take(8)),
                )
            }

            "share-incoming" -> {
                val visitor = msg.str("visitorId") ?: ""
                incomingVisitorId = visitor
                events.incoming(
                    msg.str("shareId") ?: share.shareId,
                    visitor,
                    msg.str("epPub") ?: "",
                )
            }

            "offer" -> handleOffer(msg)

            "answer" -> {
                if (role != LinkRole.SHARER) return
                val sdp = msg.str("sdp") ?: ""
                events.log("link_recv_answer", mapOf("sdp_bytes" to sdp.toByteArray(Charsets.UTF_8).size.toString()))
                events.answer(sdp)
                markConnected("answer-received")
            }

            "ice" -> events.ice(msg.str("candidate") ?: "")

            "bye" -> handleBye(msg.str("code") ?: "")

            "err" -> {
                val code = msg.str("code") ?: "err"
                events.log("link_err_frame", mapOf("code" to code))
                fail(code)
            }

            else -> events.log("link_unknown_frame", mapOf("t" to type.take(32)))
        }
    }

    private fun handleOffer(msg: J) {
        if (role != LinkRole.GUEST) {
            events.log("link_offer_ignored", mapOf("role" to roleName()))
            return
        }
        val sdp = msg.str("sdp") ?: ""
        val sig = msg.str("sig") ?: ""
        if (sdp.isEmpty() || sig.isEmpty()) {
            fail("bad-offer")
            return
        }
        if (!verifyOffer(sdp, sig)) {
            events.log("link_offer_rejected", mapOf("reason" to "bad-signature-or-fingerprint"))
            fail("bad-offer")
            return
        }
        offerSdp = sdp
        offerReceived = true
        events.log(
            "link_offer_verified",
            mapOf(
                "sdp_bytes" to sdp.toByteArray(Charsets.UTF_8).size.toString(),
                "fp_len" to LinkProtocol.fingerprintOfSdp(sdp).length.toString(),
            ),
        )
        events.offer(sdp, sig)
    }

    private fun verifyOffer(sdp: String, sig: String): Boolean {
        val dtlsFp = LinkProtocol.fingerprintOfSdp(sdp)
        if (dtlsFp.isEmpty()) return false
        if (share.expiresAt > 0L && System.currentTimeMillis() > share.expiresAt) return false
        val pub = try {
            hexToBytes(share.sharerPubHex)
        } catch (_: Throwable) {
            return false
        }
        val derived = LinkProtocol.normalizeDeviceId(DeviceIdentity.deviceIdOf(pub))
        if (derived != LinkProtocol.normalizeDeviceId(share.sharerDeviceId)) return false
        return DeviceIdentity.verify(
            pub,
            sig,
            Domains.LINK_SHARE,
            LinkProtocol.offerFields(share.token, dtlsFp, share.expiresAt),
        )
    }

    private fun handleBye(code: String) {
        events.log("link_bye", mapOf("code" to code.ifEmpty { "?" }))
        when (code) {
            "done" -> markDone("bye-done")
            "gone" -> fail("revoked")
            "offline" -> {
                if (everConnected && autoReconnect) scheduleReconnect("offline") else fail("offline")
            }
            "peer-left" -> {
                if (role == LinkRole.SHARER) {
                    // 分享者的房间还活着：访客走了，回到等下一个访客的状态，不重连自己。
                    cancelTransferTimeout()
                    if (state == LinkState.CONNECTED || state == LinkState.TRANSFERRING) {
                        setState(LinkState.SIGNALING)
                    }
                } else if (autoReconnect) {
                    scheduleReconnect("peer-left")
                } else {
                    fail("peer-left")
                }
            }
            else -> fail(code.ifEmpty { "bye" })
        }
    }

    private fun handleClosed(gen: Int, code: Int, reason: String) {
        if (gen != generation || closed.get()) return
        transportOpen = false
        if (isTerminal()) return
        events.log(
            "link_closed",
            mapOf(
                "code" to code.toString(),
                "reason" to safe(reason),
                "state" to state.name.lowercase(),
            ),
        )
        cancelSignalTimeout()
        cancelTransferTimeout()

        // 握手阶段的状态码：Worker 在分享已撤销/过期/不属于自己时直接回 410/403/400。
        if (code == 410) {
            fail("revoked")
            return
        }
        if (code == 403 || code == 400) {
            fail("rejected")
            return
        }
        if (code == 4001 || code == 4005) {
            fail("revoked")
            return
        }
        if (everConnected && autoReconnect) {
            scheduleReconnect("closed")
            return
        }
        fail(closeReason(code, reason))
    }

    private fun markConnected(reason: String) {
        if (closed.get() || isTerminal()) return
        everConnected = true
        cancelSignalTimeout()
        if (state != LinkState.CONNECTED && state != LinkState.TRANSFERRING) {
            setState(LinkState.CONNECTED)
        }
        events.log("link_connected", mapOf("role" to roleName(), "reason" to reason))
    }

    private fun markDone(reason: String) {
        if (closed.get() || isTerminal()) return
        events.log("link_done", mapOf("reason" to reason))
        setState(LinkState.DONE)
        cancelTimers()
        scheduleTransportClose()
    }

    private fun scheduleReconnect(reason: String) {
        if (closed.get() || isTerminal()) return
        if (!autoReconnect) {
            fail(reason)
            return
        }
        if (reconnectPending) return
        val attempt = reconnectAttempts + 1
        if (attempt > timeouts.maxReconnectAttempts) {
            fail("reconnect-exhausted")
            return
        }
        reconnectAttempts = attempt
        reconnectPending = true
        invalidateTransport()
        val delay = backoff(attempt)
        setState(LinkState.SIGNALING)
        events.log(
            "link_reconnect",
            mapOf(
                "attempt" to attempt.toString(),
                "delay_ms" to delay.toString(),
                "reason" to reason,
                "role" to roleName(),
            ),
        )
        try {
            reconnectFuture = executor.schedule(
                {
                    runTask {
                        if (!closed.get()) {
                            reconnectPending = false
                            beginAttempt(attempt)
                        }
                    }
                },
                delay,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: RejectedExecutionException) {
            reconnectPending = false
        }
    }

    private fun invalidateTransport() {
        generation += 1
        transportOpen = false
        val current = transport
        transport = null
        try { current?.close(1000, "reconnect") } catch (_: Throwable) {}
    }

    private fun fail(code: String) {
        if (closed.get() || isTerminal()) return
        failureReason = code
        events.log("link_failed", mapOf("code" to code, "state" to state.name.lowercase()))
        setState(LinkState.FAILED)
        events.failure(code)
        cancelTimers()
        closeTransportQuietly(1000, code.take(60))
    }

    private fun scheduleSignalTimeout(gen: Int) {
        cancelSignalTimeout()
        try {
            signalTimeout = executor.schedule(
                {
                    runTask {
                        if (gen == generation && state == LinkState.SIGNALING && !closed.get() && !isTerminal()) {
                            events.log(
                                "link_timeout",
                                mapOf("step" to "signal", "budget_ms" to timeouts.signalBudgetMs.toString()),
                            )
                            fail("connect-failed")
                        }
                    }
                },
                timeouts.signalBudgetMs,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: RejectedExecutionException) {
            /* 已关闭 */
        }
    }

    private fun scheduleTransferTimeout() {
        cancelTransferTimeout()
        try {
            transferTimeout = executor.schedule(
                {
                    runTask {
                        if (state == LinkState.TRANSFERRING && !closed.get()) {
                            events.log(
                                "link_timeout",
                                mapOf("step" to "transfer", "budget_ms" to timeouts.transferIdleMs.toString()),
                            )
                            fail("transfer-timeout")
                        }
                    }
                },
                timeouts.transferIdleMs,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: RejectedExecutionException) {
            /* 已关闭 */
        }
    }

    private fun scheduleTransportClose() {
        cancelCloseFuture()
        try {
            closeFuture = executor.schedule(
                {
                    runTask {
                        closeTransportQuietly(1000, "done")
                    }
                },
                timeouts.closeDelayMs,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: RejectedExecutionException) {
            /* 已关闭 */
        }
    }

    private fun cancelSignalTimeout() {
        signalTimeout?.cancel(false)
        signalTimeout = null
    }

    private fun cancelTransferTimeout() {
        transferTimeout?.cancel(false)
        transferTimeout = null
    }

    private fun cancelCloseFuture() {
        closeFuture?.cancel(false)
        closeFuture = null
    }

    private fun cancelTimers() {
        cancelSignalTimeout()
        cancelTransferTimeout()
        cancelCloseFuture()
        reconnectFuture?.cancel(false)
        reconnectFuture = null
    }

    private fun closeTransportQuietly(code: Int, reason: String) {
        transportOpen = false
        val current = transport
        transport = null
        try { current?.close(code, reason) } catch (_: Throwable) {}
    }

    private fun sendFrame(frame: J.Obj): Boolean {
        val current = transport ?: return false
        if (!transportOpen) return false
        return try {
            current.send(Json.write(frame))
        } catch (e: Throwable) {
            events.log("link_send_error", mapOf("error" to safe(e.message ?: e.toString())))
            false
        }
    }

    private fun buildUrl(): String {
        val base = LinkProtocol.signalUrl(share.baseUrl)
        val shareQuery = "share=" + LinkProtocol.urlEncode(share.shareId)
        return when (role) {
            LinkRole.SHARER -> {
                val id = identity ?: throw IllegalStateException("sharer identity missing")
                val dev = LinkProtocol.normalizeDeviceId(id.deviceId)
                val ts = System.currentTimeMillis() / 1000L
                val nonce = LinkProtocol.newOpaqueId(12)
                val sig = id.sign(Domains.LINK_WS, LinkProtocol.wsFields(dev, nonce, ts))
                "$base?role=sharer&$shareQuery&dev=" + LinkProtocol.urlEncode(dev) +
                    "&ts=" + ts + "&nonce=" + LinkProtocol.urlEncode(nonce) +
                    "&sig=" + LinkProtocol.urlEncode(sig)
            }
            LinkRole.GUEST -> "$base?role=guest&$shareQuery&ep=" + LinkProtocol.urlEncode(guestEp)
        }
    }

    private fun backoff(attempt: Int): Long {
        var delay = timeouts.reconnectBaseMs.coerceAtLeast(1L)
        repeat((attempt - 1).coerceAtLeast(0)) {
            delay = if (delay > timeouts.reconnectMaxMs / 2L) timeouts.reconnectMaxMs else delay * 2L
        }
        return delay.coerceIn(1L, timeouts.reconnectMaxMs)
    }

    private fun setState(next: LinkState) {
        val prev: LinkState
        synchronized(stateLock) {
            if (state == next) return
            prev = state
            state = next
            stateLock.notifyAll()
        }
        events.log(
            "link_state",
            mapOf("from" to prev.name.lowercase(), "to" to next.name.lowercase(), "role" to roleName()),
        )
        events.stateChanged(prev, next)
    }

    private fun submit(task: () -> Unit) {
        if (closed.get()) return
        if (inLoop.get()) {
            task()
            return
        }
        try {
            executor.execute { runTask(task) }
        } catch (_: RejectedExecutionException) {
            /* 已关闭 */
        }
    }

    private fun runTask(task: () -> Unit) {
        inLoop.set(true)
        try {
            task()
        } catch (e: Throwable) {
            events.log("link_loop_error", mapOf("error" to safe(e.message ?: e.toString())))
        } finally {
            inLoop.set(false)
        }
    }

    /** 在状态机线程上同步执行；从事件回调里调用时直接执行，避免自己等自己。 */
    private fun <T> callOnLoop(fallback: T, task: () -> T): T {
        if (closed.get()) return fallback
        if (inLoop.get()) return task()
        var result = fallback
        val done = CountDownLatch(1)
        try {
            executor.execute {
                runTask {
                    try {
                        result = task()
                    } finally {
                        done.countDown()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            return fallback
        }
        try {
            done.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return result
    }

    private fun roleName(): String = when (role) {
        LinkRole.SHARER -> "sharer"
        LinkRole.GUEST -> "guest"
    }

    private fun closeReason(code: Int, reason: String): String = when (code) {
        -1, 0, 1006 -> "network"
        1000 -> "closed"
        1001 -> "peer-gone"
        4002 -> "offline"
        4003 -> "bye"
        4004 -> "bye"
        else -> if (reason.isNotBlank()) safe(reason) else "closed-" + code
    }

    private fun safe(value: String?): String {
        val text = (value ?: "").replace('\n', ' ').replace('\r', ' ').trim()
        return if (text.length <= 120) text else text.substring(0, 120)
    }

    companion object {
        /** 分享者：shareId / token 来自 ShareRegistry.create 的结果。 */
        fun forSharer(
            share: ShareEndpoint,
            identity: DeviceIdentity,
            transportFactory: TransportFactory,
            events: LinkEvents,
            timeouts: LinkTimeouts,
        ): SignalClient = SignalClient(LinkRole.SHARER, share, identity, transportFactory, events, timeouts, true)

        /** 访客：share 来自 ShareRegistry.get 的结果（pub / deviceId / expiresAt 必须齐）。 */
        fun forGuest(
            share: ShareEndpoint,
            identity: DeviceIdentity?,
            transportFactory: TransportFactory,
            events: LinkEvents,
            timeouts: LinkTimeouts,
        ): SignalClient = SignalClient(LinkRole.GUEST, share, identity, transportFactory, events, timeouts, true)
    }
}
