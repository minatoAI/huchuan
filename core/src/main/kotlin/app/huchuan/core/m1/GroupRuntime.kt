package app.huchuan.core.m1

import app.huchuan.core.FileScope
import java.io.File
import java.util.Base64

/** 群元数据。keySeed 只在 TLS 通道内的 join.ok 里下发，不落 mDNS、不落 tracker、不进日志（协议 §2.2）。 */
class GroupMeta(
    val groupId: String,
    val gHandle: String,
    var name: String,
    val invite: InviteCode,
    val keySeed: ByteArray,
    var epoch: Int,
    var joinOpen: Boolean,
    val createdAt: Long,
) {
    val kGrp: ByteArray by lazy { Hkdf.derive(keySeed, invite.codeBytes, Domains.GROUP, 32) }

    /** fp = B32(SHA-256(kGrp)[0..5])，8 字符，P6 口头核对用。 */
    val fp: String by lazy { B32.encode(sha256(kGrp).copyOf(5)) }

    /** hello 的群归属证明：HMAC(kGrp, signBytes("huchuan/proof1/v1", [gid, chanId]))（协议 §10）。 */
    fun proofHex(chanId: ByteArray): String =
        hmacSha256(kGrp, SignBytes.of(Domains.PROOF, listOf(SignBytes.str(groupId), SignBytes.bin(chanId)))).toHex()
}

/**
 * 一个群在本机的运行时：成员表、文件清单、评论日志、已建立的成员连接、BT 任务。
 * 所有协议字段的读写都收在这一个类里，PeerLink 负责传输，M1Kernel 负责生命周期与持久化。
 */
class GroupRuntime internal constructor(
    internal val kernel: M1Kernel,
    internal val meta: GroupMeta,
) {
    private val lock = Any()

    internal val files = LinkedHashMap<String, FileEntry>()
    internal val members = LinkedHashMap<String, MemberRecord>()
    internal val links = LinkedHashMap<String, PeerLink>()

    /** dev → (host, btPort)，来自 peer.announce 与 TLS 对端地址（**私有会话**端口）。 */
    internal val btEndpoints = HashMap<String, Pair<String, Int>>()

    /**
     * dev → (host, pubBtPort)，来自同一条 peer.announce 的 `pubBtPort`（**公开会话**端口）。
     *
     * 只在成员真的报了 1..65535 的值时存在；成员明确报 0（公开会话已停）或字段缺省时从这里移除。
     * 公开条目对成员拨号只认这一张表（[connectSeeders]），绝不回落到私有 [btEndpoints]：
     * 两个端口属于两个 SessionManager，拨错会话就是连错 swarm（public-swarm §4.1）。
     */
    internal val pubBtEndpoints = HashMap<String, Pair<String, Int>>()

    /**
     * dev → 该成员最近一条 peer.announce 自报的平台与做种数（协议 §10 的 plat / seeds）。
     * 只活在内存里：对端重连时会立刻补一条 peer.announce，重启后这两个值自己会回来。
     */
    private val memberLive = HashMap<String, MemberLive>()

    /**
     * fileId → 「上次读到哪条 op」的游标（缺口 G4）。跟着群一起落盘到 state.json，
     * 用户打开 P4（或自己写了评论）时推进，未读数由游标与可见 op 现算，不存计数器。
     */
    internal val readCursors = HashMap<String, ReadCursor>()

    internal val oplog = OpLog(kernel.identity) { dev -> kernel.pubOf(dev) }

    /** 本机已挂进 BT 会话的文件（做种或下载）。 */
    internal val localTasks = HashSet<String>()

    /**
     * 本机做种的文件（唯一来源，口径见 [SeedingLedger]）。
     *
     * 做种数、`peer.announce.seeds` 的自报值、P3 的「做种中」都读它；以前这里是一个只在
     * `addFile` 里加、`markCompleted` 不碰的 HashSet，于是「下完转做种」的设备永远自报 0。
     */
    internal val seeding = SeedingLedger()

    /**
     * 本机隐藏的条目（public-swarm §5.2 的「移除」）：只从本机列表消失，不广播、不删磁盘文件。
     *
     * `file.add` / `file.list` 再送来同一 fileId 时一律丢弃，否则对端下一轮同步就会把「移除」
     * 原地复活。隐藏标记跟着群落盘（state.json 的 hidden），重启后仍然隐藏。
     */
    internal val hidden = LinkedHashSet<String>()

    /**
     * 原始私有 fileId → 「公开到群外」新建出来的公开条目 fileId。
     *
     * 公开种子是另一个 infohash（private 位在 info 字典里，BEP 27），光看条目无法知道它由谁发布；
     * 这张表负责幂等（同一私有文件重复发布返回同一条）与「按私有 id 取消公开」。跟着群落盘。
     */
    internal val publishedTo = LinkedHashMap<String, String>()

    /**
     * 重启续种的待办：state.json 里记着「上次在做种」的条目（fileId → 本地路径），
     * 等 BT 会话起来后由 [resumeSeeds] 逐条判定，判定通过才进 [seeding]。
     */
    private val pendingSeeds = LinkedHashMap<String, String>()

    /**
     * dev → 上次成功建链的对端消息层端点（host:port），跟着群一起落盘。
     *
     * 冷启动重连的第一优先候选（[reconnectCandidates]）：只记「本机拨出去并成功绑定」的端点 ——
     * 入向链路拿到的是对端的临时源端口，不是它的监听端口，记下来没用。
     */
    internal val peerAddrs = LinkedHashMap<String, String>()

    /**
     * 上次加入时用过、或本机建群后手动填过的 IP:port（host:port），跟着群一起落盘。
     *
     * 用途只有一个：mDNS 不通、上次连过的端点也变了的时候，冷启动还能按同一组地址连回去（协议 §3.3）。
     */
    internal val manualAddrs = LinkedHashSet<String>()

    /**
     * tracker 最近一次 announce 回来的端点（协议 §3.2）：别的成员在公网上的 `(源 ip, 消息层端口)`。
     *
     * 只活在内存里 —— 它本来就是「刚问过服务端的那份名单」，落盘没有意义，重启后下一拍
     * announce 会再拿回来。合并进 [reconnectCandidates] 时优先级最低（§3.4）。
     */
    private val trackerCandidates = ArrayList<Candidate>()

    /** 已连接的 BT 端点（做种侧标记用）。 */
    internal val connectedEndpoints = HashSet<String>()

    /**
     * fileId@dev → 这个成员的 BT 拨号状态（端点、拨了几次、下一次最早什么时候拨）。
     *
     * 按**成员**记而不是按「有没有 peer」判：同一台设备先后换端口（重启）时端点变了就当新成员重来。
     * 成员一旦在 libtorrent 的 peer 列表里被确认互连，这条记录就被删掉 —— 重复调用 connectSeeders
     * 不会给已经连上的成员再拨一次。
     */
    private val dials = HashMap<String, MemberDial>()

    /** 已上报过「下载完成」的文件，避免重复事件。 */
    private val completed = HashSet<String>()

    /** fileId → 连续几拍「数据不完整 / torrent 不在会话里」，够两拍才降级（避开挂种瞬间的状态抖动）。 */
    private val unhealthySeeds = HashMap<String, Int>()

    val groupId: String get() = meta.groupId
    val name: String get() = meta.name
    val inviteCode: String get() = meta.invite.code
    val invitePretty: String get() = meta.invite.pretty
    val fingerprint: String get() = meta.fp

    // ------------------------------------------------------------------ 读

    /** 成员表：除 dev/name/seq/pub 外，带该成员自报的平台与做种数（协议 §10 的 peer.announce）。 */
    fun members(): List<MemberRecord> = synchronized(lock) {
        members.values.map { m ->
            val live = memberLive[m.dev]
            m.copy(
                // 本机平台不用等自己 announce：直接取内核的 platform（协议 §10 的 plat 同一口径）。
                platform = if (m.dev == kernel.deviceId) Proto.normalizePlatform(kernel.platform)
                else live?.platform ?: Proto.PLATFORM_UNKNOWN,
                seedingCount = seedingCountFor(m.dev),
            )
        }
    }

    /**
     * 本机在当前群做种的文件数（只数还在清单里的那些）。
     *
     * 读数只有 [SeedingLedger.count] 一处：本机自报的 seeds（[peerAnnounceBody]）与成员表里
     * 「本机」那一行都走它，所以「下载完成 → 做种」这件事在这两处不可能不一致。
     */
    fun seedingFileCount(): Int = synchronized(lock) { seedingCountLocked() }

    /** 某个成员自报的做种数：本机走真实值，别人的值来自他最近一条 peer.announce（从未自报为 0）。 */
    fun seedingCountOf(dev: String): Int = synchronized(lock) { seedingCountFor(dev) }

    private fun seedingCountFor(dev: String): Int =
        if (dev == kernel.deviceId) seedingCountLocked() else memberLive[dev]?.seeds ?: 0

    private fun seedingCountLocked(): Int = seeding.count(files.keys)

    fun files(): List<FileEntry> = synchronized(lock) { files.values.toList() }

    fun file(fileId: String): FileEntry? = synchronized(lock) { files[fileId] }

    /**
     * 直接按公开条目 id，或按原始私有 id 经发布映射找到的公开条目。
     *
     * 「公开到群外」的幂等与取消公开都以它为入口：映射指向的条目被隐藏 / 删除后，映射就地清掉。
     */
    internal fun publicEntryOf(fileId: String): FileEntry? = synchronized(lock) {
        val direct = files[fileId]
        if (direct != null && direct.scope == FileScope.PUBLIC) return@synchronized direct
        val pubId = publishedTo[fileId] ?: return@synchronized null
        val entry = files[pubId]
        if (entry == null || entry.scope != FileScope.PUBLIC) {
            publishedTo.remove(fileId)
            null
        } else {
            entry
        }
    }

    /** 由某个公开条目反查它的私有来源 fileId；不是本机发布过的公开条目返回 null。 */
    internal fun sourceOfPublic(publicFileId: String): String? = synchronized(lock) {
        publishedTo.entries.firstOrNull { it.value == publicFileId }?.key
    }

    /** 原始私有 fileId 已经发布过，且公开条目还在：返回它（幂等快路径）。 */
    internal fun publishedEntryOf(privateFileId: String): FileEntry? = synchronized(lock) {
        val pubId = publishedTo[privateFileId] ?: return@synchronized null
        val entry = files[pubId]
        if (entry == null || entry.scope != FileScope.PUBLIC) {
            publishedTo.remove(privateFileId)
            null
        } else {
            entry
        }
    }

    /** 记下「这个私有文件已经公开成哪一条」。 */
    internal fun rememberPublished(privateFileId: String, publicFileId: String) {
        if (privateFileId.isEmpty() || publicFileId.isEmpty()) return
        synchronized(lock) { publishedTo[privateFileId] = publicFileId }
    }

    /** 本机做种集合里记的落盘路径；不在集合里返回 ""。 */
    internal fun localSeedPath(fileId: String): String = seeding.localPath(fileId)

    /** 「公开到群外」加种成功之后：公开条目也进本地任务表与做种集合。 */
    internal fun markPublicSeed(fileId: String, localPath: String) {
        synchronized(lock) {
            localTasks.add(fileId)
            seeding.enter(fileId, localPath)
            connectedEndpoints.add("seed:" + fileId)
        }
    }

    /**
     * 本地移除（public-swarm §5.2）：条目从本机清单消失、标记为隐藏，磁盘文件不动。
     * 返回被移除的条目；本来就不在清单里返回 null。
     */
    internal fun hideLocally(fileId: String): FileEntry? = synchronized(lock) {
        val removed = files.remove(fileId)
        hidden.add(fileId)
        publishedTo.entries.removeIf { it.value == fileId }
        readCursors.remove(fileId)
        completed.remove(fileId)
        unhealthySeeds.remove(fileId)
        dials.keys.removeIf { it.startsWith(fileId + "@") }
        removed
    }

    /** 载入 state.json 时用：先恢复隐藏集合，再恢复条目（隐藏条目不入库）。 */
    internal fun restoreHidden(fileId: String) {
        if (fileId.isEmpty()) return
        synchronized(lock) { hidden.add(fileId) }
    }

    /** 载入 state.json 时用。 */
    internal fun restorePublished(privateFileId: String, publicFileId: String) {
        if (privateFileId.isEmpty() || publicFileId.isEmpty()) return
        synchronized(lock) { publishedTo[privateFileId] = publicFileId }
    }

    fun ops(fileId: String?): List<OpRecord> =
        if (fileId == null) oplog.all() else oplog.ofFile(fileId)

    fun visibleOps(fileId: String): List<OpRecord> = oplog.visible(fileId)

    fun onlinePeers(): List<String> = synchronized(lock) { links.keys.toList() }

    fun memberName(dev: String): String = synchronized(lock) { members[dev]?.name ?: "" }

    // ------------------------------------------------------------------ 写

    /**
     * 共享文件：建 v1 私有种子 → 加种 → 广播 file.add（协议 §11 第 11-12 步）。
     *
     * [superSeeding] 透传给 [BtSession.addSeed]（BEP 16，只在做种侧有意义）。
     * 真值用 [superSeeding] 读回，别信这个入参。
     */
    fun addFile(file: File): FileEntry = addFile(file, false)

    fun addFile(file: File, superSeeding: Boolean): FileEntry {
        require(file.isFile) { "not a file: " + file }
        val seeded = kernel.bt.createPrivateTorrent(file)
        val entry = FileEntry(
            fileId = seeded.fileId,
            name = seeded.name,
            size = seeded.size,
            pieceSize = seeded.pieceSize,
            addedBy = kernel.deviceId,
            addedAt = System.currentTimeMillis(),
            magnet = Proto.magnet(seeded.fileId, seeded.name, seeded.size),
            torrent = Base64.getEncoder().encodeToString(seeded.torrentBytes),
        )
        val sig = kernel.identity.sign(Domains.FILE, entry.signFields())
        // 签名跟着条目一起存：file.list 要把「原样携带 file.add 的 sig」发给别人（协议 §7）。
        val signed = entry.copy(sig = sig)
        kernel.bt.addSeed(seeded, file.parentFile ?: file.absoluteFile.parentFile, superSeeding)
        synchronized(lock) {
            files[entry.fileId] = signed
            // 本机主动共享 = 明确的「要它出现在列表里」：旧隐藏标记就地撤销（隐藏只针对本地移除过的同 id）。
            hidden.remove(entry.fileId)
            localTasks.add(entry.fileId)
            // 共享成功就在做种了：做种集合的唯一来源只有这一处「进」的入口之一（见 SeedingLedger）。
            seeding.enter(entry.fileId, file.absolutePath)
            connectedEndpoints.add("seed:" + entry.fileId)
        }
        kernel.events.log(
            "file_seeding",
            mapOf(
                "group_id" to groupId,
                "file_id" to entry.fileId,
                "name" to entry.name,
                "size" to entry.size.toString(),
                "piece_size" to entry.pieceSize.toString(),
                "torrent_bytes" to seeded.torrentBytes.size.toString(),
                "super_seeding" to superSeeding.toString(),
            ),
        )
        broadcast(
            MsgType.FILE_ADD,
            jobj("entry" to signed.toJson(), "sig" to jstr(sig)),
            null,
        )
        broadcastPeerAnnounce(null)
        kernel.saveState()
        kernel.events.fileAdded(this, signed)
        return signed
    }

    /**
     * 去重留痕：只有 DROP / PROMOTE 两种才是「同 infohash 撞了 scope」，KEEP / ADD 是日常流量，不打日志。
     *
     * 日志名 `file_scope_dedup`，字段 action=drop|promote —— 验收脚本按它判「同一 fileId 两种 scope
     * 同时到达时只留 private」（public-swarm §9 的第四条断言）。
     */
    private fun logScopeMerge(incoming: FileEntry, action: FileEntryMerge.Action, channel: String) {
        if (action != FileEntryMerge.Action.DROP && action != FileEntryMerge.Action.PROMOTE) return
        kernel.events.log(
            "file_scope_dedup",
            mapOf(
                "group_id" to groupId,
                "file_id" to incoming.fileId,
                "action" to action.wire,
                "incoming_scope" to incoming.scope.wire,
                "channel" to channel,
            ),
        )
    }

    /**
     * 公开条目入库并广播（public-swarm §1.1 / §4）。
     *
     * 与 [addFile] 的差别只有三样，全在数据上：
     *  - `scope=public`，内联 .torrent 为空（群内只传磁力，帧上限 65533 B 装不下大种子的 .torrent）；
     *  - 本机不建种、不挂会话：公开条目的数据来自公网 swarm，本机可能是纯下载方；
     *  - `size` / `pieceSize` 允许是 0（元数据未知），拿到之后由 [backfillPublicMetadata] 本地回填。
     *
     * 广播的仍是同一条 0x12 file.add（protocol §9「M3 私有层零变更」），只是多了 scope 与 magnet
     * 两个字段；磁力只带发布者自己写的 xt/dn/xl，不带我们自己加的 tracker（§2.2）。
     */
    internal fun addPublicEntry(
        fileId: String,
        magnet: String,
        name: String,
        size: Long,
        pieceSize: Int,
    ): FileEntry {
        val entry = FileEntry(
            fileId = fileId,
            name = name,
            size = size,
            pieceSize = pieceSize,
            addedBy = kernel.deviceId,
            addedAt = System.currentTimeMillis(),
            magnet = magnet,
            torrent = "",
            scope = FileScope.PUBLIC,
        )
        val sig = kernel.identity.sign(Domains.FILE, entry.signFields())
        val signed = entry.copy(sig = sig)
        val merged = synchronized(lock) {
            // 本机主动入库 = 明确的「要它出现在列表里」：撤销同 id 的本地隐藏（重新导入 / 重新发布）。
            hidden.remove(fileId)
            val result = FileEntryMerge.merge(files[fileId], signed)
            result.entry?.let { files[fileId] = it }
            result
        }
        logScopeMerge(signed, merged.action, "local_public")
        if (merged.action == FileEntryMerge.Action.DROP) {
            // 同 infohash 已经是私有条目（裁决 5：只留 private）：本地不换行，也不再往外广播这条公开条目。
            return synchronized(lock) { files[fileId] } ?: signed
        }
        kernel.events.log(
            "file_public_added",
            mapOf(
                "group_id" to groupId,
                "file_id" to fileId,
                "name" to name,
                "size" to size.toString(),
                "piece_size" to pieceSize.toString(),
                "scope" to FileScope.PUBLIC.wire,
                "action" to merged.action.wire,
            ),
        )
        broadcast(MsgType.FILE_ADD, jobj("entry" to signed.toJson(), "sig" to jstr(sig)), null)
        kernel.saveState()
        kernel.events.fileAdded(this, merged.entry ?: signed)
        return merged.entry ?: signed
    }

    /**
     * 元数据到手后本地回填 size / pieceSize / 名字（public-swarm §1.1）：
     * **不改签名、不回广播** —— 尺寸由 info 字典自身保证，谁都能解析自查，不需要信任发布者。
     */
    internal fun backfillPublicMetadata(fileId: String, name: String, size: Long, pieceSize: Int) {
        val changed = synchronized(lock) {
            val old = files[fileId] ?: return@synchronized false
            if (old.scope != FileScope.PUBLIC) return@synchronized false
            files[fileId] = old.copy(
                name = name.ifBlank { old.name },
                size = if (size > 0) size else old.size,
                pieceSize = if (pieceSize > 0) pieceSize else old.pieceSize,
            )
            true
        }
        if (changed) {
            kernel.events.log(
                "file_public_metadata",
                mapOf(
                    "group_id" to groupId,
                    "file_id" to fileId,
                    "name" to name,
                    "size" to size.toString(),
                    "piece_size" to pieceSize.toString(),
                ),
            )
            kernel.saveStateIfStarted()
        }
    }

    /** 「激活」：加种/加 peer 开始下载（协议 §11 第 13-14 步）。 */
    fun startDownload(fileId: String): Boolean {
        val entry = synchronized(lock) { files[fileId] } ?: return false
        // 公开条目走公开会话（public-swarm §1.2）：它没有内联 .torrent，只能按磁力 / 元数据缓存加种。
        // 激活成功立刻按 pubBtPort 拨一轮；等巡查那一拍也能拨到，但宿主点完「激活」不该先猜两秒。
        if (entry.scope == FileScope.PUBLIC) {
            val ok = kernel.startPublicDownload(this, entry)
            if (ok) connectSeeders(fileId)
            return ok
        }
        if (entry.torrent.isEmpty()) {
            kernel.events.log("download_no_torrent", mapOf("file_id" to fileId))
            return false
        }
        val bytes = Base64.getDecoder().decode(entry.torrent)
        val ok = kernel.bt.addDownload(entry.fileId, bytes, kernel.downloadsDir)
        if (ok) {
            synchronized(lock) { localTasks.add(fileId) }
            connectSeeders(fileId)
            kernel.events.log(
                "download_start",
                mapOf(
                    "group_id" to groupId,
                    "file_id" to fileId,
                    "name" to entry.name,
                    "size" to entry.size.toString(),
                    "save_path" to kernel.downloadsDir.absolutePath,
                    "sess" to "priv",
                ),
            )
        }
        return ok
    }

    /**
     * 把成员挨个接上（swig connect_peer；高层 API 没有这个能力）。
     *
     * **缺陷修复点**：原来的实现先算一个 `hasPeers = bt.peers(fileId) > 0`，只要有 1 条 peer 连接
     * 就整轮不拨 —— 单做种者先被一个下载者连上之后，其余下载者永远不互连，队友之间拿不到互补的片。
     * 现在按「每成员一次拨号」：候选就是成员自报过端点（私有 btPort / 公开 pubBtPort）的每个成员，
     * 已互连的跳过（幂等），还在退避里的跳过，其余按 [ReconnectPolicy.MAX_PARALLEL_DIALS] 截断。
     *
     * **公开条目不再整轮跳过**（public-swarm §4.1 / §9-5）：它拨的是成员的 `pubBtPort`，
     * 走 [M1Kernel.sessionOf] 路由到公开会话；成员的私有 `btPort` 对公开条目一律不拨 ——
     * 两个端口属于两个 SessionManager，拨错会话就是连错 swarm。没有 `pubBtPort` 的成员
     * 这一轮就是「跳过点」：等它下一次 peer.announce 带上公开端口，或干脆只靠 DHT。
     *
     * 退避与预算复用冷启动重连的那一套（[ReconnectPolicy]，1/2/4/8/16/30 s 封顶），不另立参数。
     * connect_peer 是「每个 torrent 一份」的连接，所以状态键必须带 fileId：只按 dev 去重会让
     * 第二个文件永远接不上同一个成员（一文件一 torrent）。
     */
    fun connectSeeders(fileId: String) {
        // 没挂进会话就没有可 connect_peer 的 torrent
        if (!isLocalTask(fileId)) return
        val entry = synchronized(lock) { files[fileId] } ?: return
        val publicEntry = entry.scope == FileScope.PUBLIC
        val targets = memberEndpoints(publicEntry).map { (dev, host, port) ->
            MemberDialPolicy.Target(dev, host, port)
        }
        if (targets.isEmpty()) return

        // 已经互连的成员（数据来自对应会话的 libtorrent peer 列表）+ 顺手把它们的拨号状态清掉
        val live = liveMembers(fileId, publicEntry, targets)
        val now = System.currentTimeMillis()
        val throttled = ArrayList<String>()
        synchronized(lock) {
            for (dev in live.keys) dials.remove(fileId + "@" + dev)
            for (t in targets) {
                val st = dials[fileId + "@" + t.dev] ?: continue
                if (now < st.nextDialAt) throttled.add(t.key)
            }
        }

        val plan = MemberDialPolicy.plan(
            targets,
            live.keys,
            throttled,
            ReconnectPolicy.MAX_PARALLEL_DIALS,
        )
        if (plan.isEmpty()) return
        // 同一个群里的这个 fileId 只会在一个会话里（加种时按 private 位路由），每次现取即可。
        val session = kernel.sessionOf(fileId)
        for (t in plan) {
            val addr = t.host + ":" + t.port
            val ok = session.connectPeer(fileId, t.host, t.port)
            val attempt = synchronized(lock) {
                val st = dials.getOrPut(fileId + "@" + t.dev) { MemberDial() }
                st.note(now, addr)
            }
            if (ok) {
                kernel.events.log(
                    "bt_connect_peer",
                    mapOf(
                        "file_id" to fileId,
                        "peer" to t.dev,
                        "addr" to addr,
                        // 公开条目走公开会话（pubBtPort），私有条目走私有会话（btPort）：
                        // 断言 / 排障时要能一眼看出拨的是哪一套 swarm。
                        "sess" to if (publicEntry) "pub" else "priv",
                        "attempt" to attempt.toString(),
                        "backoff_ms" to ReconnectPolicy.backoffMs(attempt - 1).toString(),
                    ),
                )
            }
        }
    }

    /** 一个文件该拨哪些成员端点：私有条目读 `btEndpoints`，公开条目读 `pubBtEndpoints`。 */
    private fun memberEndpoints(publicEntry: Boolean): List<Triple<String, String, Int>> = synchronized(lock) {
        val source = if (publicEntry) pubBtEndpoints else btEndpoints
        source.entries
            .filter { it.key != kernel.deviceId }
            .map { Triple(it.key, it.value.first, it.value.second) }
            .sortedBy { it.first }
    }

    /**
     * 当前与这个文件的 torrent **真实互连**的成员（dev → 对端快照）。
     *
     * 唯一数据来源是 libtorrent 的 peer 列表（[LocalSession.peerSnapshot]，按条目 scope 路由到
     * 私有 / 公开会话）：不看成员表、不看协议帧、不新造字段。peer_info 只有 IP 没有端口，
     * 所以「连接 → 成员」按 host 匹配；同一个 host 上有多条连接（同机多进程 / 同一 NAT 后）时
     * 按 dev 字典序逐条分配，每个成员最多一条。匹配不上的对端（非成员的直连）不计数。
     */
    private fun liveMembers(
        fileId: String,
        publicEntry: Boolean,
        targets: List<MemberDialPolicy.Target> = memberEndpoints(publicEntry).map { (dev, host, port) ->
            MemberDialPolicy.Target(dev, host, port)
        },
    ): Map<String, BtSession.PeerSnapshot> {
        val peers = kernel.sessionOf(fileId).peerSnapshot(fileId).filter { it.connected }
        if (peers.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, BtSession.PeerSnapshot>()
        val taken = HashSet<Int>()
        // ① 先按 host:port 精确配对：本机拨出去的连接看到的就是对方的监听端口，一定对得上
        for (t in targets) {
            val want = t.host + ":" + t.port
            for (i in peers.indices) {
                if (i in taken) continue
                if (peers[i].ip != want) continue
                out[t.dev] = peers[i]
                taken.add(i)
                break
            }
        }
        // ② 精确对不上的（对端拨进来的连接，端口是临时端口）退化成按 host 配，
        //    同一个 host 上有多条连接时按 dev 顺序逐条分配，每个成员最多一条
        val byHost = HashMap<String, MutableList<Int>>()
        for (i in peers.indices) {
            if (i in taken) continue
            byHost.getOrPut(hostOf(peers[i].ip)) { ArrayList() }
                .add(i)
        }
        for (t in targets) {
            if (out.containsKey(t.dev)) continue
            val list = byHost[t.host] ?: continue
            while (list.isNotEmpty()) {
                val i = list.removeAt(0)
                out[t.dev] = peers[i]
                break
            }
        }
        return out
    }

    /** `127.0.0.1:53513` → `127.0.0.1`；IPv6 字面量表里存的是裸地址，这里只按最后一个冒号切。 */
    private fun hostOf(endpoint: String): String {
        val at = endpoint.lastIndexOf(':')
        return if (at > 0) endpoint.substring(0, at) else endpoint
    }

    /**
     * 队友数（观测口径，产品规格 docs/design/product-spec.md:306）：
     * **当前与该文件 torrent 实际互连、且本机处于「部分」的成员数**，不含做种者。
     *
     * - 本机不在「部分」（没挂进会话 / 已经 100%）→ 0：文件到 100% 即退出组队。
     * - 只数 libtorrent 的 peer 列表里真握上手、且对端不是做种者的成员。
     * - 数据不落盘、不进协议：是读数，不是状态。宿主轮询即可（见 kernel.teammates）。
     */
    fun teammates(fileId: String): Int {
        if (!isLocalTask(fileId)) return 0
        // 「部分」= 数据还没齐；做种中的文件不再算组队（100% 即退出）。
        // 会话按 scope 路由：公开条目在公开会话里，用私有会话的读数会永远是 0。
        if (kernel.sessionOf(fileId).isFinished(fileId)) return 0
        val entry = synchronized(lock) { files[fileId] } ?: return 0
        return liveMembers(fileId, entry.scope == FileScope.PUBLIC).values.count { !it.seed }
    }

    fun progress(fileId: String): Double = kernel.sessionOf(fileId).progress(fileId)

    fun isComplete(fileId: String): Boolean = kernel.sessionOf(fileId).isFinished(fileId)

    fun savedFile(fileId: String): File? = kernel.sessionOf(fileId).savedFile(fileId)

    // ------------------------------------------------------------------ 宿主（Android UI / 桌面 daemon）读口

    /** 本机对一个文件的参与状态：没挂进会话 / 在做种 / 在下载。 */
    enum class LocalRole { NONE, SEEDING, DOWNLOADING }

    fun localRole(fileId: String): LocalRole = synchronized(lock) {
        when {
            !localTasks.contains(fileId) -> LocalRole.NONE
            // 做种判定与做种数读同一个集合：下完转做种的文件这里立刻是 SEEDING，不会停在 DOWNLOADING。
            seeding.isSeeding(fileId) -> LocalRole.SEEDING
            else -> LocalRole.DOWNLOADING
        }
    }

    /** 0–100 的整数进度；没挂进会话时返回 0。 */
    fun percent(fileId: String): Int =
        (kernel.sessionOf(fileId).progress(fileId) * 100.0).toInt().coerceIn(0, 100)

    fun downloadRateBps(fileId: String): Long = kernel.sessionOf(fileId).downloadRate(fileId)

    fun uploadRateBps(fileId: String): Long = kernel.sessionOf(fileId).uploadRate(fileId)

    fun peerCount(fileId: String): Int = kernel.sessionOf(fileId).peers(fileId)

    // ------------------------------------------------------------------ 未读数（缺口 G4）

    /**
     * 一个文件的未读数。
     *
     * 口径：该文件的可见 op（墓碑与被墓碑指向的不出现）里，键严格大于「上次读到的 op」的条数。
     * 有新 op 进来时这个数自然变大，不需要为每条 op 维护一个计数器；重连补齐、乱序到达、
     * 重复广播都不会算错（协议 §6 的 (lamport, author, seq) 是全序）。
     */
    fun unreadCount(fileId: String): Int = synchronized(lock) {
        Unread.count(oplog.visible(fileId), readCursors[fileId])
    }

    /** 整个群的未读总数（各文件之和）。 */
    fun unreadCount(): Int = synchronized(lock) {
        val byFile = oplog.visibleByFile()
        var total = 0
        for (fileId in files.keys) total += Unread.count(byFile[fileId] ?: emptyList(), readCursors[fileId])
        total
    }

    /**
     * 打开 P4：把该文件的游标推到当前最新一条可见 op，返回清掉的未读数。
     * 游标落盘（state.json 的 read 字段），重启后不会把读过的消息再算一遍。
     */
    fun markRead(fileId: String): Int = synchronized(lock) {
        val visible = oplog.visible(fileId)
        val cleared = Unread.count(visible, readCursors[fileId])
        val newest = Unread.newest(visible)
        val old = readCursors[fileId]
        if (old == null || newest > old) readCursors[fileId] = newest
        cleared
    }.also { kernel.saveStateIfStarted() }

    /** 整个群清零（P2 点进来时用），返回清掉的未读总数。 */
    fun markAllRead(): Int = synchronized(lock) {
        val byFile = oplog.visibleByFile()
        var cleared = 0
        for (fileId in files.keys) {
            val visible = byFile[fileId] ?: emptyList()
            cleared += Unread.count(visible, readCursors[fileId])
            val newest = Unread.newest(visible)
            val old = readCursors[fileId]
            if (old == null || newest > old) readCursors[fileId] = newest
        }
        cleared
    }.also { kernel.saveStateIfStarted() }

    /** 载入 state.json 时用。 */
    internal fun restoreReadCursor(fileId: String, cursor: ReadCursor) {
        synchronized(lock) { readCursors[fileId] = cursor }
    }

    /**
     * 「休眠」：把 torrent 从会话里摘掉（做种与下载都停）。
     * 部分下载的文件留在盘上，下次激活重新校验后从已完成的片继续。
     */
    fun stopFile(fileId: String): Boolean {
        val had = synchronized(lock) {
            localTasks.remove(fileId)
            seeding.leave(fileId)
            unhealthySeeds.remove(fileId)
            // 拨号状态也要清，否则刚休眠完再激活会被退避窗口卡住。
            dials.keys.removeIf { it.startsWith(fileId + "@") }
            connectedEndpoints.clear()
            true
        }
        kernel.sessionOf(fileId).removeTorrent(fileId)
        kernel.events.log("file_stopped", mapOf("group_id" to groupId, "file_id" to fileId))
        // 最后一条非休眠公开条目被摘掉 → 公开会话停掉（按需启停的另一半）。
        kernel.maybeStopPublicSession()
        // 做种数变了，重新自报一次（协议 §10 的 peer.announce.seeds）
        broadcastPeerAnnounce(null)
        kernel.saveState()
        return had
    }

    /** 单文件限速（协议 §4：一文件一 torrent + setUploadLimit）。 */
    /** super-seeding 的真值：读 libtorrent 的 `torrent::flags()`，不是本项目自报的开关。 */
    fun superSeeding(fileId: String): Boolean = kernel.sessionOf(fileId).superSeeding(fileId)

    /** 运行中开关 super-seeding（BEP 16 允许中途切）；成没成用 [superSeeding] 读回。 */
    fun setSuperSeeding(fileId: String, on: Boolean): Boolean =
        kernel.sessionOf(fileId).setSuperSeeding(fileId, on)

    fun setUploadLimitKbps(fileId: String, kbps: Int) {
        kernel.sessionOf(fileId).setUploadLimit(fileId, kbps)
    }

    /** 发评论：kind=0 新增 / kind=1 回复（协议 §6）。 */
    fun comment(fileId: String, body: String, parent: String?): OpRecord {
        val kind = if (parent.isNullOrEmpty()) 0 else 1
        val op = oplog.create(fileId, body, parent, kind)
        // 自己写的这条当然是「读过的」：游标跟着推进，否则刚发的评论会立刻变成未读。
        synchronized(lock) { readCursors[fileId] = ReadCursor.max(readCursors[fileId] ?: ReadCursor.ZERO, ReadCursor.of(op)) }
        broadcast(MsgType.LOG_OP, jobj("op" to op.toJson()), null)
        kernel.saveState()
        kernel.events.log(
            "comment_created",
            mapOf("group_id" to groupId, "file_id" to fileId, "op_id" to op.opId, "seq" to op.seq.toString()),
        )
        return op
    }

    // ------------------------------------------------------------------ 协议入口

    /** 当前在线的成员连接（拷贝，遍历时不受并发增删影响）。 */
    internal fun onlineLinks(): List<PeerLink> = synchronized(lock) { links.values.toList() }

    internal fun isLocalTask(fileId: String): Boolean = synchronized(lock) { localTasks.contains(fileId) }

    /** 把一条已经在会话里挂好的任务记进本地任务表（公开条目「激活」时由内核调）。 */
    internal fun markLocalTask(fileId: String) {
        synchronized(lock) { localTasks.add(fileId) }
    }

    /**
     * 还有没有「非休眠的公开条目」—— 公开会话按需启停的唯一判据（public-swarm §7.1）。
     * 只数本机真挂了任务的（休眠的公开条目不该让 DHT 常驻）。
     */
    internal fun hasActivePublicTask(): Boolean = synchronized(lock) {
        localTasks.any { id -> files[id]?.scope == FileScope.PUBLIC }
    }

    internal fun isLocalSeed(fileId: String): Boolean = synchronized(lock) { seeding.isSeeding(fileId) }

    /** [markCompleted] 的结果：事件只按 first 发一次，peer.announce 只按 seeding 变化发。 */
    internal class CompletionResult(
        /** 首次上报「下载完成」（同一份数据重复完成不再重复发事件）。 */
        val first: Boolean,
        /** 这一次真的进了做种集合（0→1 的那一下，做种数变了要重新自报）。 */
        val seeding: Boolean,
    )

    /**
     * 下载完成 → 进入做种集合。**缺陷修复点**：以前这里只往 `completed` 里加一句就完了，
     * 做种集合不动，所以下完转做种的设备自报 seeds 一直是 0（docs/m1-device.md §6-6）。
     *
     * 由巡查线程在 `isComplete()` 为真时调用（[M1Kernel] 的 monitor），和「完成判定」是同一处；
     * 本地路径取自 BT 会话（下载的落在 downloads/，共享的落在原目录），重启续种要用它。
     */
    internal fun markCompleted(fileId: String): CompletionResult {
        val path = kernel.sessionOf(fileId).savedFile(fileId)?.absolutePath ?: ""
        val result = synchronized(lock) {
            val first = completed.add(fileId)
            val entered = if (path.isNotEmpty()) seeding.enter(fileId, path) else false
            CompletionResult(first, entered)
        }
        if (result.first && !result.seeding) {
            // 数据完整却拿不到落盘路径：不当作做种，但要留痕。正常路径不会发生 ——
            // 少了这条日志，同一种缺陷（做种数停在 0）下次会再静默复发一次。
            kernel.events.log("seeding_no_path", mapOf("group_id" to groupId, "file_id" to fileId))
        }
        return result
    }

    // ------------------------------------------------------------------ 重启续种 / 做种集合对账（缺口 G2）

    /**
     * 载入 state.json 时用：记下「上次在做种」的条目，等 BT 会话起来后由 [resumeSeeds] 判定。
     *
     * 先不直接进 [seeding]：文件可能已被移走/删掉，先算进做种数就是自报一个不存在的做种。
     */
    internal fun restoreSeeding(fileId: String, path: String) {
        synchronized(lock) { pendingSeeds[fileId] = path }
    }

    /**
     * 重启续种（缺口 G2）：把 state.json 里记着「上次在做种」的条目逐条挂回 BT 会话。
     *
     * 判定口径与「直接加种 vs 重新建种」的理由见 [SeedRestore]。任一条不满足就降级为休眠
     * （日志 `seed_resume_failed reason=...`），不抛异常、不动磁盘文件。
     * 返回 (恢复数, 降级数)。
     */
    internal fun resumeSeeds(): Pair<Int, Int> {
        val pending = synchronized(lock) {
            val copy = pendingSeeds.toList()
            pendingSeeds.clear()
            copy
        }
        if (pending.isEmpty()) return 0 to 0
        var resumed = 0
        var skipped = 0
        for ((fileId, path) in pending) {
            val entry = file(fileId)
            val localFile = File(path)
            // 公开条目没有内联 .torrent：种子字节在 <data>/public/meta/<infohash>.torrent（元数据到手那天落的）。
            val inline = if (entry != null && entry.torrent.isNotEmpty()) {
                Base64.getDecoder().decode(entry.torrent)
            } else {
                null
            }
            val cached = if (entry != null && entry.scope == FileScope.PUBLIC) {
                kernel.publicCachedTorrent(fileId)
            } else {
                null
            }
            val torrentBytes = inline ?: cached?.readBytes()
            val decision = SeedRestore.decide(
                hasEntry = entry != null,
                hasTorrent = torrentBytes != null,
                fileExists = localFile.isFile,
                sizeMatches = entry != null && localFile.length() == entry.size,
            )
            var failure = decision.reason
            var activated = false
            if (decision == SeedRestore.Decision.RESUME && entry != null) {
                activated = try {
                    // 直接按原始 torrent 字节挂种：infohash 与群里其他人看到的一致（见 SeedRestore）。
                    // 会话按种子自身的 private 位选（public-swarm §1.2）：公开条目在公开会话里续种。
                    val bytes = torrentBytes ?: ByteArray(0)
                    if (kernel.sessionRoute(entry) == SessionRoute.PUBLIC) {
                        // 公开会话按需启停：续种就是「有非休眠公开条目」的那一刻，先把它起起来。
                        val pub = kernel.ensurePublicSession()
                            ?: throw IllegalStateException("public session unavailable")
                        if (!pub.addTorrent(
                                org.libtorrent4j.TorrentInfo(bytes),
                                localFile.parentFile ?: kernel.publicDataDir(),
                                true,
                            )
                        ) {
                            throw IllegalStateException("public session refused torrent")
                        }
                    } else {
                        kernel.bt.addSeed(
                            BtSession.SeededTorrent(entry.fileId, entry.name, entry.size, entry.pieceSize, bytes),
                            localFile.parentFile ?: kernel.downloadsDir,
                        )
                    }
                    synchronized(lock) {
                        localTasks.add(fileId)
                        seeding.enter(fileId, localFile.absolutePath)
                    }
                    true
                } catch (e: Throwable) {
                    failure = "activate_failed"
                    kernel.events.log(
                        "seed_resume_error",
                        mapOf("group_id" to groupId, "file_id" to fileId, "error" to e.toString()),
                    )
                    false
                }
            }
            if (activated) {
                resumed++
                kernel.events.log(
                    "seed_resumed",
                    mapOf(
                        "group_id" to groupId,
                        "file_id" to fileId,
                        "name" to (entry?.name ?: ""),
                        "path" to localFile.absolutePath,
                    ),
                )
            } else {
                // 降级为休眠：不挂会话、不进做种集合，界面按「休眠」显示，用户想继续可以再点激活。
                skipped++
                kernel.events.log(
                    "seed_resume_failed",
                    mapOf("group_id" to groupId, "file_id" to fileId, "reason" to failure, "path" to path),
                )
            }
        }
        return resumed to skipped
    }

    /**
     * 巡查对账：让做种集合与磁盘 / libtorrent 保持一致 —— 文件被移走或删掉、或校验后数据不全，
     * 就地降级为休眠（移出集合 + 从会话摘掉 torrent + 重新自报 seeds）。
     *
     * 只在巡查线程里跑（2 s 一拍）。异常一律不外抛：一次对账失败不能让巡查线程挂掉。
     */
    internal fun reconcileSeeding() {
        val candidates = seeding.entries()
        for ((fileId, path) in candidates) {
            var reason = ""
            when {
                // 条目不在清单里了（退群 / 条目被删）：做种数不该再算它
                file(fileId) == null -> reason = "entry_gone"
                // 文件被移走或删掉：本机没有数据可做种
                !File(path).isFile -> reason = "file_missing"
                else -> {
                    // 数据不完整（片上校验没过）：不能当作做种，否则对端会连上一个拿不到数据的种子。
                    // 刚挂种时状态是 checking / pending，这时候不下判断，等它对账成 finished 或 incomplete。
                    // 连续两拍异常才降级，避开挂种瞬间的状态抖动。
                    val state = kernel.sessionOf(fileId).seedState(fileId)
                    if (state == "incomplete" || state == "missing") {
                        val bad = synchronized(lock) {
                            val n = (unhealthySeeds[fileId] ?: 0) + 1
                            unhealthySeeds[fileId] = n
                            n
                        }
                        if (bad >= UNHEALTHY_STRIKES) reason = if (state == "missing") "torrent_missing" else "incomplete"
                    } else {
                        synchronized(lock) { unhealthySeeds.remove(fileId) }
                    }
                }
            }
            if (reason.isNotEmpty()) demote(fileId, reason)
        }
        // 已经不在做种集合里的，计数清零
        val live = seeding.ids().toHashSet()
        synchronized(lock) { unhealthySeeds.keys.retainAll(live) }
    }

    /** 把一条做种降级为休眠（停 torrent、移出做种集合、重新自报做种数）。 */
    private fun demote(fileId: String, reason: String) {
        val changed = synchronized(lock) {
            localTasks.remove(fileId)
            unhealthySeeds.remove(fileId)
            dials.keys.removeIf { it.startsWith(fileId + "@") }
            seeding.leave(fileId)
        }
        kernel.sessionOf(fileId).removeTorrent(fileId)
        kernel.events.log("seed_stopped", mapOf("group_id" to groupId, "file_id" to fileId, "reason" to reason))
        kernel.maybeStopPublicSession()
        if (changed) broadcastPeerAnnounce(null)
        kernel.saveState()
    }

    /**
     * 从 join.ok 快照或 file.list 收下的条目（协议 §7）。
     *
     * 每条 entry 原样带着该文件 file.add 的 sig，按 addedBy 的公钥验；验不过就丢这一条：
     * 不入库、不计入 fdigest，于是对端下一轮 sync.req 还能把它要回来，坏条目污染不了清单。
     * 公钥来自成员记录（hello / join.ok.members / member.name 三个来源，都是 deviceId 钉死的）。
     */
    internal fun onFileListEntry(entry: FileEntry, from: String?): Boolean {
        // 验签口径收在 EntryCheck 里（no_sig / no_pub / bad_sig），SelfTest 在字节层钉的是同一段代码。
        val reason = EntryCheck.reason(kernel.pubOf(entry.addedBy), entry)
        if (reason.isNotEmpty()) {
            kernel.events.log(
                "file_list_rejected",
                mapOf(
                    "group_id" to groupId,
                    "file_id" to entry.fileId,
                    "added_by" to entry.addedBy,
                    "reason" to reason,
                ),
            )
            return false
        }
        // 本地移除过的条目（public-swarm §5.2）：对端再来同一条也不入库，否则「移除」下一轮同步就复活。
        if (synchronized(lock) { hidden.contains(entry.fileId) }) {
            return false
        }
        // 去重口径只有一个实现（FileEntryMerge）：同一 infohash 同时存在 private 与 public 时只留 private。
        val merged = synchronized(lock) {
            val result = FileEntryMerge.merge(files[entry.fileId], entry)
            result.entry?.let { files[entry.fileId] = it }
            result
        }
        kernel.saveStateIfStarted()
        logScopeMerge(entry, merged.action, "file_list")
        val isNew = merged.action == FileEntryMerge.Action.ADD
        if (isNew) {
            kernel.events.log(
                "file_list_entry",
                mapOf("group_id" to groupId, "file_id" to entry.fileId, "name" to entry.name, "size" to entry.size.toString()),
            )
            kernel.events.fileAdded(this, entry)
        }
        return isNew
    }

    internal fun restoreFile(entry: FileEntry) {
        synchronized(lock) {
            // 隐藏集合先于条目恢复（见 M1Kernel.loadState）：移除过的条目重启后也不该复活。
            if (!hidden.contains(entry.fileId)) files[entry.fileId] = entry
        }
    }

    internal fun restoreOp(op: OpRecord) {
        oplog.loadAll(listOf(op))
    }

    /**
     * 一条链路绑定成功（hello 通过，对端是本群成员）：进连接表，并让宿主刷新 P6。
     *
     * 冷启动自动重连会和对端自己的重连撞在同一瞬间（两端各自拨一条），所以同一个 dev
     * 已经有活链路时不能简单覆盖：覆盖会把两端各留一条、各掐一条，结果两条都不剩。
     * 这里按 [preferNewLink] 裁决，两端用同一个规则算出同一条胜者，留下那条 TCP 连接。
     *
     * **缺陷修复点（两端同时拨号永远连不上）**：裁决前先把已经关掉的旧链路当不存在。
     * 以前一条僵尸链路（本端还没读到对端 EOF，但 socket 其实已经废了）会一直占着 [links]，
     * 于是每一条新拨进来的健康链路都被判成 duplicate_link 关掉，两端互相「你关我的我关你的」，
     * 30 s 退避一轮永远不 attach（现场见 artifacts/m1-device/reconnect-race-evidence.txt）。
     */
    internal fun attach(link: PeerLink) {
        val dev = link.peerDeviceId
        var keep: PeerLink? = null
        var drop: PeerLink? = null
        var replacedDead = false
        synchronized(lock) {
            val old = links[dev]
            when {
                old == null || old === link -> {
                    links[dev] = link
                    keep = link
                }
                // 僵尸不参与裁决：它已经关了（或对端已经不在），留它就是留一条死链路。
                old.isClosed -> {
                    links[dev] = link
                    keep = link
                    replacedDead = true
                }
                preferNewLink(old, link) -> {
                    links[dev] = link
                    keep = link
                    drop = old
                }
                else -> drop = link
            }
        }
        if (replacedDead) {
            kernel.events.log("peer_link_dead_replaced", mapOf("dev" to dev, "gid" to groupId, "host" to link.remoteHost))
        }
        // 关旧链路要放在群锁外面：close 会回灌 detach / 事件回调，持锁调容易和宿主互相等。
        if (drop != null) drop.close("duplicate_link")
        if (keep != null) kernel.events.peersChanged(this, dev, true)
    }

    /** 该成员当前有没有活链路（未关闭）。撞车兜底与「已经连着」判定都读它。 */
    internal fun liveLinkFor(dev: String): PeerLink? = synchronized(lock) {
        links[dev]?.takeIf { !it.isClosed }
    }

    /**
     * 按地址找一个活链路（用于拨号撞车兜底时还不知道对端 deviceId 的场合）。
     * 同一个 host 上有多条活链路（同机多进程 / 同一 NAT 后）时返回 null —— 宁可不认，也不认错人。
     */
    internal fun liveLinkForHost(host: String): PeerLink? = synchronized(lock) {
        val hits = links.values.filter { !it.isClosed && it.remoteHost == host }
        if (hits.size == 1) hits[0] else null
    }

    /**
     * 撞车兜底：把「同一成员已经有一条活链路」认成一次成功绑定。
     *
     * 拨号线程在自己那条链路被 duplicate_link 关掉之后走这里：不退回 30 s 重拨，
     * 而是在既有链路上补一次 peer.announce 与 sync.req（两者都幂等），把这条链路当绑定成功。
     * 返回 true 表示确实有一条可用的活链路。
     */
    internal fun settleOnExistingLink(dev: String): Boolean {
        val link = liveLinkFor(dev) ?: return false
        if (!link.isBound || link.group?.groupId != groupId) return false
        link.announceAndSync(this)
        return true
    }

    /**
     * 同一 dev 两条链路并存时留哪条。
     *
     * 同向（都是本机拨出 / 都是对端拨来）：留新的，旧的多半是没收到对端 EOF 的僵尸。
     * 异向（同时拨号）：按 deviceId 定序 —— deviceId 小的一方留自己拨出的那条。两边算出的
     * 胜者是同一条 TCP 连接，否则「你关我的、我关你的」会把两条都关掉。
     */
    private fun preferNewLink(old: PeerLink, new: PeerLink): Boolean {
        if (old.inbound == new.inbound) return true
        val weAreLower = kernel.deviceId < new.peerDeviceId
        return if (new.inbound) !weAreLower else weAreLower
    }

    internal fun detach(link: PeerLink) {
        val removed = synchronized(lock) {
            if (links[link.peerDeviceId] === link) {
                links.remove(link.peerDeviceId)
                true
            } else {
                false
            }
        }
        if (removed) kernel.events.peersChanged(this, link.peerDeviceId, false)
    }

    // ------------------------------------------------------------------ 冷启动重连（缺口一）

    /**
     * 记下「本机拨出去并绑定成功」的对端端点，落盘给下次冷启动用。
     *
     * 只在端点真的变了的时候写一次盘：每次重连成功都重写 state.json 没必要。
     */
    internal fun rememberPeerAddr(dev: String, host: String, port: Int) {
        if (dev.isEmpty() || host.isEmpty() || port <= 0) return
        val addr = host + ":" + port
        val changed = synchronized(lock) { peerAddrs.put(dev, addr) != addr }
        if (changed) {
            // 建链端点也是「消息层确认过的成员地址」：公网 peer 的话要进 ip_filter 白名单。
            kernel.refreshBtAllowlist()
            kernel.saveStateIfStarted()
        }
    }

    /** 记下手动 IP:port（P5 输入过的）。已经在集合里就不写盘。 */
    internal fun rememberManualAddrs(raw: List<String>) {
        var changed = false
        synchronized(lock) {
            for (item in raw) {
                val c = ManualCandidate.parse(item) ?: continue
                if (manualAddrs.add(c.key)) changed = true
            }
        }
        if (changed) {
            kernel.refreshBtAllowlist()
            kernel.saveStateIfStarted()
        }
    }

    /** 载入 state.json 时用。 */
    internal fun restorePeerAddr(dev: String, addr: String) {
        if (dev.isEmpty() || addr.isEmpty()) return
        synchronized(lock) { peerAddrs[dev] = addr }
    }

    /** 载入 state.json 时用。 */
    internal fun restoreManualAddr(addr: String) {
        if (addr.isEmpty()) return
        synchronized(lock) { manualAddrs.add(addr) }
    }

    /**
     * 冷启动重连的候选，按优先级（协议 §3.4）：上次连过的端点 → 手动地址 → mDNS 候选 → tracker。
     *
     * 合并去重整个交给 [CandidateMerge]：同一 host:port 只留优先级最高的那个来源，
     * 每群上限 128。本机自己的端点不发出去 —— 本机不可能连自己。
     */
    internal fun reconnectCandidates(lan: List<Candidate>): List<Candidate> {
        val saved = ArrayList<Candidate>()
        val manual = ArrayList<Candidate>()
        val tracked = ArrayList<Candidate>()
        synchronized(lock) {
            for ((dev, addr) in peerAddrs) {
                if (dev == kernel.deviceId) continue
                val c = ManualCandidate.parse(addr) ?: continue
                saved.add(c.copy(source = "peer"))
            }
            for (addr in manualAddrs) {
                val c = ManualCandidate.parse(addr) ?: continue
                manual.add(c.copy(source = "manual"))
            }
            tracked.addAll(trackerCandidates)
        }
        return CandidateMerge.merge(listOf(saved, manual, lan, tracked))
    }

    /**
     * tracker 的 announce 结果 —— 整体替换，不做增量：服务端每次给的就是「现在这个群里还在的人」，
     * 上一轮里已经消失的端点留着只会让重连去拨空地址。
     */
    internal fun setTrackerCandidates(list: List<Candidate>) {
        synchronized(lock) {
            trackerCandidates.clear()
            trackerCandidates.addAll(list)
        }
        // tracker 的 peers 也是成员地址来源之一（kGrp 派生 passkey 换来的名单）：
        // 公网源地址要进 ip_filter 白名单，下一轮名单里消失时随整体替换移除。
        kernel.refreshBtAllowlist()
    }

    /**
     * 私有会话 ip_filter 的动态白名单来源（AGENTS §9）。
     *
     * 只收「消息层已经确认过」的成员端点，四类：
     *   ① peer.announce 的 btHost（签名验过、有已登记公钥的成员；btHost 缺省时用 TLS 对端地址）；
     *   ② peer.announce 的 pubBtPort 同一 host（同一台设备的公开会话在同一个地址上）；
     *   ③ tracker 候选（passkey 由 kGrp 派生，只有本群成员能登记）；
     *   ④ 手动 IP:port 与上次建链端点（用户明确指定 / 本机真的拨通过）。
     *
     * 主机名不进来（ip_filter 按地址匹配，协议 §3.3 又不做 DNS 预解析）；私网字面量也不进来
     * （基础规则已放行）。移除策略见 docs/core.md §3.7：成员离线**不**移除，端点被新 announce
     * 替换、tracker 名单整体替换、手动地址被清、退群时才移除。
     */
    internal fun btAllowlistHosts(): Set<String> {
        val raw = ArrayList<String?>()
        synchronized(lock) {
            for ((dev, endpoint) in btEndpoints) {
                if (dev != kernel.deviceId) raw.add(endpoint.first)
            }
            for ((dev, endpoint) in pubBtEndpoints) {
                if (dev != kernel.deviceId) raw.add(endpoint.first)
            }
            for (candidate in trackerCandidates) raw.add(candidate.host)
            for (addr in manualAddrs) raw.add(ManualCandidate.parse(addr)?.host)
            for ((dev, addr) in peerAddrs) {
                if (dev != kernel.deviceId) raw.add(ManualCandidate.parse(addr)?.host)
            }
        }
        return IpAllowlist.collect(raw)
    }

    /** 当前候选表快照（mDNS 的实时结果不在这里，由重连线程自己合）。P7 诊断与验收读它。 */
    fun candidates(): List<Candidate> = reconnectCandidates(emptyList())

    internal fun linkFor(dev: String): PeerLink? = synchronized(lock) { links[dev] }

    internal fun addMember(dev: String, name: String, nameSeq: Long, pub: String) {
        synchronized(lock) {
            val old = members[dev]
            if (old == null || nameSeq > old.nameSeq) {
                members[dev] = MemberRecord(dev, name.ifEmpty { old?.name ?: "" }, nameSeq, pub.ifEmpty { old?.pub ?: "" })
            }
        }
    }

    /** 成员名字记录（协议 §10 的 0x23）：记录里带 pub，先算 deviceId(pub) == dev 再登记。 */
    internal fun onMemberName(msg: MemberNameMsg, from: String?) {
        val pub = if (msg.pub.isEmpty()) {
            // 老实现的记录没有 pub：退回已登记的公钥，仍要求签名对得上。
            kernel.pubOf(msg.dev)
        } else {
            val raw = try { hexToBytes(msg.pub) } catch (e: Exception) { null }
            if (raw == null || raw.size != 64 || DeviceIdentity.deviceIdOf(raw) != msg.dev) {
                kernel.events.log("member_name_bad_pub", mapOf("dev" to msg.dev))
                return
            }
            raw
        }
        if (pub == null) {
            kernel.events.log("member_name_no_pub", mapOf("dev" to msg.dev))
            return
        }
        if (!DeviceIdentity.verify(pub, msg.sig, Domains.NAME, msg.signFields())) {
            kernel.events.log("member_name_bad_sig", mapOf("dev" to msg.dev))
            return
        }
        kernel.registerPub(msg.dev, pub)
        addMember(msg.dev, msg.name, msg.nameSeq, pub.toHex())
        kernel.saveState()
        kernel.events.memberJoined(this, msg.dev, msg.name)
        // 转发时也带上 pub：这才让「没和作者本人握过手」的成员也能验证他的评论（协议 §10）。
        broadcast(MsgType.MEMBER_NAME, jobj(
            "dev" to jstr(msg.dev),
            "name" to jstr(msg.name),
            "nameSeq" to jnum(msg.nameSeq),
            "pub" to jstr(pub.toHex()),
            "ts" to jnum(msg.ts),
            "sig" to jstr(msg.sig),
        ), from)
    }

    internal fun onPeerAnnounce(msg: PeerAnnounceMsg, host: String, from: String?) {
        kernel.observePeerClock(msg.dev, msg.ts, System.currentTimeMillis())
        val pub = kernel.pubOf(msg.dev)
        if (pub == null) return
        if (!DeviceIdentity.verify(pub, msg.sig, Domains.PEER, msg.signFields())) {
            kernel.events.log("peer_announce_bad_sig", mapOf("dev" to msg.dev))
            return
        }
        val effectiveHost = msg.btHost.ifEmpty { host }
        if (effectiveHost.isEmpty() || msg.btPort <= 0) return
        synchronized(lock) {
            btEndpoints[msg.dev] = effectiveHost to msg.btPort
            // 协议 §10：pubBtPort 是公开会话的 BT 端口。报 0 / 缺省 = 本机没有公开会话：
            // 把旧值撤掉，否则公开条目会一直拨一个已经关掉的端口。
            if (msg.pubBtPort in 1..65535) {
                pubBtEndpoints[msg.dev] = effectiveHost to msg.pubBtPort
            } else {
                pubBtEndpoints.remove(msg.dev)
            }
            // 协议 §10：plat / seeds 是这条消息自报的两个运行期属性，只用于成员表展示。
            memberLive[msg.dev] = MemberLive(msg.platform, msg.seeds)
        }
        // 端点是新的成员知识：私有会话的 ip_filter 动态白名单跟着重建（docs/core.md §3.7）。
        kernel.refreshBtAllowlist()
        kernel.events.log(
            "peer_announce",
            mapOf(
                "dev" to msg.dev,
                "addr" to (effectiveHost + ":" + msg.btPort),
                "pub_bt_port" to msg.pubBtPort.toString(),
                "plat" to msg.platform,
                "seeds" to msg.seeds.toString(),
            ),
        )
        broadcast(MsgType.PEER_ANNOUNCE, jobj(
            "gid" to jstr(msg.gid),
            "dev" to jstr(msg.dev),
            "btPort" to jnum(msg.btPort.toLong()),
            "btHost" to jstr(msg.btHost),
            "pubBtPort" to jnum(msg.pubBtPort.toLong()),
            "plat" to jstr(msg.platform),
            "seeds" to jnum(msg.seeds.toLong()),
            "ts" to jnum(msg.ts),
            "sig" to jstr(msg.sig),
        ), from)
    }

    internal fun onFileEntry(entry: FileEntry, sig: String, from: String?): Boolean {
        val pub = kernel.pubOf(entry.addedBy)
        if (pub == null) {
            kernel.events.log("file_add_no_pub", mapOf("file_id" to entry.fileId, "added_by" to entry.addedBy))
            return false
        }
        if (!DeviceIdentity.verify(pub, sig, Domains.FILE, entry.signFields())) {
            kernel.events.log("file_add_bad_sig", mapOf("file_id" to entry.fileId))
            return false
        }
        // 本地移除过的条目（public-swarm §5.2）：广播再来同一条也不入库，否则「移除」会被下一帧复活。
        if (synchronized(lock) { hidden.contains(entry.fileId) }) {
            kernel.events.log(
                "file_add_hidden",
                mapOf("group_id" to groupId, "file_id" to entry.fileId),
            )
            return false
        }
        // 签名跟着条目一起存：之后转发 file.list 要原样带上它（协议 §7）。
        val stored = entry.copy(sig = sig)
        // 去重口径与 file.list 完全同一处（public-swarm §1.1：同一 infohash 只留 private 那一行）。
        val merged = synchronized(lock) {
            val result = FileEntryMerge.merge(files[entry.fileId], stored)
            result.entry?.let { files[entry.fileId] = it }
            result
        }
        kernel.saveState()
        logScopeMerge(stored, merged.action, "file_add")
        val isNew = merged.action == FileEntryMerge.Action.ADD
        if (isNew) {
            kernel.events.log(
                "file_add",
                mapOf(
                    "group_id" to groupId,
                    "file_id" to entry.fileId,
                    "name" to entry.name,
                    "size" to entry.size.toString(),
                    "added_by" to entry.addedBy,
                ),
            )
            broadcast(MsgType.FILE_ADD, jobj("entry" to stored.toJson(), "sig" to jstr(sig)), from)
            kernel.events.fileAdded(this, stored)
        }
        return isNew
    }

    internal fun onOp(op: OpRecord, from: String?): OpLog.Ingest {
        val result = oplog.ingest(op)
        when (result) {
            OpLog.Ingest.ADDED -> {
                val recvAt = System.currentTimeMillis()
                kernel.saveState()
                kernel.events.log(
                    "op_ingested",
                    mapOf(
                        "group_id" to groupId,
                        "file_id" to op.fileId,
                        "op_id" to op.opId,
                        "author" to op.author,
                        "seq" to op.seq.toString(),
                        "lamport" to op.lamport.toString(),
                        // sent_at 是对端墙钟的原值，只作对照；时延按本机时钟算（见 latencyMillis），
                        // 两台设备时钟不同步时直接相减会出现负值。
                        "sent_at" to op.ts.toString(),
                        "recv_at" to recvAt.toString(),
                        "latency_ms" to kernel.latencyMillis(op.author, op.ts, recvAt).toString(),
                        // 缺口 G4：这条 op 进来后该文件的未读数（游标没动，所以它自己也算一条未读）
                        "unread" to unreadCount(op.fileId).toString(),
                    ),
                )
                broadcast(MsgType.LOG_OP, jobj("op" to op.toJson()), from)
                kernel.events.opReceived(this, op)
            }
            OpLog.Ingest.BAD_SIG -> kernel.events.log("op_bad_sig", mapOf("op_id" to op.opId, "author" to op.author))
            OpLog.Ingest.UNKNOWN_AUTHOR -> kernel.events.log("op_unknown_author", mapOf("author" to op.author))
            else -> {}
        }
        return result
    }

    /** fdigest = B32(SHA-256(按 fileId 升序拼接 fileId 串)[0..8])（协议 §7）。 */
    internal fun fdigest(): String = synchronized(lock) {
        val ids = files.keys.sorted().joinToString("")
        B32.encode(sha256(ids.toByteArray(Charsets.UTF_8)).copyOf(5))
    }

    internal fun broadcastPeerAnnounce(except: String?) {
        val body = peerAnnounceBody() ?: return
        broadcast(MsgType.PEER_ANNOUNCE, body, except)
    }

    internal fun peerAnnounceBody(): J.Obj? {
        val ts = System.currentTimeMillis()
        val port = kernel.localBtPortLong()
        val pubPort = kernel.localPublicBtPort()
        val seeds = seedingFileCount()
        val unsigned = PeerAnnounceMsg(groupId, kernel.deviceId, port.toInt(), "", ts, "")
        val sig = kernel.identity.sign(Domains.PEER, unsigned.signFields())
        // plat / seeds / pubBtPort 不进 peer1 签名域（口径同 btHost）：只补字段，不改端点交换的语义。
        // pubBtPort = 0 表示本机当前没有公开会话（公开会话按需启停），对端据此跳过公开拨号。
        return jobj(
            "gid" to jstr(groupId),
            "dev" to jstr(kernel.deviceId),
            "btPort" to jnum(port),
            "pubBtPort" to jnum(pubPort.toLong()),
            "plat" to jstr(kernel.platform),
            "seeds" to jnum(seeds.toLong()),
            "ts" to jnum(ts),
            "sig" to jstr(sig),
        )
    }

    internal fun broadcast(type: Int, body: J.Obj, exceptDev: String?) {
        val targets = synchronized(lock) {
            links.entries.filter { it.key != exceptDev }.map { it.value }
        }
        for (link in targets) {
            link.sendQuietly(type, body)
        }
    }

    companion object {
        /**
         * 连续几拍「数据不完整」才把做种降级为休眠（巡查 2 s 一拍，即 ~4 s 的确认窗口）。
         */
        const val UNHEALTHY_STRIKES = 2
    }

    // ------------------------------------------------------------------ 持久化

    internal fun toStoreJson(): J.Obj = synchronized(lock) {
        jobj(
            "gid" to jstr(meta.groupId),
            "gHandle" to jstr(meta.gHandle),
            "name" to jstr(meta.name),
            "code" to jstr(meta.invite.code),
            // keySeed 只以包裹后的 token 落盘；scheme 一起写，读回时按它挑实现。
            "keySeed" to jstr(kernel.secretBox.seal(meta.keySeed)),
            "keySeedProt" to jstr(kernel.secretBox.scheme),
            "epoch" to jnum(meta.epoch.toLong()),
            "joinOpen" to jbool(meta.joinOpen),
            "createdAt" to jnum(meta.createdAt),
            "members" to jarr(members.values.map { m ->
                jobj(
                    "dev" to jstr(m.dev),
                    "name" to jstr(m.name),
                    "nameSeq" to jnum(m.nameSeq),
                    "pub" to jstr(m.pub),
                )
            }),
            "files" to jarr(files.values.map { it.toJson() }),
            // 本地移除（public-swarm §5.2）：隐藏的 fileId 与「私有 → 公开」发布映射都必须跟着库走。
            // 少了 hidden，重启后 file.list 会把移除过的公开条目原地复活；少了 published，
            // 重启后重复点「公开到群外」会再建一次种（幂等失效）。
            "hidden" to jarr(hidden.map { jstr(it) }),
            "published" to jarr(publishedTo.map { (fileId, publicFileId) ->
                jobj("fileId" to jstr(fileId), "publicFileId" to jstr(publicFileId))
            }),
            "ops" to jarr(oplog.all().map { it.toJson() }),
            "lamport" to jnum(oplog.lamport),
            // 做种集合（缺口 G2 重启续种）：fileId + 本地路径。条目本身在 files 里，这里只记
            // 「谁在做种、数据在哪个路径」；重启时按它续种，没有这个字段的旧库读回来就是「不用续种」。
            "seeding" to jarr(seeding.entries().map { (fileId, path) ->
                jobj(
                    "fileId" to jstr(fileId),
                    "path" to jstr(path),
                )
            }),
            // 冷启动重连（缺口一）：上次成功建链的对端消息层端点 + 加入时用过的手动 IP:port。
            // 没有这两个字段的旧库读回来就是「没有候选」，重连器改走 mDNS。
            "peerAddrs" to jarr(peerAddrs.map { (dev, addr) ->
                jobj("dev" to jstr(dev), "addr" to jstr(addr))
            }),
            "manual" to jarr(manualAddrs.map { jstr(it) }),
            // 未读游标（缺口 G4）：每个文件「上次读到哪条 op」，重启后未读数不会归零重算。
            "read" to jarr(readCursors.map { (fileId, cursor) ->
                jobj(
                    "fileId" to jstr(fileId),
                    "lamport" to jnum(cursor.lamport),
                    "author" to jstr(cursor.author),
                    "seq" to jnum(cursor.seq),
                )
            }),
        )
    }

    // ------------------------------------------------------------------ 退群（缺口 G1）

    /**
     * 从运行时里抹掉这个群：停掉它的全部 BT 任务、关闭它的全部消息链路、清空内存结构。
     * 密钥由 [M1Kernel.removeGroup] 调 [wipeKeys] 抹；本地库的删除也在那边（saveState 后就不再有这个群）。
     *
     * 返回 (停掉的 torrent 数, 关闭的连接数)。
     */
    internal fun purgeForRemoval(reason: String): Pair<Int, Int> {
        val taskIds = synchronized(lock) {
            val ids = localTasks.toList()
            localTasks.clear()
            seeding.clear()
            pendingSeeds.clear()
            unhealthySeeds.clear()
            connectedEndpoints.clear()
            dials.clear()
            completed.clear()
            btEndpoints.clear()
            pubBtEndpoints.clear()
            memberLive.clear()
            // 退群之后这些端点既不该再被拨，也不该留在 state.json 里
            peerAddrs.clear()
            manualAddrs.clear()
            trackerCandidates.clear()
            ids
        }
        // 成员集合消失 = 撤销动态白名单（docs/core.md §3.7 的移除策略）；私网基础规则不受影响。
        kernel.refreshBtAllowlist()
        var stopped = 0
        for (fileId in taskIds) {
            if (kernel.bt.removeTorrent(fileId)) stopped++
        }
        val open = onlineLinks()
        for (link in open) {
            // 先按协议 §10 的 0x42 道别再断，对端日志里能看到主动退群，而不是一次无故 EOF。
            link.sendBye(reason)
            link.close(reason)
        }
        synchronized(lock) { links.clear() }
        return stopped to open.size
    }

    /**
     * 删群密钥（协议 §2.2：keySeed 只应在内存里活着）。
     * keySeed 与由它派生的 kGrp 都就地清零；群对象随后被丢弃，不会再有任何持有者能拿到它。
     */
    internal fun wipeKeys(): Boolean {
        var wiped = false
        try {
            meta.kGrp.fill(0)
            wiped = true
        } catch (_: Throwable) {
            // kGrp 派生失败说明它本来就没被算出来，没什么可抹的。
        }
        meta.keySeed.fill(0)
        return wiped
    }
}

/** 成员自报的运行期属性（协议 §10 的 peer.announce.plat / seeds），只活在内存里。 */
internal class MemberLive(val platform: String, val seeds: Int)

/**
 * 单个成员的 BT 拨号状态（每个 `fileId@dev` 一条）。
 *
 * 退避直接取 [ReconnectPolicy] 的表（1/2/4/8/16/30 s，30 s 封顶），与冷启动重连同一套策略：
 * 拨号失败不会刷屏，成员重新出现（端点变了）就当新成员从头开始。
 */
private class MemberDial {
    var addr: String = ""
    var attempts: Int = 0
    var nextDialAt: Long = 0L

    /** 记一次拨号，返回这是第几次（日志用）。 */
    fun note(now: Long, addr: String): Int {
        if (this.addr != addr) {
            this.addr = addr
            attempts = 0
        }
        attempts += 1
        nextDialAt = now + ReconnectPolicy.backoffMs(attempts - 1)
        return attempts
    }
}
