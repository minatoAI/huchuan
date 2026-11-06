package app.huchuan.daemon

import app.huchuan.core.m1.FileEntry
import app.huchuan.core.m1.GroupRuntime
import app.huchuan.core.m1.J
import app.huchuan.core.m1.KernelEvents
import app.huchuan.core.m1.M1Kernel
import app.huchuan.core.m1.OpRecord
import app.huchuan.core.m1.jarr
import app.huchuan.core.m1.jbool
import app.huchuan.core.m1.jnum
import app.huchuan.core.m1.jobj
import app.huchuan.core.m1.jstr
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 进程内的一整套东西：core 内核 + 设置 + WS 推送 + 状态快照。
 *
 * 线程模型：
 *   - core 自己的线程（消息层 / BT / mDNS / 巡查）通过 [events] 回调进来，只做「记日志 + 记版本号 + 广播」；
 *   - HTTP 每个连接一个线程，读状态时只读 core 的公开读口（core 内部自带同步）；
 *   - 一个 ticker 线程每 1000 ms 采样一次传输状态，变了才推，避免空闲时刷屏。
 */
class Daemon(val options: DaemonOptions, val log: DaemonLogger) {

    val settings = SettingsStore(File(options.dataDir, "settings.json"), log)
    val hub = WsHub(log)
    val startedAt = System.currentTimeMillis()

    private val rev = AtomicLong(1)
    private val highWater = java.util.Collections.synchronizedMap(HashMap<String, Int>())
    private val stopLatch = CountDownLatch(1)
    private var ticker: Thread? = null
    private var lastTransferSignature = ""

    @Volatile
    var apiPort: Int = 0

    @Volatile
    var mdnsRunning: Boolean = false

    @Volatile
    private var kernel: M1Kernel? = null

    @Volatile
    private var stopping: Boolean = false

    val events: KernelEvents = object : KernelEvents() {
        override fun log(event: String, fields: Map<String, String>) {
            log.event(event, fields)
            when (event) {
                "kernel_started" -> {
                    mdnsRunning = fields["mdns"] == "on"
                    bump("kernel_started")
                }
                // 会让 Web UI 看到新东西的事件才推；心跳、探测、进度打点不在这里
                "file_add", "file_list_entry", "file_seeding", "file_stopped",
                "download_start", "download_completed", "comment_created", "op_ingested",
                "group_created", "group_joined", "member_name",
                -> bump(event)
                else -> Unit
            }
        }

        override fun joined(group: GroupRuntime) = bump("joined")

        override fun memberJoined(group: GroupRuntime, deviceId: String, name: String) = bump("member_joined")

        override fun fileAdded(group: GroupRuntime, entry: FileEntry) = bump("file_added")

        override fun fileCompleted(group: GroupRuntime, entry: FileEntry) = bump("file_completed")

        override fun opReceived(group: GroupRuntime, op: OpRecord) = bump("comment_added")
    }

    // ------------------------------------------------------------------ 生命周期

    fun start() {
        settings.load()
        log.event(
            "daemon_config",
            mapOf(
                "data_dir" to options.dataDir.absolutePath,
                "device_name" to options.deviceName,
                "msg_port_hint" to options.msgPortHint.toString(),
                "bt_port_hint" to options.btPortHint.toString(),
                "web_root" to (options.webRoot?.absolutePath ?: "jar:web"),
            ),
        )
        val created = M1Kernel(
            dataDir = options.dataDir,
            deviceName = options.deviceName,
            portHint = options.msgPortHint,
            btHint = options.btPortHint,
            events = events,
        )
        created.start()
        kernel = created
        // 限速不落内核（libtorrent 的 settings_pack 不持久化），重启后按落盘值重新下发一次。
        applySessionLimits()
        startTicker()
        if (options.restoreSeeds) restoreSeedsAsync()
        log.event(
            "daemon_kernel_ready",
            mapOf(
                "device_id" to created.deviceId,
                "msg_port" to created.localMessagePort().toString(),
                "bt_port" to created.localBtPort().toString(),
                "groups" to created.groups().size.toString(),
            ),
        )
    }

    fun awaitStop(timeoutMs: Long): Boolean = stopLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun requestShutdown(reason: String) {
        if (stopping) return
        stopping = true
        log.event("daemon_shutdown_requested", mapOf("reason" to reason))
        stopLatch.countDown()
    }

    fun stop() {
        try { ticker?.interrupt() } catch (_: Throwable) {}
        hub.closeAll()
        try { kernel?.stop() } catch (e: Throwable) {
            log.event("daemon_kernel_stop_error", mapOf("error" to e.toString()))
        }
        kernel = null
        log.event("daemon_stopped", mapOf("pid" to ProcessHandle.current().pid().toString()))
    }

    // ------------------------------------------------------------------ 版本与推送

    fun revision(): Long = rev.get()

    fun bump(event: String) {
        val next = rev.incrementAndGet()
        hub.broadcast("{\"type\":\"state\",\"rev\":" + next + ",\"event\":\"" + event + "\"}")
        log.event("ws_push", mapOf("kind" to "state", "rev" to next.toString(), "event" to event))
    }

    /**
     * 重启后把「本机共享过、原文件还在」的种子重新挂回会话。
     *
     * 为什么要做：core 的 state.json 只存文件条目，不存 BT 会话；daemon 重启后本机共享的文件
     * 会显示成「休眠」，而这时点「激活」走的是下载路径，会把自家文件当别人的下回来。
     * 这里用 daemon 自己记的 seedPaths 把种子重新建出来（同一个文件 → 同一个 infohash，
     * 覆盖同一条目，不会产生重复文件）。
     *
     * 放后台线程：大文件重新做种要算 piece hash，不能卡住 daemon 启动。
     * 想关掉用 --restore-seeds=0。
     */
    private fun restoreSeedsAsync() {
        val thread = Thread {
            val k = kernel ?: return@Thread
            var restored = 0
            for (g in k.groups()) {
                for (entry in g.files()) {
                    if (stopping) return@Thread
                    if (entry.addedBy != k.deviceId) continue
                    if (g.localRole(entry.fileId) != GroupRuntime.LocalRole.NONE) continue
                    val path = settings.seedPathOf(entry.fileId) ?: continue
                    val file = File(path)
                    if (!file.isFile || file.length() != entry.size) {
                        log.event("seed_restore_skip", mapOf("file_id" to entry.fileId, "path" to path))
                        continue
                    }
                    try {
                        val seeded = g.addFile(file)
                        if (seeded.fileId == entry.fileId) {
                            restored++
                            log.event("seed_restored", mapOf("file_id" to entry.fileId, "path" to path))
                        } else {
                            // 文件内容变了：infohash 变了，等于换了一个新文件
                            log.event(
                                "seed_restore_changed",
                                mapOf("old" to entry.fileId, "new" to seeded.fileId, "path" to path),
                            )
                        }
                    } catch (e: Throwable) {
                        log.event("seed_restore_error", mapOf("file_id" to entry.fileId, "error" to e.toString()))
                    }
                }
            }
            if (restored > 0) {
                log.event("seed_restore_done", mapOf("count" to restored.toString()))
                bump("seeds_restored")
            }
        }
        thread.isDaemon = true
        thread.name = "huchuan-daemon-restore"
        thread.start()
    }

    /** 传输状态采样：只有画面会变（进度 / 速率 / peer 数 / 角色）时才推。 */
    private fun startTicker() {
        val thread = Thread {
            while (!stopping) {
                try {
                    Thread.sleep(TICK_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (stopping) break
                try {
                    val signature = transferSignature()
                    if (signature != lastTransferSignature) {
                        lastTransferSignature = signature
                        val next = rev.incrementAndGet()
                        hub.broadcast("{\"type\":\"tick\",\"rev\":" + next + "}")
                    }
                } catch (_: Throwable) {
                    // 采样失败不影响主循环
                }
            }
        }
        thread.isDaemon = true
        thread.name = "huchuan-daemon-ticker"
        thread.start()
        ticker = thread
    }

    private fun transferSignature(): String {
        val k = kernel ?: return ""
        val sb = StringBuilder(128)
        for (g in k.groups()) {
            for (f in g.files()) {
                val role = g.localRole(f.fileId)
                if (role == GroupRuntime.LocalRole.NONE) continue
                val pct = g.percent(f.fileId)
                updateHighWater(f.fileId, pct)
                sb.append(f.fileId).append(':').append(pct)
                    .append(':').append(g.downloadRateBps(f.fileId))
                    .append(':').append(g.uploadRateBps(f.fileId))
                    .append(':').append(g.peerCount(f.fileId))
                    // 队友数也会让画面变（P3 的 chip / P4 的「正在组队」整行出现或消失）：
                    // 进签名以后不用等别的数字变，连接一建立就能推一帧。
                    .append(':').append(g.teammates(f.fileId))
                    .append(';')
            }
        }
        return sb.toString()
    }

    private fun updateHighWater(fileId: String, percent: Int) {
        if (percent <= 0) return
        val old = highWater[fileId] ?: 0
        if (percent > old) highWater[fileId] = percent
    }

    // ------------------------------------------------------------------ 全局限速（P7 / P4）

    /**
     * 把「全局限速」下发到内核会话，返回**内核读回值**（KB/s：上行 to 下行）。
     *
     * 口径与 Android 端 pushSessionLimits 同一套：界面存的是「用户想要的值」，「不限」开关
     * 打开时两项都按 0 下发；真正生效的是内核 session 级限速 —— 走 libtorrent 的 applySettings，
     * 运行期立即生效，对已经在传的 torrent 同样有效，多文件同时传也只有一份总预算。
     * 单文件限速（[GroupRuntime.setUploadLimitKbps]）是另一层，两层同时存在时每个 torrent 取更小的那个。
     *
     * 下发完立刻读回会话里生效的值：读到的和要的不一样就在日志里记 ok=false，不假装成功。
     */
    fun applySessionLimits(): Pair<Int, Int> {
        val k = kernel
        val up = if (settings.unlimited) 0 else settings.uploadLimitKbps.coerceAtLeast(0)
        val down = if (settings.unlimited) 0 else settings.downloadLimitKbps.coerceAtLeast(0)
        k?.setSessionUploadLimit(up)
        k?.setSessionDownloadLimit(down)
        val readUp = k?.sessionUploadLimitKbps() ?: up
        val readDown = k?.sessionDownloadLimitKbps() ?: down
        log.event(
            "session_limit_applied",
            mapOf(
                "up_kbps" to up.toString(),
                "down_kbps" to down.toString(),
                "session_up_kbps" to readUp.toString(),
                "session_down_kbps" to readDown.toString(),
                "unlimited" to settings.unlimited.toString(),
                "ok" to (readUp == up && readDown == down).toString(),
            ),
        )
        return readUp to readDown
    }

    /** 全局限速的完整读数：用户设置 + 内核读回（`session*Kbps` 是会话里真正生效的值）。 */
    fun limitsJson(): J.Obj {
        val k = kernel
        return jobj(
            "uploadLimitKbps" to jnum(settings.uploadLimitKbps.toLong()),
            "downloadLimitKbps" to jnum(settings.downloadLimitKbps.toLong()),
            "unlimited" to jbool(settings.unlimited),
            "sessionUploadLimitKbps" to jnum((k?.sessionUploadLimitKbps() ?: 0).toLong()),
            "sessionDownloadLimitKbps" to jnum((k?.sessionDownloadLimitKbps() ?: 0).toLong()),
        )
    }

    // ------------------------------------------------------------------ 快照

    fun snapshot(): J.Obj {
        val k = kernel
        val groups = k?.groups() ?: emptyList()
        return jobj(
            "daemon" to jobj(
                "version" to jstr(VERSION),
                "pid" to jnum(ProcessHandle.current().pid()),
                "apiPort" to jnum(apiPort.toLong()),
                "msgPort" to jnum((k?.localMessagePort() ?: 0).toLong()),
                "btPort" to jnum((k?.localBtPort() ?: 0).toLong()),
                "dhtNodes" to jnum(k?.dhtNodes() ?: 0L),
                "mdns" to jbool(mdnsRunning),
                "startedAt" to jnum(startedAt),
                "rev" to jnum(rev.get()),
                "wsClients" to jnum(hub.size.toLong()),
                "dataDir" to jstr(options.dataDir.absolutePath),
                "logPath" to jstr(options.logPath ?: ""),
            ),
            "device" to deviceJson(),
            "settings" to settingsJson(),
            "groups" to jarr(groups.map { groupJson(it, withComments = true) }),
        )
    }

    fun deviceJson(): J.Obj {
        val k = kernel
        return jobj(
            "deviceId" to jstr(k?.deviceId ?: ""),
            "deviceName" to jstr(options.deviceName),
            "userName" to jstr(settings.userName),
            "displayName" to jstr(if (settings.userName.isNotEmpty()) settings.userName else options.deviceName),
        )
    }

    /** 设置页要的全部：用户名 + 全局限速（含内核读回）。字段只在这里加一次，别处一律调它。 */
    fun settingsJson(): J.Obj {
        val fields = LinkedHashMap<String, J>()
        fields["userName"] = jstr(settings.userName)
        fields.putAll(limitsJson().fields)
        return J.Obj(fields)
    }

    /**
     * 群的完整 JSON。
     *
     * [withComments] = true 时每个文件带上 comments（Web UI 每次刷新只发一个 /api/state 请求，
     * 见 docs/daemon-api.md「为什么 /api/state 带评论」）。列表类接口传 false，别把整棵树放大。
     */
    fun groupJson(g: GroupRuntime, withComments: Boolean = false): J.Obj {
        val k = kernel
        val files = g.files()
        val members = g.members()
        val online = g.onlinePeers().toSet()
        val self = k?.deviceId ?: ""
        val localSeeding = files.count { g.localRole(it.fileId) == GroupRuntime.LocalRole.SEEDING }
        // 群级组队人数 = 本群正在组队的文件里最大的那一队（含本机）；没有文件在组队就是 0。
        // 与 P3 行上的 chip 同一口径：本机处于「部分」且队友 ≥ 1 才算在组队。
        val teamSize = files.fold(0) { acc, entry ->
            val mates = g.teammates(entry.fileId)
            if (mates <= 0) acc else maxOf(acc, mates + 1)
        }
        return jobj(
            "id" to jstr(g.groupId),
            "name" to jstr(g.name),
            "inviteCode" to jstr(g.inviteCode),
            "inviteTail8" to jstr(g.inviteCode.takeLast(8)),
            "invitePretty" to jstr(g.invitePretty),
            "fingerprint" to jstr(g.fingerprint),
            "memberCount" to jnum(members.size.toLong()),
            "fileCount" to jnum(files.size.toLong()),
            "seedingCount" to jnum(localSeeding.toLong()),
            "teamSize" to jnum(teamSize.toLong()),
            "unreadCount" to jnum(0L),
            "notificationLevel" to jstr(settings.notificationOf(g.groupId)),
            "onlineCount" to jnum(online.size.toLong()),
            "members" to jarr(members.map { member -> memberJson(member.dev, member.name, online.contains(member.dev), member.dev == self, g, localSeeding) }),
            "files" to jarr(files.map { fileJson(g, it, withComments) }),
        )
    }

    private fun memberJson(dev: String, name: String, online: Boolean, self: Boolean, g: GroupRuntime, localSeeding: Int): J.Obj {
        val display = name.ifEmpty { if (self) options.deviceName else dev.take(8) }
        return jobj(
            "dev" to jstr(dev),
            "name" to jstr(display),
            "online" to jbool(if (self) true else online),
            "self" to jbool(self),
            // 本机做种数是真数；别人做种几份协议里没有广播（docs/daemon-api.md 缺口 G3）
            "seedingCount" to jnum((if (self) localSeeding else 0).toLong()),
            "platform" to jstr(if (self) "Windows" else "未知"),
        )
    }

    fun fileJson(g: GroupRuntime, f: FileEntry, withComments: Boolean = false): J.Obj {
        val role = g.localRole(f.fileId)
        val live = role != GroupRuntime.LocalRole.NONE
        val percent = if (live) g.percent(f.fileId).also { updateHighWater(f.fileId, it) } else (highWater[f.fileId] ?: 0)
        val complete = if (live) g.isComplete(f.fileId) else percent >= 100
        // role 是「内核里有没有这个文件」，state 是「画面上该显示什么」：
        // 下载完但还没被内核改判成 SEEDING 的那几百毫秒里，两者必须一致地显示「做种中」。
        val roleName = when {
            !live -> "none"
            complete -> "seeding"
            else -> "downloading"
        }
        val state = when {
            !live -> "sleeping"
            complete || role == GroupRuntime.LocalRole.SEEDING -> "seeding"
            else -> "downloading"
        }
        val self = kernel?.deviceId ?: ""
        val source = g.memberName(f.addedBy).ifEmpty { if (f.addedBy == self) options.deviceName else f.addedBy.take(8) }
        // 队友数只有一个来源：内核的观测读口（docs/design/product-spec.md §306）。
        // 做种者不计入、本机到 100% 或没挂进会话时内核直接回 0，daemon 不自己推算。
        val teammates = g.teammates(f.fileId)
        val uploadLimit = settings.uploadLimitOf(f.fileId)
        val base = jobj(
            "id" to jstr(f.fileId),
            "name" to jstr(f.name),
            "sizeBytes" to jnum(f.size),
            "pieceSize" to jnum(f.pieceSize.toLong()),
            "addedAt" to jnum(f.addedAt),
            "sourceMember" to jstr(source),
            "sourceDev" to jstr(f.addedBy),
            "mine" to jbool(f.addedBy == self),
            "state" to jstr(state),
            "role" to jstr(roleName),
            "progressPercent" to jnum(percent.toLong()),
            "complete" to jbool(complete),
            "seeders" to jnum((if (live) g.peerCount(f.fileId) else 0).toLong()),
            "teammates" to jnum(teammates.toLong()),
            "uploadLimitKbps" to jnum(uploadLimit.toLong()),
            // 这条单文件限速此刻是不是真在生效：文件挂在会话里、还没到 100%（到 100% 内核会把
            // torrent 限速清零），而且确实设了值。core 没有按 torrent 读回限速的口子（缺口 G9）。
            "uploadLimitInForce" to jbool(live && !complete && uploadLimit > 0),
            "downloadRateBps" to jnum(if (live) g.downloadRateBps(f.fileId) else 0L),
            "uploadRateBps" to jnum(if (live) g.uploadRateBps(f.fileId) else 0L),
            "commentCount" to jnum(g.visibleOps(f.fileId).size.toLong()),
            "savedPath" to jstr(g.savedFile(f.fileId)?.absolutePath ?: settings.seedPathOf(f.fileId) ?: ""),
            "magnet" to jstr(f.magnet),
        )
        if (!withComments) return base
        val fields = LinkedHashMap<String, J>(base.fields.size + 1)
        fields.putAll(base.fields)
        fields["comments"] = commentsJson(g, f.fileId)
        return J.Obj(fields)
    }

    fun commentsJson(g: GroupRuntime, fileId: String): J.Arr {
        // 新评论在前（与旧的内存 store 一致：发出去的评论出现在列表顶部）。
        val ops = g.visibleOps(fileId).sortedWith(compareByDescending<OpRecord> { it.lamport }.thenByDescending { it.seq })
        return jarr(ops.map { op ->
            val author = g.memberName(op.author).ifEmpty {
                if (op.author == kernel?.deviceId) options.deviceName else op.author.take(8)
            }
            jobj(
                "id" to jstr(op.opId),
                "opId" to jstr(op.opId),
                "author" to jstr(author),
                "authorDev" to jstr(op.author),
                "self" to jbool(op.author == kernel?.deviceId),
                "body" to jstr(op.body),
                "ts" to jnum(op.ts),
                "lamport" to jnum(op.lamport),
                "seq" to jnum(op.seq),
                "kind" to jnum(op.kind.toLong()),
                "parentId" to jstr(op.parent),
            )
        })
    }

    fun kernelOrNull(): M1Kernel? = kernel

    fun group(groupId: String): GroupRuntime? = kernel?.group(groupId)

    fun file(groupId: String, fileId: String): FileEntry? = group(groupId)?.file(fileId)

    companion object {
        const val VERSION = "0.1.0"
        private const val TICK_MS = 1000L
    }
}
