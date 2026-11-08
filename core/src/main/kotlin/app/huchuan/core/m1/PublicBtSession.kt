package app.huchuan.core.m1

import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.alerts.TorrentFinishedAlert
import org.libtorrent4j.alerts.TrackerAnnounceAlert
import org.libtorrent4j.alerts.TrackerErrorAlert
import org.libtorrent4j.alerts.TrackerReplyAlert
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 公开 BT 会话（public-swarm §2.3，AGENTS §9）：**独立** SessionManager，不复用 [BtSession]。
 *
 * | 项 | 值 |
 * |---|---|
 * | enable_dht | 开（关了就找不到 peer） |
 * | enable_lsd | 关（把公开 infohash 广播给整网段，收益为零） |
 * | PEX | 开 —— per-torrent 不设 DISABLE_PEX（PEX 没有会话级开关） |
 * | uTP | 显式开（公网 peer 里 uTP 占比高） |
 * | ip_filter | **不设**（设了就永远连不到公网 peer） |
 * | 端口 | 6881 起顺延，与私有段的 51413 起不重叠 |
 * | 目录 | `<data>/public/{data,meta}` |
 * | 日志 | 事件名带 `pub_` 前缀 + `sess=pub` 字段 |
 *
 * 加种归属只看种子自身的 private 位（BEP 27）：private=1 的一律拒绝（[accepts]），
 * public 会话只收 public=0 的种子 —— 与 [BtSession] 的拒绝方向相反，两边合起来才是
 * 「加种前一律按 TorrentInfo.priv() 决定用哪个会话」（public-swarm §1.2）。
 *
 * 生命周期按需：有非休眠公开条目才 [start]，全部休眠 / 移除后 [stop]（顺带撤端口映射）。
 * 本类不自己判断「还有没有活」，由 [M1Kernel] 在群状态变化时调用。
 */
class PublicBtSession(
    /** `<data>/public`；不会与私有会话的 `<data>/bt` 混用。 */
    private val dataRoot: File,
    private val portHint: Int = DEFAULT_PORT,
    private val events: KernelEvents? = null,
) : LocalSession {

    private class Task(
        val fileId: String,
        val handle: TorrentHandle,
        val savePath: File,
        val name: String,
        var seeding: Boolean,
        /**
         * 「只为取元数据而生」：磁力导入建的任务是 true —— 元数据一到位就把这个 torrent 从会话里
         * **摘掉**（`.torrent` 已经落进缓存），用户点「激活」时再按缓存加种，一个数据字节都不下
         * （docs/core.md §3.12）。发布 / 续种 / 激活路径建的任务是 false。
         *
         * 为什么是「摘掉」而不是 libtorrent 的 `pause()`：暂停过的 torrent 恢复之后要等下一次
         * **周期** announce 才重新拉 peer（实测 57 s，且 `force_reannounce` 不生效），
         * 用户点「激活」会先干等一分钟；按缓存重新加种当场就 announce + 连 peer（实测 1 s 内出数据）。
         */
        var importOnly: Boolean = false,
    )

    /** 一个 tracker 最近一次 announce 的结果（诊断读口用，不是判定）。 */
    private class TrackerReport(
        var url: String,
        var ok: Boolean = false,
        var state: String = "pending",
        var lastAt: Long = 0L,
        var peers: Int = 0,
        var failures: Int = 0,
        var error: String = "",
    )

    /** 一次磁力导入的等待槽：元数据到手 / 保存 .torrent 两个闸门。 */
    private class MetadataWait {
        val received = CountDownLatch(1)
        val saved = CountDownLatch(1)

        @Volatile
        var failed: Boolean = false

        /**
         * 用户取消：内核把这条任务摘掉了（[cancelAcquire]）。两个闸门同时放开，等着的
         * [addMagnet] 立刻以 [PublicMagnetResult.CANCELLED] 返回，而不是等到超时再报
         * 「找不到来源」—— 用户点了停止，不该收到一条失败。
         */
        @Volatile
        var cancelled: Boolean = false

        @Volatile
        var torrentBytes: ByteArray? = null
    }

    /** 一次「元数据到手」的事实（名字 / 尺寸 / 分片 / .torrent 缓存路径）。 */
    class MetadataArrival(
        val fileId: String,
        val name: String,
        val size: Long,
        val pieceSize: Int,
        val torrentPath: String,
        val privateTorrent: Boolean,
    )

    /**
     * 公开会话的诊断快照（daemon 的 `/api/state.public` 直接把它摊成 JSON）。
     *
     * 这三样以前都没有读口（docs/reports/public-swarm-real-world.md 风险 3）：
     * DHT 节点数读的是**私有会话**（DHT 关，恒 0）、tracker announce 的结果只能靠外部探针猜、
     * 当前 peer 端点列表干脆拿不到。全部在这里补齐，字段来源只有 libtorrent：
     *
     * | 字段 | 来源 |
     * |---|---|
     * | `dhtNodes` | `SessionManager.dhtNodes()`（公开会话自己的 DHT） |
     * | `trackers[].ok/failures/error` | `tracker_reply` / `tracker_error` 告警 |
     * | `trackers[].lastAnnounceAt` | `tracker_announce` 告警的时刻 |
     * | `trackers[].nextAnnounceAt` | `torrent_status.next_announce`（内核给的下次间隔）+ 读的时刻 |
     * | `peers[]` | `peer_info` 快照（与队友数同一个口径 [BtSession.snapshotOf]） |
     *
     * **只读、不参与任何判定**：状态机读的是 [isPaused] / [hasMetadata] 这些真值，不是这张表。
     */
    class Diagnostics(
        val running: Boolean,
        val port: Int,
        val torrents: Int,
        val dhtNodes: Long,
        val dhtRunning: Boolean,
        val portMapping: Boolean,
        val lastError: String,
        val trackers: List<Tracker>,
        val peers: List<PeerEndpoint>,
    ) {
        /** 一条 tracker 的最近一次 announce 结果。 */
        class Tracker(
            val fileId: String,
            val url: String,
            /** true = 最近一次 announce 有应答；false = 失败或还没跑过。 */
            val ok: Boolean,
            /** replied / failed / announcing / pending。 */
            val state: String,
            /** 最近一次收到应答 / 报错的时刻（ms）；还没跑过是 0。 */
            val lastAnnounceAt: Long,
            /**
             * 内核报的「距下次 announce 还有多少**毫秒**」（`torrent_status.next_announce`）。
             * 拿不到 / 挂起中（暂停的 torrent 不排 announce）是 0。实测 2 s 采样每次减 2000，
             * 单位是毫秒不是秒（探针见 docs/core.md §3.12）。
             */
            val nextAnnounceInMs: Long,
            /** `nextAnnounceInMs` 换算出的绝对时刻（ms）；未知是 0。 */
            val nextAnnounceAt: Long,
            /** 最近一次应答里对端报的 peer 数（失败时为 0）。 */
            val peers: Int,
            /** 连续失败次数（成功即清零）。 */
            val failures: Int,
            /** 失败原因原文（跟踪的是 libtorrent 给的那句话，不是我们自己编的）。 */
            val error: String,
        )

        /** 当前连着的一个 peer 端点（诊断用；队友数另有口径，不读这张表）。 */
        class PeerEndpoint(
            val fileId: String,
            /** `host:port`（已去掉 tcp:// / utp:// 前缀）。 */
            val endpoint: String,
            val connected: Boolean,
            val seed: Boolean,
        )
    }

    private val lock = Any()
    private var manager: SessionManager? = null
    private val tasks = LinkedHashMap<String, Task>()
    private val waiters = HashMap<String, MetadataWait>()

    /**
     * `fileId → tracker URL → 最近一次 announce 的结果`（告警线程写、诊断读口读，都在 [lock] 里）。
     *
     * 公开会话的 `alert_mask` 原本不含 tracker 类（docs/core.md §3.8），所以「announce 到底成没成」
     * 一直只能靠外部探针猜。本轮把它加进 mask 与 listener，只为了这个读口 —— 判定逻辑一行没变。
     */
    private val trackerReports = HashMap<String, LinkedHashMap<String, TrackerReport>>()

    /**
     * 摘任务的专用线程（单线程、懒建、随会话关掉）：磁力导入的「元数据到手就摘掉」可能发生在
     * libtorrent 的告警线程上，那边只该往队列里丢一件事，不该自己去改会话。
     */
    @Volatile
    private var retireExecutor: java.util.concurrent.ExecutorService? = null

    /** 公开下载落盘目录（`<data>/public/data`，tech-options §6.4 的独立下载目录）。 */
    val dataDir: File get() = File(dataRoot, DATA_SUBDIR)

    /** .torrent 缓存目录（`<data>/public/meta`）：元数据到手就落盘，重启不再重拉。 */
    val metaDir: File get() = File(dataRoot, META_SUBDIR)

    @Volatile
    var listenPort: Int = 0
        private set

    /** 起不来的原因（"" = 正常）。给内核打日志、给诊断页读。 */
    @Volatile
    var lastError: String = ""
        private set

    /** 端口映射（UPnP / NAT-PMP）当前是否打开；按需开关，默认关（§2.3 裁决 2）。 */
    @Volatile
    var portMappingEnabled: Boolean = false
        private set

    @Volatile
    private var sessionUploadKbps: Int = 0

    @Volatile
    private var sessionDownloadKbps: Int = 0

    /**
     * 完成告警出口，口径同 [BtSession.onTorrentFinished]：只把 fileId 交给内核的队列，
     * 回调运行在 libtorrent 的告警线程上，这里不做任何判定。
     */
    @Volatile
    var onTorrentFinished: ((String) -> Unit)? = null

    /**
     * 元数据真正到手时的出口（**包括**「磁力导入早超时、元数据几分钟后才到」那条路）。
     * 内核按它回填条目上的 size / pieceSize，但只改本地、不改签名、不回广播（public-swarm §1.1）。
     */
    @Volatile
    var onMetadataArrived: ((MetadataArrival) -> Unit)? = null

    /**
     * 一条「只为取元数据」的任务退场之后的出口：内核据此重新判一次「公开会话还要不要留着」
     * （public-swarm §7.1 的按需启停）。跑在摘任务线程上，只做一次判定，不碰会话。
     */
    @Volatile
    var onImportRetired: (() -> Unit)? = null

    val isRunning: Boolean get() = manager?.isRunning == true

    /** 会话里的 torrent 数（断言「磁力加种走公网会话」用）。 */
    fun torrentCount(): Int = synchronized(lock) { tasks.size }

    fun fileIds(): List<String> = synchronized(lock) { tasks.keys.toList() }

    // ------------------------------------------------------------------ 生命周期

    fun start(): Int {
        manager?.takeIf { it.isRunning }?.let { return listenPort }
        installAlertTypeCompat()
        var lastFailure: Throwable? = null
        for (i in 0 until PORT_TRIES) {
            val port = portHint + i
            val mgr = SessionManager()
            try {
                // 不设 ip_filter：公开会话必须能连到公网 peer（§2.3）。
                mgr.start(SessionParams(settings(port)))
                val actual = mgr.swig().listen_port()
                if (actual > 0) {
                    synchronized(lock) { manager = mgr }
                    listenPort = actual
                    attachListener(mgr)
                    // 收紧告警类别：公开会话只订它和 SessionManager 真正用得到的那四类。
                    // 必须在 start() 之后：SessionManager.start() 会把 alert_mask 强行改写成
                    // all_categories 去掉 log 类，我们在 settings() 里设的值一律被覆盖。
                    applyPublicAlertMask(mgr)
                    lastError = ""
                    log(
                        "session_start",
                        mapOf(
                            "port" to actual.toString(),
                            "dht_enabled" to enableDhtSetting().toString(),
                            "lsd_enabled" to enableLsdSetting().toString(),
                            "utp_in" to enableIncomingUtp().toString(),
                            "utp_out" to enableOutgoingUtp().toString(),
                            "port_mapping" to portMappingEnabled.toString(),
                            // 实际生效的告警类别（十六进制）。真机上靠它一眼看出 ip_block 有没有被排掉：
                            // 该位是 bit 8，0x4d = status|port_mapping|storage|error 里没有它。
                            "alert_mask" to Integer.toHexString(alertMaskSetting()),
                            "data_dir" to dataDir.absolutePath,
                        ),
                    )
                    return actual
                }
                mgr.stop()
            } catch (e: Throwable) {
                lastFailure = e
                try { mgr.stop() } catch (_: Throwable) {}
            }
        }
        lastError = lastFailure?.toString() ?: "no free port"
        log("session_start_failed", mapOf("error" to lastError, "port_hint" to portHint.toString()))
        throw IllegalStateException("public BT session could not bind any port from $portHint (last: $lastFailure)")
    }

    fun stop() {
        val mgr = synchronized(lock) {
            val m = manager
            manager = null
            tasks.clear()
            m
        } ?: return
        try { mgr.removeListener(listener) } catch (_: Throwable) {}
        try { mgr.stop() } catch (_: Throwable) {}
        listenPort = 0
        // 摘任务线程跟着会话一起收：会话已经没了，排着的摘任务动作没有意义。
        synchronized(lock) {
            try { retireExecutor?.shutdownNow() } catch (_: Throwable) {}
            retireExecutor = null
        }
        log("session_stop", mapOf("torrents" to "0"))
    }

    /**
     * 端口映射开关（有非休眠公开条目时开、结束撤销）。
     * 运行中改走 applySettings，立即作用到当前会话；会话没起来时记下来，[start] 带进 settings_pack。
     */
    fun setPortMapping(on: Boolean) {
        portMappingEnabled = on
        val mgr = manager ?: return
        try {
            val pack = SettingsPack()
            pack.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), on)
            pack.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), on)
            mgr.applySettings(pack)
        } catch (e: Throwable) {
            lastError = e.toString()
        }
        log("port_mapping", mapOf("on" to on.toString()))
    }

    /** 会话级上行限速（KB/s，0 = 不限）。公开侧与私有侧各自封顶（§7.2 裁决 3：总峰值最多 2×）。 */
    fun setSessionUploadLimit(kbps: Int) {
        sessionUploadKbps = kbps.coerceIn(0, MAX_KBPS)
        applySessionLimits()
    }

    fun setSessionDownloadLimit(kbps: Int) {
        sessionDownloadKbps = kbps.coerceIn(0, MAX_KBPS)
        applySessionLimits()
    }

    // ------------------------------------------------------------------ 加种

    /**
     * 加一个公开 torrent（完整种子，例如元数据缓存下来的 .torrent）。
     *
     * private=1 的一律拒绝：那是私有会话的东西（public-swarm §1.2 的「加种前一律按
     * TorrentInfo.priv() 决定用哪个会话」）。返回 false 就是被拒 / 加不进去，不抛异常。
     */
    @JvmOverloads
    fun addTorrent(
        ti: TorrentInfo,
        savePath: File,
        seedMode: Boolean = false,
        /**
         * 额外要挂上的 tracker（磁力里原样带来的 `tr`）。
         *
         * 导入时落的 `.torrent` 缓存只有 info 字典（`save_resume_data(SAVE_INFO_DICT)` 的产物），
         * **不带 announce**：从缓存加种时若不把磁力的 tracker 补回来，就只剩 DHT 一条路 ——
         * 实测「按缓存激活」会干等 20 s 以上连不上做种者。磁力的 `tr` 是发布者的选择
         * （public-swarm §2.2），导入时原样保留，激活时原样挂回去。
         */
        trackers: List<String> = emptyList(),
    ): Boolean {
        if (!accepts(ti.isPrivate)) {
            log("reject_private_torrent", mapOf("infohash" to ti.infoHash().toString(), "scope" to "public"))
            return false
        }
        val fileId = ti.infoHash().toString()
        if (synchronized(lock) { tasks.containsKey(fileId) }) {
            // 任务已经在会话里（导入还在找源的那条）：接管它，不重复加种。
            adopt(fileId)
            return true
        }
        val mgr = manager ?: return false
        return try {
            savePath.mkdirs()
            val atp = org.libtorrent4j.swig.add_torrent_params()
            atp.set_ti(ti.swig())
            atp.setSave_path(savePath.absolutePath)
            // 显式清零：DISABLE_DHT / DISABLE_PEX / DISABLE_LSD 一个都不设（§2.1）。
            atp.setFlags(publicFlags(extra = if (seedMode) TorrentFlags.SEED_MODE else null))
            val ec = org.libtorrent4j.swig.error_code()
            val handle = TorrentHandle(mgr.swig().add_torrent(atp, ec))
            if (ec.value() != 0) {
                log("add_torrent_failed", mapOf("infohash" to fileId, "error" to ec.message()))
                return false
            }
            synchronized(lock) { tasks[fileId] = Task(fileId, handle, savePath, ti.name(), seedMode) }
            val attached = attachTrackers(handle, trackers)
            log(
                "torrent_added",
                mapOf(
                    "infohash" to fileId,
                    "name" to ti.name(),
                    "size" to ti.totalSize().toString(),
                    "seed_mode" to seedMode.toString(),
                    "extra_trackers" to attached.toString(),
                ),
            )
            true
        } catch (e: Throwable) {
            lastError = e.toString()
            log("add_torrent_error", mapOf("infohash" to fileId, "error" to e.toString()))
            false
        }
    }

    /**
     * 磁力导入（public-swarm §3）：加进公开会话 → 等 MetadataReceivedAlert → 落 .torrent 缓存。
     *
     * **永不抛异常**，五种失败各一句话（[PublicMagnetResult.messageFor]）：
     * 非法链接 / 重复 / 无源 / 超时 / 元数据无效。失败时 torrent 仍留在会话里继续找源（§3.1
     * 「无元数据不报错」），除了元数据无效那一种 —— 那一条确定没救，摘掉。
     */
    @JvmOverloads
    fun addMagnet(
        magnetUri: String,
        savePath: File = dataDir,
        timeoutMs: Long = DEFAULT_METADATA_WAIT_MS,
        /**
         * 这条任务**只为取元数据**（默认 true = 导入语义）：元数据一到手就把它从会话里摘掉。
         *
         * 导入只为了拿 info 字典，拿到之后就不该再动用户一个字节：没有元数据时 libtorrent 根本
         * 发不出 piece 请求，所以「等元数据」这一段天然不会下数据；到手那一刻起才是下载，
         * 而这一刻被这个标记掐住 —— 任务当场退场，`.torrent` 留在缓存里，等用户点「激活」
         * 再按缓存加种（[adopt] / [retire]）。产品语义见 docs/core.md §3.12。
         */
        importOnly: Boolean = true,
    ): PublicMagnetResult {
        val parsed = MagnetLink.parse(magnetUri)
            ?: return failure(PublicMagnetResult.INVALID_LINK, "")
        if (has(parsed.fileId)) {
            log("magnet_duplicate", mapOf("infohash" to parsed.fileId))
            return failure(PublicMagnetResult.DUPLICATE, parsed.fileId)
        }
        val mgr = try {
            start()
            manager
        } catch (e: Throwable) {
            log("magnet_session_error", mapOf("infohash" to parsed.fileId, "error" to e.toString()))
            null
        } ?: return failure(PublicMagnetResult.SESSION_ERROR, parsed.fileId)

        val waiter = MetadataWait()
        synchronized(lock) { waiters[parsed.fileId] = waiter }
        var handle: TorrentHandle? = null
        try {
            savePath.mkdirs()
            val ec = org.libtorrent4j.swig.error_code()
            val atp = org.libtorrent4j.swig.libtorrent.parse_magnet_uri(parsed.normalized, ec)
            if (ec.value() != 0) {
                log("magnet_parse_failed", mapOf("infohash" to parsed.fileId, "error" to ec.message()))
                return failure(PublicMagnetResult.INVALID_LINK, parsed.fileId)
            }
            atp.setSave_path(savePath.absolutePath)
            // 与 addTorrent 同一个常量：显式清零，DHT / PEX / LSD 全部不禁（§2.1）。
            atp.setFlags(publicFlags())
            handle = TorrentHandle(mgr.swig().add_torrent(atp, ec))
            if (ec.value() != 0) {
                log("magnet_add_failed", mapOf("infohash" to parsed.fileId, "error" to ec.message()))
                return failure(PublicMagnetResult.INVALID_LINK, parsed.fileId)
            }
            val h = handle
            synchronized(lock) {
                tasks[parsed.fileId] = Task(parsed.fileId, h, savePath, parsed.displayName, false, importOnly)
            }
            log(
                "magnet_added",
                mapOf(
                    "infohash" to parsed.fileId,
                    "name" to parsed.displayName,
                    "trackers" to parsed.trackers.size.toString(),
                    "save_path" to savePath.absolutePath,
                    // 导入（import_only=true）只拉元数据，到手就退场；激活路径加完就开始下载。
                    "import_only" to importOnly.toString(),
                ),
            )

            val got = try {
                waiter.received.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!got) {
                // 没拿到元数据不报错（§3.1）：torrent 留在会话里继续找源。
                val peers = try { h.status().numPeers() } catch (e: Throwable) { 0 }
                val reason = if (peers <= 0) PublicMagnetResult.NO_SOURCE else PublicMagnetResult.TIMEOUT
                log(
                    "magnet_metadata_timeout",
                    mapOf("infohash" to parsed.fileId, "peers" to peers.toString(), "reason" to reason),
                )
                return PublicMagnetResult(
                    ok = false,
                    fileId = parsed.fileId,
                    reason = reason,
                    message = PublicMagnetResult.messageFor(reason),
                    entry = null,
                    metadataPath = "",
                )
            }
            // 用户取消优先于「没等到」：取消会放开等待槽（got=false），不先判这一位就退化成
            // 一条「找不到来源」的失败 —— 用户点了停止，不该收到一条失败（docs/core.md §3.12）。
            if (waiter.cancelled) {
                log("magnet_cancelled", mapOf("infohash" to parsed.fileId, "phase" to "metadata"))
                return failure(PublicMagnetResult.CANCELLED, parsed.fileId)
            }
            if (waiter.failed) {
                removeTorrent(parsed.fileId)
                log("magnet_metadata_failed", mapOf("infohash" to parsed.fileId))
                return failure(PublicMagnetResult.BAD_METADATA, parsed.fileId)
            }

            // 元数据到手：等 save_resume_data 把 .torrent 字节吐出来（与 libtorrent4j 的 fetchMagnet 同一条路）。
            val saved = try {
                waiter.saved.await(SAVE_TORRENT_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            // 元数据已经到手、正在取 .torrent 字节时被取消：取消同样优先 —— 任务已经不在会话里了，
            // 再往下走会把一条已经被摘掉的导入当成成功报给调用方。
            if (waiter.cancelled) {
                log("magnet_cancelled", mapOf("infohash" to parsed.fileId, "phase" to "saving"))
                return failure(PublicMagnetResult.CANCELLED, parsed.fileId)
            }
            val bytes = if (saved) waiter.torrentBytes else null

            // 元数据自身校验（§3.1「文件信息无效」）：必须是同一条 v1 元数据，infohash 必须对得上。
            val info = try {
                if (bytes != null) TorrentInfo(bytes) else h.torrentFile()
            } catch (e: Throwable) {
                null
            }
            if (info == null || !info.isValid || !info.hasV1() || info.infoHash().toString() != parsed.fileId) {
                removeTorrent(parsed.fileId)
                log(
                    "magnet_bad_metadata",
                    mapOf(
                        "infohash" to parsed.fileId,
                        "info" to (info == null).toString(),
                        "v1" to (info?.hasV1() ?: false).toString(),
                        "hash_ok" to (info?.infoHash()?.toString() == parsed.fileId).toString(),
                    ),
                )
                return failure(PublicMagnetResult.BAD_METADATA, parsed.fileId)
            }
            if (bytes == null) {
                // 元数据有了但 .torrent 字节没吐出来：条目照样能用（会话里已经有 torrent），只是重启要重拉。
                log("magnet_torrent_bytes_missing", mapOf("infohash" to parsed.fileId))
            }
            val metaPath = if (bytes != null) writeTorrentCache(parsed.fileId, bytes) else ""
            // 导入语义在这里收口：元数据到手 + `.torrent` 已经落进缓存 = 「导入」这件事做完了，
            // 任务当场退场（休眠）。激活时按缓存加种，不再重拉元数据。docs/core.md §3.12。
            // 顺序不能反：先摘就拿不到字节、缓存写不下来。
            if (importOnly) retire(parsed.fileId, "import_done")
            synchronized(lock) {
                tasks[parsed.fileId]?.let {
                    // 元数据到手后名字要按 info 字典修正（dn 可能是空的或对不上）；import_only 原样保留。
                    tasks[parsed.fileId] = Task(it.fileId, it.handle, it.savePath, info.name(), it.seeding, it.importOnly)
                }
            }
            // private=1 的元数据**不收后账**：libtorrent 按 BEP 27 自己把这个 torrent 的 DHT/PEX 关掉，
            // 效果等同「只在群内传」，不需要我们再摘一次（public-swarm §1.2）。这条日志是唯一的观测点。
            log(
                "magnet_metadata_received",
                mapOf(
                    "infohash" to parsed.fileId,
                    "name" to info.name(),
                    "size" to info.totalSize().toString(),
                    "piece_size" to info.pieceLength().toString(),
                    "private" to info.isPrivate.toString(),
                    "torrent_path" to metaPath,
                ),
            )
            return PublicMagnetResult(
                ok = true,
                fileId = parsed.fileId,
                reason = PublicMagnetResult.OK,
                message = "",
                entry = null,
                metadataPath = metaPath,
                name = info.name(),
                size = info.totalSize(),
                pieceSize = info.pieceLength(),
            )
        } catch (e: Throwable) {
            lastError = e.toString()
            log("magnet_error", mapOf("infohash" to parsed.fileId, "error" to e.toString()))
            return failure(PublicMagnetResult.SESSION_ERROR, parsed.fileId)
        } finally {
            synchronized(lock) { waiters.remove(parsed.fileId) }
        }
    }

    // ------------------------------------------------------------------ 导入 → 休眠 → 激活

    /**
     * 「激活」时接管一条**已经在会话里**的导入任务（元数据还没到手、正在找源的那条）：
     * 清掉「只为取元数据」的标记，使它到手之后不再被摘掉。
     *
     * 返回 false = 会话里没有这个 torrent（正常路径：导入时元数据已经到手，任务已经摘掉，
     * 宿主按 `<data>/public/meta/<infohash>.torrent` 重新加种）。
     */
    fun adopt(fileId: String): Boolean {
        val task = synchronized(lock) { tasks[fileId] } ?: return false
        synchronized(lock) { task.importOnly = false }
        log("torrent_adopted", mapOf("infohash" to fileId, "name" to task.name))
        return true
    }

    /**
     * 取消「等元数据 / 找来源」：把这条任务从公开会话里摘掉，并让任何正卡在 [addMagnet] 同步
     * 等待里的调用方立刻以 [PublicMagnetResult.CANCELLED] 返回（不是等到超时再报「找不到来源」）。
     *
     * 与 [retire] 的分工：
     *  - [retire] 是内核自己做完事（导入拿到元数据）后的退场，异步、跑在摘任务线程上；
     *  - 这里是**用户点了停止**（daemon 的 active=false），同步做完 —— 调用方要在同一个请求里把
     *    「内核现在是什么状态」如实回给页面，异步摘会让回包先说停掉了、状态却还是 acquiring。
     *
     * 导入任务（importOnly）与激活过（adopt）的任务都走这一条路：两者都是「有任务、没元数据」，
     * 差别只在用户要不要它，停止对它们应该是同一件事（docs/core.md §3.12）。
     *
     * 返回 true = 真的从会话里摘掉了一条任务；false = 会话里本来就没有它（已经停过 / 已退场）。
     */
    fun cancelAcquire(fileId: String, reason: String = REASON_USER_CANCEL): Boolean {
        val task = synchronized(lock) {
            val found = tasks[fileId] ?: return false
            // 等待槽与任务在同一次加锁里处理：addMagnet 不可能在「任务已经没了」之后还把等待放过去。
            waiters[fileId]?.let { w ->
                w.cancelled = true
                w.received.countDown()
                w.saved.countDown()
            }
            found
        }
        val removed = removeTorrent(fileId)
        log(
            "acquire_cancelled",
            mapOf(
                "infohash" to fileId,
                "name" to task.name,
                // 导入任务（只为取元数据）与激活过的任务都走这一条路；这一位让日志能分开两种现场。
                "import_only" to task.importOnly.toString(),
                "reason" to reason,
                "removed" to removed.toString(),
                "torrents" to torrentCount().toString(),
            ),
        )
        return removed
    }

    /**
     * 往一条已经在会话里的 torrent 补挂 tracker（磁力里带来的 `tr`）。
     * 返回真的挂上去的条数；单条失败不影响其他条，也不抛异常。
     */
    private fun attachTrackers(handle: TorrentHandle, urls: List<String>): Int {
        if (urls.isEmpty()) return 0
        var n = 0
        for (url in urls) {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) continue
            try {
                handle.addTracker(org.libtorrent4j.AnnounceEntry(trimmed))
                n++
            } catch (_: Throwable) {
                // 单条坏链接（不是 panic）不影响其他 tracker
            }
        }
        return n
    }

    private fun retirer(): java.util.concurrent.ExecutorService {
        synchronized(lock) {
            retireExecutor?.let { if (!it.isShutdown) return it }
            val created = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                val t = Thread(r, "huchuan-pub-retire")
                t.isDaemon = true
                t
            }
            retireExecutor = created
            return created
        }
    }

    /**
     * 把一条「只为取元数据」的任务从会话里摘掉（导入路径做完事的收尾动作）。
     *
     * 摘掉 = 休眠：没有任务就没有传输，`kernelState` 是 `ABSENT`，界面是「休眠」；
     * `.torrent` 已经在缓存里，激活时按它加种（当场 announce + 连 peer，实测 1 s 内出数据）。
     * **不**用 libtorrent 的 `pause()`：暂停过的 torrent 恢复后要等下一次**周期** announce 才
     * 重新拉 peer（实测 57 s，`force_reannounce` 也不生效），激活会先干等一分钟 —— 那是缺陷换缺陷。
     *
     * 可以在告警线程上调用：摘任务交给一条专门的单线程（libtorrent 的 remove 本身线程安全，
     * 但把「改会话」与「收告警」分到两条线程上，告警循环不会被我们卡住）。
     */
    private fun retire(fileId: String, reason: String) {
        val task = synchronized(lock) { tasks[fileId] } ?: return
        log(
            "torrent_parked",
            mapOf("infohash" to fileId, "name" to task.name, "reason" to reason),
        )
        try {
            retirer().execute {
                try {
                    removeTorrent(fileId)
                    log("torrent_retired", mapOf("infohash" to fileId, "reason" to reason))
                    onImportRetired?.invoke()
                } catch (e: Throwable) {
                    lastError = e.toString()
                    log("torrent_retire_failed", mapOf("infohash" to fileId, "error" to e.toString()))
                }
            }
        } catch (e: Throwable) {
            lastError = e.toString()
            log("torrent_retire_failed", mapOf("infohash" to fileId, "error" to e.toString()))
        }
    }

    // ------------------------------------------------------------------ 读口（LocalSession）

    override fun has(fileId: String): Boolean = synchronized(lock) { tasks.containsKey(fileId) }

    override fun progress(fileId: String): Double {
        val st = status(fileId, true) ?: return 0.0
        return st.progress().toDouble()
    }

    override fun isFinished(fileId: String): Boolean = status(fileId, false)?.isFinished == true

    override fun isPaused(fileId: String): Boolean {
        val handle = synchronized(lock) { tasks[fileId]?.handle } ?: return false
        return try {
            (handle.flags.to_int() and PAUSED_BIT) != 0
        } catch (e: Throwable) {
            false
        }
    }

    override fun hasMetadata(fileId: String): Boolean = status(fileId, false)?.hasMetadata() == true

    override fun downloadedBytes(fileId: String): Long {
        val st = status(fileId, false) ?: return 0L
        val wanted = st.totalWantedDone()
        return if (wanted > 0L) wanted else st.totalDone()
    }

    override fun totalBytes(fileId: String): Long {
        val st = status(fileId, false) ?: return 0L
        val wanted = st.totalWanted()
        return if (wanted > 0L) wanted else st.total()
    }

    /**
     * 会话里这条任务是不是「用户要的」：导入（`importOnly = true`）只为取元数据，不算；
     * 激活时 [adopt] 把标记清掉，之后元数据一到就开始下（docs/core.md §3.12）。
     */
    override fun wantsData(fileId: String): Boolean =
        synchronized(lock) { tasks[fileId]?.let { !it.importOnly } ?: false }

    override fun diskBytes(fileId: String): Long {
        val handle = synchronized(lock) { tasks[fileId]?.handle } ?: return 0L
        val st = status(fileId, false) ?: return 0L
        return BtSession.diskBytesOf(handle, st)
    }

    override fun savedFile(fileId: String): File? {
        val task = synchronized(lock) { tasks[fileId] } ?: return null
        return File(task.savePath, task.name)
    }

    override fun peers(fileId: String): Int = status(fileId, false)?.numPeers() ?: 0

    /**
     * 公开条目对群成员的拨号（public-swarm §4.1）：目标是成员的**公开会话** BT 端口，
     * 不是私有会话的 btPort —— 两个 SessionManager 各开一套 swarm，拨错会话等于连错群。
     */
    override fun connectPeer(fileId: String, host: String, port: Int): Boolean {
        val handle = synchronized(lock) { tasks[fileId]?.handle } ?: return false
        return try {
            handle.swig().connect_peer(TcpEndpoint(host, port).swig())
            true
        } catch (e: Throwable) {
            false
        }
    }

    override fun peerSnapshot(fileId: String): List<BtSession.PeerSnapshot> {
        val handle = synchronized(lock) { tasks[fileId]?.handle } ?: return emptyList()
        return BtSession.snapshotOf(handle)
    }

    override fun seedState(fileId: String): String {
        val st = status(fileId, false) ?: return "missing"
        if (st.isFinished) return "finished"
        return when (st.state()) {
            TorrentStatus.State.CHECKING_FILES, TorrentStatus.State.CHECKING_RESUME_DATA -> "checking"
            TorrentStatus.State.UNKNOWN, TorrentStatus.State.DOWNLOADING_METADATA -> "pending"
            else -> "incomplete"
        }
    }

    override fun downloadRate(fileId: String): Long =
        status(fileId, true)?.downloadPayloadRate()?.toLong() ?: 0L

    override fun uploadRate(fileId: String): Long =
        status(fileId, true)?.uploadPayloadRate()?.toLong() ?: 0L

    override fun removeTorrent(fileId: String): Boolean {
        val task = synchronized(lock) { tasks.remove(fileId) } ?: return false
        try {
            manager?.remove(task.handle)
        } catch (_: Throwable) {
            // 会话已经停了就没什么可摘的；文件留在盘上。
        }
        return true
    }

    override fun setUploadLimit(fileId: String, kbps: Int) {
        val handle = synchronized(lock) { tasks[fileId]?.handle } ?: return
        handle.setUploadLimit(if (kbps <= 0) 0 else kbps * 1024)
    }

    override fun superSeeding(fileId: String): Boolean {
        val handle = synchronized(lock) { tasks[fileId]?.handle } ?: return false
        return try {
            (handle.flags.to_int() and SUPER_SEEDING_BIT) != 0
        } catch (e: Throwable) {
            false
        }
    }

    override fun setSuperSeeding(fileId: String, on: Boolean): Boolean {
        val handle = synchronized(lock) { tasks[fileId]?.handle } ?: return false
        return try {
            val flag = TorrentFlags.SUPER_SEEDING
            if (on) handle.setFlags(flag) else handle.unsetFlags(flag)
            true
        } catch (e: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------------ 会话参数 / 读回

    /** 会话归属判定：public 会话只收 private=0 的种子（与 [BtSession.accepts] 互补）。 */
    fun accepts(privateTorrent: Boolean): Boolean = !privateTorrent

    fun dhtNodes(): Long = manager?.dhtNodes() ?: 0L

    fun isDhtRunning(): Boolean = manager?.isDhtRunning ?: false

    /**
     * 公开会话的完整诊断快照（DHT 节点数 / tracker announce 结果 / 当前 peer 端点）。
     *
     * 永远不抛异常：会话没起来、任务在中途被摘掉都只是少几条记录。会话没起来时
     * 除 `running=false` 外其余为 0 / 空表 —— 「没跑」本身也是答案。
     */
    fun diagnostics(): Diagnostics {
        val mgr = manager
        val running = mgr?.isRunning == true
        val snapshot = synchronized(lock) { tasks.values.toList() }
        val trackers = ArrayList<Diagnostics.Tracker>()
        val peers = ArrayList<Diagnostics.PeerEndpoint>()
        val now = System.currentTimeMillis()
        for (task in snapshot) {
            val st = status(task.fileId, false)
            val nextMs = nextAnnounceMs(st)
            val nextAt = if (nextMs > 0L) now + nextMs else 0L
            val urls = LinkedHashSet<String>()
            try {
                task.handle.trackers()?.forEach { entry -> entry.url()?.takeIf { it.isNotEmpty() }?.let { urls.add(it) } }
            } catch (_: Throwable) {}
            try {
                st?.currentTracker()?.takeIf { it.isNotEmpty() }?.let { urls.add(it) }
            } catch (_: Throwable) {}
            val known = synchronized(lock) {
                trackerReports[task.fileId]?.values?.map { it.url } ?: emptyList()
            }
            urls.addAll(known)
            for (url in urls) {
                val report = synchronized(lock) { trackerReports[task.fileId]?.get(url) }
                val state = when {
                    report == null -> "pending"
                    report.state == "announcing" -> "announcing"
                    report.ok -> "replied"
                    else -> "failed"
                }
                trackers.add(
                    Diagnostics.Tracker(
                        fileId = task.fileId,
                        url = url,
                        ok = report?.ok == true,
                        state = state,
                        lastAnnounceAt = report?.lastAt ?: 0L,
                        nextAnnounceInMs = nextMs,
                        nextAnnounceAt = nextAt,
                        peers = report?.peers ?: 0,
                        failures = report?.failures ?: 0,
                        error = report?.error ?: "",
                    ),
                )
            }
            val snap = try {
                BtSession.snapshotOf(task.handle)
            } catch (_: Throwable) {
                emptyList<BtSession.PeerSnapshot>()
            }
            for (p in snap.take(MAX_DIAG_PEERS)) {
                peers.add(Diagnostics.PeerEndpoint(task.fileId, p.ip, p.connected, p.seed))
            }
        }
        return Diagnostics(
            running = running,
            port = if (running) listenPort else 0,
            torrents = snapshot.size,
            dhtNodes = if (running) dhtNodes() else 0L,
            dhtRunning = running && isDhtRunning(),
            portMapping = portMappingEnabled,
            lastError = lastError,
            trackers = trackers,
            peers = peers,
        )
    }

    /**
     * `torrent_status.next_announce`：libtorrent 报的「距下次 announce」的毫秒数（实测单位见类注释）。
     * 取不到 / 明显离谱时按 0 处理，免得把一个坏读数换算成一个看似正常的「下次 announce 在 30 年后」。
     */
    private fun nextAnnounceMs(st: TorrentStatus?): Long {
        val raw = try {
            st?.nextAnnounce() ?: 0L
        } catch (_: Throwable) {
            0L
        }
        return if (raw in 1..MAX_NEXT_ANNOUNCE_MS) raw else 0L
    }

    /** settings_pack 里 enable_dht 的真值（断言「公开会话 DHT 为开」用）。 */
    fun enableDhtSetting(): Boolean = settingBool { it.isEnableDht }

    /** settings_pack 里 enable_lsd 的真值（断言「公开会话 LSD 为关」用）。 */
    fun enableLsdSetting(): Boolean = settingBool { it.isEnableLsd }

    /**
     * 运行中会话的**实际** alert_mask（断言「公开侧不订 ip_block」用）。
     *
     * 走 `session.get_settings()` 读的是 live 值，不是我们在 settings() 里写进去、随后被
     * `SessionManager.start()` 覆盖掉的那个值。会话没起来时返回 0。
     */
    fun alertMaskSetting(): Int = try {
        manager?.settings()?.getInteger(settings_pack.int_types.alert_mask.swigValue()) ?: 0
    } catch (e: Throwable) {
        0
    }

    fun enableIncomingUtp(): Boolean =
        settingBool { it.getBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue()) }

    fun enableOutgoingUtp(): Boolean =
        settingBool { it.getBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue()) }

    private fun settingBool(read: (SettingsPack) -> Boolean): Boolean = try {
        manager?.settings()?.let(read) ?: false
    } catch (e: Throwable) {
        false
    }

    // ------------------------------------------------------------------ 内部

    private fun status(fileId: String, accurate: Boolean): TorrentStatus? {
        val handle = synchronized(lock) { tasks[fileId]?.handle } ?: return null
        return try {
            handle.status(accurate)
        } catch (e: Throwable) {
            null
        }
    }

    private fun failure(reason: String, fileId: String): PublicMagnetResult = PublicMagnetResult(
        ok = false,
        fileId = fileId,
        reason = reason,
        message = PublicMagnetResult.messageFor(reason),
        entry = null,
        metadataPath = "",
    )

    /** 把 .torrent 写进 `<data>/public/meta/<infohash>.torrent`，返回路径（写失败返回 ""）。 */
    private fun writeTorrentCache(fileId: String, bytes: ByteArray): String {
        return try {
            metaDir.mkdirs()
            val target = File(metaDir, fileId + ".torrent")
            val tmp = File(metaDir, fileId + ".torrent.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.writeBytes(bytes)
                tmp.delete()
            }
            target.absolutePath
        } catch (e: Throwable) {
            log("torrent_cache_error", mapOf("infohash" to fileId, "error" to e.toString()))
            ""
        }
    }

    /** 缓存的 .torrent 文件；没有返回 null（重启后按它直接挂种，不再重拉元数据）。 */
    fun cachedTorrent(fileId: String): File? {
        val f = File(metaDir, fileId + ".torrent")
        return if (f.isFile) f else null
    }

    /**
     * 把一份已经建好的 .torrent 写进 `<data>/public/meta/<infohash>.torrent`，返回路径（失败为 ""）。
     *
     * 「公开到群外」与磁力导入共用同一份缓存：发布路径也把种子落盘，重启续种时
     * [M1Kernel.resumeSeeds] 才能按缓存直接挂回公开会话，不再重拉元数据。
     */
    fun cacheTorrent(fileId: String, bytes: ByteArray): String = writeTorrentCache(fileId, bytes)

    /**
     * 后台补元数据到手（磁力导入早超时、torrent 还留在会话里继续找源的那条路）：
     * 落 .torrent 缓存、把任务名按 info 字典修正、通知内核回填条目。告警线程上不碰网络，只碰文件。
     */
    private fun onBackgroundMetadata(fileId: String, bytes: ByteArray) {
        val info = try {
            TorrentInfo(bytes)
        } catch (_: Throwable) {
            null
        }
        if (info == null || !info.isValid) {
            log("background_metadata_invalid", mapOf("infohash" to fileId))
            return
        }
        val path = writeTorrentCache(fileId, bytes)
        // 后台补元数据那条路（导入早超时、torrent 还留在会话里找源）：缓存落盘之后同样要退场，
        // 否则它会在「休眠」的名义下继续下载（就是这次要修的缺陷）。
        if (synchronized(lock) { tasks[fileId]?.importOnly == true }) retire(fileId, "background_metadata")
        synchronized(lock) {
            tasks[fileId]?.let { old ->
                // import_only 要带着走：后台补元数据的那条路同样要在到手后退场。
                tasks[fileId] = Task(old.fileId, old.handle, old.savePath, info.name(), old.seeding, old.importOnly)
            }
        }
        log(
            "background_metadata",
            mapOf(
                "infohash" to fileId,
                "name" to info.name(),
                "size" to info.totalSize().toString(),
                "torrent_path" to path,
            ),
        )
        try {
            onMetadataArrived?.invoke(
                MetadataArrival(fileId, info.name(), info.totalSize(), info.pieceLength(), path, info.isPrivate),
            )
        } catch (_: Throwable) {
            // 内核那边抛异常不能把告警线程带崩
        }
    }

    /**
     * 补齐 [AlertType] 映射表的空槽（根因见 [AlertTypeCompat]）。
     *
     * 必须在建 [SessionManager] **之前**跑完：告警线程在 `SessionManager.start()` 里就起来了，
     * 补表晚了会先吃一次 NPE。幂等，重复调用只有第一次真的干活。
     */
    private fun installAlertTypeCompat() {
        val r = AlertTypeCompat.install()
        if (!r.isClean) {
            // 补不上不代表一定崩（Windows 的 dll 本来就没有空洞），但 Android 上这就是崩溃前兆，必须留痕。
            lastError = r.error
            log("alert_type_compat_failed", mapOf("error" to r.error))
            return
        }
        if (r.filledIds.isNotEmpty()) {
            log(
                "alert_type_compat",
                mapOf(
                    "native" to r.nativeTypes.toString(),
                    "table" to r.tableLength.toString(),
                    "filled" to r.filledIds.joinToString(","),
                ),
            )
        }
    }

    /**
     * 收紧告警类别（public-swarm §2.1 的「只订需要的」）。
     *
     * 为什么不在 [settings] 里写：`SessionManager.start()` 有一句
     * `sp.setInteger(alert_mask, alertMask(logging))`（注释写着 "we always control the alert mask"），
     * 会话参数里的值一律被覆盖，只有 start 之后再 `apply_settings` 才生效。
     *
     * 订的五类：
     * - `status`：metadata_received / torrent_finished / add_torrent / state_update（SessionManager 内部用）
     * - `error`：metadata_failed / alerts_dropped
     * - `storage`：save_resume_data(_failed)
     * - `port_mapping`：portmap（只用于 isFirewalled 标记，留着不增加风险）
     * - `tracker`：tracker_announce / reply / error —— **只喂诊断读口**（[diagnostics]），
     *   状态机一条都不读它。加它是为了回答「announce 成没成、下次什么时候」，那是外部探针
     *   之前唯一能拿到的答案（docs/reports/public-swarm-real-world.md 风险 3）。
     *
     * 不订 `dht` / `peer` / `ip_block`：公开会话不消费它们，而且 `ip_block` 正是
     * 崩溃那条 `ip_ban_alert` 的类别 —— 不订它，libtorrent 连发都不会发（ban_ip 里有 should_post 判断）。
     * `session_stats` 不在此列但照样会到：它在 post_session_stats() 里直接 emplace，不看 mask，
     * 所以 dhtNodes() 的读口径不受影响。
     */
    private fun applyPublicAlertMask(mgr: SessionManager) {
        // 类别集由 [publicAlertMask] 一处给出（tracker 类只为诊断读口，见 [diagnostics]）。
        try {
            val pack = SettingsPack()
            pack.setInteger(settings_pack.int_types.alert_mask.swigValue(), publicAlertMask())
            mgr.applySettings(pack)
        } catch (e: Throwable) {
            lastError = e.toString()
            log("alert_mask_error", mapOf("error" to e.toString()))
        }
    }

    private fun attachListener(mgr: SessionManager) {
        try {
            mgr.addListener(listener)
        } catch (e: Throwable) {
            lastError = e.toString()
            log("listener_error", mapOf("error" to e.toString()))
        }
    }

    private val listener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(
            AlertType.METADATA_RECEIVED.swig(),
            AlertType.METADATA_FAILED.swig(),
            AlertType.SAVE_RESUME_DATA.swig(),
            AlertType.SAVE_RESUME_DATA_FAILED.swig(),
            AlertType.TORRENT_FINISHED.swig(),
            // 只喂诊断读口（[diagnostics]）。这几种都在 0..104 之内，不是 AlertTypeCompat 补的洞。
            AlertType.TRACKER_ANNOUNCE.swig(),
            AlertType.TRACKER_REPLY.swig(),
            AlertType.TRACKER_ERROR.swig(),
        )

        override fun alert(a: Alert<*>) {
            val torrentAlert = a as? TorrentAlert<*> ?: return
            val fileId = try {
                torrentAlert.handle().infoHash().toString()
            } catch (_: Throwable) {
                return
            }
            if (fileId.isEmpty()) return
            when (a.type()) {
                AlertType.METADATA_RECEIVED -> {
                    val waiter = synchronized(lock) { waiters[fileId] }
                    val handle = try { (a as? MetadataReceivedAlert)?.handle() } catch (_: Throwable) { null }
                    // 元数据到手当场让 libtorrent 把 info 字典吐出来（SAVE_INFO_DICT）。
                    // 无论有没有人在等都要做：后台补元数据的那条路也要把 .torrent 落进缓存（§3）。
                    try { handle?.saveResumeData(TorrentHandle.SAVE_INFO_DICT) } catch (_: Throwable) {}
                    // 到手就**停**（导入语义）：这之后一个数据字节都不该再下。暂停必须在 save_resume_data
                    // 之前 —— 缓存那一步可能要几秒，而回环上 16 MiB 只要几百毫秒，不掐住就会在
                    // 「休眠」的名义下真下载（这正是要修的缺陷）。暂停不影响 save_resume_data（纯磁盘操作）。
                    // 退场（摘任务）在缓存落盘之后：先摘就拿不到字节，激活时只能重拉元数据。
                    if (synchronized(lock) { tasks[fileId]?.importOnly == true } && handle != null) {
                        try { handle.pause() } catch (_: Throwable) {}
                    }
                    waiter?.received?.countDown()
                }
                AlertType.METADATA_FAILED -> {
                    synchronized(lock) { waiters[fileId] }?.let {
                        it.failed = true
                        it.received.countDown()
                    }
                }
                AlertType.SAVE_RESUME_DATA -> {
                    val bytes = try {
                        AddTorrentParams.writeResumeDataBuf((a as SaveResumeDataAlert).params())
                    } catch (_: Throwable) {
                        null
                    }
                    val waiter = synchronized(lock) { waiters[fileId] }
                    if (waiter != null) {
                        if (bytes != null) waiter.torrentBytes = bytes
                        waiter.saved.countDown()
                    } else if (bytes != null) {
                        // 没有人在等：磁力是早先超时留在会话里继续找源的那一条，元数据现在才到。
                        onBackgroundMetadata(fileId, bytes)
                    }
                }
                AlertType.SAVE_RESUME_DATA_FAILED -> {
                    synchronized(lock) { waiters[fileId] }?.saved?.countDown()
                }
                AlertType.TORRENT_FINISHED -> {
                    try { (a as? TorrentFinishedAlert)?.handle()?.let { onTorrentFinished?.invoke(fileId) } } catch (_: Throwable) {}
                }
                // tracker 三件事只落在诊断表里，不回灌任何判定（docs/core.md §3.12）。
                AlertType.TRACKER_ANNOUNCE -> {
                    val url = try { (a as? TrackerAnnounceAlert)?.trackerUrl() ?: "" } catch (_: Throwable) { "" }
                    report(fileId, url) { it.state = "announcing" }
                }
                AlertType.TRACKER_REPLY -> {
                    val url = try { (a as? TrackerReplyAlert)?.trackerUrl() ?: "" } catch (_: Throwable) { "" }
                    val num = try { (a as? TrackerReplyAlert)?.numPeers() ?: 0 } catch (_: Throwable) { 0 }
                    report(fileId, url) {
                        it.state = "replied"
                        it.ok = true
                        it.lastAt = System.currentTimeMillis()
                        it.peers = num
                        it.failures = 0
                        it.error = ""
                    }
                }
                AlertType.TRACKER_ERROR -> {
                    val url = try { (a as? TrackerErrorAlert)?.trackerUrl() ?: "" } catch (_: Throwable) { "" }
                    val times = try { (a as? TrackerErrorAlert)?.timesInRow() ?: 0 } catch (_: Throwable) { 0 }
                    val message = try { (a as? TrackerErrorAlert)?.errorMessage() ?: "" } catch (_: Throwable) { "" }
                    report(fileId, url) {
                        it.state = "failed"
                        it.ok = false
                        it.lastAt = System.currentTimeMillis()
                        it.peers = 0
                        it.failures = times
                        it.error = message
                    }
                }
                else -> {}
            }
        }
    }

    /** 改一条 tracker 的诊断记录（没有就建一条）。告警线程调，全程在 [lock] 里。 */
    private fun report(fileId: String, url: String, mutate: (TrackerReport) -> Unit) {
        val key = url.ifEmpty { UNKNOWN_TRACKER }
        synchronized(lock) {
            val byUrl = trackerReports.getOrPut(fileId) { LinkedHashMap() }
            val entry = byUrl.getOrPut(key) { TrackerReport(key) }
            mutate(entry)
            // 一个 torrent 的真 tracker 不会超过个位数；表是诊断用的，别让它无限长。
            while (byUrl.size > MAX_TRACKERS_PER_TORRENT) {
                val first = byUrl.keys.firstOrNull() ?: break
                byUrl.remove(first)
            }
        }
    }

    private fun settings(port: Int): SettingsPack {
        val s = SettingsPack()
        s.setEnableDht(true)
        s.setEnableLsd(false)
        s.setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), true)
        s.setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), true)
        // 端口映射默认关，只在一个跨网分享窗口 / 有非休眠公开条目期间按需打开（§2.3 裁决 2）。
        s.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), portMappingEnabled)
        s.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), portMappingEnabled)
        s.setBoolean(settings_pack.bool_types.enable_ip_notifier.swigValue(), false)
        s.setString(settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:" + port)
        s.connectionsLimit(CONNECTIONS_LIMIT)
        s.activeDownloads(ACTIVE_DOWNLOADS)
        s.activeSeeds(ACTIVE_SEEDS)
        s.uploadRateLimit(sessionUploadKbps * 1024)
        s.downloadRateLimit(sessionDownloadKbps * 1024)
        return s
    }

    private fun applySessionLimits() {
        val mgr = manager ?: return
        try {
            val pack = SettingsPack()
            pack.uploadRateLimit(sessionUploadKbps * 1024)
            pack.downloadRateLimit(sessionDownloadKbps * 1024)
            mgr.applySettings(pack)
        } catch (e: Throwable) {
            lastError = e.toString()
        }
    }

    /** 独立日志前缀：事件名 `pub_*` + 字段 `sess=pub`（§7.1 的日志口径）。 */
    private fun log(event: String, fields: Map<String, String>) {
        val sink = events ?: return
        val withSess = LinkedHashMap<String, String>(fields.size + 1)
        withSess.putAll(fields)
        withSess["sess"] = "pub"
        try {
            sink.log("pub_" + event, withSess)
        } catch (_: Throwable) {
            // 日志出口抛异常不能把告警线程带崩
        }
    }

    companion object {
        /** 公开会话的监听端口起点：6881 是 BT 客户端的惯例端口，私有段从 51413 起，两段不重叠。 */
        const val DEFAULT_PORT = 6881

        /** 顺延尝试次数：6881–6980。 */
        const val PORT_TRIES = 100

        /** 等元数据的默认上限（§3.1：BT 拉元数据本来就是分钟级）。 */
        const val DEFAULT_METADATA_WAIT_MS = 60_000L

        /**
         * [cancelAcquire] 的默认理由：用户点了停止（daemon 的 active=false / 本机移除）。
         * 写进日志 `pub_acquire_cancelled reason=user_cancel`：可 grep 的事实，不是"失败"。
         */
        const val REASON_USER_CANCEL = "user_cancel"

        /** 元数据到手之后等 save_resume_data 吐 .torrent 字节的上限。 */
        private const val SAVE_TORRENT_WAIT_MS = 5_000L

        private const val CONNECTIONS_LIMIT = 200
        private const val ACTIVE_DOWNLOADS = 3
        private const val ACTIVE_SEEDS = 20

        private const val DATA_SUBDIR = "data"
        private const val META_SUBDIR = "meta"

        private const val MAX_KBPS = Int.MAX_VALUE / 1024

        /** 诊断读口里每个 torrent 最多报多少条 peer 端点（够定位问题，又不把 /api/state 撑大）。 */
        private const val MAX_DIAG_PEERS = 64

        /** 诊断表里每个 torrent 最多留几条 tracker 记录。 */
        private const val MAX_TRACKERS_PER_TORRENT = 16

        /** `next_announce` 的合理上限（30 天，毫秒）；超出当坏读数处理。 */
        private const val MAX_NEXT_ANNOUNCE_MS = 30L * 24 * 60 * 60 * 1000

        /** 告警里拿不到 tracker URL 时的占位键（仍然记结果，只是不知道是谁答的）。 */
        private const val UNKNOWN_TRACKER = "-"

        private val SUPER_SEEDING_BIT: Int by lazy { TorrentFlags.SUPER_SEEDING.to_int() }

        /** `torrent_flags::paused` 的位：挂起判定读它，不读我们自己记的 park 位。 */
        private val PAUSED_BIT: Int by lazy { TorrentFlags.PAUSED.to_int() }

        /**
         * 公开 torrent 的 per-torrent flags：**显式清零**。
         *
         * DISABLE_DHT / DISABLE_LSD / DISABLE_PEX 一个都不设 —— PEX 没有会话级开关，
         * 不设就是开（public-swarm §2.1）。和 [BtSession.PRIVATE_FLAGS] 的 0x380000 是互补的：
         * 那边「忘了传」= 隐私事故，这边「忘了传」= 功能缺失，两个方向都不能靠默认值兜。
         */
        @JvmStatic
        fun publicFlags(): torrent_flags_t = torrent_flags_t()

        /** 只在 SEED_MODE 一类场景下叠位，其余一律从清零常量出发。 */
        private fun publicFlags(extra: torrent_flags_t?): torrent_flags_t =
            if (extra == null) publicFlags() else publicFlags().or_(extra)

        /** 公开条目的 per-torrent flags 真值（断言 PEX 等位没有被设上）。 */
        @JvmStatic
        fun publicFlagsBits(): Int = publicFlags().to_int()

        /**
         * 公开会话的告警类别：status | error | storage | port_mapping。
         *
         * 顺序不能反：`Alert.ALL_CATEGORIES` 之类是 swig 对象，每次 getstatic 都拿同一个实例，
         * 这里只在真正要下发时算一次。用 [Alert] 接口上的常量而不是硬编码数字，跟着库走。
         */
        @JvmStatic
        fun publicAlertMask(): Int =
            Alert.STATUS_NOTIFICATION
                .or_(Alert.ERROR_NOTIFICATION)
                .or_(Alert.STORAGE_NOTIFICATION)
                .or_(Alert.PORT_MAPPING_NOTIFICATION)
                // tracker 类这一个是本轮新加的：订它只为了把「announce 成没成」记进诊断表
                // （docs/core.md §3.12）。dht / peer / ip_block 依旧不订 —— 后者的 ip_ban_alert
                // 正是崩溃那一条（§3.8），收益为零。
                .or_(Alert.TRACKER_NOTIFICATION)
                .to_int()

        /** 崩溃那条 ip_ban 的类别（断言「公开会话不订它」用）。 */
        @JvmStatic
        fun ipBlockCategory(): Int = Alert.IP_BLOCK_NOTIFICATION.to_int()
    }
}
