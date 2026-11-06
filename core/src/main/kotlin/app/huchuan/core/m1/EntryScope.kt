package app.huchuan.core.m1

import app.huchuan.core.FileScope

/**
 * 同群内的条目去重（public-swarm §1.1，L1 裁决 5）：**同一 infohash 同时存在 private 与 public
 * 时只留 private**，后到的那条丢弃并记日志。
 *
 * 为什么需要：一个文件「私有发一次、公开发一次」在 BEP 27 下必然是两个 infohash（private 位写在
 * info 字典里），是两行；但同一个 infohash 被两个成员以不同 scope 各发一次时，清单里只该有一行 ——
 * 公开行允许 size=0、允许没有 .torrent，私有行才有可下载的内联种子。留 private 是不丢信息的那一支。
 *
 * 纯函数，不碰会话、不碰磁盘：SelfTest 在字节层钉的就是这段代码（两种到达顺序各一遍），
 * [GroupRuntime.onFileEntry] 与 [GroupRuntime.onFileListEntry] 都只调它，不会出现两套口径。
 */
object FileEntryMerge {

    /** 这一次合并做了什么。日志与断言都读它。 */
    enum class Action(val wire: String) {
        /** 清单里原来没有这个 fileId。 */
        ADD("add"),

        /** 同 scope 的重复条目：只更新元信息（name / magnet / torrent / sig）。 */
        KEEP("keep"),

        /** 原来那条是 public，来的是 private：整条换成 private（公开行让位）。 */
        PROMOTE("promote"),

        /** 原来那条是 private，来的是 public：丢弃来条，私有行不动。 */
        DROP("drop"),
    }

    /** [entry] 为 null 表示这条不该入库（DROP）。 */
    class Result(val entry: FileEntry?, val action: Action)

    /**
     * 合并一条新到的条目。
     *
     * 规则只有三条：
     *  - 没有旧条目 → 收下（ADD）。
     *  - 旧 private + 新 public → 丢（DROP）。
     *  - 旧 public + 新 private → 换成 private（PROMOTE）。
     *  - 其余（同 scope）→ 保留旧条目的 scope，更新元信息（KEEP）。
     */
    fun merge(existing: FileEntry?, incoming: FileEntry): Result {
        if (existing == null) return Result(incoming, Action.ADD)
        if (existing.scope == incoming.scope) {
            return Result(mergeMetadata(existing, incoming), Action.KEEP)
        }
        return if (incoming.scope == FileScope.PRIVATE) {
            // 公开行让位给私有行：private 条目带的是可下载的内联种子与真实尺寸。
            Result(withMetadata(incoming, existing), Action.PROMOTE)
        } else {
            Result(null, Action.DROP)
        }
    }

    /**
     * 同 scope 的元信息合并：非空 / 非零的一方胜出。
     *
     * 口径与 M1 的「幂等：同 fileId 只更新元信息」一致（协议 §7）；size / pieceSize 额外允许由
     * 「未知（0）→ 已知」回填，这正是公开条目拿到元数据之后本地回填的那一步。
     */
    private fun mergeMetadata(existing: FileEntry, incoming: FileEntry): FileEntry = existing.copy(
        name = if (incoming.name.isNotEmpty()) incoming.name else existing.name,
        size = if (incoming.size > 0) incoming.size else existing.size,
        pieceSize = if (incoming.pieceSize > 0) incoming.pieceSize else existing.pieceSize,
        torrent = if (incoming.torrent.isNotEmpty()) incoming.torrent else existing.torrent,
        magnet = if (incoming.magnet.isNotEmpty()) incoming.magnet else existing.magnet,
        sig = if (incoming.sig.isNotEmpty()) incoming.sig else existing.sig,
    )

    /** 升到 private：private 那条是权威，但它没带上的东西（比如 .torrent）取旧的补。 */
    private fun withMetadata(privateEntry: FileEntry, old: FileEntry): FileEntry = privateEntry.copy(
        torrent = if (privateEntry.torrent.isNotEmpty()) privateEntry.torrent else old.torrent,
        magnet = if (privateEntry.magnet.isNotEmpty()) privateEntry.magnet else old.magnet,
        size = if (privateEntry.size > 0) privateEntry.size else old.size,
        pieceSize = if (privateEntry.pieceSize > 0) privateEntry.pieceSize else old.pieceSize,
    )
}
