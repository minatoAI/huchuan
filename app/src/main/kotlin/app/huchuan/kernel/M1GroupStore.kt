package app.huchuan.kernel

import android.util.Log
import app.huchuan.core.Comment
import app.huchuan.core.FileState
import app.huchuan.core.Group
import app.huchuan.core.GroupFile
import app.huchuan.core.GroupStore
import app.huchuan.core.Member
import app.huchuan.core.NotificationLevel
import app.huchuan.core.m1.FileEntry
import app.huchuan.core.m1.GroupRuntime
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P2/P3/P4/P5/P6 的数据来源：把 M1 内核的 [GroupRuntime] 投影成界面模型。
 *
 * 界面上看到的每个数字都来自内核实时状态：文件状态来自 BT 会话，评论来自签名 op 日志，
 * 成员在线与否来自消息层连接表。没有任何假数据兜底 —— 内核没起来就是空列表。
 */
class M1GroupStore(private val rt: M1Runtime) : GroupStore {

    /** 单文件限速（协议 §4）：界面设置过才记，0 表示不限。 */
    private val uploadLimits = HashMap<String, Int>()

    // ------------------------------------------------------------------ GroupStore

    override fun groups(): List<Group> = rt.kernel.groups().map { toGroup(it) }

    override fun group(groupId: String): Group? = rt.kernel.group(groupId)?.let { toGroup(it) }

    override fun createGroup(name: String): Group = toGroup(rt.kernel.createGroup(name))

    /**
     * 只收后 8 位（协议 §2.1 + §3.3 手动兜底）。会阻塞到拿到 join.ok 或超时，
     * 界面必须放到后台线程调用（HuchuanState.joinGroup 已经 withContext(IO)）。
     */
    override fun joinGroup(tail8: String): Group? {
        val clean = tail8.trim().uppercase()
        if (clean.length != 8) return null
        val group = rt.kernel.joinByTail8(clean, rt.manualCandidates, JOIN_TIMEOUT_MS) ?: return null
        return toGroup(group)
    }

    override fun setNotificationLevel(groupId: String, level: NotificationLevel) {
        rt.prefs.setNotificationLevel(groupId, level)
    }

    override fun setFileActive(groupId: String, fileId: String, active: Boolean) {
        val group = rt.kernel.group(groupId) ?: return
        val entry = group.file(fileId) ?: return
        if (!active) {
            group.stopFile(fileId)
            return
        }
        if (group.localRole(fileId) != GroupRuntime.LocalRole.NONE) return
        if (entry.addedBy == rt.kernel.deviceId) {
            // 自己做种的文件被休眠过：从私有目录里的副本重新做种。
            val copy = File(rt.shareDir, entry.name)
            if (copy.isFile) {
                rt.shareFile(group, copy)
            } else {
                Log.w(M1Runtime.TAG, "event=reseed_missing file_id=" + fileId + " name=" + entry.name)
            }
        } else {
            group.startDownload(fileId)
        }
    }

    override fun setFileUploadLimit(groupId: String, fileId: String, kbps: Int) {
        val group = rt.kernel.group(groupId) ?: return
        uploadLimits[fileId] = kbps.coerceAtLeast(0)
        group.setUploadLimitKbps(fileId, kbps)
    }

    override fun addComment(groupId: String, fileId: String, body: String, parentId: String?) {
        val text = body.trim()
        if (text.isEmpty()) return
        val group = rt.kernel.group(groupId) ?: return
        group.comment(fileId, text, parentId)
    }

    // ------------------------------------------------------------------ 投影

    fun toGroup(group: GroupRuntime): Group {
        val selfId = rt.kernel.deviceId
        val online = group.onlinePeers().toHashSet()
        val entries = group.files()
        // 做种数与平台直接取 MemberRecord（G3）：本机是真值，别人是自己 peer.announce 的自报值。
        val members = group.members().map { record ->
            Member(
                name = record.name.ifBlank { shortId(record.dev) },
                online = record.dev == selfId || online.contains(record.dev),
                seedingCount = record.seedingCount,
                platform = record.platform,
            )
        }
        val files = entries.map { toGroupFile(group, it) }
        return Group(
            id = group.groupId,
            name = group.name,
            inviteCode = group.inviteCode,
            members = members,
            files = files,
            notificationLevel = rt.prefs.notificationLevel(group.groupId),
            // 未读数（G4）：内核按读游标与可见 op 现算，P3/P4 打开时由 M1Runtime 清零。
            unreadCount = group.unreadCount(),
        )
    }

    /**
     * 一条文件的内核投影。
     *
     * 状态只有 [GroupRuntime.localRole] 一个来源：内核的 SeedingLedger 决定「在做种」，
     * 进集合的入口只有共享成功 / 下载完成 / 重启续种三处，界面不再按「文件在盘上」二次推测。
     * 以前那句 `DOWNLOADING && onDisk -> SEEDING` 是 SeedingLedger 覆盖下载完成之前的临时兜底，
     * 留着只会让「下完但内核还没对账」的文件提前显示成做种中，属于两套口径，故删除。
     */
    fun toGroupFile(group: GroupRuntime, entry: FileEntry): GroupFile {
        val role = group.localRole(entry.fileId)
        val saved = group.savedFile(entry.fileId)
        // 休眠的文件不挂在 BT 会话里，percent() 恒为 0：盘上有完整数据就按 100% 显示。
        val onDisk = rt.isCompleted(entry.fileId) ||
            (saved != null && saved.isFile && saved.length() == entry.size)
        val state = when (role) {
            GroupRuntime.LocalRole.SEEDING -> FileState.SEEDING
            GroupRuntime.LocalRole.DOWNLOADING -> FileState.DOWNLOADING
            GroupRuntime.LocalRole.NONE -> FileState.SLEEPING
        }
        val percent = when (state) {
            FileState.SEEDING -> 100
            FileState.DOWNLOADING -> group.percent(entry.fileId)
            FileState.SLEEPING -> if (onDisk) 100 else 0
        }
        val peers = group.peerCount(entry.fileId)
        val teammates = if (state == FileState.DOWNLOADING) (peers - 1).coerceAtLeast(0) else 0
        val seeders = (if (entry.addedBy == rt.kernel.deviceId) 0 else 1) +
            (if (state == FileState.SEEDING) 1 else 0)
        val comments = group.visibleOps(entry.fileId).map { op ->
            Comment(
                id = op.opId,
                author = group.memberName(op.author).ifBlank { shortId(op.author) },
                body = op.body,
                time = timeLabel(op.ts),
                parentId = op.parent.ifEmpty { null },
            )
        }.reversed()
        return GroupFile(
            id = entry.fileId,
            name = entry.name,
            sizeBytes = entry.size,
            sourceMember = group.memberName(entry.addedBy).ifBlank { shortId(entry.addedBy) },
            state = state,
            progressPercent = percent,
            seeders = seeders,
            teammates = teammates,
            uploadLimitKbps = uploadLimits[entry.fileId] ?: 0,
            downloadRateBps = if (state == FileState.DOWNLOADING) group.downloadRateBps(entry.fileId) else 0L,
            uploadRateBps = if (state == FileState.SEEDING) group.uploadRateBps(entry.fileId) else 0L,
            comments = comments,
        )
    }

    private fun shortId(deviceId: String): String =
        if (deviceId.length > 6) deviceId.takeLast(6) else deviceId

    private fun timeLabel(ts: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date(ts))

    companion object {
        /** 加入的整体超时：mDNS 浏览 4s + 每个候选 12s，留出余量。 */
        const val JOIN_TIMEOUT_MS = 60_000L
    }
}
