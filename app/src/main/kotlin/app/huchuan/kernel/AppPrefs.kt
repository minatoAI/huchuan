package app.huchuan.kernel

import android.content.Context
import android.os.Build
import app.huchuan.core.LocalDevice
import app.huchuan.core.LocalDeviceStore
import app.huchuan.core.NotificationLevel

/**
 * 本机设置（P1 用户名 / P7 全局限速 / 跨网发现 / P4 单文件限速 / 通知档）。落 SharedPreferences，进程重启不丢。
 *
 * 两份限速都落盘、都由宿主在设置变化时立刻下发内核，内核自己不做持久化：
 * 全局限速是 session 级（[M1Runtime.applyGlobalLimits]），单文件限速挂在 torrent handle 上
 * （[M1GroupStore.setFileUploadLimit]）。通知档（逐群三档）落盘后由宿主同步到该群的
 * 通知渠道并据此决定发不发系统通知（notify/GroupNotifier.kt）。
 *
 * 跨网发现（tracker）的两项不一样：内核只接受「start() 之前」给一次基址，
 * 所以这里只负责落盘，由 [M1Runtime.start] 在内核起来之前读一次（见 [trackerBaseUrlForKernel]）。
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

    /**
     * P4「移除」的本地隐藏标记（public-swarm §5.2）：**只影响本机列表**，不广播 file.del
     * （M2 才有）、不删磁盘文件。
     *
     * 内核没有「本地移除」这个动作，所以隐藏记在宿主侧：M1GroupStore 投影时跳过被隐藏的条目，
     * 重启后仍然不显示。屏蔽 infohash（§5.2 同一次操作顺带写的那张表）没有实现，见 docs/public-ui.md。
     */
    fun hideFile(groupId: String, fileId: String) {
        prefs.edit().putBoolean(KEY_HIDDEN + groupId + "." + fileId, true).apply()
    }

    fun isFileHidden(groupId: String, fileId: String): Boolean =
        prefs.getBoolean(KEY_HIDDEN + groupId + "." + fileId, false)

    // ------------------------------------------------------------------ 跨网发现（P7）

    /** tracker 基址。默认就是已部署的那个 Worker 地址（内核里没有写死任何线上地址）。 */
    var trackerBaseUrl: String
        get() = prefs.getString(KEY_TRACKER_BASE, DEFAULT_TRACKER_BASE).orEmpty().trim()
        set(value) {
            prefs.edit().putString(KEY_TRACKER_BASE, value.trim()).apply()
        }

    /** P7「跨网发现」开关，默认开。关掉 = 交给内核空串（内核口径：空串 = 整个功能关闭）。 */
    var trackerDiscoveryEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRACKER_DISCOVERY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_TRACKER_DISCOVERY, value).apply()
        }

    /**
     * 真正交给内核的基址：开关关掉、或地址为空，都是空串 —— 内核据此不建 TrackerService、
     * 一条请求都不发（docs/core.md §3.5「关闭时」）。
     */
    fun trackerBaseUrlForKernel(): String =
        if (trackerDiscoveryEnabled) trackerBaseUrl else ""

    /** 群通知档；M1 只在 P7 里存，不产生系统通知。 */
    fun notificationLevel(groupId: String): NotificationLevel {
        val raw = prefs.getString(KEY_NOTIFY + groupId, null) ?: return NotificationLevel.SYSTEM_BUBBLE
        return NotificationLevel.entries.firstOrNull { it.name == raw } ?: NotificationLevel.SYSTEM_BUBBLE
    }

    fun setNotificationLevel(groupId: String, level: NotificationLevel) {
        prefs.edit().putString(KEY_NOTIFY + groupId, level.name).apply()
    }

    /**
     * POST_NOTIFICATIONS 是否已经自动请求过一次（Android 13+）。
     *
     * 只自动问一次：用户拒绝之后就是明确的"不要系统通知"，再弹会被系统静默吞掉，
     * 也会变成骚扰。想要就自己去系统设置里开 —— 应用内未读那时已经正常工作。
     */
    var notifyPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_PERMISSION_ASKED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_NOTIFY_PERMISSION_ASKED, value).apply()
        }

    companion object {
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_UPLOAD_LIMIT = "upload_limit_kbps"
        private const val KEY_DOWNLOAD_LIMIT = "download_limit_kbps"
        private const val KEY_UNLIMITED = "unlimited"
        private const val KEY_NOTIFY = "notify."
        private const val KEY_NOTIFY_PERMISSION_ASKED = "notify_permission_asked"
        private const val KEY_FILE_LIMIT = "file_limit."
        private const val KEY_HIDDEN = "hidden."
        private const val KEY_TRACKER_BASE = "tracker_base_url"
        private const val KEY_TRACKER_DISCOVERY = "tracker_discovery"

        /**
         * 已部署的 relay 地址；只在宿主里出现这一次，内核不许写死。
         *
         * 入口走 **pages.dev**（Pages Functions 转发到 Worker）：国内网络下 workers.dev 被
         * DNS 污染 / SNI 阻断，pages.dev 可达（AGENTS §9 客户端默认地址一条）。
         */
        const val DEFAULT_TRACKER_BASE = "https://huchuan-a9f.pages.dev"
    }
}
