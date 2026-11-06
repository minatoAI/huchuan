package app.huchuan.core.m1

import org.libtorrent4j.AlertListener
import org.libtorrent4j.PortmapProtocol
import org.libtorrent4j.PortmapTransport
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.PortmapAlert
import org.libtorrent4j.alerts.PortmapErrorAlert
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 跨网分享窗口（AGENTS §9 / docs/design/nat-traversal.md §7）：
 * **私有会话只在窗口内开端口映射，窗口一到点立刻撤销，监听端口在窗口内换成随机高位端口。**
 *
 * 为什么不是「打开 setting 就完事」：
 *  - 开 UPnP / NAT-PMP 之后 libtorrent 会把**当前监听端口**映射出去。私有会话的监听口默认是
 *    51413（脚本 / 会话提示给的固定值），直接开会把这个固定端口暴露给公网 —— 正是设计里要避免的
 *    「固定指纹」。所以窗口先把监听端口换到 [PORT_MIN]..[PORT_MAX] 里的随机空闲端口，再开映射；
 *    关窗口时先关映射、再把监听端口恢复回去（顺序不能反，否则 51413 会被短暂映射出去）。
 *  - 换端口走 `apply_settings(listen_interfaces)`，运行期生效，不重启会话；
 *    已建立的 BT 连接是独立 socket，不受影响；新入向连接走新端口。
 *  - 关掉两个 setting 就是**撤销映射**：libtorrent 的 stop_upnp()/stop_natpmp() 会对当前监听端口
 *    发 DeletePortMapping / NAT-PMP 删除包（见 [BtSession.setPortMapping]）。
 *
 * 到期：一次性调度任务（不是轮询线程）；[tick] 是内核心跳的兜底，[status] 也会就地判到期。
 * 线程随窗口关闭退出（daemon）。
 *
 * 失败路径：任何失败都返回 [OpenResult]（ok=false + 一句中文），**不抛异常**；
 * 端口重试最多 [MAX_OPEN_ATTEMPTS] 次，超过就放弃并恢复原端口。
 */
class CrossNetWindow @JvmOverloads constructor(
    private val bt: BtSession,
    private val events: KernelEvents,
    /**
     * 窗口开 / 关后立刻刷新私有会话的「可达性状态」：ip_filter 动态白名单（[M1Kernel.refreshBtAllowlist]）、
     * mDNS 通告里的 BT 端口、peer.announce 的 btPort。由内核注入，本类不依赖 M1Kernel。
     */
    private val onSessionRefresh: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 一条端口映射的当前状态（诊断 / status().mappings）。 */
    data class Mapping(
        /** upnp | natpmp（NAT-PMP 模块里先试 PCP）。 */
        val transport: String,
        /** tcp | udp。 */
        val protocol: String,
        /** 路由器分配的外部端口；0 = 还不知道（[STATE_REQUESTED]）。 */
        val externalPort: Int,
        /** 本机会话的监听端口（窗口端口）。 */
        val internalPort: Int,
        /** requested（已申请，结果未回）| mapped（路由器已建映射）| failed（路由器拒绝 / 不支持）。 */
        val state: String,
        /** 失败原文（state=failed 时非空）。 */
        val error: String = "",
    )

    /** [M1Kernel.crossNetWindowStatus] 的返回体。closed 时 port=0、remainingMs=0、mappings 为空。 */
    data class Status(
        val open: Boolean,
        val port: Int,
        val remainingMs: Long,
        val mappings: List<Mapping>,
    )

    /** [M1Kernel.crossNetWindowOpen] 的返回体。失败不抛异常，code 是稳定标识、message 是给人看的一句话。 */
    data class OpenResult(
        val ok: Boolean,
        val code: String,
        val message: String,
        val port: Int = 0,
        val expiresAt: Long = 0L,
    )

    private val lock = Any()

    /** open / close 的串行化：换端口与关映射都是有中间态的操作，不能让两个调用交叉。 */
    private val changeLock = Any()

    private val random = SecureRandom()

    private var open = false
    private var port = 0

    /** 窗口打开前的监听端口；关窗口时恢复它。 */
    private var previousPort = 0
    private var expiresAt = 0L

    /** key = transport/protocol。窗口关闭时清空。 */
    private val mappings = LinkedHashMap<String, Mapping>()

    private var scheduler: ScheduledExecutorService? = null
    private var expiryTask: ScheduledFuture<*>? = null

    @Volatile
    private var listenerAttached = false

    // ------------------------------------------------------------------ 公共 API

    /**
     * 打开窗口，[durationMs] 毫秒后自动撤销。已经开着时只续期（不重换端口、不重开映射）。
     *
     * 成功 = 会话换了随机监听端口 + 两个映射 setting 已读回为 true + 到期任务已挂上。
     * 路由器是否真的建了映射由 PortmapAlert 异步回报，进 [status] 的 mappings；
     * 无路由器时窗口照样有效（局域网不受影响），不把它当 open 失败。
     */
    @JvmOverloads
    fun open(durationMs: Long = DEFAULT_DURATION_MS): OpenResult = synchronized(changeLock) {
        if (durationMs <= 0L) {
            events.log("window_open_failed", mapOf("code" to CODE_INVALID_DURATION, "duration_ms" to durationMs.toString()))
            return@synchronized OpenResult(false, CODE_INVALID_DURATION, "时长必须大于 0 毫秒，跨网分享窗口没打开")
        }
        val duration = durationMs.coerceAtMost(MAX_DURATION_MS)

        // 已经开着：只续期。端口 / 映射 / 刷新都是幂等的，不需要重做。
        val extended = synchronized(lock) {
            if (!open) {
                null
            } else {
                expiresAt = clock() + duration
                scheduleExpiryLocked(duration)
                port to expiresAt
            }
        }
        if (extended != null) {
            events.log(
                "window_extend",
                mapOf("port" to extended.first.toString(), "expires" to extended.second.toString()),
            )
            return@synchronized OpenResult(
                true,
                CODE_ALREADY_OPEN,
                "跨网分享窗口已经开着，已续期",
                extended.first,
                extended.second,
            )
        }

        if (!bt.isRunning || bt.listenPort <= 0) {
            events.log("window_open_failed", mapOf("code" to CODE_SESSION_DOWN))
            return@synchronized OpenResult(false, CODE_SESSION_DOWN, "BT 会话没起来，跨网分享窗口打不开")
        }

        val before = bt.listenPort
        var chosen = 0
        var attempt = 0
        var detail = ""
        while (attempt < MAX_OPEN_ATTEMPTS) {
            attempt++
            val candidate = pickFreePort(before)
            if (candidate <= 0) {
                detail = "没有可用端口"
                continue
            }
            val actual = bt.setListenPort(candidate)
            if (actual == candidate) {
                chosen = candidate
                detail = ""
                break
            }
            detail = "端口 $candidate 绑不上（listen_port=$actual）"
            events.log(
                "window_open_retry",
                mapOf("attempt" to attempt.toString(), "port" to candidate.toString(), "actual" to actual.toString()),
            )
        }
        if (chosen <= 0) {
            restoreListenPort(before)
            events.log(
                "window_open_failed",
                mapOf("code" to CODE_PORT_UNAVAILABLE, "detail" to detail, "attempts" to attempt.toString()),
            )
            return@synchronized OpenResult(false, CODE_PORT_UNAVAILABLE, "换不到随机监听端口（$detail），跨网分享窗口没打开")
        }

        // 先换端口、后开映射：反过来的话 libtorrent 会先把旧端口（51413）映射出去。
        attachMappingListener()
        if (!bt.setPortMapping(true)) {
            // 防御：apply_settings 是投递到会话线程的异步调用，「读不回 true」可能只是慢，
            // 不补一次 false 的话它可能在 open 返回失败之后才生效 —— 窗口没开、映射却开着。
            bt.setPortMapping(false)
            restoreListenPort(before)
            detachMappingListener()
            events.log(
                "window_open_failed",
                mapOf(
                    "code" to CODE_MAPPING_UNSUPPORTED,
                    "detail" to bt.sessionSettingsError,
                    "attempts" to attempt.toString(),
                ),
            )
            return@synchronized OpenResult(
                false,
                CODE_MAPPING_UNSUPPORTED,
                "UPnP / NAT-PMP 打不开（设置写不进去），跨网分享窗口没打开",
            )
        }

        val udp = bt.utpEnabled()
        val at = clock() + duration
        synchronized(lock) {
            open = true
            port = chosen
            previousPort = before
            expiresAt = at
            mappings.clear()
            seedMappingsLocked(chosen, udp)
            scheduleExpiryLocked(duration)
        }
        events.log(
            "window_open",
            mapOf(
                "port" to chosen.toString(),
                "expires" to at.toString(),
                "prev_port" to before.toString(),
                "attempts" to attempt.toString(),
            ),
        )
        refreshSession(chosen, open = true)
        OpenResult(true, CODE_OK, "跨网分享窗口已打开：$chosen 端口，到点自动撤销", chosen, at)
    }

    /**
     * 立刻撤销映射并关掉两个 setting，然后恢复窗口前的监听端口。
     *
     * [reason] 只允许 manual / expired 两种（其它值一律记 manual）。返回是否真的关掉了一个开着的窗口；
     * 幂等：没开时也返回 false 并把两个 setting 再压一遍（防止上一个进程留在「开」的状态）。
     */
    @JvmOverloads
    fun close(reason: String = REASON_MANUAL): Boolean = synchronized(changeLock) {
        val normalized = if (reason == REASON_EXPIRED) REASON_EXPIRED else REASON_MANUAL
        val wasOpen: Boolean
        val closingPort: Int
        val before: Int
        synchronized(lock) {
            wasOpen = open
            closingPort = port
            before = previousPort
            open = false
            port = 0
            previousPort = 0
            expiresAt = 0L
            mappings.clear()
            expiryTask?.cancel(false)
            expiryTask = null
            scheduler?.shutdown()
            scheduler = null
        }
        // 没窗口开着也可能是上一次崩在「开」的状态：把 setting 再压一遍，保证不变量成立。
        val settingsWereOn = bt.upnpEnabled() || bt.natpmpEnabled()
        // 撤销映射：关掉两个 setting，libtorrent 的 stop_upnp()/stop_natpmp() 会发删除包。
        val revoked = bt.setPortMapping(false)
        detachMappingListener()
        // 顺序不能反：先撤映射，再把监听端口恢复回去，否则恢复出来的 51413 会被重新映射出去。
        val restored = if (wasOpen && before > 0) bt.setListenPort(before) else before
        // 没开过窗口、setting 本来也是关的（例如 kernel.stop() 的兜底调用）不留 window_close 噪音。
        if (wasOpen || settingsWereOn) {
            events.log(
                "window_close",
                mapOf(
                    "reason" to normalized,
                    "port" to closingPort.toString(),
                    "revoked" to revoked.toString(),
                    "restored_port" to restored.toString(),
                    "was_open" to wasOpen.toString(),
                ),
            )
        }
        if (wasOpen) refreshSession(closingPort, open = false)
        wasOpen
    }

    /**
     * 到期兜底：内核心跳（200 ms）调用一次。正常路径由一次性调度任务触发，这里是调度线程
     * 被意外卡住时的保险；不自己占线程、不做循环。
     */
    fun tick() {
        val due = synchronized(lock) { open && clock() >= expiresAt }
        if (due) close(REASON_EXPIRED)
    }

    /** 当前状态。到期未关时先就地关（所以调用方看到的 open 一定是真值）。 */
    fun status(): Status {
        tick()
        synchronized(lock) {
            val remaining = if (open) (expiresAt - clock()).coerceAtLeast(0L) else 0L
            return Status(
                open = open,
                port = port,
                remainingMs = remaining,
                mappings = if (open) mappings.values.toList() else emptyList(),
            )
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 挑一个没被占用的随机高位端口。避开 51413（私有会话默认口）与 47100（消息层口）——
     * 固定值是扫描器的入口，随机端口让「全端口扫一遍」的成本高一个数量级（nat-traversal §7）。
     */
    private fun pickFreePort(avoid: Int): Int {
        repeat(PORT_PICK_TRIES) {
            val candidate = PORT_MIN + random.nextInt(PORT_MAX - PORT_MIN + 1)
            if (candidate == avoid || candidate in RESERVED_PORTS) return@repeat
            if (isPortFree(candidate)) return candidate
        }
        return 0
    }

    /** 在 JVM 里先绑一次再释放，确认端口此刻可用（libtorrent 绑不上会把会话停在 listen_port=0）。 */
    private fun isPortFree(port: Int): Boolean = try {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress("0.0.0.0", port))
        }
        true
    } catch (e: Throwable) {
        false
    }

    private fun restoreListenPort(want: Int) {
        if (want <= 0) return
        val actual = bt.setListenPort(want)
        if (actual != want) {
            events.log("window_restore_failed", mapOf("want" to want.toString(), "actual" to actual.toString()))
        }
    }

    /** 申请中的映射先按"requested"记下来，PortmapAlert / PortmapErrorAlert 到达后改状态。 */
    private fun seedMappingsLocked(windowPort: Int, udp: Boolean) {
        for (transport in TRANSPORTS) {
            mappings["$transport/tcp"] = Mapping(transport, "tcp", 0, windowPort, STATE_REQUESTED)
            if (udp) {
                mappings["$transport/udp"] = Mapping(transport, "udp", 0, windowPort, STATE_REQUESTED)
            }
        }
    }

    private fun scheduleExpiryLocked(durationMs: Long) {
        val sched = scheduler ?: Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "huchuan-crossnet").apply { isDaemon = true }
        }.also { scheduler = it }
        expiryTask?.cancel(false)
        expiryTask = sched.schedule({ onExpiryTask() }, durationMs, TimeUnit.MILLISECONDS)
    }

    private fun onExpiryTask() {
        val stillOpen = synchronized(lock) { open }
        if (stillOpen) close(REASON_EXPIRED)
    }

    private fun refreshSession(windowPort: Int, open: Boolean) {
        try {
            onSessionRefresh()
            events.log("window_refresh", mapOf("port" to windowPort.toString(), "open" to open.toString()))
        } catch (e: Throwable) {
            events.log("window_refresh_error", mapOf("error" to e.toString()))
        }
    }

    // ------------------------------------------------------------------ 端口映射告警

    private val mappingListener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(
            AlertType.PORTMAP.swig(),
            AlertType.PORTMAP_ERROR.swig(),
        )

        override fun alert(a: Alert<*>) {
            try {
                when (a) {
                    is PortmapAlert -> onMapped(a)
                    is PortmapErrorAlert -> onMappingFailed(a)
                    else -> Unit
                }
            } catch (_: Throwable) {
                // 告警线程上不允许抛：映射结果只是诊断，不能把会话的告警循环带崩
            }
        }
    }

    private fun attachMappingListener() {
        if (listenerAttached) return
        listenerAttached = bt.addAlertListener(mappingListener)
    }

    private fun detachMappingListener() {
        if (!listenerAttached) return
        listenerAttached = false
        bt.removeAlertListener(mappingListener)
    }

    private fun onMapped(alert: PortmapAlert) {
        val transport = transportName(alert.mapTransport())
        val protocol = protocolName(alert.mapProtocol())
        if (protocol == "none") return
        val external = try {
            alert.externalPort()
        } catch (e: Throwable) {
            0
        }
        updateMapping(transport, protocol, external, STATE_MAPPED, "")
    }

    private fun onMappingFailed(alert: PortmapErrorAlert) {
        val transport = transportName(alert.mapTransport())
        val error = try {
            alert.error().message
        } catch (e: Throwable) {
            ""
        }
        // 协议不带在 error 告警里：只把该传输下**还没结果**的申请标红，已经 mapped 的不动
        // （路由器可能 TCP 拒了、UDP 通了，反过来也一样）。
        synchronized(lock) {
            if (!open) return
            for (protocol in listOf("tcp", "udp")) {
                val key = "$transport/$protocol"
                val old = mappings[key] ?: continue
                if (old.state != STATE_REQUESTED) continue
                mappings[key] = old.copy(state = STATE_FAILED, error = error)
            }
        }
        events.log(
            "window_mapping",
            mapOf("transport" to transport, "protocol" to "*", "state" to STATE_FAILED, "error" to error),
        )
    }

    private fun updateMapping(transport: String, protocol: String, externalPort: Int, state: String, error: String) {
        synchronized(lock) {
            if (!open) return
            val key = "$transport/$protocol"
            val old = mappings[key] ?: Mapping(transport, protocol, 0, port, STATE_REQUESTED)
            mappings[key] = old.copy(
                externalPort = if (externalPort > 0) externalPort else old.externalPort,
                state = state,
                error = error,
            )
        }
        events.log(
            "window_mapping",
            mapOf(
                "transport" to transport,
                "protocol" to protocol,
                "external_port" to externalPort.toString(),
                "state" to state,
            ),
        )
    }

    private fun transportName(transport: PortmapTransport): String = when (transport) {
        PortmapTransport.UPNP -> "upnp"
        PortmapTransport.NAT_PMP -> "natpmp"
    }

    private fun protocolName(protocol: PortmapProtocol): String = when (protocol) {
        PortmapProtocol.TCP -> "tcp"
        PortmapProtocol.UDP -> "udp"
        else -> "none"
    }

    companion object {
        /** 默认窗口时长：10 分钟（够一次跨网传输起步；到点自动撤销）。 */
        const val DEFAULT_DURATION_MS: Long = 10 * 60 * 1000L

        /** 窗口时长上限：24 小时。超过按 24 小时算，避免一个手滑把端口映射开成"永久"。 */
        const val MAX_DURATION_MS: Long = 24 * 60 * 60 * 1000L

        /** 随机端口范围：IANA 动态端口段（高位，避开 51413 / 47100 这类固定值）。 */
        const val PORT_MIN = 49152
        const val PORT_MAX = 65535

        /** 端口重试上限（窗口内任何"换端口"动作都不超过这个数）。 */
        const val MAX_OPEN_ATTEMPTS = 3

        /** 一轮随机里最多试几个候选（每轮都先 bind 一次确认可用）。 */
        private const val PORT_PICK_TRIES = 20

        /** 固定值端口：私有会话默认 BT 口、消息层口。窗口绝不监听它们。 */
        val RESERVED_PORTS: Set<Int> = setOf(51413, 47100)

        const val REASON_MANUAL = "manual"
        const val REASON_EXPIRED = "expired"

        const val STATE_REQUESTED = "requested"
        const val STATE_MAPPED = "mapped"
        const val STATE_FAILED = "failed"

        const val CODE_OK = "ok"
        const val CODE_ALREADY_OPEN = "already_open"
        const val CODE_INVALID_DURATION = "invalid_duration"
        const val CODE_SESSION_DOWN = "session_down"
        const val CODE_PORT_UNAVAILABLE = "port_unavailable"
        const val CODE_MAPPING_UNSUPPORTED = "mapping_unsupported"

        private val TRANSPORTS = listOf("upnp", "natpmp")
    }
}
