package app.huchuan.core.m1

import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.TorrentFinishedAlert
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File

/**
 * 私有 BT 会话（协议 §8）：DHT / LSD / UPnP / NAT-PMP / ip_notifier 全关，
 * 每 torrent 强制 flags = DISABLE_DHT | DISABLE_LSD | DISABLE_PEX = 0x380000。
 * 建种只用 v1（AGENTS.md §9）。
 *
 * **公开种子不进这个会话**（public-swarm §2.1）：加种前按种子自身的 private 位（BEP 27）选会话，
 * 私有会话只收 private=1 的种子，public 会话只收 private=0 的。加参数复用本类等于把「忘了传」
 * 变成隐私事故（BtSession.kt:555 无条件 or_ 0x380000），所以公开侧另起 [PublicBtSession]。
 */
class BtSession(
    private val portHint: Int,
    private val saveRoot: File,
    private val ipFilterEnabled: Boolean = true,
) : LocalSession {
    class SeededTorrent(
        val fileId: String,
        val name: String,
        val size: Long,
        val pieceSize: Int,
        val torrentBytes: ByteArray,
    )

    private class Task(
        val fileId: String,
        var handle: TorrentHandle,
        val savePath: File,
        val name: String,
        var seeding: Boolean,
    )

    private var manager: SessionManager? = null
    private val tasks = LinkedHashMap<String, Task>()
    private val lock = Any()

    /**
     * 动态白名单：消息层确认的**群成员公网 IP 字面量**（AGENTS §9）。
     *
     * 私有会话的 ip_filter 基础规则是「全禁 + 只放行私网」，跨网 peer 会被 libtorrent 自己拒掉；
     * 这里收 GroupRuntime 从 `peer.announce.btHost` / tracker 候选 / 手动 IP:port 学到的地址，
     * 重建整份 ip_filter 交给运行中的会话（`session::set_ip_filter`）。会话没起来时先记着，
     * [start] 建会话时一起带进 session_params。
     */
    private val allowLock = Any()
    private val publicAllow = LinkedHashSet<String>()

    /** 动态 ip_filter 写不进会话时的原因（"" = 正常）。诊断用。 */
    @Volatile
    var ipFilterError: String = ""
        private set

    /** 告警类型映射表补不上的原因（"" = 正常）。诊断用；见 [AlertTypeCompat]。 */
    @Volatile
    var alertTypeCompatError: String = ""
        private set

    /**
     * 会话级（P7 全局限速）的目标值，单位 **KB/s**，0 = 不限。
     *
     * 会话没起来时先记在这里，[start] 建会话时带进 settings_pack；
     * 会话起来了就走 [setSessionUploadLimit] / [setSessionDownloadLimit] 的 applySettings，
     * **立即**作用到当前会话 —— 包括已经在传输的 torrent（见 §6 口径表）。
     */
    @Volatile
    private var sessionUploadKbps = 0

    @Volatile
    private var sessionDownloadKbps = 0

    /** 会话级限速写不进去、或 peer class 改不动时的原因（"" = 正常）。给内核打日志用。 */
    @Volatile
    var rateLimitError: String = ""
        private set

    /**
     * 下载完成告警出口：libtorrent 判定「这个 torrent 下完了」的**当场**回调（torrent_finished_alert）。
     *
     * 完成判定不再挂在 2 s 的巡查节拍上（缺口二：下完之后 P3 会停在「下载中 100%」等一拍）。
     * 回调运行在 libtorrent 自己的告警线程上，所以实现方只往里塞一个队列，不能在这里做重活。
     * 拿不到告警（掩码/平台差异）也不会漏判：M1Kernel 还有一条 200 ms 的兜底巡查走同一个判定函数。
     */
    @Volatile
    internal var onTorrentFinished: ((String) -> Unit)? = null

    @Volatile
    var listenPort: Int = 0
        private set

    val isRunning: Boolean get() = manager?.isRunning == true

    /**
     * 会话归属判定：private 位（BEP 27）为真才属于本会话（public-swarm §1.2）。
     *
     * 这是「加种前一律按 TorrentInfo.priv() 决定用哪个会话」的唯一判据的函数形式；
     * [addTorrent] 也走它，所以「忘了校验」不会变成一个可选项。
     */
    fun accepts(privateTorrent: Boolean): Boolean = privateTorrent

    /** 这个 fileId 的 torrent 是否挂在本会话里。 */
    override fun has(fileId: String): Boolean = synchronized(lock) { tasks.containsKey(fileId) }

    /** 会话里的 torrent 数（诊断与验收读口）。 */
    fun torrentCount(): Int = synchronized(lock) { tasks.size }

    /**
     * settings_pack 里 enable_dht 的真值（自检断言「私有会话 DHT 为关」用）。
     * 会话没起来时返回 false。
     */
    fun enableDhtSetting(): Boolean = try {
        manager?.settings()?.isEnableDht ?: false
    } catch (e: Throwable) {
        false
    }

    fun start(): Int {
        manager?.let { return listenPort }
        // 补告警类型映射表的空槽（根因见 AlertTypeCompat）：补表是进程级一次性动作，
        // 但私有会话的告警循环也是同一个 SessionManager，所以两边都在 start 前补一次。
        // 私有会话不设 events，失败的原文记在 [alertTypeCompatError] 上给诊断页读。
        AlertTypeCompat.install().let { if (!it.isClean) alertTypeCompatError = it.error }
        val candidates = ArrayList<Int>()
        if (portHint > 0) for (i in 0 until 100) candidates.add(portHint + i)
        // 兜底「系统分配端口」不能直接把 0 交给 libtorrent：实测 2.1.0-39 下
        // listen_interfaces="0.0.0.0:0" 会报出 listen_port>0、is_listening=true，
        // 但那个端口根本不接 TCP（从外部 connect 直接 ConnectException），
        // 也就是兜底出来的会话收不到任何入向连接。先在 JVM 里要一个空闲端口再交下去。
        for (i in 0 until FALLBACK_PORT_TRIES) freePort()?.let { candidates.add(it) }

        var lastError: Throwable? = null
        for (port in candidates) {
            val mgr = SessionManager()
            try {
                val params = SessionParams(hardenedSettings(port))
                if (ipFilterEnabled) applyIpFilter(params)
                mgr.start(params)
                val actual = mgr.swig().listen_port()
                if (actual > 0) {
                    manager = mgr
                    listenPort = actual
                    // 建会话之后、任何 torrent / peer 之前：把私网段并进 global peer class，
                    // 否则会话级限速在局域网上等于没设（见 bindLocalPeersToGlobalClass）。
                    bindLocalPeersToGlobalClass(mgr)
                    attachFinishedListener(mgr)
                    return actual
                }
                mgr.stop()
            } catch (e: Throwable) {
                lastError = e
                try { mgr.stop() } catch (_: Throwable) {}
            }
        }
        throw IllegalStateException("BT session could not bind any port (last error: " + lastError + ")")
    }

    fun stop() {
        val mgr = manager ?: return
        manager = null
        try { mgr.removeListener(finishedListener) } catch (_: Throwable) {}
        try { mgr.stop() } catch (_: Throwable) {}
    }

    /**
     * 只订 torrent_finished 一类告警：回调在 libtorrent 的告警线程上跑，
     * 这里只取 infoHash（= 群里的 fileId）转手抛给内核，不做任何判定。
     */
    private fun attachFinishedListener(mgr: SessionManager) {
        try {
            mgr.addListener(finishedListener)
            listenerError = ""
        } catch (e: Throwable) {
            // 订不上不影响正确性：内核有一条 200 ms 的兜底巡查走同一个完成判定。
            // 但必须留痕 —— 否则「完成事件为什么总是慢一拍」下次又得从头查。
            listenerError = e.toString()
        }
    }

    /** 告警监听挂不上时的原因（给内核打日志用）；正常为 ""。 */
    @Volatile
    var listenerError: String = ""
        private set

    private val finishedListener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(AlertType.TORRENT_FINISHED.swig())

        override fun alert(a: Alert<*>) {
            val alert = a as? TorrentFinishedAlert ?: return
            val fileId = try {
                alert.handle().infoHash().toString()
            } catch (_: Throwable) {
                return
            }
            if (fileId.isEmpty()) return
            try {
                onTorrentFinished?.invoke(fileId)
            } catch (_: Throwable) {
                // 出口抛异常不能把告警线程带崩
            }
        }
    }

    fun dhtNodes(): Long = manager?.dhtNodes() ?: 0L

    fun isDhtRunning(): Boolean = manager?.isDhtRunning ?: false

    /**
     * 建 v1 私有种子（协议 §4）：private=true、无 trackers/nodes/url-list。
     * 建完重新解析一遍再断言，不信任「我设置了」。
     */
    fun createPrivateTorrent(file: File): SeededTorrent {
        val pieceSize = pieceSizeFor(file.length())
        val builder = TorrentBuilder()
            .path(file)
            .creator("huchuan/1")
            .comment("huchuan private v1")
            .setPrivate(true)
            .flags(TorrentBuilder.V1_ONLY)
            .pieceSize(pieceSize)
        check(builder.trackers().isEmpty()) { "builder must not add trackers" }
        check(builder.nodes().isEmpty()) { "builder must not add DHT nodes" }
        check(builder.urlSeeds().isEmpty()) { "builder must not add url seeds" }
        val built = builder.generate()
        val bytes = built.entry().bencode()
        val ti = TorrentInfo(bytes)
        check(ti.isPrivate) { "torrent must be private" }
        check(ti.totalSize() == file.length()) { "torrent size mismatch" }
        check(ti.hasV1() && !ti.hasV2()) { "torrent must be v1-only" }
        return SeededTorrent(
            fileId = ti.infoHash().toString(),
            name = ti.name(),
            size = ti.totalSize(),
            pieceSize = ti.pieceLength(),
            torrentBytes = bytes,
        )
    }

    /**
     * 挂种。[superSeeding] = true 时给这个 torrent 打开 BEP 16 super-seeding。
     *
     * 走的是 `add_torrent_params.flags`（与 [PRIVATE_FLAGS] 同一个字段，按位或上去），
     * 不是 settings_pack —— 2.1.0-39 的 settings_pack 里**没有** super_seeding 这个布尔
     * （见 docs/reports/e0-superseed.md §1）。真值用 [superSeeding] 读回来。
     */
    fun addSeed(seeded: SeededTorrent, savePath: File) = addSeed(seeded, savePath, false)

    fun addSeed(seeded: SeededTorrent, savePath: File, superSeeding: Boolean) {
        val ti = TorrentInfo(seeded.torrentBytes)
        val handle = addTorrent(ti, savePath, if (superSeeding) superSeedingFlag() else null)
        synchronized(lock) {
            tasks[seeded.fileId] = Task(seeded.fileId, handle, savePath, seeded.name, true)
        }
    }

    /**
     * super-seeding 的**真值**：直接读 libtorrent 的 `torrent::flags()` 那一位。
     *
     * 这一位在 libtorrent 源码里位于 `#ifndef TORRENT_DISABLE_SUPERSEEDING` 之内
     * （`torrent::flags()`，见 tools/build-libtorrent-android/build3/src/libtorrent4j/swig/deps/
     * libtorrent/src/torrent.cpp），所以读回 true 同时说明两件事：参数进了 native、
     * 且这份 native 库把这个特性编译进来了。编译掉的库里这一位恒为 0，不会静默骗人。
     */
    override fun superSeeding(fileId: String): Boolean {
        val handle = synchronized(lock) { tasks[fileId]?.handle } ?: return false
        return try {
            (handle.flags.to_int() and SUPER_SEEDING_BIT) != 0
        } catch (e: Throwable) {
            false
        }
    }

    /** 运行中改 super-seeding（BEP 16 允许中途开关）。成功与否用 [superSeeding] 读回判定。 */
    override fun setSuperSeeding(fileId: String, on: Boolean): Boolean {
        val handle = synchronized(lock) { tasks[fileId]?.handle } ?: return false
        return try {
            val flag = superSeedingFlag()
            if (on) handle.setFlags(flag) else handle.unsetFlags(flag)
            true
        } catch (e: Throwable) {
            false
        }
    }

    fun addDownload(fileId: String, torrentBytes: ByteArray, savePath: File): Boolean {
        synchronized(lock) { if (tasks.containsKey(fileId)) return true }
        return try {
            val ti = TorrentInfo(torrentBytes)
            val handle = addTorrent(ti, savePath)
            synchronized(lock) {
                tasks[fileId] = Task(fileId, handle, savePath, ti.name(), false)
            }
            true
        } catch (e: Throwable) {
            false
        }
    }

    override fun connectPeer(fileId: String, host: String, port: Int): Boolean {
        val handle = synchronized(lock) { tasks[fileId]?.handle } ?: return false
        return try {
            handle.swig().connect_peer(TcpEndpoint(host, port).swig())
            true
        } catch (e: Throwable) {
            false
        }
    }

    /** 把 torrent 从会话里摘掉（休眠 / 取消）。文件留在盘上。 */
    override fun removeTorrent(fileId: String): Boolean {
        val task = synchronized(lock) { tasks.remove(fileId) } ?: return false
        try {
            manager?.remove(task.handle)
        } catch (e: Throwable) {
            // 会话已经停了就没什么可摘的；文件本来也留在盘上。
        }
        return true
    }

    override fun downloadRate(fileId: String): Long = status(fileId, true)?.downloadPayloadRate()?.toLong() ?: 0L

    override fun uploadRate(fileId: String): Long = status(fileId, true)?.uploadPayloadRate()?.toLong() ?: 0L

    override fun setUploadLimit(fileId: String, kbps: Int) {
        val handle = synchronized(lock) { tasks[fileId]?.handle } ?: return
        // 单位是 KB/s（P4 的「单文件限速」标的就是 KB/s）：直接乘 1024 得字节/秒。
        // 早先写成 *1024/8 是按 kbit/s 算的，实际限速只有标称值的 1/8。
        handle.setUploadLimit(if (kbps <= 0) 0 else kbps * 1024)
    }

    // ---------------------------------------------------------------- 会话级（P7 全局限速）

    /**
     * 会话级上行限速，入参 **KB/s**（0 = 不限）：内部 ×1024 交给 libtorrent（字节/秒）。
     *
     * 走 `SessionManager.applySettings`，所以**运行期立即生效**，不是只写进「下次建会话」的
     * settings_pack：libtorrent 的 upload_rate_limit 是会话级带宽通道，当前会话里所有 torrent
     * （含已经在传的）共用它，不需要逐个 torrent 重设。单文件限速（[setUploadLimit]）是另一层，
     * 两者同时存在时每个 torrent 取更小的那个。
     *
     * 会话还没起来时先记下，[start] 时带进 settings_pack。
     */
    fun setSessionUploadLimit(kbps: Int) {
        sessionUploadKbps = saneKbps(kbps)
        applySessionLimits()
    }

    /** 会话级下行限速，口径同 [setSessionUploadLimit]。 */
    fun setSessionDownloadLimit(kbps: Int) {
        sessionDownloadKbps = saneKbps(kbps)
        applySessionLimits()
    }

    /**
     * 上行限速**真值**（KB/s）：读的是会话里生效的那个值（`session::get_settings()` 会同步到
     * 会话线程再返回），不是本类自己的缓存。会话没起来时返回待生效的目标值。
     */
    fun sessionUploadLimitKbps(): Int = liveSessionLimit(upload = true)

    /** 下行限速真值（KB/s），口径同 [sessionUploadLimitKbps]。 */
    fun sessionDownloadLimitKbps(): Int = liveSessionLimit(upload = false)

    /**
     * 私网 / 回环对端是否也受会话级限速约束（读会话当前的 peer class filter）。
     *
     * libtorrent 默认把局域网与回环对端放进 local peer class，而 upload_rate_limit /
     * download_rate_limit 只作用在 **global** peer class 上（settings_pack 原文：
     * "By default peers on the local network are not rate limited."）。不并类的话，
     * 本产品的全部流量（私有模式：局域网 + 回环）都不受限，读数却仍然是「已设置」。
     * [bindLocalPeersToGlobalClass] 在 [start] 里把这个洞补掉，这里给出可断言的结果。
     */
    fun localPeersRateLimited(): Boolean {
        val mgr = manager ?: return false
        return try {
            val filter = mgr.swig().get_peer_class_filter()
            val globalBits = parseAddress(PROBE_NON_LOCAL)?.let { filter.access(it) } ?: return false
            val localBits = parseAddress(PROBE_LOCAL)?.let { filter.access(it) } ?: return false
            globalBits > 0L && (localBits and globalBits) == globalBits
        } catch (e: Throwable) {
            false
        }
    }

    override fun progress(fileId: String): Double {
        val st = status(fileId, true) ?: return 0.0
        return st.progress().toDouble()
    }

    override fun isFinished(fileId: String): Boolean {
        val st = status(fileId, false) ?: return false
        return st.isFinished
    }

    override fun peers(fileId: String): Int {
        val st = status(fileId, false) ?: return 0
        return st.numPeers()
    }

    /**
     * libtorrent 视角的一条对端连接（只读快照）。
     *
     * peer_info 只给 IP、不给端口，所以「连接 → 群成员」的匹配只能按 host 做，由 GroupRuntime 负责；
     * 这里只把判定所需的四个量取出来：是不是真握上手了、对端完成度、对端是不是做种者。
     */
    class PeerSnapshot(
        /**
         * 对端端点，**已经去掉 scheme**：libtorrent4j 的 `peer_info.ip()` 返回的是
         * `tcp://127.0.0.1:53513` 这种带协议与端口的形式，这里统一成 `127.0.0.1:53513`。
         * 端口对局域网成员是它的监听端口（本机拨出去的连接），入向连接拿到的是临时端口。
         */
        val ip: String,
        /** 对端完成度 0..1_000_000（1_000_000 = 对端数据完整）。 */
        val progressPpm: Int,
        /** 握手是否已完成（peer_info 的 connecting / handshake 位都没置）。 */
        val connected: Boolean,
        /** 对端自报是 seed（peer_info 的 seed 位，或完成度满）。 */
        val seed: Boolean,
    )

    /**
     * 该 torrent 当前的 BT 对端列表。
     *
     * 这是「队友数」与「成员是否已互连」的唯一数据来源（产品规格 §306 的观测口径）：
     * 不看成员表、不看协议帧。torrent 不在会话里、或 handle 已失效时返回空表，不抛异常。
     */
    override fun peerSnapshot(fileId: String): List<PeerSnapshot> {
        val handle = synchronized(lock) { tasks[fileId]?.handle } ?: return emptyList()
        return snapshotOf(handle)
    }

    /**
     * 做种侧健康状态（巡查对账用）：
     *   finished   数据完整，可以对外做种
     *   checking   正在做片上校验（结果还没出来，先不下结论）
     *   pending    刚挂上、元数据/状态还没就绪（同上）
     *   incomplete 校验过但缺片 —— 不能当作做种，否则对端会连上一个拿不到数据的种子
     *   missing    torrent 已经不在会话里
     *
     * 判定顺序有意义：`isFinished` 先看，避免「校验已过但 state 还停在 downloading」被误判成缺片。
     */
    override fun seedState(fileId: String): String {
        val st = status(fileId, false) ?: return "missing"
        if (st.isFinished) return "finished"
        return when (st.state()) {
            TorrentStatus.State.CHECKING_FILES, TorrentStatus.State.CHECKING_RESUME_DATA -> "checking"
            TorrentStatus.State.UNKNOWN, TorrentStatus.State.DOWNLOADING_METADATA -> "pending"
            else -> "incomplete"
        }
    }

    override fun savedFile(fileId: String): File? {
        val task = synchronized(lock) { tasks[fileId] } ?: return null
        return File(task.savePath, task.name)
    }

    private fun status(fileId: String, accurate: Boolean): TorrentStatus? {
        val handle = synchronized(lock) { tasks[fileId]?.handle } ?: return null
        return try {
            handle.status(accurate)
        } catch (e: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 让操作系统给一个当前空闲的 TCP 端口（用完立刻释放，交给 libtorrent 再绑一次）。 */
    private fun freePort(): Int? = try {
        java.net.ServerSocket(0).use { it.localPort }
    } catch (e: Throwable) {
        null
    }

    /** KB/s → 字节/秒，顺带把负值归零、把会溢出的超大值夹到上限。 */
    private fun saneKbps(kbps: Int): Int = kbps.coerceIn(0, MAX_KBPS)

    private fun applySessionLimits() {
        val mgr = manager ?: return // 还没起会话：值已经记下，start() 会带进 settings_pack
        try {
            // 两个限额放在同一个 pack 里下发：一次 apply_settings，不会出现「上行改了、下行还是旧的」的中间态。
            val pack = SettingsPack()
            pack.uploadRateLimit(sessionUploadKbps * 1024)
            pack.downloadRateLimit(sessionDownloadKbps * 1024)
            mgr.applySettings(pack)
            rateLimitError = ""
        } catch (e: Throwable) {
            rateLimitError = e.toString()
        }
    }

    private fun liveSessionLimit(upload: Boolean): Int {
        val wanted = if (upload) sessionUploadKbps else sessionDownloadKbps
        val mgr = manager ?: return wanted
        return try {
            val pack = mgr.settings()
            (if (upload) pack.uploadRateLimit() else pack.downloadRateLimit()) / 1024
        } catch (e: Throwable) {
            rateLimitError = e.toString()
            wanted
        }
    }

    private fun parseAddress(text: String): org.libtorrent4j.swig.address? = try {
        val ec = org.libtorrent4j.swig.error_code()
        org.libtorrent4j.swig.address.from_string(text, ec)
    } catch (e: Throwable) {
        null
    }

    /**
     * 把私网段与回环段的地址**同时**映射进 local 与 global 两个 peer class。
     *
     * 为什么非改不可：libtorrent 的 `upload_rate_limit` / `download_rate_limit` 只作用在
     * global peer class 上，而私网 / 回环对端默认只挂 local class（`init_peer_class_filter(true)`），
     * 于是「全局限速」在局域网与回环上完全不生效 —— 读数照样是设置过的值，骗人。
     * 实测（2.1.0-39，16 MiB loopback）：设 2048 KB/s 之后仍然按 31 MB/s 跑完；
     * 并类之后同样的流量被压到 ≈1.4 MB/s。
     *
     * 两个类都保留：local 类那一份带着「始终 unchoke、连接上限 150%」的属性，去掉会改变
     * 局域网内的起步行为；两者的限额都参与 `request_bandwidth` 的额度计算（取最小）。
     * 类位不写死，从会话当前的 filter 读回（`access()`）；读不到就什么都不做，只记错误。
     */
    private fun bindLocalPeersToGlobalClass(mgr: SessionManager) {
        try {
            val current = mgr.swig().get_peer_class_filter()
            val probeGlobal = parseAddress(PROBE_NON_LOCAL) ?: return
            val probeLocal = parseAddress(PROBE_LOCAL) ?: return
            val globalBits = current.access(probeGlobal)
            val localBits = current.access(probeLocal)
            if (globalBits <= 0L || localBits <= 0L) return
            if ((localBits and globalBits) == globalBits) return // 已经并好了
            val ec = org.libtorrent4j.swig.error_code()
            fun addr(text: String) = org.libtorrent4j.swig.address.from_string(text, ec)
            val filter = org.libtorrent4j.swig.ip_filter()
            // 规则是「地址区间 → flags」的覆盖：先全量给 global，再把私网段并成 global|local。
            filter.add_rule(addr("0.0.0.0"), addr("255.255.255.255"), globalBits)
            for (range in IpAllowlist.BASE_V4) {
                filter.add_rule(addr(range.first), addr(range.second), globalBits or localBits)
            }
            filter.add_rule(addr("::"), addr("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"), globalBits)
            for (range in IpAllowlist.BASE_V6) {
                filter.add_rule(addr(range.first), addr(range.second), globalBits or localBits)
            }
            mgr.swig().set_peer_class_filter(filter)
            rateLimitError = ""
        } catch (e: Throwable) {
            rateLimitError = e.toString()
        }
    }

    private fun addTorrent(ti: TorrentInfo, savePath: File, extraFlags: torrent_flags_t? = null): TorrentHandle {
        // 归属校验放在最前面：private=0 的种子属于公开会话，进到这里就是路由写错了。
        require(accepts(ti.isPrivate)) {
            "public torrent must go to PublicBtSession (private session only takes private=1)"
        }
        val mgr = manager ?: throw IllegalStateException("BT session not started")
        savePath.mkdirs()
        val atp = org.libtorrent4j.swig.add_torrent_params()
        atp.set_ti(ti.swig())
        atp.setSave_path(savePath.absolutePath)
        atp.setFlags(if (extraFlags == null) PRIVATE_FLAGS else PRIVATE_FLAGS.or_(extraFlags))
        val ec = org.libtorrent4j.swig.error_code()
        val handle = mgr.swig().add_torrent(atp, ec)
        check(ec.value() == 0) { "add_torrent failed: " + ec.message() }
        return TorrentHandle(handle)
    }

    /** 协议 §8：settings_pack 关掉一切公网发现；ip_filter 只放行内网与回环。 */
    private fun hardenedSettings(port: Int): SettingsPack {
        val s = SettingsPack()
        s.setEnableDht(false)
        s.setEnableLsd(false)
        s.setDhtBootstrapNodes("")
        s.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false)
        s.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false)
        s.setBoolean(settings_pack.bool_types.enable_ip_notifier.swigValue(), false)
        s.setBoolean(settings_pack.bool_types.allow_multiple_connections_per_ip.swigValue(), true)
        s.setString(settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:" + port)
        // 会话级限速：建会话时按宿主设过的目标值带上（运行期改走 applySettings，见 setSessionUploadLimit）。
        s.uploadRateLimit(sessionUploadKbps * 1024)
        s.downloadRateLimit(sessionDownloadKbps * 1024)
        return s
    }

    // ------------------------------------------------------------------ 动态 ip_filter（AGENTS §9）

    /**
     * 更新动态白名单：消息层确认的群成员公网 IP 字面量（来源与移除策略见 docs/core.md §3.7）。
     *
     * 整份重建而不是增量合并：libtorrent 的 `ip_filter` 内部是地址区间表，重建一次几微秒，
     * 比维护「哪条规则对应哪个成员」的账本简单，也不会留下已经失效的地址。返回是否变化。
     *
     * 会话没起来时只记在内存里，[start] 建会话时带进 session_params；起来了就
     * `session::set_ip_filter` 立即生效（libtorrent 只对**新**连接应用，已在传的连接不受影响）。
     */
    fun updatePublicAllowlist(hosts: Collection<String>): Boolean {
        val next = IpAllowlist.collect(hosts)
        val changed = synchronized(allowLock) {
            if (publicAllow == next) {
                false
            } else {
                publicAllow.clear()
                publicAllow.addAll(next)
                true
            }
        }
        if (changed) applyIpFilterToSession()
        return changed
    }

    /** 当前动态白名单快照（诊断 / 验收读口）。 */
    fun publicAllowlist(): Set<String> = synchronized(allowLock) { LinkedHashSet(publicAllow) }

    /**
     * 这个地址现在会不会被 ip_filter 放行（诊断 / 验收读口）。
     *
     * 会话起来后读的是**会话里生效的那份 filter**（`session::get_ip_filter().access()`）——
     * 断言的是 libtorrent 真正看到的东西，不是本类的缓存。会话没起来时按同一套规则算。
     */
    fun ipFilterAllows(host: String): Boolean {
        val normalized = IpAllowlist.normalizeLiteral(host) ?: return false
        val mgr = manager
        if (mgr != null) {
            return try {
                val addr = parseAddress(normalized) ?: return false
                mgr.swig().get_ip_filter().access(addr) == ALLOW
            } catch (e: Throwable) {
                false
            }
        }
        return IpAllowlist.isPrivateLiteral(normalized) ||
            synchronized(allowLock) { publicAllow.contains(normalized) }
    }

    /** 建会话前的 ip_filter：基础私网段 + 当前动态白名单。 */
    private fun applyIpFilter(params: SessionParams) {
        try {
            params.swig().setIp_filter(buildIpFilter())
        } catch (e: Throwable) {
            // ip_filter 是纵深防御；拿不到就退回「私有种子 + DHT/PEX/LSD 全关」。
            ipFilterError = e.toString()
        }
    }

    /** 运行期重建整份 ip_filter 并交给会话（不重启会话、不影响已建立的连接）。 */
    private fun applyIpFilterToSession() {
        val mgr = manager ?: return
        try {
            mgr.swig().set_ip_filter(buildIpFilter())
            ipFilterError = ""
        } catch (e: Throwable) {
            ipFilterError = e.toString()
        }
    }

    private fun buildIpFilter(): org.libtorrent4j.swig.ip_filter {
        val filter = org.libtorrent4j.swig.ip_filter()
        val ec = org.libtorrent4j.swig.error_code()
        fun addr(text: String) = org.libtorrent4j.swig.address.from_string(text, ec)
        // 规则是「地址区间 → flags」的覆盖，后加的区间覆盖先加的，与 add 顺序无关；
        // 先全禁，再放行基础私网段，最后逐个放行动态白名单里的公网字面量。
        filter.add_rule(addr("0.0.0.0"), addr("255.255.255.255"), BLOCK)
        for ((first, last) in IpAllowlist.BASE_V4) {
            filter.add_rule(addr(first), addr(last), ALLOW)
        }
        val hosts = synchronized(allowLock) { publicAllow.toList() }
        for (host in hosts) {
            val single = addr(host)
            if (single.is_v4) filter.add_rule(single, single, ALLOW)
            if (single.is_v6) filter.add_rule(single, single, ALLOW)
        }
        filter.add_rule(addr("::"), addr("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"), BLOCK)
        for ((first, last) in IpAllowlist.BASE_V6) {
            filter.add_rule(addr(first), addr(last), ALLOW)
        }
        for (host in hosts) {
            val single = addr(host)
            if (single.is_v6) filter.add_rule(single, single, ALLOW)
        }
        return filter
    }

    companion object {
        /** DISABLE_DHT(0x80000) | DISABLE_LSD(0x100000) | DISABLE_PEX(0x200000) = 0x380000。 */
        val PRIVATE_FLAGS = TorrentFlags.DISABLE_DHT
            .or_(TorrentFlags.DISABLE_LSD)
            .or_(TorrentFlags.DISABLE_PEX)

        private const val BLOCK = 1L
        private const val ALLOW = 0L

        /** KB/s 的上限：libtorrent 的限额是 int 字节/秒，再大就溢出了。 */
        private const val MAX_KBPS = Int.MAX_VALUE / 1024

        /** 「端口全被占」时向系统要空闲端口的次数。 */
        private const val FALLBACK_PORT_TRIES = 5

        /** peer class filter 的两个探针地址：非私网（global 类）、回环（local 类）。 */
        private const val PROBE_NON_LOCAL = "8.8.8.8"
        private const val PROBE_LOCAL = "127.0.0.1"

        /**
         * `torrent_flags::super_seeding`（第 8 位，0x100）。
         *
         * 第一次用时才取，理由同 SEED_BIT：类加载阶段碰 swig 会分配 native 对象，此时 JNI 未必就绪。
         */
        private fun superSeedingFlag(): torrent_flags_t = TorrentFlags.SUPER_SEEDING

        /** 同一位的整数形式（读回真值用）。 */
        private val SUPER_SEEDING_BIT: Int by lazy { superSeedingFlag().to_int() }

        /**
         * peer_info.flags 的两个位，第一次用时才取。
         *
         * 不能在类加载阶段碰 swig：那几条静态字段会分配 native 对象，此时 JNI 库未必已经加载。
         */
        private val SEED_BIT: Int by lazy { org.libtorrent4j.swig.peer_info.seed.to_int() }

        /** connecting / handshake：位还举着就说明还没真握上手，不算「互连」。 */
        private val UNCONNECTED_BITS: Int by lazy {
            org.libtorrent4j.swig.peer_info.connecting.to_int() or
                org.libtorrent4j.swig.peer_info.handshake.to_int()
        }

        /**
         * `tcp://127.0.0.1:53513` → `127.0.0.1:53513`。
         *
         * peer_info.ip() 是带 scheme 的端点串（tcp / utp / ssl 前缀），群成员表里记的是裸 host:port。
         * 不归一化就永远对不上，队友数会恒为 0（这是 harness 实测踩到的坑，见 docs/core.md §3.4）。
         */
        internal fun normalizeEndpoint(raw: String): String {
            var s = raw
            val at = s.indexOf("://")
            if (at >= 0) s = s.substring(at + 3)
            return s.trim()
        }

        /**
         * 从一个 torrent handle 取对端快照。私有 / 公开两个会话共用这一份字段口径：
         * 队友数与「成员是否已互连」在两个会话里必须是同一个判定（public-swarm §2.3）。
         */
        internal fun snapshotOf(handle: TorrentHandle): List<PeerSnapshot> {
            val raw = try {
                handle.peerInfo()
            } catch (e: Throwable) {
                return emptyList()
            }
            if (raw.isEmpty()) return emptyList()
            val seedBit = SEED_BIT
            val busyBits = UNCONNECTED_BITS
            val out = ArrayList<PeerSnapshot>(raw.size)
            for (p in raw) {
                val ip = normalizeEndpoint(try { p.ip() } catch (e: Throwable) { "" })
                if (ip.isEmpty()) continue
                val flags = try { p.flags() } catch (e: Throwable) { 0 }
                val ppm = try { p.progressPpm() } catch (e: Throwable) { 0 }
                out.add(
                    PeerSnapshot(
                        ip = ip,
                        progressPpm = ppm,
                        connected = (flags and busyBits) == 0,
                        seed = (flags and seedBit) != 0 || ppm >= 1_000_000,
                    ),
                )
            }
            return out
        }

        /** 协议 §4 的 pieceSize 规则 + 「>8192 片则翻倍」。 */
        fun pieceSizeFor(size: Long): Int {
            var piece = when {
                size <= 64L * 1024 * 1024 -> 64 * 1024
                size <= 512L * 1024 * 1024 -> 256 * 1024
                size <= 4L * 1024 * 1024 * 1024 -> 1024 * 1024
                size <= 16L * 1024 * 1024 * 1024 -> 2 * 1024 * 1024
                else -> 4 * 1024 * 1024
            }
            while (size / piece > 8192) piece *= 2
            return piece
        }
    }
}
