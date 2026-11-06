package app.huchuan.core.m1

import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.Alerts
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * 补齐 libtorrent4j `AlertType` 映射表里空着的槽位（**规避，不是修复**；根因与跟进见 docs/core.md §9）。
 *
 * ## 为什么需要
 *
 * `Alerts.NUM_ALERT_TYPES = libtorrent.getNum_alert_types()` —— 这个数字是**从 native 读的**，
 * 而 `AlertType.buildTable()` 是 libtorrent4j 发布时按当时的 libtorrent 生成的、只填到下标 104。
 * 两者的差值不是 0 时，`TABLE` 尾部就有 null 槽：
 *
 * | 侧 | num_alert_types | buildTable 填了 | TABLE 尾部 |
 * |---|---|---|---|
 * | Maven 的 Windows dll（2.1.0-39） | 105 | 0..104 | 无空洞 |
 * | **我们自编的 arm64 .so**（libtorrent RC_2_1 head 170663b） | **108** | 0..104 | **105/106/107 是 null** |
 *
 * 那三个是 `file_priorities_alert`(105) / `file_status_alert`(106) / `ip_ban_alert`(107)。
 * `AlertType.fromSwig(107)` 于是返回 null，而 `SessionManager.alertsLoop()` 里那句
 * `switch (AlertType.fromSwig(type))` 被 javac 编译成 `SwitchMap[fromSwig(type).ordinal()]` ——
 * null 上取 ordinal() 直接 NPE，告警线程 `SessionManager-alertsLoop` 当场死掉，进程随之被杀。
 *
 * 真正会自己冒出来的是 107 `ip_ban`：它的类别是 `ip_block`，由 `session_impl::ban_ip()` 发出，
 * 而 `ban_ip` 只在 peer_connection 收到「info-hash 由 DHT secret id 反推出来」的入向连接时被调用
 * —— 也就是公开会话开 DHT 跑一会儿就会遇上的那种探测流量。这就是「导入磁力约 2 分钟后崩溃」的成因。
 *
 * ## 修法
 *
 * 启动会话之前，把 `TABLE` 里的 null 槽填成 [AlertType.UNKNOWN]。UNKNOWN 是库自己给
 * 0..3 那几个「native 有、Java 没建模」的类型用的同一个值，所以：
 *
 * - 已知类型（0..104）一个都不动，映射结果与补之前完全一致；
 * - 真正未知的类型落到 UNKNOWN，`switch` 走 default，**不会**进任何 case；
 * - 我们的 `AlertListener` 只订了自己关心的几个类型（都 < 105），于是这些告警被安静跳过。
 *
 * **不是** try/catch 包住整个告警循环：异常照常会从 `alertsLoop` 里抛出来，只是产生 null 的源头被去掉了。
 *
 * ## 用这个类时的硬约束
 *
 * 1. **不要**给空洞里的 type id（105/106/107，以及将来 native 再多出来的）注册 `AlertListener`：
 *    `listeners[107] != null` 会让库去调 `Alerts.cast(a)`，而那一步查的是 `Alerts.TABLE`（同样有空洞），
 *    会在同一个循环里换一个地方再 NPE 一次。
 * 2. **不要**注册 catch-all 监听器（`types()` 返回 null）。同理：catch-all 分支也会调 `Alerts.cast`。
 * 3. [install] 必须在任何 `SessionManager.start()` **之前**跑完。
 */
object AlertTypeCompat {

    /** 一次安装的结果；给内核打日志、给 harness 断言、给真机诊断页读。 */
    class Report(
        /** native 报的告警种类数（Android 108，Windows 105）。 */
        @JvmField val nativeTypes: Int,
        /** `AlertType.TABLE` 的长度（正常情况下等于 nativeTypes）。 */
        @JvmField val tableLength: Int,
        /** 被填成 UNKNOWN 的下标，升序。空数组 = 本来就没有空洞。 */
        @JvmField val filledIds: IntArray,
        /** 安装失败的原因（"" = 正常）。失败时 [install] 不抛异常，但调用方要记日志。 */
        @JvmField val error: String,
    ) {
        val isClean: Boolean get() = error.isEmpty()

        override fun toString(): String =
            "native=" + nativeTypes + " table=" + tableLength +
                " filled=[" + filledIds.joinToString(",") + "]" +
                (if (error.isEmpty()) "" else " error=" + error)
    }

    /** 最近一次安装的结果（没装过时是一份 error 非空的占位）。 */
    @Volatile
    var report: Report = Report(-1, -1, IntArray(0), "not installed")
        private set

    /** 装成功过的结果；之后再调 [install] 直接返回它，保留「当时填了哪几个槽」这条证据。 */
    @Volatile
    private var installed: Report? = null

    /**
     * 把 `AlertType.TABLE` 的 null 槽填成 [AlertType.UNKNOWN]；幂等，重复调用只填一次。
     *
     * 失败（反射拿不到字段 / 类型对不上）时**不抛异常**，只把原因写进返回的 [Report]：
     * 这里抛出去等于让会话起不来，比原来的崩溃还糟；调用方必须把 error 打进日志。
     */
    @JvmStatic
    @Synchronized
    fun install(): Report {
        installed?.let { return it }
        val r = doInstall()
        report = r
        if (r.isClean) {
            installed = r
        }
        return r
    }

    /**
     * 纯函数：就地把 [table] 里的 null 槽填成 [unknown]，返回被填的下标（升序）。
     *
     * 抽出来是为了让 harness 能拿一张合成表直接断言（不必依赖 native 真的报 108）。
     */
    @JvmStatic
    fun fillTable(table: Array<AlertType?>, unknown: AlertType): IntArray {
        val filled = ArrayList<Int>(4)
        for (i in table.indices) {
            if (table[i] == null) {
                table[i] = unknown
                filled.add(i)
            }
        }
        return filled.toIntArray()
    }

    private fun doInstall(): Report {
        // 读这个常量会触发 Alerts 的静态初始化，也就把 native 库载进来；拿不到就是环境问题。
        val nativeTypes = try {
            Alerts.NUM_ALERT_TYPES
        } catch (t: Throwable) {
            return Report(-1, -1, IntArray(0), "num_alert_types unavailable: " + t)
        }

        val field = tableField()
            ?: return Report(nativeTypes, -1, IntArray(0), "AlertType.TABLE not found")

        val table = try {
            @Suppress("UNCHECKED_CAST")
            field.get(null) as? Array<AlertType?>
                ?: return Report(nativeTypes, -1, IntArray(0), "AlertType.TABLE is not AlertType[]")
        } catch (t: Throwable) {
            return Report(nativeTypes, -1, IntArray(0), "reflection failed: " + t)
        }

        val filled = fillTable(table, AlertType.UNKNOWN)
        return Report(nativeTypes, table.size, filled, "")
    }

    /**
     * 找 `AlertType.TABLE`。先按名字找，名字被混淆/改名时退回到「静态 + 元素类型是 AlertType」的字段，
     * 免得 R8 一开就静默失效。
     */
    private fun tableField(): Field? {
        val cls = AlertType::class.java
        val byName = try {
            cls.getDeclaredField("TABLE")
        } catch (_: NoSuchFieldException) {
            null
        }
        val candidate = byName
            ?: cls.declaredFields.firstOrNull { isAlertTypeArray(it) && Modifier.isStatic(it.modifiers) }
            ?: return null
        return try {
            candidate.isAccessible = true
            candidate
        } catch (_: Throwable) {
            null
        }
    }

    private fun isAlertTypeArray(f: Field): Boolean {
        val t = f.type
        return t.isArray && t.componentType == AlertType::class.java
    }
}
