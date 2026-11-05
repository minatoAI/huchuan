package app.huchuan.core.m1

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
) : AutoCloseable {

    internal lateinit var identity: DeviceIdentity
        private set

    internal lateinit var tlsIdentity: TlsIdentity
        private set

    internal lateinit var bt: BtSession
        private set

    val downloadsDir: File get() = File(dataDir, "downloads")

    private val stateLock = Any()
    private val groupsById = LinkedHashMap<String, GroupRuntime>()
    private val pubKeys = HashMap<String, ByteArray>()
    private val linkSet: MutableSet<PeerLink> = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    internal val lanDiscovery = LanDiscovery()
    internal val joinRate = JoinRateLimiter()

    private val nonceLru = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 256
    }

    private val random = SecureRandom()
    private val closed = AtomicBoolean(false)
    private var started = false
    private var server: PeerServer? = null
    private var monitorThread: Thread? = null

    /** 每个文件上一次打点过的进度，避免同一百分比刷屏。 */
    private val progressSeen = HashMap<String, Int>()

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

        bt = BtSession(btHint, File(dataDir, "bt"), ipFilterEnabled = true)
        bt.start()

        val srv = PeerServer(this)
        messagePortValue = srv.start()
        server = srv

        val mdnsOk = lanDiscovery.start()
        for (g in allGroups()) lanDiscovery.register(g.meta, messagePortValue, bt.listenPort)

        startMonitor()
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
            ),
        )
    }

    override fun close() {
        stop()
    }

    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        monitorThread?.interrupt()
        for (g in allGroups()) {
            lanDiscovery.unregister(g.groupId)
            for (link in g.onlineLinks()) link.close()
        }
        for (link in linkSet.toList()) link.close()
        try { server?.stop() } catch (_: Throwable) {}
        try { lanDiscovery.close() } catch (_: Throwable) {}
        try { if (this::bt.isInitialized) bt.stop() } catch (_: Throwable) {}
        try { saveState() } catch (_: Throwable) {}
        events.log("kernel_stopped", mapOf("device_id" to deviceId))
    }

    fun isStarted(): Boolean = started && !closed.get()

    fun localMessagePort(): Int = messagePortValue

    fun localBtPort(): Int = if (this::bt.isInitialized) bt.listenPort else 0

    fun dhtNodes(): Long = if (this::bt.isInitialized) bt.dhtNodes() else 0L

    internal fun messagePortHint(): Int = portHint

    internal fun localBtPortLong(): Long = localBtPort().toLong()

    // ------------------------------------------------------------------ 群

    fun groups(): List<GroupRuntime> = allGroups()

    fun group(groupId: String): GroupRuntime? = synchronized(stateLock) { groupsById[groupId] }

    internal fun allGroups(): List<GroupRuntime> = synchronized(stateLock) { groupsById.values.toList() }

    internal fun groupById(gid: String): GroupRuntime? = group(gid)

    internal fun singleGroupOrNull(): GroupRuntime? = synchronized(stateLock) {
        if (groupsById.size == 1) groupsById.values.first() else null
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
        synchronized(stateLock) { groupsById[groupId] = rt }
        if (localMessagePort() > 0) lanDiscovery.register(meta, localMessagePort(), localBtPort())
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
        var socket: SSLSocket? = null
        try {
            val ctx = Tls.clientContext(tlsIdentity)
            socket = ctx.socketFactory.createSocket() as SSLSocket
            socket.connect(InetSocketAddress(candidate.host, candidate.port), CONNECT_TIMEOUT_MS)
            Tls.configure(socket)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket.startHandshake()
            socket.soTimeout = 0
            val created = PeerLink(this, socket, inbound = false, remoteHost = candidate.host)
            link = created
            registerLink(created)
            val thread = Thread { created.run() }
            thread.isDaemon = true
            thread.name = "huchuan-link-out"
            thread.start()
            return try {
                attempt.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                intent.lastError = "timeout connecting " + candidate.key
                null
            } catch (e: ExecutionException) {
                intent.lastError = e.cause?.message ?: e.message ?: "join rejected"
                null
            }
        } catch (e: Throwable) {
            intent.lastError = e.toString()
            return null
        } finally {
            if (!attempt.isDone) {
                attempt.cancel(true)
                if (link != null) link.close() else try { socket?.close() } catch (_: Throwable) {}
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

    internal fun pubOf(dev: String): ByteArray? = synchronized(stateLock) { pubKeys[dev] }

    internal fun registerPub(dev: String, pub: ByteArray) {
        if (dev.isEmpty() || pub.size != 64) return
        val changed = synchronized(stateLock) {
            val old = pubKeys[dev]
            if (old == null || !old.contentEquals(pub)) {
                pubKeys[dev] = pub
                true
            } else {
                false
            }
        }
        if (changed) {
            // 公钥落库，供离线验签（协议 §1.2 的 deviceId 由公钥唯一决定）
            saveStateIfStarted()
        }
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
                "keys" to J.Obj(pubKeys.entries.associate { it.key to jstr(it.value.toHex()) }),
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
                    pubKeys[entry.key] = hexToBytes(value.value)
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
                for (f in o.arr("files") ?: emptyList()) {
                    val fo = f as? J.Obj ?: continue
                    try { rt.restoreFile(FileEntry.fromJson(fo)) } catch (_: Exception) {}
                }
                for (op in o.arr("ops") ?: emptyList()) {
                    val oo = op as? J.Obj ?: continue
                    try { rt.restoreOp(OpRecord.fromJson(oo)) } catch (_: Exception) {}
                }
                o.num("lamport")?.let { rt.oplog.setLamport(it) }
                synchronized(stateLock) { groupsById[gid] = rt }
            }
            events.log("state_loaded", mapOf("groups" to groupsById.size.toString(), "keys" to pubKeys.size.toString()))
        } catch (e: Throwable) {
            events.log("state_load_error", mapOf("error" to e.toString()))
        }
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
                        for (f in g.files()) {
                            if (!g.isLocalTask(f.fileId) || g.isLocalSeed(f.fileId)) continue
                            if (!g.isComplete(f.fileId)) {
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
                                        ),
                                    )
                                }
                            }
                            if (g.isComplete(f.fileId)) {
                                if (g.markCompleted(f.fileId)) {
                                    events.fileCompleted(g, f)
                                    events.log(
                                        "download_completed",
                                        mapOf(
                                            "group_id" to g.groupId,
                                            "file_id" to f.fileId,
                                            "size" to f.size.toString(),
                                            "path" to (g.savedFile(f.fileId)?.absolutePath ?: ""),
                                        ),
                                    )
                                    g.setUploadLimitKbps(f.fileId, 0)
                                }
                            } else {
                                g.connectSeeders(f.fileId)
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

    companion object {
        const val CONNECT_TIMEOUT_MS = 4000
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        const val HEARTBEAT_MS = 2000L
        const val PING_INTERVAL_MS = 30_000L
    }
}
