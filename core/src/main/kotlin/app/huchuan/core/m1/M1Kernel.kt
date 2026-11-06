package app.huchuan.core.m1

import app.huchuan.core.FileScope
import java.io.File
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

/**
 * M1 内核门面（不含 UI）：身份、群存储、消息层、BT 会话、发现层的装配点。
 *
 * 用法（宿主 / harness）：
 *   1. new M1Kernel(dataDir, deviceName, 47100, 51413, events)
 *   2. start()
 *   3. createGroup(name) 或 joinByTail8(tail8, manualCandidates, timeoutMs)
 *   4. group.addFile / group.startDownload / group.comment
 *   5. close()
 */
class M1Kernel @JvmOverloads constructor(
    val dataDir: File,
    val deviceName: String,
    private val portHint: Int,
    private val btHint: Int,
    internal val events: KernelEvents,
    /**
     * 群密钥落盘方案。桌面 harness 走默认的明文（与旧 state.json 兼容）；
     * Android 传 Keystore 包裹的实现，keySeed 不再明文落盘。
     */
    internal val secretBox: SecretBox = PlaintextSecretBox,
    /** 本机平台（协议 §10 的 peer.announce.plat）：android / windows / unknown。宿主可覆盖。 */
    val platform: String = Proto.detectPlatform(),
    /**
     * 跨网发现用的自建 tracker 基址（协议 §3.2），例如 `https://huchuan-relay.example.workers.dev`。
     *
     * **空字符串 = 整个功能关闭**：只失去「跨网」这一档，局域网 mDNS 与手动 IP:port 完全照旧，
     * 不报错、不弹窗、不发任何请求。代码里没有写死的线上地址，绝不替用户默认打开。
     * 宿主也可以在建好内核之后、[start] 之前用 [setTrackerBaseUrl] 给值（配置读盘晚于建对象的场景）。
     */
    trackerBaseUrl: String = "",
) : AutoCloseable {

    internal lateinit var identity: DeviceIdentity
        private set

    internal lateinit var tlsIdentity: TlsIdentity
        private set

    internal lateinit var bt: BtSession
        private set

    /**
     * 公开会话（public-swarm §2.3，AGENTS §9）：**按需启停** —— 对象在 [start] 里建好但不起，
     * 有非休眠公开条目（磁力导入 / 激活公开条目）时才 [PublicBtSession.start]，
     * 全部休眠或移除就 [PublicBtSession.stop]，顺带撤端口映射。
     */
    internal lateinit var publicBt: PublicBtSession
        private set

    val downloadsDir: File get() = File(dataDir, "downloads")

    private val stateLock = Any()
    private val groupsById = LinkedHashMap<String, GroupRuntime>()
    private val pubKeys = PubRegistry()
    private val linkSet: MutableSet<PeerLink> = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    internal val lanDiscovery = LanDiscovery()
    internal val joinRate = JoinRateLimiter()

    /**
     * 跨网发现（协议 §3.2）的装配点。[start] 时按 [trackerBaseUrl] 建；配置为空就一直是 null，
     * 于是「tracker 这一档静默缺席」，其余发现路径一行都不用改。
     */
    private var tracker: TrackerService? = null

    /** tracker 基址（已去掉首尾空白与尾部斜杠）。空串 = 关闭。 */
    var trackerBaseUrl: String = normalizeTrackerBaseUrl(trackerBaseUrl)
        private set

    /** 跨网发现是否在工作（配置有值 + 内核已起来 + 服务没关）。宿主 / 诊断页读它。 */
    val trackerEnabled: Boolean get() = tracker?.enabled == true

    private val nonceLru = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 256
    }

    private val random = SecureRandom()
    private val closed = AtomicBoolean(false)
    private var started = false
    private var server: PeerServer? = null
    private var monitorThread: Thread? = null

    /**
     * 冷启动重连（缺口一）：只对「[start] 时从 state.json 恢复出来的群」工作。
     * 刚建 / 刚加入的群不进这里 —— 它们的链路本来就由加入流程建立。
     */
    private val restoredGroupIds: MutableSet<String> =
        Collections.synchronizedSet(LinkedHashSet<String>())

    /** 冷启动重连器；没有恢复群时根本不启动（省掉 mDNS 浏览与空转线程）。 */
    private var reconnecter: PeerReconnector? = null

    /**
     * 完成告警队列（缺口二）：libtorrent 的 torrent_finished_alert 只往里塞 fileId，
     * 判定与实际动作（进做种集合 / 推事件 / 重新自报 seeds）在 [completionWatcher] 线程上做。
     * 不能直接在告警线程上做：那上面碰 socket 与 state.json 会把 libtorrent 的告警循环卡住。
     */
    private val completionQueue = java.util.concurrent.LinkedBlockingQueue<String>()
    private var completionWatcher: Thread? = null

    /** 每个文件上一次打点过的进度，避免同一百分比刷屏。（巡查线程读写、退群时主线程清，需同步） */
    private val progressSeen = Collections.synchronizedMap(HashMap<String, Int>())

    /** 每个文件上一次打点过的队友数：只在变化时打一行，观测口径见 [teammates]。 */
    private val teamsSeen = Collections.synchronizedMap(HashMap<String, Int>())

    @Volatile
    private var joinIntent: JoinIntent? = null

    @Volatile
    private var messagePortValue = 0

    val deviceId: String get() = if (this::identity.isInitialized) identity.deviceId else ""

    // ------------------------------------------------------------------ 生命周期

    fun start() {
        if (started) return
        started = true
        dataDir.mkdirs()
        downloadsDir.mkdirs()
        identity = DeviceIdentity.loadOrCreate(File(dataDir, "identity.txt"))
        tlsIdentity = TlsIdentity.create(identity)
        loadState()
        // 记下「这次是从本地库恢复出来的群」：只有它们需要冷启动主动重连（缺口一）。
        for (g in allGroups()) restoredGroupIds.add(g.groupId)

        bt = BtSession(btHint, File(dataDir, "bt"), ipFilterEnabled = true)
        // P7 全局限速：宿主可能在 start() 之前就把设置读出来了，先把目标值交给会话（start 前设置进 settings_pack）。
        if (sessionUploadKbps > 0) bt.setSessionUploadLimit(sessionUploadKbps)
        if (sessionDownloadKbps > 0) bt.setSessionDownloadLimit(sessionDownloadKbps)
        // 完成判定不再等 2 s 巡查（缺口二）：libtorrent 判完当场塞队列，由完成线程立刻推事件。
        bt.onTorrentFinished = { fileId -> onTorrentFinishedAlert(fileId) }
        bt.start()
        // ip_filter 动态白名单（AGENTS §9）：state.json 恢复出来的手动地址 / 上次建链端点先带进去，
        // 之后 peer.announce / tracker / 手动地址每次变化再重建（见 refreshBtAllowlist）。
        refreshBtAllowlist()

        // 公开会话：先只建对象（数据目录 <data>/public，端口从 6881 起），真正起来按需（§7.1）。
        publicBt = PublicBtSession(File(dataDir, PUBLIC_DIR), PublicBtSession.DEFAULT_PORT, events)
        publicBt.onTorrentFinished = { fileId -> onTorrentFinishedAlert(fileId) }
        publicBt.onMetadataArrived = { arrival -> onPublicMetadata(arrival) }

        // 重启续种（缺口 G2）：state.json 里记着「上次在做种」的条目，会话一起来就按判定挂回去。
        // 放在 PeerServer / mDNS 之前：先让本机有东西可做种，再开始对外通告端口。
        resumeSeeds()

        val srv = PeerServer(this)
        messagePortValue = srv.start()
        server = srv

        val mdnsOk = lanDiscovery.start()
        for (g in allGroups()) lanDiscovery.register(g.meta, messagePortValue, bt.listenPort)

        startMonitor()
        // 缺口二：完成判定自己有一条线程（告警队列 + 200 ms 兜底巡查），不再等 2 s 巡查节拍。
        startCompletionWatcher()
        // 缺口一：恢复出来的群主动连回去（mDNS + 落盘的手动地址 + 上次连过的端点，指数退避）。
        if (restoredGroupIds.isNotEmpty()) {
            reconnecter = PeerReconnector(this).also { it.start() }
        }
        // 跨网发现（协议 §3.2）：配置为空时这一步什么都不做，只剩局域网与手动 IP:port。
        startTracker()
        events.log(
            "kernel_started",
            mapOf(
                "device_id" to identity.deviceId,
                "device_name" to deviceName,
                "data_dir" to dataDir.absolutePath,
                "msg_port" to messagePortValue.toString(),
                "bt_port" to bt.listenPort.toString(),
                "mdns" to if (mdnsOk) "on" else "off",
                "mdns_error" to lanDiscovery.lastError,
                "dht_nodes" to bt.dhtNodes().toString(),
                "dht_running" to bt.isDhtRunning().toString(),
                "restored_groups" to restoredGroupIds.size.toString(),
                "reconnect" to if (reconnecter != null) "on" else "off",
                "completion_alert" to if (bt.listenerError.isEmpty()) "on" else "off",
                "completion_alert_error" to bt.listenerError,
                "session_up_kbps" to sessionUploadLimitKbps().toString(),
                "session_down_kbps" to sessionDownloadLimitKbps().toString(),
                "session_limit_local_scope" to bt.localPeersRateLimited().toString(),
                "tracker" to if (trackerEnabled) "on" else "off",
                "tracker_base" to trackerBaseUrl,
                // 公开会话按需启停：日志里留一条「它现在是什么状态」，断言不用去猜。
                "public_session" to if (publicBt.isRunning) "on" else "off",
                "public_port_hint" to PublicBtSession.DEFAULT_PORT.toString(),
            ),
        )
    }

    override fun close() {
        stop()
    }

    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        reconnecter?.close()
        reconnecter = null
        completionWatcher?.interrupt()
        completionWatcher = null
        completionQueue.clear()
        monitorThread?.interrupt()
        for (g in allGroups()) {
            lanDiscovery.unregister(g.groupId)
            for (link in g.onlineLinks()) link.close()
        }
        for (link in linkSet.toList()) link.close()
        try { tracker?.close() } catch (_: Throwable) {}
        tracker = null
        try { server?.stop() } catch (_: Throwable) {}
        try { lanDiscovery.close() } catch (_: Throwable) {}
        try { if (this::publicBt.isInitialized) publicBt.stop() } catch (_: Throwable) {}
        try { if (this::bt.isInitialized) bt.stop() } catch (_: Throwable) {}
        try { saveState() } catch (_: Throwable) {}
        events.log("kernel_stopped", mapOf("device_id" to deviceId))
    }

    fun isStarted(): Boolean = started && !closed.get()

    fun localMessagePort(): Int = messagePortValue

    fun localBtPort(): Int = if (this::bt.isInitialized) bt.listenPort else 0

    /**
     * 公开会话的 BT 监听端口；会话没起来（按需启停）时返回 0。
     *
     * 进 `peer.announce.pubBtPort`（协议 §10）：对端据此对公开条目拨号。私有 btPort 与它
     * 是两个 SessionManager 的端口，不能互相顶替（public-swarm §4.1）。
     */
    internal fun localPublicBtPort(): Int =
        if (this::publicBt.isInitialized && publicBt.isRunning) publicBt.listenPort else 0

    // ------------------------------------------------------------------ ip_filter 动态白名单（AGENTS §9）

    /**
     * 重建私有会话的 ip_filter 动态白名单：所有群 `btAllowlistHosts()` 的并集。
     *
     * 调用点：peer.announce 落到群（新端点 / 端点替换）、tracker 名单整体替换、手动地址与
     * 建链端点落盘、退群、内核启动（把 state.json 里恢复出来的手动地址先带进 filter）。
     * 白名单变化时打一条 `bt_ip_allowlist` 事件，字段是实际放行的公网字面量（不含私网段）。
     */
    internal fun refreshBtAllowlist() {
        if (!this::bt.isInitialized) return
        val hosts = LinkedHashSet<String>()
        for (group in allGroups()) hosts.addAll(group.btAllowlistHosts())
        if (bt.updatePublicAllowlist(hosts)) {
            val allowed = bt.publicAllowlist()
            events.log(
                "bt_ip_allowlist",
                mapOf(
                    "count" to allowed.size.toString(),
                    "hosts" to allowed.joinToString(","),
                ),
            )
        }
    }

    /** 当前动态白名单快照（诊断 / 验收读口；不含基础私网段）。 */
    fun btAllowlistHosts(): Set<String> =
        if (this::bt.isInitialized) bt.publicAllowlist() else emptySet()

    /** 这个地址现在会不会被私有会话的 ip_filter 放行（诊断 / 验收读口）。 */
    fun btIpFilterAllows(host: String): Boolean =
        if (this::bt.isInitialized) bt.ipFilterAllows(host) else false

    /** 把最新的 `peer.announce`（含 pubBtPort 变化）推给所有在线成员。 */
    internal fun broadcastPeerAnnounceAll() {
        for (group in allGroups()) group.broadcastPeerAnnounce(null)
    }

    fun dhtNodes(): Long = if (this::bt.isInitialized) bt.dhtNodes() else 0L

    internal fun messagePortHint(): Int = portHint

    /** 冷启动重连的目标群（[start] 时从 state.json 恢复出来的那些，且现在还在本地库里）。 */
    internal fun reconnectTargets(): List<GroupRuntime> = synchronized(stateLock) {
        restoredGroupIds.mapNotNull { groupsById[it] }
    }

    /** 建一条去程链路：TLS 握手成功后把 PeerLink 的读线程起起来，返回时链路已在收发 hello。 */
    internal fun openOutboundLink(host: String, port: Int): PeerLink {
        val ctx = Tls.clientContext(tlsIdentity)
        val socket = ctx.socketFactory.createSocket() as SSLSocket
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            Tls.configure(socket)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket.startHandshake()
            socket.soTimeout = 0
        } catch (e: Throwable) {
            try { socket.close() } catch (_: Throwable) {}
            throw e
        }
        val link = PeerLink(this, socket, inbound = false, remoteHost = host)
        registerLink(link)
        val thread = Thread { link.run() }
        thread.isDaemon = true
        thread.name = "huchuan-link-out"
        thread.start()
        return link
    }

    /**
     * 一条链路绑定成功（[PeerLink.onBound]）。只在去程链路上记端点：
     * 入向链路拿到的是对端的临时源端口，不是它的监听端口，落盘也没有意义。
     */
    internal fun onLinkBound(link: PeerLink, group: GroupRuntime) {
        if (link.inbound) return
        val dev = link.peerDeviceId
        if (dev.isEmpty()) return
        group.rememberPeerAddr(dev, link.remoteHost, link.remotePort)
    }

    internal fun localBtPortLong(): Long = localBtPort().toLong()

    // ------------------------------------------------------------------ 公开会话（public-swarm）

    /** 公开会话是否在跑（按需启停，见 §3.6）。诊断与验收读口。 */
    val publicSessionRunning: Boolean get() = this::publicBt.isInitialized && publicBt.isRunning

    /** 公开会话的监听端口；没起来返回 0。 */
    fun publicSessionPort(): Int = if (this::publicBt.isInitialized) publicBt.listenPort else 0

    /** 公开会话里的 torrent 数（诊断与验收读口）。 */
    fun publicTorrentCount(): Int = if (this::publicBt.isInitialized) publicBt.torrentCount() else 0

    /** 私有会话里的 torrent 数（诊断与验收读口）。 */
    fun privateTorrentCount(): Int = if (this::bt.isInitialized) bt.torrentCount() else 0

    /** 公开条目的 .torrent 缓存（`<data>/public/meta/<infohash>.torrent`）；没有返回 null。 */
    internal fun publicCachedTorrent(fileId: String): File? =
        if (this::publicBt.isInitialized) publicBt.cachedTorrent(fileId) else null

    /**
     * 公开会话里某个 torrent 的 .torrent 字节（发布 / 磁力导入都会落到
     * `<data>/public/meta/<infohash>.torrent`）。没有缓存或读失败返回 null。
     *
     * 诊断与验收读口：发布之后用它解析 TorrentInfo，断言 private=0、infohash 与磁力一致，
     * 不必去翻 `<data>/public/meta` 的具体路径。
     */
    fun publicTorrentBytes(fileId: String): ByteArray? = try {
        publicCachedTorrent(fileId)?.readBytes()
    } catch (e: Throwable) {
        null
    }

    /** 公开会话的落盘目录（`<data>/public/data`）；会话还没建好时按同一约定算。 */
    internal fun publicDataDir(): File =
        if (this::publicBt.isInitialized) publicBt.dataDir else File(File(dataDir, PUBLIC_DIR), "data")

    /**
     * 本机某个 fileId 的 torrent 在哪个会话里。
     *
     * 先问私有会话（常驻、绝大多数任务在这里），再问公开会话（按需起来）；
     * 两个都没有时返回私有会话 —— 调用方拿到的读数全是「没有这个 torrent」，与旧行为一致。
     */
    internal fun sessionOf(fileId: String): LocalSession {
        val priv: LocalSession? = if (this::bt.isInitialized) bt else null
        if (priv != null && priv.has(fileId)) return priv
        val pub: LocalSession? = if (this::publicBt.isInitialized) publicBt else null
        if (pub != null && pub.has(fileId)) return pub
        return priv ?: checkNotNull(pub) { "kernel not started" }
    }

    /**
     * 加种前一律按种子自身的 private 位（BEP 27）决定会话，**不信条目上的 scope 字段**
     * （public-swarm §1.2）。没有内联种子的公开条目只能走公开会话。
     */
    internal fun sessionRoute(entry: FileEntry): String {
        if (entry.torrent.isNotEmpty()) {
            val privateTorrent = try {
                org.libtorrent4j.TorrentInfo(java.util.Base64.getDecoder().decode(entry.torrent)).isPrivate
            } catch (e: Throwable) {
                entry.scope != FileScope.PUBLIC
            }
            return SessionRoute.of(privateTorrent)
        }
        return if (entry.scope == FileScope.PUBLIC) SessionRoute.PUBLIC else SessionRoute.PRIVATE
    }

    /** 公开条目「激活」：先在 `<data>/public/meta` 找 .torrent 缓存，没有就把磁力挂进公开会话。 */
    internal fun startPublicDownload(group: GroupRuntime, entry: FileEntry): Boolean {
        val pub = ensurePublicSession() ?: return false
        val cached = pub.cachedTorrent(entry.fileId)
        val ok = if (cached != null) {
            try {
                pub.addTorrent(org.libtorrent4j.TorrentInfo(cached), pub.dataDir)
            } catch (e: Throwable) {
                events.log("public_download_bad_torrent", mapOf("file_id" to entry.fileId, "error" to e.toString()))
                false
            }
        } else if (entry.magnet.isEmpty()) {
            // 公开条目没有磁力：既没有缓存种子也没有可加的东西（对端发来的坏条目）。
            events.log("public_download_no_magnet", mapOf("file_id" to entry.fileId))
            false
        } else {
            // 没有元数据缓存：把磁力重新挂进公开会话（0 = 不阻塞宿主，元数据由后台继续找）。
            pub.addMagnet(entry.magnet, pub.dataDir, 0L)
            pub.has(entry.fileId)
        }
        if (!ok) {
            events.log("public_download_failed", mapOf("file_id" to entry.fileId, "magnet" to entry.magnet.length.toString()))
            return false
        }
        group.markLocalTask(entry.fileId)
        events.log(
            "download_start",
            mapOf(
                "group_id" to group.groupId,
                "file_id" to entry.fileId,
                "name" to entry.name,
                "size" to entry.size.toString(),
                "save_path" to pub.dataDir.absolutePath,
                "sess" to "pub",
                "from_cache" to (cached != null).toString(),
            ),
        )
        return true
    }

    /**
     * 磁力导入（public-swarm §3）：插进公开会话 → 等元数据 → 落 .torrent → 建公开条目并广播。
     *
     * **永不抛异常**。五种失败各一句话（[PublicMagnetResult.messageFor]）：非法链接 / 重复 /
     * 无源 / 超时 / 元数据无效。除前两种外都建条目（§3.1「无元数据不报错」：条目正常存在、
     * 状态「等待来源」，周期重试）。
     *
     * @param timeoutMs 等元数据的上限；M3 默认 60 s，自检与自动化用短超时（0 = 不阻塞）。
     */
    @JvmOverloads
    fun addPublicMagnet(
        groupId: String,
        magnet: String,
        name: String? = null,
        timeoutMs: Long = PublicBtSession.DEFAULT_METADATA_WAIT_MS,
    ): PublicMagnetResult {
        val group = group(groupId) ?: run {
            val reason = PublicMagnetResult.NO_GROUP
            events.log("public_magnet_rejected", mapOf("gid" to groupId, "reason" to reason))
            return PublicMagnetResult(false, "", reason, PublicMagnetResult.messageFor(reason), null, "")
        }
        val parsed = MagnetLink.parse(magnet) ?: run {
            val reason = PublicMagnetResult.INVALID_LINK
            events.log("public_magnet_rejected", mapOf("gid" to groupId, "reason" to reason))
            return PublicMagnetResult(false, "", reason, PublicMagnetResult.messageFor(reason), null, "")
        }
        // 同群内按 fileId 去重（§1.1 / 裁决 5）：已经有这个 infohash 就是「重复」，不再建第二次。
        if (group.file(parsed.fileId) != null) {
            val reason = PublicMagnetResult.DUPLICATE
            events.log("public_magnet_rejected", mapOf("gid" to groupId, "file_id" to parsed.fileId, "reason" to reason))
            return PublicMagnetResult(false, parsed.fileId, reason, PublicMagnetResult.messageFor(reason), null, "")
        }
        val pub = ensurePublicSession() ?: run {
            val reason = PublicMagnetResult.SESSION_ERROR
            events.log("public_magnet_rejected", mapOf("gid" to groupId, "reason" to reason))
            return PublicMagnetResult(false, parsed.fileId, reason, PublicMagnetResult.messageFor(reason), null, "")
        }
        val result = pub.addMagnet(parsed.normalized, pub.dataDir, timeoutMs)
        // 条目名：调用方给的名字 > 元数据 / dn > 未命名种子（§3.1 的默认文案）。
        val entryName = name?.trim()?.takeIf { it.isNotEmpty() }
            ?: result.name.ifBlank { parsed.displayName }
                .ifBlank { "未命名种子" }
        val entry = group.addPublicEntry(
            fileId = parsed.fileId,
            magnet = parsed.normalized,
            name = entryName,
            size = result.size,
            pieceSize = result.pieceSize,
        )
        events.log(
            "public_magnet",
            mapOf(
                "gid" to groupId,
                "file_id" to parsed.fileId,
                "name" to entryName,
                "ok" to result.ok.toString(),
                "reason" to result.reason,
                "size" to result.size.toString(),
                "metadata_path" to result.metadataPath,
                "port" to pub.listenPort.toString(),
                "torrents" to pub.torrentCount().toString(),
            ),
        )
        return result.copy(entry = entry)
    }

    /**
     * 「公开到群外」（public-swarm §6.1）：把一个本机正在做种的私有文件**另建**为 private=0 的
     * v1 公开种子，加入公开会话并广播一条新的 public 条目。原私有条目原样保留 —— 两条 infohash
     * 不同（private 位写在 info 字典里，BEP 27），所以这是新建种子，不是改标记。
     *
     * 校验（失败全部走 [PublicPublishResult.reason]，不抛异常）：
     *  - 群存在、fileId 在本群清单里，且是本机还没公开过的私有条目；
     *  - 本机做种集合里有它的落盘路径，文件在、大小与条目一致（「本机有完整文件」）；
     *  - 公开会话能起来并接受这个种子。
     *
     * 幂等：同一个私有 fileId 第二次调用直接返回已有的公开条目，不重新建种、不重复广播。
     * 建好的 .torrent 会写进 `<data>/public/meta/<pub_infohash>.torrent`，重启续种直接用它。
     */
    @JvmOverloads
    fun publishPublic(
        groupId: String,
        fileId: String,
        opts: PublicPublishOptions = PublicPublishOptions(),
    ): PublicPublishResult {
        val group = group(groupId)
            ?: return publishFailure(groupId, fileId, PublicPublishResult.NO_GROUP)
        val entry = group.file(fileId)
            ?: return publishFailure(groupId, fileId, PublicPublishResult.NO_FILE)

        // 幂等快路径：原始私有 id 已经发布过；也接受把公开条目的 fileId 传进来（重试 / 宿主幂等调用）。
        val existing = group.publishedEntryOf(fileId)
            ?: if (entry.scope == FileScope.PUBLIC && group.sourceOfPublic(fileId) != null) {
                group.file(fileId)
            } else {
                null
            }
        if (existing != null && existing.scope == FileScope.PUBLIC) {
            // 宿主可能拿公开 id 重试：回显原私有 id，日志与结果都按「私有 → 公开」这一对来写。
            val sourceId = group.sourceOfPublic(existing.fileId) ?: fileId
            group.broadcastPeerAnnounce(null)
            events.log(
                "public_publish",
                mapOf(
                    "gid" to groupId,
                    "file_id" to sourceId,
                    "pub_infohash" to existing.fileId,
                    "name" to existing.name,
                    "size" to existing.size.toString(),
                    "piece_size" to existing.pieceSize.toString(),
                    "magnet" to existing.magnet,
                    "reused" to "true",
                ),
            )
            return PublicPublishResult(
                ok = true,
                fileId = existing.fileId,
                privateFileId = sourceId,
                reason = PublicPublishResult.OK,
                message = "",
                entry = existing,
                magnet = existing.magnet,
            )
        }
        if (entry.scope == FileScope.PUBLIC) {
            return publishFailure(groupId, fileId, PublicPublishResult.ALREADY_PUBLIC)
        }

        // 「本机有完整文件」：做种集合记着路径（本机共享 / 下载完成都进集合），会话里还有落盘路径兜底。
        // 刚下完、完成巡查还没把它挪进做种集合的那 200 ms 里，isComplete 已经是真 —— 也放行。
        val role = group.localRole(fileId)
        val complete = when (role) {
            GroupRuntime.LocalRole.SEEDING -> true
            GroupRuntime.LocalRole.DOWNLOADING -> group.isComplete(fileId)
            else -> false
        }
        if (!complete) {
            val reason = if (role == GroupRuntime.LocalRole.DOWNLOADING) {
                PublicPublishResult.INCOMPLETE
            } else {
                PublicPublishResult.NOT_SEEDING
            }
            return publishFailure(groupId, fileId, reason)
        }
        val localSeedPath = group.localSeedPath(fileId)
        val localFile = if (localSeedPath.isNotEmpty()) {
            File(localSeedPath)
        } else {
            group.savedFile(fileId)
        } ?: return publishFailure(groupId, fileId, PublicPublishResult.LOCAL_FILE_MISSING)
        if (!localFile.isFile) {
            return publishFailure(groupId, fileId, PublicPublishResult.LOCAL_FILE_MISSING)
        }
        if (localFile.length() != entry.size) {
            return publishFailure(groupId, fileId, PublicPublishResult.SIZE_MISMATCH)
        }

        // 内容不变，只把 private 位翻成 0：piece size 规则与私有建种共用 TorrentBuild / BtSession.pieceSizeFor。
        val built = try {
            TorrentBuild.v1(localFile, privateTorrent = false, comment = "huchuan public v1")
        } catch (e: Throwable) {
            events.log(
                "public_publish_error",
                mapOf("gid" to groupId, "file_id" to fileId, "error" to e.toString()),
            )
            return publishFailure(groupId, fileId, PublicPublishResult.BUILD_FAILED)
        }
        if (built.fileId == entry.fileId) {
            return publishFailure(groupId, fileId, PublicPublishResult.HASH_COLLISION, built.fileId)
        }

        val pub = ensurePublicSession()
            ?: return publishFailure(groupId, fileId, PublicPublishResult.SESSION_ERROR, built.fileId)
        val saveDir = localFile.parentFile ?: pub.dataDir
        // SEED_MODE：刚建完种，数据已知完好（私有侧刚校验 / 共享过），免掉 libtorrent 的二次全量哈希（§6.1）。
        // TorrentInfo 构造与加种都不许把异常抛给宿主：公开会话失败只是一个结果码（内核其余功能照旧）。
        val added = try {
            pub.addTorrent(org.libtorrent4j.TorrentInfo(built.torrentBytes), saveDir, true)
        } catch (e: Throwable) {
            events.log(
                "public_publish_error",
                mapOf(
                    "gid" to groupId,
                    "file_id" to fileId,
                    "pub_infohash" to built.fileId,
                    "error" to e.toString(),
                ),
            )
            false
        }
        if (!added) {
            // 会话是这一次 ensure 起来的，加种失败后不该留下一个空转的 DHT 会话（按需启停，§7.1）。
            maybeStopPublicSession()
            return publishFailure(groupId, fileId, PublicPublishResult.SESSION_ERROR, built.fileId)
        }
        val cachePath = pub.cacheTorrent(built.fileId, built.torrentBytes)

        val name = opts.name.trim().takeIf { it.isNotEmpty() } ?: entry.name.ifEmpty { built.name }
        val magnet = Proto.magnet(built.fileId, name, built.size)
        val publicEntry = group.addPublicEntry(
            fileId = built.fileId,
            magnet = magnet,
            name = name,
            size = built.size,
            pieceSize = built.pieceSize,
        )
        // addPublicEntry 只在「同 infohash 已是私有条目」（裁决 5：只留 private）时 DROP；正常情况下
        // 不会发生（private 位已经让两个 infohash 不同）。真发生了就把刚加的公开 torrent 摘掉。
        if (publicEntry.scope != FileScope.PUBLIC || publicEntry.fileId != built.fileId) {
            pub.removeTorrent(built.fileId)
            maybeStopPublicSession()
            return publishFailure(groupId, fileId, PublicPublishResult.HASH_COLLISION, built.fileId)
        }

        group.markPublicSeed(built.fileId, localFile.absolutePath)
        group.rememberPublished(entry.fileId, built.fileId)
        group.broadcastPeerAnnounce(null)
        saveState()
        events.log(
            "public_publish",
            mapOf(
                "gid" to groupId,
                "file_id" to entry.fileId,
                "pub_infohash" to built.fileId,
                "name" to name,
                "size" to built.size.toString(),
                "piece_size" to built.pieceSize.toString(),
                "magnet" to magnet,
                "torrent_path" to cachePath,
                "torrents" to pub.torrentCount().toString(),
                "reused" to "false",
            ),
        )
        return PublicPublishResult(
            ok = true,
            fileId = built.fileId,
            privateFileId = entry.fileId,
            reason = PublicPublishResult.OK,
            message = "",
            entry = publicEntry,
            magnet = publicEntry.magnet.ifEmpty { magnet },
        )
    }

    /**
     * 取消「公开到群外」：停掉公开会话里的该 torrent、把公开条目从本机清单移除（本地隐藏），
     * 不广播删除、不删磁盘文件（public-swarm §5.2 的移除语义）。私有条目不受影响。
     *
     * [fileId] 既可以是公开条目的 infohash，也可以是当初 [publishPublic] 传入的私有 fileId。
     * 返回 true = 这次真的移除了公开条目；群 / 条目不存在、或传的是私有条目时返回 false。
     */
    fun unpublishPublic(groupId: String, fileId: String): Boolean {
        val group = group(groupId) ?: run {
            events.log(
                "public_unpublish_rejected",
                mapOf("gid" to groupId, "file_id" to fileId, "reason" to "no_group"),
            )
            return false
        }
        val entry = group.publicEntryOf(fileId) ?: run {
            events.log(
                "public_unpublish_rejected",
                mapOf("gid" to groupId, "file_id" to fileId, "reason" to "not_public"),
            )
            return false
        }
        if (entry.scope != FileScope.PUBLIC) {
            events.log(
                "public_unpublish_rejected",
                mapOf("gid" to groupId, "file_id" to fileId, "reason" to "not_public"),
            )
            return false
        }
        val wasActive = group.localRole(entry.fileId) != GroupRuntime.LocalRole.NONE
        // 无条件摘一次：公开条目可能在「激活」之前就挂在会话里找源，不摘的话移除之后还在后台跑。
        group.stopFile(entry.fileId)
        val removed = group.hideLocally(entry.fileId) != null
        saveState()
        events.log(
            "public_unpublish",
            mapOf(
                "gid" to groupId,
                "file_id" to entry.fileId,
                "pub_infohash" to entry.fileId,
                "was_active" to wasActive.toString(),
                "removed" to removed.toString(),
            ),
        )
        return removed
    }

    /** 失败结果 + 一条可 grep 的 `public_publish_rejected` 日志（永不上抛）。 */
    private fun publishFailure(
        groupId: String,
        fileId: String,
        reason: String,
        pubInfohash: String = "",
    ): PublicPublishResult {
        val fields = LinkedHashMap<String, String>()
        fields["gid"] = groupId
        fields["file_id"] = fileId
        if (pubInfohash.isNotEmpty()) fields["pub_infohash"] = pubInfohash
        fields["reason"] = reason
        events.log("public_publish_rejected", fields)
        return PublicPublishResult(
            ok = false,
            fileId = fileId,
            privateFileId = fileId,
            reason = reason,
            message = PublicPublishResult.messageFor(reason),
            entry = null,
            magnet = "",
        )
    }

    /** 起公开会话（幂等）。失败只记日志、返回 null —— 公开功能缺席，其余内核功能照旧。 */
    internal fun ensurePublicSession(): PublicBtSession? {
        if (!this::publicBt.isInitialized) return null
        if (publicBt.isRunning) return publicBt
        return try {
            publicBt.setSessionUploadLimit(sessionUploadKbps)
            publicBt.setSessionDownloadLimit(sessionDownloadKbps)
            publicBt.start()
            // 公开会话的端口可能变了：立刻重报 peer.announce，让成员知道新的 pubBtPort。
            broadcastPeerAnnounceAll()
            events.log(
                "public_session_started",
                mapOf(
                    "port" to publicBt.listenPort.toString(),
                    "dht_nodes" to publicBt.dhtNodes().toString(),
                    "dht_running" to publicBt.isDhtRunning().toString(),
                ),
            )
            publicBt
        } catch (e: Throwable) {
            events.log("public_session_error", mapOf("error" to e.toString()))
            null
        }
    }

    /** 没有任何非休眠公开条目时停掉公开会话（按需启停的另一半，§7.1）。 */
    internal fun maybeStopPublicSession() {
        if (!this::publicBt.isInitialized || !publicBt.isRunning) return
        if (allGroups().any { it.hasActivePublicTask() }) return
        publicBt.stop()
        // 端口已经没了：重报一条 pubBtPort=0，对端下一轮就会跳过公开拨号。
        broadcastPeerAnnounceAll()
        events.log("public_session_stopped", mapOf("reason" to "no_active_public_entry"))
    }

    /** 公开会话的元数据到手（含后台补元数据）：只回填本地条目，不改签名、不回广播。 */
    private fun onPublicMetadata(arrival: PublicBtSession.MetadataArrival) {
        for (g in allGroups()) g.backfillPublicMetadata(arrival.fileId, arrival.name, arrival.size, arrival.pieceSize)
        events.log(
            "public_metadata_arrived",
            mapOf(
                "file_id" to arrival.fileId,
                "name" to arrival.name,
                "size" to arrival.size.toString(),
                "private" to arrival.privateTorrent.toString(),
                "torrent_path" to arrival.torrentPath,
            ),
        )
    }

    // ------------------------------------------------------------------ 会话选择

    /**
     * 设置 tracker 基址（只能在内核起来之前调用）。
     *
     * 起来之后再改会被忽略并留一条日志：已经在跑的 announcer 手里有「服务端记着的那把 passkey」，
     * 中途换基址会把这条连续性打断（新地址上要么从头注册，要么报 409），不如明确不支持。
     */
    fun setTrackerBaseUrl(url: String) {
        val normalized = normalizeTrackerBaseUrl(url)
        if (started) {
            events.log(
                "tracker_config_ignored",
                mapOf("reason" to "kernel_already_started", "base_len" to normalized.length.toString()),
            )
            return
        }
        trackerBaseUrl = normalized
    }

    /**
     * 起跨网发现。配置为空 / 非法时只留一条日志就返回 —— 不抛、不重试、不影响局域网那一档。
     */
    private fun startTracker() {
        val base = trackerBaseUrl
        if (base.isEmpty()) {
            events.log("tracker_disabled", mapOf("reason" to "base_url_empty"))
            return
        }
        val service = TrackerService(this, base)
        if (!service.enabled) {
            events.log("tracker_disabled", mapOf("reason" to service.configError, "base" to service.baseUrl))
            return
        }
        tracker = service
        service.start()
        // 从 state.json 恢复出来的群：立刻注册并 announce，冷启动重连的候选表里就有跨网那一份。
        for (group in allGroups()) service.watch(group)
    }

    // ------------------------------------------------------------------ 会话级（P7 全局限速）

    /** P7 全局限速的目标值（KB/s，0 = 不限）。会话起来之前先记在这里，[start] 时补一次。 */
    @Volatile
    private var sessionUploadKbps = 0

    @Volatile
    private var sessionDownloadKbps = 0

    /**
     * P7 全局限速（上行）。入参 **KB/s**，0 = 不限；内部 ×1024 交给 libtorrent（字节/秒）。
     *
     * 运行期调用**立即生效**：走 `SessionManager.applySettings`，作用在本会话的带宽通道上，
     * 当前会话里所有 torrent（含已经在传的）都受它约束 —— 不是只写进「下次建会话」的 settings_pack。
     * 与单文件限速（[BtSession.setUploadLimit]）是两层，同时存在时每个 torrent 取更小的那个。
     * 会话还没起来时先记下，[start] 建会话时带上。
     */
    fun setSessionUploadLimit(kbps: Int) {
        val value = if (kbps < 0) 0 else kbps
        sessionUploadKbps = value
        if (this::bt.isInitialized) bt.setSessionUploadLimit(value)
        events.log(
            "session_upload_limit",
            mapOf(
                "kbps" to value.toString(),
                "applied_kbps" to sessionUploadLimitKbps().toString(),
                "error" to sessionLimitError(),
            ),
        )
    }

    /** P7 全局限速（下行），口径同 [setSessionUploadLimit]。 */
    fun setSessionDownloadLimit(kbps: Int) {
        val value = if (kbps < 0) 0 else kbps
        sessionDownloadKbps = value
        if (this::bt.isInitialized) bt.setSessionDownloadLimit(value)
        events.log(
            "session_download_limit",
            mapOf(
                "kbps" to value.toString(),
                "applied_kbps" to sessionDownloadLimitKbps().toString(),
                "error" to sessionLimitError(),
            ),
        )
    }

    /** 上行限速**真值**（KB/s）：读的是会话里生效的值，不是本类的缓存；会话没起来时为待生效目标值。 */
    fun sessionUploadLimitKbps(): Int =
        if (this::bt.isInitialized) bt.sessionUploadLimitKbps() else sessionUploadKbps

    /** 下行限速真值（KB/s），口径同 [sessionUploadLimitKbps]。 */
    fun sessionDownloadLimitKbps(): Int =
        if (this::bt.isInitialized) bt.sessionDownloadLimitKbps() else sessionDownloadKbps

    private fun sessionLimitError(): String = if (this::bt.isInitialized) bt.rateLimitError else ""

    // ------------------------------------------------------------------ 群

    fun groups(): List<GroupRuntime> = allGroups()

    fun group(groupId: String): GroupRuntime? = synchronized(stateLock) { groupsById[groupId] }

    internal fun allGroups(): List<GroupRuntime> = synchronized(stateLock) { groupsById.values.toList() }

    internal fun groupById(gid: String): GroupRuntime? = group(gid)

    internal fun singleGroupOrNull(): GroupRuntime? = synchronized(stateLock) {
        if (groupsById.size == 1) groupsById.values.first() else null
    }

    // ------------------------------------------------------------------ 未读数（缺口 G4）

    /** 整个群未读消息数（P2 的「N 条新消息」）；群不存在返回 0。 */
    fun unreadCount(groupId: String): Int = group(groupId)?.unreadCount() ?: 0

    /** 单个文件的未读数（P3 行上的角标）；群或文件不存在返回 0。 */
    fun unreadCount(groupId: String, fileId: String): Int = group(groupId)?.unreadCount(fileId) ?: 0

    /** P2 打开时清零整群未读，返回清掉的条数；群不存在返回 0。 */
    fun markGroupRead(groupId: String): Int = group(groupId)?.markAllRead() ?: 0

    /** P4 打开时清零该文件的未读，返回清掉的条数；群或文件不存在返回 0。 */
    fun markFileRead(groupId: String, fileId: String): Int = group(groupId)?.markRead(fileId) ?: 0

    // ------------------------------------------------------------------ 队友数（观测口径）

    /**
     * 队友数（产品规格 docs/design/product-spec.md:306）：当前与该文件 torrent 实际互连、
     * 且本机处于「部分」的成员数，不含做种者；本机已经 100% 或没挂进会话时一律 0。
     *
     * 读的是 libtorrent 的 peer 列表（[GroupRuntime.teammates]），没有新协议字段、不用 0x53。
     * 宿主按需轮询（P3 行 chip / P4 标题）即可：这个数只随连接实况变化，没有对应事件。
     * 群或文件不存在返回 0，不抛。
     */
    fun teammates(groupId: String, fileId: String): Int = group(groupId)?.teammates(fileId) ?: 0

    // ------------------------------------------------------------------ 退群（缺口 G1）

    /**
     * 退群 / 解散群。
     *
     * 一次做完四件事：① 停掉该群全部 torrent（做种与下载都摘出会话，磁盘文件不动）；
     * ② 关闭该群的全部消息链路（先发 0x42 bye，再断 socket）；③ 删群密钥（keySeed 与派生的 kGrp 就地清零）；
     * ④ 从本地库移除（saveState 的快照里不再有这个群）。
     *
     * **幂等**：groupId 不在本地群列表里（没建过、已删过、或本来就不属于本机）返回 false，
     * 不抛异常、不改库、不重复发事件。返回 true 表示这一次真的删掉了。
     */
    fun removeGroup(groupId: String): Boolean {
        val group = synchronized(stateLock) { groupsById.remove(groupId) } ?: run {
            events.log("group_remove_noop", mapOf("gid" to groupId))
            return false
        }
        val fileIds = group.files().map { it.fileId }
        // ① ②：停 torrent + 关连接
        val (stopped, closedLinks) = group.purgeForRemoval("group_removed")
        // 兜底：任何仍挂在被删群上的连接（极端竞态下没进 group.links 的）一并关掉
        for (link in linkSet.toList()) {
            if (link.group?.groupId == groupId) link.close("group_removed")
        }
        // ③ 的另一半：退群后不该再被同网段当成该群成员
        try { lanDiscovery.unregister(groupId) } catch (_: Throwable) {}
        // 跨网那一跳：退群后不再为这个群注册 / announce（close 里会补一条 event=stopped）
        try { tracker?.forget(groupId) } catch (_: Throwable) {}
        // ③：删群密钥
        val keyWiped = group.wipeKeys()
        // 顺带清掉进度打点缓存里属于这个群的条目，避免长跑进程里越积越多
        for (id in fileIds) {
            progressSeen.remove(id)
            teamsSeen.remove(id)
        }
        // ④：从本地库移除（saveState 只看 groupsById，群已经不在里面了）
        saveState()
        events.log(
            "group_removed",
            mapOf(
                "gid" to groupId,
                "name" to group.name,
                "torrents_stopped" to stopped.toString(),
                "links_closed" to closedLinks.toString(),
                "files" to fileIds.size.toString(),
                "key_wiped" to keyWiped.toString(),
            ),
        )
        events.groupRemoved(group)
        return true
    }

    /** 建群（协议 §11 第 1-3 步）。 */
    fun createGroup(name: String): GroupRuntime {
        val groupIdBytes = ByteArray(10).also { random.nextBytes(it) }
        val groupId = B32.encode(groupIdBytes)
        val handleBytes = ByteArray(5).also { random.nextBytes(it) }
        val gHandle = B32.encode(handleBytes)
        val keySeed = ByteArray(32).also { random.nextBytes(it) }
        val invite = InviteCode.create(random)
        val meta = GroupMeta(
            groupId = groupId,
            gHandle = gHandle,
            name = name.ifBlank { deviceName + "的群组" },
            invite = invite,
            keySeed = keySeed,
            epoch = 0,
            joinOpen = true,
            createdAt = System.currentTimeMillis(),
        )
        val rt = GroupRuntime(this, meta)
        rt.addMember(deviceId, deviceName, 1, identity.pub64.toHex())
        // 创建者也要把自己的公钥登记进公钥库，不能只写进成员表。
        // 少了这一句，别人把创建者的 file.list 条目回传过来时，本进程按 addedBy 取不到公钥，
        // 只能按 no_pub 丢掉（证据 artifacts/m1/log-catchup-a.log 里 232 条 file_list_rejected reason=no_pub）。
        registerPub(deviceId, identity.pub64)
        synchronized(stateLock) { groupsById[groupId] = rt }
        if (localMessagePort() > 0) lanDiscovery.register(meta, localMessagePort(), localBtPort())
        // 跨网发现：建群就把 groupHash20 注册到 tracker（配置为空时 tracker 为 null，这里什么都不发生）
        tracker?.watch(rt)
        saveState()
        events.log(
            "group_created",
            mapOf(
                "gid" to groupId,
                "name" to meta.name,
                "invite_code" to invite.code,
                "invite_pretty" to invite.pretty,
                "tail8" to invite.tail8,
                "fp" to meta.fp,
                "g_handle" to gHandle,
            ),
        )
        events.joined(rt)
        return rt
    }

    internal fun installJoinedGroup(msg: JoinOkMsg, invite: InviteCode, keySeed: ByteArray): GroupRuntime {
        synchronized(stateLock) {
            groupsById[msg.gid]?.let { return it }
        }
        val handleBytes = ByteArray(5).also { random.nextBytes(it) }
        val meta = GroupMeta(
            groupId = msg.gid,
            gHandle = B32.encode(handleBytes),
            name = msg.gname.ifBlank { "群组" },
            invite = invite,
            keySeed = keySeed,
            epoch = msg.epoch,
            joinOpen = true,
            createdAt = System.currentTimeMillis(),
        )
        val rt = GroupRuntime(this, meta)
        rt.addMember(deviceId, deviceName, 1, identity.pub64.toHex())
        // 自己的公钥同样进库：join.ok.members 正常情况下已经把本机这条带回来了，这里兜住
        // 「应答方没把自己列进 members」的老实现，否则本机条目在别人的 file.list 里一律 no_pub。
        registerPub(deviceId, identity.pub64)
        // 成员表里带 pub（协议 §10）。先算 deviceId(pub) == dev 再登记：对不上的整条丢掉，
        // 对得上的写进公钥库 —— 紧接着要处理的 files 快照就靠它按 addedBy 验签。
        var pubs = 0
        for (m in msg.members) {
            if (m.pub.isEmpty()) {
                rt.addMember(m.dev, m.name, m.nameSeq, "")
                continue
            }
            val raw = try { hexToBytes(m.pub) } catch (e: Exception) { null }
            if (raw == null || raw.size != 64 || DeviceIdentity.deviceIdOf(raw) != m.dev) {
                events.log("join_ok_member_bad_pub", mapOf("gid" to msg.gid, "dev" to m.dev))
                continue
            }
            registerPub(m.dev, raw)
            rt.addMember(m.dev, m.name, m.nameSeq, m.pub)
            pubs++
        }
        // files 快照走同一条验签入口：验不过的条目不入库，也就不会进 fdigest。
        for (f in msg.files) rt.onFileListEntry(f, null)
        synchronized(stateLock) { groupsById[msg.gid] = rt }
        lanDiscovery.register(meta, localMessagePort(), localBtPort())
        // 入群同样要登记：新成员不会等到下一次重启才出现在 tracker 上
        tracker?.watch(rt)
        saveState()
        events.log(
            "group_joined",
            mapOf(
                "gid" to msg.gid,
                "name" to meta.name,
                "fp" to meta.fp,
                "fp_ok" to (meta.fp == msg.fp).toString(),
                "members" to rt.members().size.toString(),
                "member_pubs" to pubs.toString(),
                "files" to rt.files().size.toString(),
            ),
        )
        events.joined(rt)
        return rt
    }

    // ------------------------------------------------------------------ 加入

    /** P5 只收后 8 位；manualCandidates 是 §3.3 的手动 IP:port 兜底。 */
    fun joinByTail8(tail8: String, manualCandidates: List<String>, timeoutMs: Long): GroupRuntime? {
        val invite = InviteCode.fromTail8(tail8) ?: run {
            events.log("join_bad_tail8", mapOf("input_length" to tail8.length.toString()))
            return null
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        val intent = JoinIntent(invite)
        intent.manualCandidates = manualCandidates
        joinIntent = intent
        try {
            val lanCandidates = if (lanDiscovery.start()) {
                lanDiscovery.browse(minOf(4000L, timeoutMs))
            } else {
                emptyList()
            }
            val merged = LinkedHashMap<String, Candidate>()
            for (c in lanCandidates) merged[c.key] = c
            for (raw in manualCandidates) {
                val c = ManualCandidate.parse(raw) ?: continue
                merged[c.key] = c
            }
            // 连接顺序：mDNS（M1 主路径，§3.1）优先，手动 IP:port 兜底（§3.3）。
            // §3.4 的 manual > lan 只用于「同一 endpoint 的候选去重」时的优先级记录，
            // 这里两者的 key 不同，不构成同键冲突。
            val ordered = merged.values.sortedByDescending { if (it.source == "lan") 1 else 0 }
            events.log(
                "join_candidates",
                mapOf(
                    "lan" to lanCandidates.size.toString(),
                    "total" to ordered.size.toString(),
                    "tail8" to invite.tail8,
                    "addrs" to ordered.joinToString(",") { it.key },
                ),
            )
            for (c in ordered) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 1000) break
                val group = attemptJoin(c, intent, minOf(12_000L, remaining))
                if (group != null) {
                    events.log("join_via", mapOf("source" to c.source, "addr" to c.key))
                    return group
                }
                // 候选连不上要留痕：多网卡机器上 mDNS 解析出的地址经常不可达，
                // 只看到「最后走了 manual」是查不出原因的。
                events.log(
                    "join_attempt_failed",
                    mapOf("addr" to c.key, "source" to c.source, "error" to intent.lastError),
                )
            }
            events.log("join_failed", mapOf("reason" to intent.lastError))
            return null
        } finally {
            joinIntent = null
        }
    }

    private fun attemptJoin(candidate: Candidate, intent: JoinIntent, timeoutMs: Long): GroupRuntime? {
        val attempt = CompletableFuture<GroupRuntime>()
        intent.attempt = attempt
        var link: PeerLink? = null
        try {
            link = openOutboundLink(candidate.host, candidate.port)
            val joined = try {
                attempt.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                intent.lastError = "timeout connecting " + candidate.key
                null
            } catch (e: ExecutionException) {
                intent.lastError = e.cause?.message ?: e.message ?: "join rejected"
                null
            }
            if (joined != null) {
                // 这一条端点是「加入成功用过的地址 + 手动候选项」：落盘给下次冷启动重连（缺口一）。
                // 建群方没有手动候选，但它的对端（加入方）会把这两样都记住。
                joined.rememberPeerAddr(link.peerDeviceId, candidate.host, candidate.port)
                joined.rememberManualAddrs(intent.manualCandidates)
            }
            return joined
        } catch (e: Throwable) {
            intent.lastError = e.toString()
            return null
        } finally {
            if (!attempt.isDone) {
                // 连接已经建好但没等到 join.ok：关掉这条链路（socket 的收尾在 PeerLink.close 里）。
                attempt.cancel(true)
                try { link?.close() } catch (_: Throwable) {}
            }
        }
    }

    internal fun currentJoinIntent(): JoinIntent? = joinIntent

    internal fun onJoinComplete(intent: JoinIntent, group: GroupRuntime, link: PeerLink) {
        intent.attempt?.complete(group)
        events.log(
            "join_ok",
            mapOf(
                "gid" to group.groupId,
                "name" to group.name,
                "fp" to group.fingerprint,
                "dev" to link.peerDeviceId,
            ),
        )
    }

    internal fun joinFailed(intent: JoinIntent, reason: String) {
        intent.lastError = reason
        intent.attempt?.completeExceptionally(IllegalStateException(reason))
    }

    // ------------------------------------------------------------------ 设备公钥

    internal fun pubOf(dev: String): ByteArray? = pubKeys.of(dev)

    /** 已登记公钥的十六进制形式；没有登记返回 ""。给宿主做诊断用（不打日志）。 */
    fun pubHexOf(dev: String): String = pubKeys.of(dev)?.toHex() ?: ""

    internal fun registerPub(dev: String, pub: ByteArray) {
        // 公钥落库，供离线验签（协议 §1.2 的 deviceId 由公钥唯一决定）
        if (pubKeys.register(dev, pub)) saveStateIfStarted()
    }

    // ------------------------------------------------------------------ 跨设备时钟（只用于展示时延）

    /** dev → 「对端墙钟 − 本机墙钟」的估计（ms）。只做展示换算，不参与任何排序与判定。 */
    private val peerClockSkewMs = HashMap<String, Long>()

    /**
     * 观察一条来自 [dev] 的帧：对端自报的时刻是 [peerTs]（对端墙钟），本机在 [recvLocal] 收到。
     *
     * peerTs − recvLocal = 时钟差 − 单程时延：单程时延越小越接近真实的时钟差，
     * 所以取历次的最大值（即单程时延最小的那一次）。
     * 只用「对端主动上报自己时刻」的帧（hello / join.req / join.ok / peer.announce / member.name）校准；
     * 评论 op 的 ts 恰恰是要量的对象，拿它估偏移会把自身时延抹平成 0。
     */
    internal fun observePeerClock(dev: String, peerTs: Long, recvLocal: Long) {
        if (dev.isEmpty() || peerTs <= 0L || recvLocal <= 0L) return
        val sample = peerTs - recvLocal
        synchronized(stateLock) {
            val old = peerClockSkewMs[dev]
            if (old == null || sample > old) peerClockSkewMs[dev] = sample
        }
    }

    /**
     * 端到端时延（ms），**减法两边都在本机时钟上**，只用于展示。
     *
     * 对端的 op.ts 是对端墙钟。两台设备时钟不同步时，直接拿本机墙钟减对端 ts 会得到负值
     * （实测 -1907 ms）。这里先把对端的发送时刻按 [observePeerClock] 估出的偏移换算到本机时钟，
     * 再用本机收到时刻去减：时延 = recvLocal − (peerTs − 偏移)。
     * 偏移样本不存在的极端情况下按 0 处理并夹到 1 ms —— 对宿主而言「已到达」就是 1 ms。
     *
     * 时延与 ts 都只展示：评论的顺序固定按 (lamport, author, seq)（协议 §6）。
     */
    fun latencyMillis(dev: String, peerTs: Long, localRecvAt: Long): Long {
        val skew = synchronized(stateLock) { peerClockSkewMs[dev] } ?: 0L
        val peerSendLocal = peerTs - skew
        val delta = localRecvAt - peerSendLocal
        return if (delta < 1L) 1L else delta
    }

    internal fun nonceSeen(dev: String, nonce: String): Boolean = synchronized(stateLock) {
        val key = dev + "/" + nonce
        if (nonceLru.containsKey(key)) true else {
            nonceLru[key] = true
            false
        }
    }

    internal fun rememberNonce(dev: String, nonce: String) {
        synchronized(stateLock) { nonceLru[dev + "/" + nonce] = true }
    }

    // ------------------------------------------------------------------ 连接登记

    internal fun registerLink(link: PeerLink) {
        linkSet.add(link)
    }

    internal fun unregisterLink(link: PeerLink) {
        linkSet.remove(link)
    }

    // ------------------------------------------------------------------ 持久化

    internal fun saveStateIfStarted() {
        if (started && !closed.get()) saveState()
    }

    internal fun saveState() {
        if (!started || !this::identity.isInitialized) return
        val snapshot = synchronized(stateLock) {
            jobj(
                "format" to jstr("huchuan-m1-state/1"),
                "device" to jobj("dev" to jstr(identity.deviceId), "name" to jstr(deviceName)),
                "keys" to J.Obj(pubKeys.entries().entries.associate { it.key to jstr(it.value.toHex()) }),
                "groups" to jarr(allGroups().map { it.toStoreJson() }),
            )
        }
        try {
            val tmp = File(dataDir, "state.json.tmp")
            tmp.writeText(Json.write(snapshot), Charsets.UTF_8)
            if (!tmp.renameTo(File(dataDir, "state.json"))) {
                File(dataDir, "state.json").writeText(Json.write(snapshot), Charsets.UTF_8)
            }
        } catch (e: Throwable) {
            events.log("state_save_error", mapOf("error" to e.toString()))
        }
    }

    /**
     * 读回群密钥。方案对不上、或 Keystore 密钥失效（刷机 / 恢复出厂 / 密钥被系统清掉）时返回 null，
     * 该群跳过而不是拿明文兜底 —— 解不开就是解不开，宁可少一个群也不把密钥写回明文。
     */
    private fun unsealKeySeed(token: String, scheme: String?): ByteArray? {
        val box: SecretBox = when (val s = scheme ?: PlaintextSecretBox.scheme) {
            secretBox.scheme -> secretBox
            PlaintextSecretBox.scheme -> PlaintextSecretBox
            else -> {
                events.log("state_key_scheme_unknown", mapOf("scheme" to s))
                return null
            }
        }
        val plain = box.open(token)
        if (plain == null) {
            events.log("state_key_unseal_failed", mapOf("scheme" to box.scheme))
            return null
        }
        return if (plain.size == 32) plain else null
    }

    private fun loadState() {
        val file = File(dataDir, "state.json")
        if (!file.isFile) return
        try {
            val root = Json.parse(file.readText(Charsets.UTF_8))
            val keys = root.obj("keys")
            if (keys != null) {
                for (entry in keys.fields.entries) {
                    val value = entry.value as? J.Str ?: continue
                    pubKeys.register(entry.key, hexToBytes(value.value))
                }
            }
            val groupArray = root.arr("groups") ?: emptyList()
            for (item in groupArray) {
                val o = item as? J.Obj ?: continue
                val gid = o.str("gid") ?: continue
                val code = o.str("code") ?: continue
                val invite = InviteCode.fromFull(code) ?: continue
                val keySeedToken = o.str("keySeed") ?: continue
                val keySeed = unsealKeySeed(keySeedToken, o.str("keySeedProt")) ?: continue
                val meta = GroupMeta(
                    groupId = gid,
                    gHandle = o.str("gHandle") ?: "",
                    name = o.str("name") ?: "",
                    invite = invite,
                    keySeed = keySeed,
                    epoch = (o.num("epoch") ?: 0L).toInt(),
                    joinOpen = o.bool("joinOpen") ?: true,
                    createdAt = o.num("createdAt") ?: 0L,
                )
                val rt = GroupRuntime(this, meta)
                for (m in o.arr("members") ?: emptyList()) {
                    val mo = m as? J.Obj ?: continue
                    val dev = mo.str("dev") ?: continue
                    rt.addMember(dev, mo.str("name") ?: "", mo.num("nameSeq") ?: 0L, mo.str("pub") ?: "")
                }
                // 本地移除（public-swarm §5.2）：隐藏集合必须先于 files 恢复，否则被移除的条目会先入库、
                // 再被隐藏标记「晾」在清单里（restoreFile 只对后恢复的条目生效）。
                for (h in o.arr("hidden") ?: emptyList()) {
                    val hs = h as? J.Str ?: continue
                    rt.restoreHidden(hs.value)
                }
                for (f in o.arr("files") ?: emptyList()) {
                    val fo = f as? J.Obj ?: continue
                    try { rt.restoreFile(FileEntry.fromJson(fo)) } catch (_: Exception) {}
                }
                // 「私有 → 公开」发布映射（幂等 / 取消公开）：必须在 files 之后，映射指向的条目要先存在。
                for (p in o.arr("published") ?: emptyList()) {
                    val po = p as? J.Obj ?: continue
                    rt.restorePublished(po.str("fileId") ?: "", po.str("publicFileId") ?: "")
                }
                for (op in o.arr("ops") ?: emptyList()) {
                    val oo = op as? J.Obj ?: continue
                    try { rt.restoreOp(OpRecord.fromJson(oo)) } catch (_: Exception) {}
                }
                // 做种集合（缺口 G2）：先记成待续种，等 BT 会话起来后由群自己判定（文件还在不在 / 大小对不对）。
                for (s in o.arr("seeding") ?: emptyList()) {
                    val so = s as? J.Obj ?: continue
                    val fileId = so.str("fileId") ?: continue
                    rt.restoreSeeding(fileId, so.str("path") ?: "")
                }
                // 冷启动重连（缺口一）：上次成功连过的端点 + 加入时用过的手动 IP:port。
                // 没有这两个字段的旧库读回来就是「没有候选」，重连器改走 mDNS（browseEntries 按 gid 归群）。
                for (p in o.arr("peerAddrs") ?: emptyList()) {
                    val po = p as? J.Obj ?: continue
                    rt.restorePeerAddr(po.str("dev") ?: "", po.str("addr") ?: "")
                }
                for (m in o.arr("manual") ?: emptyList()) {
                    val ms = m as? J.Str ?: continue
                    rt.restoreManualAddr(ms.value)
                }
                // 未读游标（缺口 G4）：没有这一段的旧 state.json 读回来就是「全都未读」，
                // 所以缺字段一律当没有游标，不做兼容补算。
                for (r in o.arr("read") ?: emptyList()) {
                    val ro = r as? J.Obj ?: continue
                    val fileId = ro.str("fileId") ?: continue
                    rt.restoreReadCursor(fileId, ReadCursor.fromJson(ro))
                }
                o.num("lamport")?.let { rt.oplog.setLamport(it) }
                synchronized(stateLock) { groupsById[gid] = rt }
            }
            events.log("state_loaded", mapOf("groups" to groupsById.size.toString(), "keys" to pubKeys.size.toString()))
        } catch (e: Throwable) {
            events.log("state_load_error", mapOf("error" to e.toString()))
        }
    }

    /**
     * 重启续种（缺口 G2）：逐群把「上次在做种」的条目挂回会话，失败降级为休眠并记日志。
     * 返回 (恢复数, 降级数)；日志 `seed_resume_done` 每轮都打，0 条也是证据。
     */
    private fun resumeSeeds(): Pair<Int, Int> {
        var resumed = 0
        var skipped = 0
        for (g in allGroups()) {
            val (r, s) = try {
                g.resumeSeeds()
            } catch (e: Throwable) {
                events.log("seed_resume_error", mapOf("group_id" to g.groupId, "error" to e.toString()))
                0 to 0
            }
            resumed += r
            skipped += s
        }
        events.log("seed_resume_done", mapOf("resumed" to resumed.toString(), "skipped" to skipped.toString()))
        if (resumed + skipped > 0) saveState()
        return resumed to skipped
    }

    // ------------------------------------------------------------------ 后台巡查

    private fun startMonitor() {
        val thread = Thread {
            var lastPing = 0L
            while (!closed.get()) {
                try {
                    val now = System.currentTimeMillis()
                    val groups = allGroups()
                    events.log(
                        "heartbeat",
                        mapOf(
                            "dht_nodes" to bt.dhtNodes().toString(),
                            "groups" to groups.size.toString(),
                            "msg_port" to messagePortValue.toString(),
                            "bt_port" to bt.listenPort.toString(),
                        ),
                    )
                    for (g in groups) {
                        // 做种集合先对账：文件被移走删掉、或校验后数据不全的，就地降级为休眠（缺口 G2 兜底）。
                        g.reconcileSeeding()
                        for (f in g.files()) {
                            if (!g.isLocalTask(f.fileId) || g.isLocalSeed(f.fileId)) continue
                            if (!g.isComplete(f.fileId)) {
                                // 队友数每拍算一次（观测口径，见 teammates）：进度打点与「变化才报」都用它。
                                val team = try {
                                    g.teammates(f.fileId)
                                } catch (e: Throwable) {
                                    events.log(
                                        "teammates_probe_error",
                                        mapOf("group_id" to g.groupId, "file_id" to f.fileId, "error" to e.toString()),
                                    )
                                    -1
                                }
                                // 进度只在变化时打点，脚本靠它判断「下载在动 / 休眠后停了」。
                                val pct = g.percent(f.fileId)
                                if (progressSeen[f.fileId] != pct) {
                                    progressSeen[f.fileId] = pct
                                    events.log(
                                        "download_progress",
                                        mapOf(
                                            "group_id" to g.groupId,
                                            "file_id" to f.fileId,
                                            "pct" to pct.toString(),
                                            "down_bps" to g.downloadRateBps(f.fileId).toString(),
                                            "teammates" to team.toString(),
                                        ),
                                    )
                                }
                                // 拨号不再看「有没有 peer」，而是每成员一次（见 GroupRuntime.connectSeeders）
                                try {
                                    g.connectSeeders(f.fileId)
                                } catch (e: Throwable) {
                                    events.log(
                                        "connect_seeders_error",
                                        mapOf("group_id" to g.groupId, "file_id" to f.fileId, "error" to e.toString()),
                                    )
                                }
                                // 队友数只在变化时打一行：它只随 BT 连接实况变化，不会刷屏。
                                val before = teamsSeen.put(f.fileId, team)
                                if (before == null) {
                                    events.log(
                                        "teammates_first_sample",
                                        mapOf(
                                            "group_id" to g.groupId,
                                            "file_id" to f.fileId,
                                            "count" to team.toString(),
                                            "peers" to g.peerCount(f.fileId).toString(),
                                        ),
                                    )
                                } else if (before != team && team >= 0) {
                                    events.log(
                                        "teammates_changed",
                                        mapOf(
                                            "group_id" to g.groupId,
                                            "file_id" to f.fileId,
                                            "count" to team.toString(),
                                            "peers" to g.peerCount(f.fileId).toString(),
                                        ),
                                    )
                                }
                            } else {
                                // 完成判定 → 做种集合，判定与动作都在 completeFile 一处。
                                // 正常路径由完成线程（告警 / 200 ms 兜底巡查）先命中，这里只是兜底：
                                // markCompleted 是幂等的，同一个文件不会推两次事件。
                                completeFile(g, f.fileId, "monitor")
                            }
                        }
                    }
                    if (now - lastPing >= PING_INTERVAL_MS) {
                        lastPing = now
                        for (g in groups) for (link in g.onlineLinks()) link.ping()
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Throwable) {
                    // 巡查线程不允许挂
                }
                try {
                    Thread.sleep(HEARTBEAT_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        thread.isDaemon = true
        thread.name = "huchuan-monitor"
        thread.start()
        monitorThread = thread
    }

    // ------------------------------------------------------------------ 完成判定（缺口二）

    /**
     * 完成判定到事件的整条路径（**唯一一处**）：
     * 数据落盘且校验通过 → 进做种集合 → 推 [KernelEvents.fileCompleted] + 打 download_completed
     * → 做种数变了重新自报 peer.announce.seeds。
     *
     * 幂等：[GroupRuntime.markCompleted] 用集合去重，同一个文件重复进来不会重复出事件。
     * [source] 只用于日志 —— alert（libtorrent 的 torrent_finished_alert 当场）/ sweep（200 ms 兜底巡查）
     * / monitor（2 s 巡查兜底）。真机缺陷就是它迟到一拍（docs/m1-device.md §2.5）：
     * 下完之后 P3 会停在「下载中 100%」等巡查，最多 2 s 才转「做种中」。
     */
    internal fun completeFile(group: GroupRuntime, fileId: String, source: String): Boolean {
        val entry = group.file(fileId) ?: return false
        if (!group.isLocalTask(fileId) || group.isLocalSeed(fileId)) return false
        if (!group.isComplete(fileId)) return false
        val result = group.markCompleted(fileId)
        if (result.first) {
            // 先写日志行再推事件：宿主（含 harness）常在 fileCompleted 回调里立刻读
            // "download_completed 的 source"，反过来写会有一个「事件已到、字段还没填」的窗口，
            // 表现为 source="" 把快通道误判成慢通道（真机与 harness 都踩到过）。
            events.log(
                "download_completed",
                mapOf(
                    "group_id" to group.groupId,
                    "file_id" to fileId,
                    "size" to entry.size.toString(),
                    "path" to (group.savedFile(fileId)?.absolutePath ?: ""),
                    "source" to source,
                ),
            )
            events.fileCompleted(group, entry)
            group.setUploadLimitKbps(fileId, 0)
        }
        // 做种数变了才重新自报（协议 §10 的 peer.announce.seeds）
        if (result.seeding) group.broadcastPeerAnnounce(null)
        return result.first
    }

    /**
     * libtorrent 告警线程的入口：只入队，判定与动作交给 [completionWatcher]。
     *
     * 告警会对「挂进会话时数据就完整」的做种 torrent 也发（续种、校验完），所以先按本机任务表
     * 过一遍：只有「挂进会话、还没进做种集合」的才值得排队，免得把队列和日志刷成噪音。
     * 这一遍只读内存结构，不碰磁盘也不碰 socket —— 告警线程上不能做重活。
     */
    private fun onTorrentFinishedAlert(fileId: String) {
        if (fileId.isEmpty()) return
        var downloading = false
        for (g in allGroups()) {
            if (g.file(fileId) == null) continue
            if (g.isLocalTask(fileId) && !g.isLocalSeed(fileId)) {
                downloading = true
                break
            }
        }
        if (!downloading) return
        events.log("completion_alert", mapOf("file_id" to fileId))
        completionQueue.offer(fileId)
    }

    /**
     * 完成线程：告警一来立刻判定；没有告警时每 [COMPLETION_TICK_MS] 兜底扫一遍在下的文件。
     *
     * 两条路走同一个 [completeFile]，所以「事件早到 / 晚到」只影响时延，不影响结果。
     * 兜底那一半是必要的：升级前就在下、或者平台没投递告警的 torrent 只能靠它。
     */
    private fun startCompletionWatcher() {
        val thread = Thread {
            while (!closed.get()) {
                var fileId: String? = null
                try {
                    fileId = completionQueue.poll(COMPLETION_TICK_MS, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    break
                }
                if (closed.get()) break
                try {
                    if (fileId != null) completeByFileId(fileId, "alert")
                    sweepDownloads("sweep")
                } catch (e: Throwable) {
                    // 完成判定不允许把线程带崩：下一个节拍还会再来
                }
            }
        }
        thread.isDaemon = true
        thread.name = "huchuan-complete"
        thread.start()
        completionWatcher = thread
    }

    private fun completeByFileId(fileId: String, source: String): Boolean {
        for (g in allGroups()) {
            if (g.file(fileId) == null) continue
            if (!g.isLocalTask(fileId) || g.isLocalSeed(fileId)) return false
            return completeFile(g, fileId, source)
        }
        return false
    }

    /** 兜底巡查：只碰「挂进会话且还没进做种集合」的文件，正常就 0–2 条。 */
    private fun sweepDownloads(source: String) {
        for (g in allGroups()) {
            for (f in g.files()) {
                if (!g.isLocalTask(f.fileId) || g.isLocalSeed(f.fileId)) continue
                if (!g.isComplete(f.fileId)) continue
                completeFile(g, f.fileId, source)
            }
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 4000
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        const val HEARTBEAT_MS = 2000L
        const val PING_INTERVAL_MS = 30_000L

        /**
         * 完成兜底巡查的节拍。真机要求「下载完成 → 状态变化」在 500 ms 以内，
         * 告警当场到达时这条兜底不会触发；告警拿不到时最坏也就是这一档的时延。
         */
        const val COMPLETION_TICK_MS = 200L

        /** 公开会话的数据根：`<data>/public`（session 内部再分 data / meta）。 */
        const val PUBLIC_DIR = "public"
    }
}

/**
 * 会话归属（public-swarm §1.2）：加种前**只看**种子自身的 private 位（BEP 27），
 * 不看条目上的 scope 字段 —— 传输中的 scope 可以被转发方篡改，传输面按它走就会走错会话。
 */
object SessionRoute {
    const val PRIVATE = "private"
    const val PUBLIC = "public"

    @JvmStatic
    fun of(privateTorrent: Boolean): String = if (privateTorrent) PRIVATE else PUBLIC
}

/** tracker 基址的规范化：去首尾空白与尾部斜杠（`https://x/` 与 `https://x` 是同一个基址）。 */
internal fun normalizeTrackerBaseUrl(raw: String): String = raw.trim().trimEnd('/')
