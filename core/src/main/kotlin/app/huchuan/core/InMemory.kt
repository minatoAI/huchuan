package app.huchuan.core

/**
 * 内存假实现。M1 之前全部数据都在进程内存里，退出即丢；不涉及网络、磁盘与传输内核。
 */

/** 内置演示数据：一个群组、两个文件、三条评论。 */
object FakeData {
    const val DEVICE_NAME: String = "我的设备"

    /** 16 位 Crockford Base32 邀请码；P5 只收后 8 位。 */
    const val INVITE_CODE: String = "K3P9T7Q2M8X4Z6VB"

    val members: List<Member> = listOf(
        Member(name = DEVICE_NAME, online = true, seedingCount = 1, platform = "Android"),
        Member(name = "书房电脑", online = true, seedingCount = 2, platform = "Windows"),
        Member(name = "平板", online = false, seedingCount = 0, platform = "Android"),
    )

    val comments: List<Comment> = listOf(
        Comment(id = "c1", author = "书房电脑", body = "这段我下到 60% 了", time = "昨天 21:32"),
        Comment(id = "c2", author = "平板", body = "周日一起看", time = "昨天 21:40"),
        Comment(id = "c3", author = DEVICE_NAME, body = "先下前半段", time = "今天 08:05"),
    )

    val files: List<GroupFile> = listOf(
        GroupFile(
            id = "f1",
            name = "纪录片 4K.mp4",
            sizeBytes = 3_650_722_202L,
            sourceMember = "书房电脑",
            state = FileState.DOWNLOADING,
            progressPercent = 42,
            seeders = 1,
            teammates = 1,
            uploadLimitKbps = 512,
            downloadRateBps = 2_400_000L,
            uploadRateBps = 180_000L,
            comments = comments,
        ),
        GroupFile(
            id = "f2",
            name = "旅行照片.zip",
            sizeBytes = 1_288_490_188L,
            sourceMember = DEVICE_NAME,
            state = FileState.SEEDING,
            progressPercent = 100,
            seeders = 1,
            teammates = 0,
            uploadLimitKbps = 0,
            downloadRateBps = 0L,
            uploadRateBps = 620_000L,
            comments = emptyList(),
        ),
    )

    /** 群名按 spec §4.2 的建群默认值：{用户名}的群组。 */
    val groups: List<Group> = listOf(
        Group(
            id = "g1",
            name = DEVICE_NAME + "的群组",
            inviteCode = INVITE_CODE,
            members = members,
            files = files,
            notificationLevel = NotificationLevel.SYSTEM_BUBBLE,
            unreadCount = 2,
        ),
    )

    val network: NetworkReport = NetworkReport(
        status = NetworkStatus.PASS,
        items = listOf(
            NetworkCheckItem(NetworkCheckKind.NAT_TYPE, passed = true),
            NetworkCheckItem(NetworkCheckKind.IPV6, passed = true),
            NetworkCheckItem(NetworkCheckKind.IPV4, passed = true),
        ),
    )
}

class InMemoryGroupStore(seed: List<Group> = FakeData.groups) : GroupStore {
    private val state: MutableList<Group> = seed.toMutableList()
    private var nextId: Int = 100

    override fun groups(): List<Group> = state.toList()

    override fun group(groupId: String): Group? = state.firstOrNull { it.id == groupId }

    override fun createGroup(name: String): Group {
        val created = Group(
            id = "g" + nextId++,
            name = name,
            inviteCode = FakeData.INVITE_CODE,
            members = FakeData.members,
            files = emptyList(),
            notificationLevel = NotificationLevel.SYSTEM_BUBBLE,
            unreadCount = 0,
        )
        state.add(created)
        return created
    }

    override fun joinGroup(tail8: String): Group? {
        val tail = tail8.trim().uppercase()
        if (tail.length != 8) return null
        return state.firstOrNull { it.inviteCode.endsWith(tail) }
    }

    override fun setNotificationLevel(groupId: String, level: NotificationLevel) {
        update(groupId) { it.copy(notificationLevel = level) }
    }

    override fun setFileActive(groupId: String, fileId: String, active: Boolean) {
        updateFile(groupId, fileId) { file ->
            val next = when {
                !active -> FileState.SLEEPING
                file.progressPercent >= 100 -> FileState.SEEDING
                file.seeders > 0 -> FileState.DOWNLOADING
                else -> FileState.SLEEPING
            }
            file.copy(
                state = next,
                teammates = if (next == FileState.DOWNLOADING) file.teammates else 0,
            )
        }
    }

    override fun setFileUploadLimit(groupId: String, fileId: String, kbps: Int) {
        updateFile(groupId, fileId) { it.copy(uploadLimitKbps = kbps) }
    }

    override fun addComment(groupId: String, fileId: String, body: String, parentId: String?) {
        val text = body.trim()
        if (text.isEmpty()) return
        updateFile(groupId, fileId) { file ->
            val comment = Comment(
                id = "c" + nextId++,
                author = FakeData.DEVICE_NAME,
                body = text,
                time = "刚刚",
                parentId = parentId,
            )
            // 新评论置顶（spec §4.7）。
            file.copy(comments = listOf(comment) + file.comments)
        }
    }

    override fun addFile(groupId: String, name: String, sizeBytes: Long, sourceMember: String): GroupFile {
        val file = GroupFile(
            id = "f" + nextId++,
            name = name,
            sizeBytes = sizeBytes,
            sourceMember = sourceMember,
            state = FileState.SEEDING,
            progressPercent = 100,
            seeders = 1,
            teammates = 0,
            uploadLimitKbps = 0,
            downloadRateBps = 0L,
            uploadRateBps = 0L,
        )
        update(groupId) { it.copy(files = it.files + file) }
        return file
    }

    private fun update(groupId: String, block: (Group) -> Group) {
        val index = state.indexOfFirst { it.id == groupId }
        if (index >= 0) state[index] = block(state[index])
    }

    private fun updateFile(groupId: String, fileId: String, block: (GroupFile) -> GroupFile) {
        update(groupId) { group ->
            group.copy(files = group.files.map { if (it.id == fileId) block(it) else it })
        }
    }
}

class InMemoryLocalDeviceStore : LocalDeviceStore {
    private var state = LocalDevice(
        deviceName = FakeData.DEVICE_NAME,
        userName = "",
        uploadLimitKbps = 0,
        downloadLimitKbps = 0,
        unlimited = true,
    )

    override fun device(): LocalDevice = state

    override fun saveUserName(name: String) {
        state = state.copy(userName = name.trim())
    }

    override fun setUploadLimit(kbps: Int) {
        state = state.copy(uploadLimitKbps = kbps.coerceAtLeast(0))
    }

    override fun setDownloadLimit(kbps: Int) {
        state = state.copy(downloadLimitKbps = kbps.coerceAtLeast(0))
    }

    override fun setUnlimited(unlimited: Boolean) {
        state = state.copy(unlimited = unlimited)
    }
}

class InMemoryNetworkProbe(
    private val report: NetworkReport = FakeData.network,
) : NetworkProbe {
    override fun check(): NetworkReport = report
}
