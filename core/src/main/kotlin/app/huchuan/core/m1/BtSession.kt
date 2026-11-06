package app.huchuan.core.m1

import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.swig.settings_pack
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
        try { mgr.stop() } catch (_: Throwable) {}
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

    fun addSeed(seeded: SeededTorrent, savePath: File) {
        val ti = TorrentInfo(seeded.torrentBytes)
        val handle = addTorrent(ti, savePath)
        synchronized(lock) {
            tasks[seeded.fileId] = Task(seeded.fileId, handle, savePath, seeded.name, true)
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

    private fun addTorrent(ti: TorrentInfo, savePath: File): TorrentHandle {
        val mgr = manager ?: throw IllegalStateException("BT session not started")
        savePath.mkdirs()
        val atp = org.libtorrent4j.swig.add_torrent_params()
        atp.set_ti(ti.swig())
        atp.setSave_path(savePath.absolutePath)
        atp.setFlags(PRIVATE_FLAGS)
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
