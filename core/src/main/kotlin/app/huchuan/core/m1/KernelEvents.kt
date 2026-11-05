package app.huchuan.core.m1

/**
 * 内核 → 宿主（harness / 桌面 daemon / Android UI）的事件出口。
 *
 * 做成 abstract class 而不是 interface：Java 侧只覆盖自己关心的方法，
 * 不必实现全部（Kotlin interface 默认方法对纯 Java 实现类不总是可见）。
 */
abstract class KernelEvents {
    /** 结构化日志。字段名用 snake_case，值一律字符串，方便 grep。 */
    open fun log(event: String, fields: Map<String, String>) {}

    /** 入群完成（本机成功加入别人的群，或本机建群后第一次 ready）。 */
    open fun joined(group: GroupRuntime) {}

    /** 群里某个成员的名字记录更新。 */
    open fun memberJoined(group: GroupRuntime, deviceId: String, name: String) {}

    /** 文件清单新增一条。 */
    open fun fileAdded(group: GroupRuntime, entry: FileEntry) {}

    /** 本机下载完成（落盘且校验通过）。 */
    open fun fileCompleted(group: GroupRuntime, entry: FileEntry) {}

    /** 收到并入库一条他人的评论。 */
    open fun opReceived(group: GroupRuntime, op: OpRecord) {}
}

/** 加入流程的共享状态：一次 joinByTail8 只对应一个，候选之间逐个重试。 */
internal class JoinIntent(val invite: InviteCode) {
    @Volatile
    var attempt: java.util.concurrent.CompletableFuture<GroupRuntime>? = null

    @Volatile
    var lastError: String = ""
}

/** 协议 §8 的准入限速：join.req 失败 5 次/10 min/源 IP、30 次/h/群。 */
internal class JoinRateLimiter {
    private val lock = Any()
    private val byIp = HashMap<String, MutableList<Long>>()
    private val byGroup = HashMap<String, MutableList<Long>>()

    fun allowed(ip: String, gid: String, now: Long): Boolean = synchronized(lock) {
        prune(byIp[ip], now, IP_WINDOW_MS)
        prune(byGroup[gid], now, GROUP_WINDOW_MS)
        (byIp[ip]?.size ?: 0) < IP_LIMIT && (byGroup[gid]?.size ?: 0) < GROUP_LIMIT
    }

    fun recordFailure(ip: String, gid: String, now: Long) {
        synchronized(lock) {
            byIp.getOrPut(ip) { ArrayList() }.add(now)
            byGroup.getOrPut(gid) { ArrayList() }.add(now)
        }
    }

    private fun prune(list: MutableList<Long>?, now: Long, window: Long) {
        if (list == null) return
        list.removeIf { now - it > window }
    }

    companion object {
        const val IP_LIMIT = 5
        const val IP_WINDOW_MS = 10L * 60 * 1000
        const val GROUP_LIMIT = 30
        const val GROUP_WINDOW_MS = 60L * 60 * 1000
    }
}
