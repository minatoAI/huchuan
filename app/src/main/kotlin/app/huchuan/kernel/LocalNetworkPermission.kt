package app.huchuan.kernel

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Android 17（API 37）的本地网络运行时权限。
 *
 * 权限名核实依据（不是凭印象）：
 * - `platforms/android-37.0/android.jar` 的 `android.Manifest$permission` 有常量
 *   `ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"`；
 * - 同目录 `data/api-versions.xml` 记 `ACCESS_LOCAL_NETWORK" since="37.0"/>`；
 * - OPPO（API 37）`pm list permissions -g -d -f` 报 `protectionLevel:dangerous`，
 *   即与应用同一套 requestPermissions / checkSelfPermission 的运行时权限。
 *
 * 为什么要有 [enforced]：targetSdk < 37 时系统给的是 legacy 隐式授权（本机实测：
 * APK 没声明该权限，dumpsys 里也是 granted=true）。升到 37 之后这份授权消失，
 * 局域网流量默认被拦。反过来，API < 37 的系统根本不认识这个权限名，
 * 在那儿请求只会立刻拿到 DENIED，把 P0 卡在「无权限」态——所以只在 API >= 37 生效。
 */
object LocalNetworkPermission {

    /** 与内核日志同一个 tag：验收脚本只 grep 一个前缀。 */
    const val LOG_TAG = "huchuan/m1"

    /**
     * 直接引 SDK 常量而不是抄字符串。`Manifest.permission.*` 是编译期常量，
     * javac/kotlinc 会把字面量内联进本类，所以在 API < 37 的机器上也不会有 NoSuchFieldError。
     */
    const val PERMISSION: String = Manifest.permission.ACCESS_LOCAL_NETWORK

    /** API 37 = `Build.VERSION_CODES.CINNAMON_BUN`（javap 自 android.jar 核实）。 */
    val enforced: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN

    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * 还能不能再弹一次系统授权框。
     *
     * false 有两种含义：从没请求过，或者用户已经选了「不再询问」——调用方用
     * 「已经请求过至少一次」把两者区分开，见 NetworkScreen 的 permanentDenial。
     */
    fun shouldShowRationale(activity: Activity?): Boolean =
        activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, PERMISSION)

    /** 去本应用的应用详情页（系统不再弹窗时唯一的出路）。 */
    fun openAppSettings(context: Context) {
        log("settings_requested", null)
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "event=local_network_permission state=settings_failed error=" + t)
        }
    }

    /**
     * 结构化日志：`event=local_network_permission state=<x> sdk=<n> [k=v ...]`。
     * 状态取值：required / request / granted / denied / revoked / settings_requested。
     */
    fun log(state: String, detail: String?) {
        val tail = if (detail.isNullOrEmpty()) "" else " " + detail
        Log.i(LOG_TAG, "event=local_network_permission state=" + state + " sdk=" + Build.VERSION.SDK_INT + tail)
    }
}

/** 从 Compose 的 LocalContext 拿宿主 Activity（shouldShowRequestPermissionRationale 要它）。 */
fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
