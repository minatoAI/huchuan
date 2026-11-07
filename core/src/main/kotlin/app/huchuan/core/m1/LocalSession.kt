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

    /**
     * 这个 torrent 此刻是不是被**挂起**（libtorrent 的 `torrent_flags::paused`）。
     *
     * 磁力导入之后、用户还没点「激活」的公开条目就是挂起的：元数据能拉、数据一个字节都不拉
     * （product-spec「休眠 = 不参与传输」+ public-swarm §3.1，口径见 docs/core.md §3.12）。
     * 宿主的「休眠」判定必须读它，不能只看本地任务表 —— 任务是任务，传输是传输。
     */
    fun isPaused(fileId: String): Boolean

    /**
     * 元数据（info 字典）到手了没有。公开条目在「等待来源」期间是 false：
     * 没有 info 字典就既没有 piece 表也没有尺寸，任何数据都传不了。
     */
    fun hasMetadata(fileId: String): Boolean

    /**
     * 已完成的**有效字节**（libtorrent 的 `total_wanted_done`，wanted 那部分里已落盘校验过的）。
     *
     * 比整数百分比细一档：21.4 GB 的种子 1% = 214 MB，用它才看得出「8 MB 真的动了」。
     * torrent 不在会话里、或元数据还没到手时返回 0（不是「不知道」，是「确实一个字节都没有」）。
     */
    fun downloadedBytes(fileId: String): Long

    /** 这个 torrent 的总字节（libtorrent 的 `total_wanted`）；不在会话里 / 元数据未到时 0。 */
    fun totalBytes(fileId: String): Long

    /** 本地落盘文件；不在会话里返回 null。 */
    fun savedFile(fileId: String): File?

    fun peers(fileId: String): Int

    /**
     * 对端列表快照（队友数与「成员是否已互连」的唯一数据源）。
     * 私有 / 公开两个会话字段口径相同（[BtSession.snapshotOf]）。
     */
    fun peerSnapshot(fileId: String): List<BtSession.PeerSnapshot>

    /**
     * 手动给某个 torrent 加一个 peer（swig `connect_peer`；libtorrent4j 高层 API 没有这个能力）。
     *
     * 私有条目拨成员的私有 BT 端口（peer.announce.btPort），公开条目拨公开会话端口
     * （peer.announce.pubBtPort，public-swarm §4.1）：两条通道分属两个 SessionManager，
     * 拨错会话就连错 swarm。
     */
    fun connectPeer(fileId: String, host: String, port: Int): Boolean

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
