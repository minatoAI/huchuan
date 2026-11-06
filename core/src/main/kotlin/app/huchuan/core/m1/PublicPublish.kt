package app.huchuan.core.m1

/**
 * 「公开到群外」的参数（public-swarm §6.1）。
 *
 * 公开种子是按私有文件内容**新建**的一条 v1 种子（private=0），不是改私有条目的标记：
 * private 位写在 info 字典里，改了它就是另一个 infohash（§1.1），所以两条要同时存在。
 */
data class PublicPublishOptions @JvmOverloads constructor(
    /** 公开条目的显示名（同时进 magnet 的 dn）；空串 = 与私有条目同名。 */
    val name: String = "",
)

/**
 * 一次「公开到群外」的结果。
 *
 * 与 [PublicMagnetResult] 同一套约定：**永不抛异常**，失败一律返回 [reason] + 一句中文 [message]，
 * 宿主直接显示，不弹窗。成功时 [fileId] 是**新公开种子**的 infohash（与传入的私有 fileId 不同）。
 */
data class PublicPublishResult(
    /** 成功：新公开条目已入库、公开会话在做种。 */
    val ok: Boolean,
    /** 成功时是公开种子的 infohash；失败时回显传入的 fileId（没有则为空串）。 */
    val fileId: String,
    /** 传入的私有条目 fileId（成功 / 失败都回显，方便宿主把结果对回原行）。 */
    val privateFileId: String,
    /** "" 表示成功；否则是下面某个失败码。 */
    val reason: String,
    /** 一句中文，直接给 UI 用；成功时为 ""。 */
    val message: String,
    /** 入库的公开条目；失败时为 null。 */
    val entry: FileEntry?,
    /** 公开种子的磁力（xt=urn:btih:<新 infohash>）；失败时为空串。 */
    val magnet: String,
) {
    companion object {
        const val OK = ""

        /** 群不存在。 */
        const val NO_GROUP = "no_group"

        /** fileId 不在本群清单里。 */
        const val NO_FILE = "no_file"

        /** 传进来的是一条非本机发布的公开条目（已经公开过了）。 */
        const val ALREADY_PUBLIC = "already_public"

        /** 私有条目所在文件还没下完（做种集合里没有）。 */
        const val INCOMPLETE = "incomplete"

        /** 私有条目不在做种中：既没有做种记录，也没有可用的本地路径。 */
        const val NOT_SEEDING = "not_seeding"

        /** 做种集合 / 会话里记得路径，但盘上找不到文件。 */
        const val LOCAL_FILE_MISSING = "local_file_missing"

        /** 盘上文件大小与私有条目不一致（文件被截断 / 换掉）。 */
        const val SIZE_MISMATCH = "size_mismatch"

        /** 公开会话起不来 / 拒绝加种。 */
        const val SESSION_ERROR = "session_error"

        /** 建公开种子（TorrentBuilder）失败。 */
        const val BUILD_FAILED = "build_failed"

        /** 公开种子的 infohash 与私有种子相同（BEP 27 下不应发生）。 */
        const val HASH_COLLISION = "hash_collision"

        /**
         * 失败原因 → 一句话（与 [PublicMagnetResult.messageFor] 同一套 UI 口径）。
         *
         * 纯函数：SelfTest 在字节层钉住「每个失败码都有文案、彼此不同、都不换行」，不用起真 swarm。
         */
        @JvmStatic
        fun messageFor(reason: String): String = when (reason) {
            NO_GROUP -> "群不存在"
            NO_FILE -> "这个文件不在群里"
            ALREADY_PUBLIC -> "这个文件已经是公开种子"
            INCOMPLETE -> "文件还没下完"
            NOT_SEEDING -> "文件不在做种中"
            LOCAL_FILE_MISSING -> "本机找不到这个文件"
            SIZE_MISMATCH -> "本机文件与条目大小不一致"
            SESSION_ERROR -> "公开会话没起来"
            BUILD_FAILED -> "建公开种子失败"
            HASH_COLLISION -> "公开种子的 infohash 与私有种子相同"
            else -> ""
        }

        /** 全部失败码（顺序与上面的常量一致）；SelfTest 用它做「文案齐全」的断言。 */
        @JvmStatic
        fun failureReasons(): Array<String> = arrayOf(
            NO_GROUP,
            NO_FILE,
            ALREADY_PUBLIC,
            INCOMPLETE,
            NOT_SEEDING,
            LOCAL_FILE_MISSING,
            SIZE_MISMATCH,
            SESSION_ERROR,
            BUILD_FAILED,
            HASH_COLLISION,
        )
    }
}
