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
 *   - 预算（§5-7）：访客口径的建链预算只在「握手」上用；分享者挂着等访客用的是等待预算
 *     （默认 = 分享剩余有效期，见 LinkTimeouts.sharerWaitMs），期间不判 connect-failed，
 *     访客在场时暂停计时，访客走了按剩余额度接着等 —— 一条链接因此可以长期有效、反复接入。
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

    /** 分享者的等待相：true = 房里没有访客、正在挂着等。只有 SHARER 用。 */
    private var waiting = false

    /** 分享者等待预算总额（毫秒），start() 时定死。 */
    private var waitBudgetMs = 0L

    /** 等待预算的剩余额度（毫秒）；进入等待相时从这里起算，离开时把用掉的部分扣掉。 */
    private var waitRemainingMs = 0L

    /** 本轮等待相开始的时刻（System.nanoTime）。 */
    private var waitingSinceNanos = 0L
    private var signalTimeout: ScheduledFuture<*>? = null
    private var transferTimeout: ScheduledFuture<*>? = null
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var closeFuture: ScheduledFuture<*>? = null

    /** 开始连接/加入房间。幂等；返回 false 表示客户端已经关闭。 */
    fun start(): Boolean {
        if (closed.get()) return false
        if (!started.compareAndSet(false, true)) return true
        if (role == LinkRole.SHARER) {
            waitBudgetMs = resolveWaitBudgetMs()
            waitRemainingMs = waitBudgetMs
            enterWaiting()
        }
        events.log(
            "link_start",
            mapOf(
                "role" to roleName(),
                "share" to share.shareId.take(8),
                "guest_limit" to share.guestLimit.toString(),
                "guest_budget_ms" to timeouts.guestConnectBudgetMs.toString(),
                "wait_budget_ms" to waitBudgetMs.toString(),
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
                "budget_ms" to currentBudgetMs().toString(),
                "phase" to phaseName(),
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
                // 房里出现访客：等待预算暂停，改按访客口径的 15 s 等握手（不拿 TTL 等握手）。
                leaveWaiting()
                scheduleSignalTimeout(generation)
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
            "done" -> if (role == LinkRole.SHARER) endVisit("bye-done") else markDone("bye-done")
            "gone" -> fail("revoked")
            "offline" -> {
                if (everConnected && autoReconnect) scheduleReconnect("offline") else fail("offline")
            }
            "peer-left" -> {
                if (role == LinkRole.SHARER) {
                    // 分享者的房间还活着：访客走了，回到等下一个访客的状态，不重连自己。
                    endVisit("peer-left")
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
        // 分享者是个长连接房间：等访客期间、以及房里的访客刚走之后，只要等待预算还有剩余就
        // 重连（掉线即本次访问结束，回到等待相，按剩余额度续算，不续满）。
        if (role == LinkRole.SHARER && autoReconnect) {
            if (remainingWaitMs() > 0L) {
                enterWaiting()
                scheduleReconnect("closed")
                return
            }
            if (waiting) {
                fail("wait-expired")
                return
            }
        }
        if (autoReconnect && everConnected) {
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

    /**
     * 分享者这边的一次访问收工（访客发 bye{done}，或访客断开给了 bye{peer-left}）。
     *
     * 房间还活着、分享还没过期：回到「挂着等访客」的相，等待预算按剩余额度接着算，
     * 下一个访客不用重新开链接。分享者自己调 [finish] 才是 DONE。
     */
    private fun endVisit(reason: String) {
        if (closed.get() || isTerminal() || role != LinkRole.SHARER) return
        cancelTransferTimeout()
        cancelSignalTimeout()
        events.log(
            "link_visit_end",
            mapOf(
                "visitor" to incomingVisitorId.take(8),
                "reason" to reason,
                "state" to state.name.lowercase(),
            ),
        )
        incomingVisitorId = ""
        offerReceived = false
        offerSdp = ""
        if (state == LinkState.CONNECTED || state == LinkState.TRANSFERRING) {
            setState(LinkState.SIGNALING)
        }
        enterWaiting()
        scheduleSignalTimeout(generation)
    }

    private fun markDone(reason: String) {
        if (closed.get() || isTerminal()) return
        events.log("link_done", mapOf("reason" to reason))
        setState(LinkState.DONE)
        cancelTimers()
        scheduleTransportClose()
    }

    /* ---------------------------------------------------------------- 分享者的等待预算 */

    /** 分享者：进入「挂着等访客」的相。等待计时从这里起算，已经在等就不动刻度。 */
    private fun enterWaiting() {
        if (role != LinkRole.SHARER || waiting || closed.get() || isTerminal()) return
        waiting = true
        waitingSinceNanos = System.nanoTime()
        events.log(
            "link_wait",
            mapOf(
                "share" to share.shareId.take(8),
                "wait_ms" to remainingWaitMs().toString(),
                "budget_ms" to waitBudgetMs.toString(),
            ),
        )
    }

    /** 分享者：房里出现访客，暂停等待计时（用掉的部分扣掉，剩下的留给下一个）。 */
    private fun leaveWaiting() {
        if (role != LinkRole.SHARER || !waiting) return
        val left = remainingWaitMs()
        waitRemainingMs = left
        waiting = false
        events.log(
            "link_visitor_engaged",
            mapOf(
                "visitor" to incomingVisitorId.take(8),
                "budget_ms" to timeouts.guestConnectBudgetMs.toString(),
                "wait_left_ms" to left.toString(),
            ),
        )
    }

    /** 等待预算的剩余额度：进入等待相后按经过的时间扣。 */
    private fun remainingWaitMs(): Long {
        if (role != LinkRole.SHARER) return 0L
        val used = if (waiting) (System.nanoTime() - waitingSinceNanos) / 1_000_000L else 0L
        return (waitRemainingMs - used).coerceAtLeast(0L)
    }

    /** 这一次尝试该看哪个预算（只用于日志与超时口径）。 */
    private fun currentBudgetMs(): Long =
        if (role == LinkRole.SHARER && waiting) remainingWaitMs() else timeouts.guestConnectBudgetMs

    /**
     * 分享者的等待预算：默认等于分享剩余有效期。
     *
     * 反向验收钩子（-Dhuchuan.mutants=sharer_budget_15s，默认关）：忽略分享有效期，退回 15 s
     * 固定预算 —— 也就是修这个缺陷之前的样子。打开后 harness 的 link.wait.* 必须变红
     * （docs/core.md §3.11）。显式 sharerWaitBudgetMs 仍然生效，否则变异会连"预算是参数"
     * 这件事一起打掉，红的位置就不在等待预算上了。
     */
    private fun resolveWaitBudgetMs(): Long {
        if (timeouts.sharerWaitBudgetMs > 0L) return timeouts.sharerWaitBudgetMs
        if (sharerBudgetMutant()) return LinkDefaults.GUEST_CONNECT_BUDGET_MS
        return timeouts.sharerWaitMs(share.expiresAt)
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
        val delay = timeouts.reconnectDelayMs(attempt)
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

    /**
     * 排一次「多久没进展就放弃」。两种口径，别混：
     *   - 握手中（访客，或分享者的房里已经有访客）：建链预算 [LinkTimeouts.guestConnectBudgetMs]，
     *     超时 FAILED(connect-failed)。
     *   - 分享者挂着等访客：等待预算的剩余额度，超时 FAILED(wait-expired)。这笔额度跨重连、
     *     跨访客累计，单次重连不会把它续满。
     */
    private fun scheduleSignalTimeout(gen: Int) {
        cancelSignalTimeout()
        val waitingForGuest = role == LinkRole.SHARER && waiting
        val budget = if (waitingForGuest) remainingWaitMs() else timeouts.guestConnectBudgetMs
        val step = if (waitingForGuest) "sharer-wait" else "signal"
        try {
            signalTimeout = executor.schedule(
                {
                    runTask {
                        if (gen == generation && state == LinkState.SIGNALING && !closed.get() && !isTerminal()) {
                            events.log(
                                "link_timeout",
                                mapOf("step" to step, "budget_ms" to budget.toString(), "phase" to phaseName()),
                            )
                            fail(if (waitingForGuest) "wait-expired" else "connect-failed")
                        }
                    }
                },
                budget.coerceAtLeast(1L),
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

    /** 日志用：当前在用哪套预算（等访客 / 握手中）。 */
    private fun phaseName(): String = when {
        role == LinkRole.GUEST -> "connect"
        waiting -> "wait"
        else -> "connect"
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
        /**
         * 反向验收钩子（-Dhuchuan.mutants=sharer_budget_15s，默认关）：把分享者的等待预算
         * 退回固定的 15 s 建链预算，也就是修这个缺陷之前的样子。打开后 harness 的
         * link.wait.* 那几条必须变红（docs/core.md §3.11 的复现命令）。
         */
        private val SHARER_BUDGET_15S: Boolean = System.getProperty("huchuan.mutants", "")
            .split(",")
            .any { it.trim() == "sharer_budget_15s" }

        private fun sharerBudgetMutant(): Boolean = SHARER_BUDGET_15S

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
