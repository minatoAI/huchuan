package app.huchuan.core

/**
 * 领域模型。术语与 docs/design/product-spec.md §1 术语表一一对应：
 * 群组 / 邀请码 / 文件 / 激活（做种中）/ 休眠 / 部分（下载中）/ 队友 / 组队 / 成员 / 用户名 / 做种者 / 通知档。
 */

/** 文件状态机（spec §5）：休眠 / 下载中（部分）/ 做种中（激活）。 */
enum class FileState {
    SLEEPING,
    DOWNLOADING,
    SEEDING,
}

/**
 * 文件作用域（docs/design/public-swarm.md §1.1）：私有 = 只在群内传；公开 = 走公网 swarm。
 *
 * 它是**判别字段**，不是安全边界：加种前一律按种子自身的 private 位（BEP 27）选会话，
 * 传输中的 scope 可被转发方篡改，最多影响列表归属与 UI（§1.2）。
 * 缺省 private —— 旧 state.json 里没有这个字段的条目读回来就是私有，零迁移。
 */
enum class FileScope {
    PRIVATE,
    PUBLIC,
    ;

    /** 落盘 / 上线的写法（file.add / file.list 的 entry.scope）。 */
    val wire: String get() = if (this == PUBLIC) "public" else "private"

    companion object {
        /** 认不出的值一律当 private：一个未知字符串不该把私有条目变成公开条目。 */
        fun parse(raw: String?): FileScope =
            if (raw?.trim()?.lowercase() == PUBLIC.wire) PUBLIC else PRIVATE
    }
}

/** 通知档（spec §1、P7）：系统气泡 / 仅应用内 / 静音。 */
enum class NotificationLevel {
    SYSTEM_BUBBLE,
    IN_APP_ONLY,
    MUTED,
}

/** P0 检测项：NAT 类型 / IPv6 / IPv4。 */
enum class NetworkCheckKind {
    NAT_TYPE,
    IPV6,
    IPV4,
}

data class NetworkCheckItem(
    val kind: NetworkCheckKind,
    val passed: Boolean,
)

/** P0 整体判定：检测中 / 可互传 / 仅本机可用 / 无网络连接。 */
enum class NetworkStatus {
    CHECKING,
    PASS,
    FAIL,
    OFFLINE,
}

data class NetworkReport(
    val status: NetworkStatus,
    val items: List<NetworkCheckItem> = emptyList(),
)

data class Member(
    val name: String,
    val online: Boolean,
    val seedingCount: Int,
    val platform: String,
)

data class Comment(
    val id: String,
    val author: String,
    val body: String,
    val time: String,
    val parentId: String? = null,
)

data class GroupFile(
    val id: String,
    val name: String,
    /** 字节数；0 = 未知（公开条目在元数据到手前就是 0，P3 行不显示大小，public-swarm §3.1）。 */
    val sizeBytes: Long,
    val sourceMember: String,
    val state: FileState,
    val progressPercent: Int,
    val seeders: Int,
    val teammates: Int,
    /** 单文件限速；0 表示不限。 */
    val uploadLimitKbps: Int,
    val downloadRateBps: Long,
    val uploadRateBps: Long,
    val comments: List<Comment> = emptyList(),
    /** 私有 / 公开（public-swarm §1.1）。缺省 private；P3 行上的「公开」chip 只看它。 */
    val scope: FileScope = FileScope.PRIVATE,
    /** 公开条目的磁力（xt=urn:btih:<40 hex>）；私有条目为空串。 */
    val magnet: String = "",
    /** 分片大小；0 = 未知（公开条目在元数据到手前就是 0）。 */
    val pieceSize: Int = 0,
) {
    /** 组队只存在于「部分」（下载中）；队友数 = 同时下载的同组成员 − 1。 */
    val inTeam: Boolean get() = state == FileState.DOWNLOADING && teammates > 0
    val teamSize: Int get() = teammates + 1
}

data class Group(
    val id: String,
    val name: String,
    /** 16–20 位邀请码；P6 是唯一显示处。 */
    val inviteCode: String,
    val members: List<Member>,
    val files: List<GroupFile>,
    val notificationLevel: NotificationLevel,
    val unreadCount: Int,
) {
    /** 「做种 n」：群内成员合计做种数。 */
    val seedingCount: Int get() = members.sumOf { it.seedingCount }

    /** 「组队 n 人」：群内任一文件正在组队时取最大队伍规模。 */
    val teamSize: Int get() = files.filter { it.inTeam }.maxOfOrNull { it.teamSize } ?: 0
}

data class LocalDevice(
    val deviceName: String,
    val userName: String,
    val uploadLimitKbps: Int,
    val downloadLimitKbps: Int,
    val unlimited: Boolean,
) {
    /** 用户名未设置（首次启动 P1 之前）。 */
    val userNameUnset: Boolean get() = userName.isBlank()
}
