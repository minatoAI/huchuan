package app.huchuan.notify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Android 13（API 33）起的通知运行时权限 POST_NOTIFICATIONS。
 *
 * 与本地网络权限（kernel/LocalNetworkPermission.kt）同一套写法：只在 API >= 33 上生效。
 * 系统常量直接引 [Manifest.permission]，它是编译期字面量，在 API < 33 的机器上也不会
 * NoSuchFieldError；老系统上 [isGranted] 恒为 true（根本没有这个权限概念）。
 *
 * 拒绝的后果只有一条：系统通知发不出去。应用内未读不受影响 —— 未读数由内核按读游标现算
 * （GroupRuntime.unreadCount），与通知权限没有关系。
 */
object NotifyPermission {

    /** 与内核日志同一个 tag：验收脚本只 grep 一个前缀。 */
    const val LOG_TAG = "huchuan/m1"

    const val PERMISSION: String = Manifest.permission.POST_NOTIFICATIONS

    /** API 33 = TIRAMISU。 */
    val enforced: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun isGranted(context: Context): Boolean = !enforced ||
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** 结构化日志：`event=notify_permission state=<x> sdk=<n> [k=v ...]`。 */
    fun log(state: String, detail: String? = null) {
        val tail = if (detail.isNullOrEmpty()) "" else " " + detail
        Log.i(LOG_TAG, "event=notify_permission state=" + state + " sdk=" + Build.VERSION.SDK_INT + tail)
    }
}
