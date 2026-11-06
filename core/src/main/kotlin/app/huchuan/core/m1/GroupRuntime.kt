package app.huchuan.core.m1

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

    /** dev → (host, btPort)，来自 peer.announce 与 TLS 对端地址。 */
    internal val btEndpoints = HashMap<String, Pair<String, Int>>()

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
     * 重启续种的待办：state.json 里记着「上次在做种」的条目（fileId → 本地路径），
     * 等 BT 会话起来后由 [resumeSeeds] 逐条判定，判定通过才进 [seeding]。
     */
    private val pendingSeeds = LinkedHashMap<String, String>()

    /** 已连接的 BT 端点（做种侧标记用）。 */
    internal val connectedEndpoints = HashSet<String>()

    /** fileId@dev:port → 上次 connect_peer 的时间戳，避免刷屏式重连。 */
    private val lastConnect = HashMap<String, Long>()

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

    fun ops(fileId: String?): List<OpRecord> =
        if (fileId == null) oplog.all() else oplog.ofFile(fileId)

    fun visibleOps(fileId: String): List<OpRecord> = oplog.visible(fileId)

    fun onlinePeers(): List<String> = synchronized(lock) { links.keys.toList() }

    fun memberName(dev: String): String = synchronized(lock) { members[dev]?.name ?: "" }

    // ------------------------------------------------------------------ 写

    /** 共享文件：建 v1 私有种子 → 加种 → 广播 file.add（协议 §11 第 11-12 步）。 */
    fun addFile(file: File): FileEntry {
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
        kernel.bt.addSeed(seeded, file.parentFile ?: file.absoluteFile.parentFile)
        synchronized(lock) {
            files[entry.fileId] = signed
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

    /** 「激活」：加种/加 peer 开始下载（协议 §11 第 13-14 步）。 */
    fun startDownload(fileId: String): Boolean {
        val entry = synchronized(lock) { files[fileId] } ?: return false
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
                ),
            )
        }
        return ok
    }

    /**
     * 把已知的做种端点接上（swig connect_peer；高层 API 没有这个能力）。
     *
     * connect_peer 是「每个 torrent 一份」的连接，所以去重键必须带 fileId：
     * 只按 dev:port 去重会让第二个文件永远接不上做种者（一文件一 torrent）。
     * 另外 libtorrent 断线不会自己重连，没有活连接时按 CONNECT_RETRY_MS 重试。
     */
    fun connectSeeders(fileId: String) {
        val targets = synchronized(lock) {
            btEndpoints.entries.map { Triple(it.key, it.value.first, it.value.second) }
        }
        val hasPeers = kernel.bt.peers(fileId) > 0
        for ((dev, host, port) in targets) {
            if (dev == kernel.deviceId) continue
            val key = fileId + "@" + dev + ":" + port
            val now = System.currentTimeMillis()
            val attempt = synchronized(lock) {
                if (hasPeers) {
                    false
                } else {
                    val last = lastConnect[key] ?: 0L
                    if (now - last >= CONNECT_RETRY_MS) {
                        lastConnect[key] = now
                        true
                    } else {
                        false
                    }
                }
            }
            if (!attempt) continue
            if (kernel.bt.connectPeer(fileId, host, port)) {
                kernel.events.log(
                    "bt_connect_peer",
                    mapOf("file_id" to fileId, "peer" to dev, "addr" to (host + ":" + port)),
                )
            }
        }
    }

    fun progress(fileId: String): Double = kernel.bt.progress(fileId)

    fun isComplete(fileId: String): Boolean = kernel.bt.isFinished(fileId)

    fun savedFile(fileId: String): File? = kernel.bt.savedFile(fileId)

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
        (kernel.bt.progress(fileId) * 100.0).toInt().coerceIn(0, 100)

    fun downloadRateBps(fileId: String): Long = kernel.bt.downloadRate(fileId)

    fun uploadRateBps(fileId: String): Long = kernel.bt.uploadRate(fileId)

    fun peerCount(fileId: String): Int = kernel.bt.peers(fileId)

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
            // 重连计时也要清，否则刚休眠完再激活会被 CONNECT_RETRY_MS 卡住。
            lastConnect.keys.removeIf { it.startsWith(fileId + "@") }
            connectedEndpoints.clear()
            true
        }
        kernel.bt.removeTorrent(fileId)
        kernel.events.log("file_stopped", mapOf("group_id" to groupId, "file_id" to fileId))
        // 做种数变了，重新自报一次（协议 §10 的 peer.announce.seeds）
        broadcastPeerAnnounce(null)
        kernel.saveState()
        return had
    }

    /** 单文件限速（协议 §4：一文件一 torrent + setUploadLimit）。 */
    fun setUploadLimitKbps(fileId: String, kbps: Int) {
        kernel.bt.setUploadLimit(fileId, kbps)
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
        val path = kernel.bt.savedFile(fileId)?.absolutePath ?: ""
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
            val decision = SeedRestore.decide(
                hasEntry = entry != null,
                hasTorrent = entry != null && entry.torrent.isNotEmpty(),
                fileExists = localFile.isFile,
                sizeMatches = entry != null && localFile.length() == entry.size,
            )
            var failure = decision.reason
            var activated = false
            if (decision == SeedRestore.Decision.RESUME && entry != null) {
                activated = try {
                    // 直接按原始 torrent 字节挂种：infohash 与群里其他人看到的一致（见 SeedRestore）。
                    val bytes = Base64.getDecoder().decode(entry.torrent)
                    kernel.bt.addSeed(
                        BtSession.SeededTorrent(entry.fileId, entry.name, entry.size, entry.pieceSize, bytes),
                        localFile.parentFile ?: kernel.downloadsDir,
                    )
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
                    val state = kernel.bt.seedState(fileId)
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
            lastConnect.keys.removeIf { it.startsWith(fileId + "@") }
            seeding.leave(fileId)
        }
        kernel.bt.removeTorrent(fileId)
        kernel.events.log("seed_stopped", mapOf("group_id" to groupId, "file_id" to fileId, "reason" to reason))
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
        val isNew = synchronized(lock) {
            if (files.containsKey(entry.fileId)) {
                val old = files.getValue(entry.fileId)
                files[entry.fileId] = old.copy(
                    torrent = if (entry.torrent.isNotEmpty()) entry.torrent else old.torrent,
                    magnet = if (entry.magnet.isNotEmpty()) entry.magnet else old.magnet,
                    sig = if (entry.sig.isNotEmpty()) entry.sig else old.sig,
                )
                false
            } else {
                files[entry.fileId] = entry
                true
            }
        }
        kernel.saveStateIfStarted()
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
        synchronized(lock) { files[entry.fileId] = entry }
    }

    internal fun restoreOp(op: OpRecord) {
        oplog.loadAll(listOf(op))
    }

    internal fun attach(link: PeerLink) {
        synchronized(lock) { links[link.peerDeviceId] = link }
    }

    internal fun detach(link: PeerLink) {
        synchronized(lock) {
            if (links[link.peerDeviceId] === link) links.remove(link.peerDeviceId)
        }
    }

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
            // 协议 §10：plat / seeds 是这条消息自报的两个运行期属性，只用于成员表展示。
            memberLive[msg.dev] = MemberLive(msg.platform, msg.seeds)
        }
        kernel.events.log(
            "peer_announce",
            mapOf(
                "dev" to msg.dev,
                "addr" to (effectiveHost + ":" + msg.btPort),
                "plat" to msg.platform,
                "seeds" to msg.seeds.toString(),
            ),
        )
        broadcast(MsgType.PEER_ANNOUNCE, jobj(
            "gid" to jstr(msg.gid),
            "dev" to jstr(msg.dev),
            "btPort" to jnum(msg.btPort.toLong()),
            "btHost" to jstr(msg.btHost),
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
        // 签名跟着条目一起存：之后转发 file.list 要原样带上它（协议 §7）。
        val stored = entry.copy(sig = sig)
        val isNew = synchronized(lock) {
            val existing = files[entry.fileId]
            if (existing == null) {
                files[entry.fileId] = stored
                true
            } else {
                // 幂等：同 fileId 只更新元信息（协议 §7）。
                files[entry.fileId] = existing.copy(
                    name = if (entry.name.isNotEmpty()) entry.name else existing.name,
                    torrent = if (entry.torrent.isNotEmpty()) entry.torrent else existing.torrent,
                    magnet = if (entry.magnet.isNotEmpty()) entry.magnet else existing.magnet,
                    sig = if (sig.isNotEmpty()) sig else existing.sig,
                )
                false
            }
        }
        kernel.saveState()
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
        val seeds = seedingFileCount()
        val unsigned = PeerAnnounceMsg(groupId, kernel.deviceId, port.toInt(), "", ts, "")
        val sig = kernel.identity.sign(Domains.PEER, unsigned.signFields())
        // plat / seeds 不进 peer1 签名域（口径同 btHost）：只补字段，不改端点交换的语义。
        return jobj(
            "gid" to jstr(groupId),
            "dev" to jstr(kernel.deviceId),
            "btPort" to jnum(port),
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
        /** 没有活 BT 连接时的 connect_peer 重试间隔。 */
        const val CONNECT_RETRY_MS = 5_000L

        /** 连续几拍「数据不完整」才把做种降级为休眠（巡查 2 s 一拍，即 ~4 s 的确认窗口）。 */
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
            lastConnect.clear()
            completed.clear()
            btEndpoints.clear()
            memberLive.clear()
            ids
        }
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
