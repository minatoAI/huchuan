package app.huchuan.core.m1

import java.io.File

/**
 * 本机一个 torrent 任务所在的**会话**（私有 [BtSession] / 公开 [PublicBtSession]）。
 *
 * 两个会话是两套 SessionManager（settings_pack 是会话级的，AGENTS §9），但内核里「这个文件在
 * 本机走到哪了」的读数只该有一套口径。GroupRuntime / M1Kernel 因此只读这个接口，按
 * [M1Kernel.sessionOf] 选会话，而不是到处写 kernel.bt.xxx —— 否则接上公开会话之后，公开任务
 * 的进度、完成判定、做种对账全都会被私有会话答成「没有这个 torrent」，表现为做种被静默降级。
 *
 * 只包含「读状态 + 摘任务 + 单文件限速」这几件两个会话都能答的事；加种（addSeed / addDownload /
 * 磁力）留在各自会话上，因为它们的入参本来就不同（内联种子 vs 磁力链接）。
 */
interface LocalSession {
    /** 该 fileId 的 torrent 是否挂在这个会话里。 */
    fun has(fileId: String): Boolean

    fun progress(fileId: String): Double

    fun isFinished(fileId: String): Boolean

    /** 本地落盘文件；不在会话里返回 null。 */
    fun savedFile(fileId: String): File?

    fun peers(fileId: String): Int

    /** 做种侧健康状态：finished / checking / pending / incomplete / missing。 */
    fun seedState(fileId: String): String

    fun downloadRate(fileId: String): Long

    fun uploadRate(fileId: String): Long

    /** 把 torrent 从会话里摘掉（休眠 / 取消）。文件留在盘上。 */
    fun removeTorrent(fileId: String): Boolean

    fun setUploadLimit(fileId: String, kbps: Int)

    fun superSeeding(fileId: String): Boolean

    fun setSuperSeeding(fileId: String, on: Boolean): Boolean
}
