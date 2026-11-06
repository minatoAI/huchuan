package app.huchuan.kernel

import android.content.Context
import android.os.Build
import app.huchuan.core.LocalDevice
import app.huchuan.core.LocalDeviceStore
import app.huchuan.core.NotificationLevel

/**
 * 本机设置（P1 用户名 / P7 全局限速 / P4 单文件限速 / 通知档）。落 SharedPreferences，进程重启不丢。
 *
 * 两份限速都落盘、都由宿主在设置变化时立刻下发内核，内核自己不做持久化：
 * 全局限速是 session 级（[M1Runtime.applyGlobalLimits]），单文件限速挂在 torrent handle 上
 * （[M1GroupStore.setFileUploadLimit]）。通知档仍只有本地含义，等 M2 的通知通道。
 */
class AppPrefs(context: Context) : LocalDeviceStore {

    private val prefs = context.applicationContext.getSharedPreferences("huchuan", Context.MODE_PRIVATE)

    /** 设备名：Build.MODEL（如 Mi MIX 2S），P1 的默认值。 */
    val deviceName: String = (Build.MODEL ?: "Android").trim().ifEmpty { "Android" }

    override fun device(): LocalDevice = LocalDevice(
        deviceName = deviceName,
        userName = prefs.getString(KEY_USER_NAME, "").orEmpty(),
        uploadLimitKbps = prefs.getInt(KEY_UPLOAD_LIMIT, 0),
        downloadLimitKbps = prefs.getInt(KEY_DOWNLOAD_LIMIT, 0),
        unlimited = prefs.getBoolean(KEY_UNLIMITED, true),
    )

    override fun saveUserName(name: String) {
        prefs.edit().putString(KEY_USER_NAME, name.trim()).apply()
    }

    override fun setUploadLimit(kbps: Int) {
        prefs.edit().putInt(KEY_UPLOAD_LIMIT, kbps.coerceAtLeast(0)).apply()
    }

    override fun setDownloadLimit(kbps: Int) {
        prefs.edit().putInt(KEY_DOWNLOAD_LIMIT, kbps.coerceAtLeast(0)).apply()
    }

    override fun setUnlimited(unlimited: Boolean) {
        prefs.edit().putBoolean(KEY_UNLIMITED, unlimited).apply()
    }

    /**
     * P4 单文件限速（KB/s，0 = 不限）。
     *
     * 跟全局限速一样落盘：libtorrent 的 per-torrent limit 挂在 handle 上，进程一死就没了，
     * 所以重启后要靠这里把上一次的值重新下发（见 M1Runtime.reapplyStoredLimits）。
     */
    fun fileUploadLimitKbps(fileId: String): Int = prefs.getInt(KEY_FILE_LIMIT + fileId, 0)

    fun setFileUploadLimitKbps(fileId: String, kbps: Int) {
        prefs.edit().putInt(KEY_FILE_LIMIT + fileId, kbps.coerceAtLeast(0)).apply()
    }

    /** 所有设过单文件限速的条目（fileId → KB/s）；冷启动恢复时用。 */
    fun fileUploadLimits(): Map<String, Int> = prefs.all.entries
        .filter { it.key.startsWith(KEY_FILE_LIMIT) }
        .mapNotNull { entry ->
            val fileId = entry.key.removePrefix(KEY_FILE_LIMIT)
            val kbps = (entry.value as? Int) ?: return@mapNotNull null
            if (fileId.isEmpty() || kbps <= 0) null else fileId to kbps
        }
        .toMap()

    /** 群通知档；M1 只在 P7 里存，不产生系统通知。 */
    fun notificationLevel(groupId: String): NotificationLevel {
        val raw = prefs.getString(KEY_NOTIFY + groupId, null) ?: return NotificationLevel.SYSTEM_BUBBLE
        return NotificationLevel.entries.firstOrNull { it.name == raw } ?: NotificationLevel.SYSTEM_BUBBLE
    }

    fun setNotificationLevel(groupId: String, level: NotificationLevel) {
        prefs.edit().putString(KEY_NOTIFY + groupId, level.name).apply()
    }

    companion object {
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_UPLOAD_LIMIT = "upload_limit_kbps"
        private const val KEY_DOWNLOAD_LIMIT = "download_limit_kbps"
        private const val KEY_UNLIMITED = "unlimited"
        private const val KEY_NOTIFY = "notify."
        private const val KEY_FILE_LIMIT = "file_limit."
    }
}
