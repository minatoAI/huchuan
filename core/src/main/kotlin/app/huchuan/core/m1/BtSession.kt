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
 */
class BtSession(
    private val portHint: Int,
    private val saveRoot: File,
    private val ipFilterEnabled: Boolean = true,
) {
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

    fun start(): Int {
        manager?.let { return listenPort }
        val candidates = ArrayList<Int>()
        if (portHint > 0) for (i in 0 until 100) candidates.add(portHint + i)
        candidates.add(0)

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
    fun superSeeding(fileId: String): Boolean {
        val handle = synchronized(lock) { tasks[fileId]?.handle } ?: return false
        return try {
            (handle.flags.to_int() and SUPER_SEEDING_BIT) != 0
        } catch (e: Throwable) {
            false
        }
    }

    /** 运行中改 super-seeding（BEP 16 允许中途开关）。成功与否用 [superSeeding] 读回判定。 */
    fun setSuperSeeding(fileId: String, on: Boolean): Boolean {
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

    fun connectPeer(fileId: String, host: String, port: Int): Boolean {
        val handle = synchronized(lock) { tasks[fileId]?.handle } ?: return false
        return try {
            handle.swig().connect_peer(TcpEndpoint(host, port).swig())
            true
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * `tcp://127.0.0.1:53513` → `127.0.0.1:53513`。
     *
     * peer_info.ip() 是带 scheme 的端点串（tcp / utp / ssl 前缀），群成员表里记的是裸 host:port。
     * 不归一化就永远对不上，队友数会恒为 0（这是 harness 实测踩到的坑，见 docs/core.md §3.4）。
     */
    private fun normalizeEndpoint(raw: String): String {
        var s = raw
        val at = s.indexOf("://")
        if (at >= 0) s = s.substring(at + 3)
        return s.trim()
    }

    /** 把 torrent 从会话里摘掉（休眠 / 取消）。文件留在盘上。 */
    fun removeTorrent(fileId: String): Boolean {
        val task = synchronized(lock) { tasks.remove(fileId) } ?: return false
        try {
            manager?.remove(task.handle)
        } catch (e: Throwable) {
            // 会话已经停了就没什么可摘的；文件本来也留在盘上。
        }
        return true
    }

    fun downloadRate(fileId: String): Long = status(fileId, true)?.downloadPayloadRate()?.toLong() ?: 0L

    fun uploadRate(fileId: String): Long = status(fileId, true)?.uploadPayloadRate()?.toLong() ?: 0L

    fun setUploadLimit(fileId: String, kbps: Int) {
        val handle = synchronized(lock) { tasks[fileId]?.handle } ?: return
        // 单位是 KB/s（P4 的「单文件限速」标的就是 KB/s）：直接乘 1024 得字节/秒。
        // 早先写成 *1024/8 是按 kbit/s 算的，实际限速只有标称值的 1/8。
        handle.setUploadLimit(if (kbps <= 0) 0 else kbps * 1024)
    }

    fun progress(fileId: String): Double {
        val st = status(fileId, true) ?: return 0.0
        return st.progress().toDouble()
    }

    fun isFinished(fileId: String): Boolean {
        val st = status(fileId, false) ?: return false
        return st.isFinished
    }

    fun peers(fileId: String): Int {
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
    fun peerSnapshot(fileId: String): List<PeerSnapshot> {
        val handle = synchronized(lock) { tasks[fileId]?.handle } ?: return emptyList()
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
    fun seedState(fileId: String): String {
        val st = status(fileId, false) ?: return "missing"
        if (st.isFinished) return "finished"
        return when (st.state()) {
            TorrentStatus.State.CHECKING_FILES, TorrentStatus.State.CHECKING_RESUME_DATA -> "checking"
            TorrentStatus.State.UNKNOWN, TorrentStatus.State.DOWNLOADING_METADATA -> "pending"
            else -> "incomplete"
        }
    }

    fun savedFile(fileId: String): File? {
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

    private fun addTorrent(ti: TorrentInfo, savePath: File, extraFlags: torrent_flags_t? = null): TorrentHandle {
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
        s.uploadRateLimit(0)
        s.downloadRateLimit(0)
        return s
    }

    private fun applyIpFilter(params: SessionParams) {
        try {
            val filter = org.libtorrent4j.swig.ip_filter()
            val ec = org.libtorrent4j.swig.error_code()
            fun addr(text: String) = org.libtorrent4j.swig.address.from_string(text, ec)
            // 顺序有意义：libtorrent 从后往前匹配，先全禁再放行内网。
            filter.add_rule(addr("0.0.0.0"), addr("255.255.255.255"), BLOCK)
            filter.add_rule(addr("127.0.0.0"), addr("127.255.255.255"), ALLOW)
            filter.add_rule(addr("10.0.0.0"), addr("10.255.255.255"), ALLOW)
            filter.add_rule(addr("172.16.0.0"), addr("172.31.255.255"), ALLOW)
            filter.add_rule(addr("192.168.0.0"), addr("192.168.255.255"), ALLOW)
            filter.add_rule(addr("169.254.0.0"), addr("169.254.255.255"), ALLOW)
            filter.add_rule(addr("::"), addr("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"), BLOCK)
            filter.add_rule(addr("::1"), addr("::1"), ALLOW)
            filter.add_rule(addr("fc00::"), addr("fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"), ALLOW)
            filter.add_rule(addr("fe80::"), addr("febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff"), ALLOW)
            params.swig().setIp_filter(filter)
        } catch (e: Throwable) {
            // ip_filter 只是纵深防御；拿不到就退回「私有种子 + DHT/PEX/LSD 全关」。
        }
    }

    companion object {
        /** DISABLE_DHT(0x80000) | DISABLE_LSD(0x100000) | DISABLE_PEX(0x200000) = 0x380000。 */
        val PRIVATE_FLAGS = TorrentFlags.DISABLE_DHT
            .or_(TorrentFlags.DISABLE_LSD)
            .or_(TorrentFlags.DISABLE_PEX)

        private const val BLOCK = 1L
        private const val ALLOW = 0L

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
