package app.huchuan.core.m1

import java.util.LinkedHashMap

/**
 * 本机「在做种的文件」集合 —— 全内核只有这一份来源。
 *
 * 缺陷现场（2026-11-06 真机）：`seedingFileCount()` 只数 `addFile` 加进去的那一份，下载完成
 * （`markCompleted`）不进去，于是一台下完转做种的设备自报 `seeds=0`，P6 上显示「做种 0」
 * （证据 docs/m1-device.md §6-6、artifacts/m1-device/screenshots/2026061214-final-members.png）。
 * 修法是把「谁在做种」这件事收成一份集合，进 / 出各只有一处入口，计数只有 [count] 一处：
 * `GroupRuntime.seedingFileCount()`、成员自报的 `peer.announce.seeds`、P3 的做种状态
 * 全部读它，不可能再出现两套口径各说各话。
 *
 * 口径：
 *  - 进：[GroupRuntime.addFile]（本机共享、挂进会话之后）、[GroupRuntime.markCompleted]
 *    （下载完成、数据落盘且校验通过）、[GroupRuntime.resumeSeeds] 里续种成功的那一支。
 *  - 出：[GroupRuntime.stopFile]（休眠 / 暂停）、巡查发现文件被移走删掉或数据不全
 *    （[GroupRuntime.reconcileSeeding]）、退群（`purgeForRemoval`）。
 *
 * 只做集合运算：不碰 BT 会话、不碰磁盘，所以 SelfTest 能直接拿它钉状态机，不需要起内核。
 * 线程安全（内部有锁）；GroupRuntime 在它自己的群锁里调用它，是单向依赖，不会成环。
 */
class SeedingLedger {

    private val lock = Any()

    /** fileId → 本地文件绝对路径。路径要一起存：重启续种得按原路径把数据找回来。 */
    private val seeding = LinkedHashMap<String, String>()

    /** 进集合。已经在里面返回 false —— 重复的完成事件不会把做种数算成两份。 */
    fun enter(fileId: String, localPath: String): Boolean {
        if (fileId.isEmpty()) return false
        synchronized(lock) {
            return seeding.put(fileId, localPath) == null
        }
    }

    /** 出集合（休眠 / 暂停 / 文件没了）。不在里面返回 false。 */
    fun leave(fileId: String): Boolean {
        synchronized(lock) {
            return seeding.remove(fileId) != null
        }
    }

    fun isSeeding(fileId: String): Boolean = synchronized(lock) { seeding.containsKey(fileId) }

    /** 这条做种的本地文件路径；不在集合里返回 ""。 */
    fun localPath(fileId: String): String = synchronized(lock) { seeding[fileId] ?: "" }

    /**
     * 本机做种数：只数还在群清单里的那些。
     *
     * 清单里已经没有的（条目被删、退群）不算 —— 否则对端会看到一个本机根本没有的文件的做种数。
     */
    fun count(known: Collection<String>): Int = synchronized(lock) {
        var n = 0
        for (fileId in seeding.keys) if (known.contains(fileId)) n++
        n
    }

    fun isEmpty(): Boolean = synchronized(lock) { seeding.isEmpty() }

    /** 集合里的 fileId（顺序稳定：LinkedHashMap 的插入顺序）。 */
    fun ids(): List<String> = synchronized(lock) { seeding.keys.toList() }

    /** 落盘用：[(fileId, 本地路径)]。 */
    fun entries(): List<Pair<String, String>> = synchronized(lock) {
        seeding.entries.map { it.key to it.value }
    }

    fun clear() {
        synchronized(lock) { seeding.clear() }
    }
}

/**
 * 重启续种（原缺口 G2）的判定口径。
 *
 * 只把「上次在做种、条目还在、inline torrent 还在、文件还在、大小对得上」的条目挂回会话；
 * 其余一律降级为休眠并记日志（不抛异常、不动磁盘）。判定写成纯函数，SelfTest 直接钉 ③④。
 *
 * **为什么是「直接加种」而不是「重新建种」**（二选一，这里选前者）：
 *  `FileEntry.torrent` 是这条 file.add 原始的 info 字典，按它挂种得到的 infohash 与群清单里
 *  其他人看到的完全一致；而「重新建种」（TorrentBuilder 再扫一遍文件）是按**当前盘上的内容**
 *  重算 —— 文件被截断或换掉时会得到一个别人的清单里根本不存在的新 infohash，等于悄悄发布
 *  一个没人认的文件，还会覆盖掉本地条目。所以续种只认原始 torrent 字节。
 *
 * 数据完整性交给 libtorrent 自己的片上校验：挂种后校验不通过（缺片 / 大小对不上）时，
 * 巡查 [GroupRuntime.reconcileSeeding] 会把它降级为休眠，绝不给对端发坏数据。
 */
object SeedRestore {

    /** 一条续种判定的结果。[reason] 写进日志（`seed_resume_failed reason=...`）。 */
    enum class Decision(val reason: String, val resume: Boolean) {
        RESUME("ok", true),
        NO_ENTRY("entry_missing", false),
        NO_TORRENT("no_torrent", false),
        FILE_MISSING("file_missing", false),
        SIZE_MISMATCH("size_mismatch", false),
    }

    /**
     * 纯函数：只看四个事实 —— 条目还在不在清单里、条目有没有 inline torrent、
     * 本地文件在不在、文件大小与条目是否吻合。
     */
    fun decide(
        hasEntry: Boolean,
        hasTorrent: Boolean,
        fileExists: Boolean,
        sizeMatches: Boolean,
    ): Decision = when {
        !hasEntry -> Decision.NO_ENTRY
        !hasTorrent -> Decision.NO_TORRENT
        !fileExists -> Decision.FILE_MISSING
        !sizeMatches -> Decision.SIZE_MISMATCH
        else -> Decision.RESUME
    }
}
