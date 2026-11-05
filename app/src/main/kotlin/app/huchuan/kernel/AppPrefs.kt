package app.huchuan.kernel

import android.content.Context
import android.os.Build
import app.huchuan.core.LocalDevice
import app.huchuan.core.LocalDeviceStore
import app.huchuan.core.NotificationLevel

/**
 * 本机设置（P1 用户名 / P7 限速 / 通知档）。落 SharedPreferences，进程重启不丢。
 *
 * 全局限速与通知档在 M1 里只有本地含义：M1 内核按文件限速（协议 §4），
 * 通知档要等 M2 的通知通道，这里先把用户的选择存住。
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

    /** 群通知档；M1 只在 P7 里存，不产生系统通知。 */
    fun notificationLevel(groupId: String): NotificationLevel {
        val raw = prefs.getString(KEY_NOTIFY + groupId, null) ?: return NotificationLevel.SYSTEM_BUBBLE
        return NotificationLevel.entries.firstOrNull { it.name == raw } ?: NotificationLevel.SYSTEM_BUBBLE
    }

    fun setNotificationLevel(groupId: String, level: NotificationLevel) {
        prefs.edit().putString(KEY_NOTIFY + groupId, level.name).apply()
    }

    /** 群组未读计数（内存态）：收到别人的评论时 +1，进群组页清零。 */
    private val unread = HashMap<String, Int>()

    fun bumpUnread(groupId: String) {
        synchronized(unread) { unread[groupId] = (unread[groupId] ?: 0) + 1 }
    }

    fun clearUnread(groupId: String) {
        synchronized(unread) { unread.remove(groupId) }
    }

    fun unreadOf(groupId: String): Int = synchronized(unread) { unread[groupId] ?: 0 }

    companion object {
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_UPLOAD_LIMIT = "upload_limit_kbps"
        private const val KEY_DOWNLOAD_LIMIT = "download_limit_kbps"
        private const val KEY_UNLIMITED = "unlimited"
        private const val KEY_NOTIFY = "notify."
    }
}
