package app.huchuan.daemon

import app.huchuan.core.m1.J
import app.huchuan.core.m1.Json
import app.huchuan.core.m1.jobj
import app.huchuan.core.m1.jnum
import app.huchuan.core.m1.jstr
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * daemon 自己的持久化（<data-dir>/settings.json）：
 * 用户名、全局限速、通知档、单文件限速、以及「这个文件当初是从哪个本地路径加的」。
 *
 * 这些不属于 core 的协议状态（core 的 state.json 只存群、成员、文件、评论），
 * 但 Web UI 的设置页要用，所以落在 daemon 侧。
 */
class SettingsStore(private val file: File, private val log: DaemonLogger) {

    @Volatile var userName: String = ""
        private set
    @Volatile var uploadLimitKbps: Int = 0
        private set
    @Volatile var downloadLimitKbps: Int = 0
        private set
    @Volatile var unlimited: Boolean = true
        private set

    /** 跨网发现开关（默认开）。关掉 = 只剩局域网 mDNS 与手动地址，内核不发任何 tracker 请求。 */
    @Volatile var trackerEnabled: Boolean = DEFAULT_TRACKER_ENABLED
        private set

    /** 自建 tracker 基址（默认已部署的 Worker）。空串 = 内核不装配 tracker。 */
    @Volatile var trackerBaseUrl: String = DEFAULT_TRACKER_BASE_URL
        private set

    private val notifications = ConcurrentHashMap<String, String>()
    private val uploadLimits = ConcurrentHashMap<String, Int>()
    private val seedPaths = ConcurrentHashMap<String, String>()

    /**
     * P4「移除」的本地隐藏标记（public-swarm §5.2）：只从本机列表消失，不广播、不删磁盘文件。
     *
     * 内核没有「本地移除」这个动作（条目还在 core 的清单里，别的成员照旧能看到），
     * 所以隐藏记在 daemon 侧；key 是 gid + "/" + fileId（两者都是定长字母数字，不会撞分隔符）。
     */
    private val hiddenFiles = ConcurrentHashMap<String, Boolean>()

    fun load() {
        if (!file.isFile) return
        try {
            val root = Json.parse(file.readText(Charsets.UTF_8))
            userName = root.str("userName") ?: ""
            uploadLimitKbps = (root.num("uploadLimitKbps") ?: 0L).toInt()
            downloadLimitKbps = (root.num("downloadLimitKbps") ?: 0L).toInt()
            unlimited = root.bool("unlimited") ?: true
            root.bool("trackerEnabled")?.let { trackerEnabled = it }
            root.str("trackerBaseUrl")?.let { trackerBaseUrl = normalizeBaseUrl(it) }
            root.obj("notifications")?.fields?.forEach { (gid, value) ->
                (value as? J.Str)?.let { notifications[gid] = it.value }
            }
            root.obj("uploadLimits")?.fields?.forEach { (fileId, value) ->
                (value as? J.Num)?.let { uploadLimits[fileId] = it.value.toInt() }
            }
            root.obj("seedPaths")?.fields?.forEach { (fileId, value) ->
                (value as? J.Str)?.let { seedPaths[fileId] = it.value }
            }
            root.obj("hiddenFiles")?.fields?.forEach { (key, value) ->
                if ((value as? J.Bool)?.value == true) hiddenFiles[key] = true
            }
        } catch (e: Throwable) {
            log.event("settings_load_error", mapOf("error" to e.toString()))
        }
    }

    fun save() {
        val body = jobj(
            "format" to jstr("huchuan-daemon-settings/1"),
            "userName" to jstr(userName),
            "uploadLimitKbps" to jnum(uploadLimitKbps.toLong()),
            "downloadLimitKbps" to jnum(downloadLimitKbps.toLong()),
            "unlimited" to app.huchuan.core.m1.jbool(unlimited),
            "trackerEnabled" to app.huchuan.core.m1.jbool(trackerEnabled),
            "trackerBaseUrl" to jstr(trackerBaseUrl),
            "notifications" to J.Obj(notifications.entries.associate { it.key to jstr(it.value) }),
            "uploadLimits" to J.Obj(uploadLimits.entries.associate { it.key to jnum(it.value.toLong()) }),
            "seedPaths" to J.Obj(seedPaths.entries.associate { it.key to jstr(it.value) }),
            "hiddenFiles" to J.Obj(hiddenFiles.keys.associate { it to app.huchuan.core.m1.jbool(true) }),
        )
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(Json.write(body), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(Json.write(body), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Throwable) {
            log.event("settings_save_error", mapOf("error" to e.toString()))
        }
    }

    fun applyPatch(patch: J): Boolean {
        var changed = false
        patch.str("userName")?.let {
            val trimmed = it.trim().take(20)
            if (trimmed != userName) {
                userName = trimmed
                changed = true
            }
        }
        patch.num("uploadLimitKbps")?.let {
            val value = it.toInt().coerceAtLeast(0)
            if (value != uploadLimitKbps) {
                uploadLimitKbps = value
                changed = true
            }
        }
        patch.num("downloadLimitKbps")?.let {
            val value = it.toInt().coerceAtLeast(0)
            if (value != downloadLimitKbps) {
                downloadLimitKbps = value
                changed = true
            }
        }
        patch.bool("unlimited")?.let {
            if (it != unlimited) {
                unlimited = it
                changed = true
            }
        }
        if (changed) save()
        return changed
    }

    /**
     * 写跨网发现的开关与地址。取值校验在 [Api] 做（要回 400 与具体 code），这里只管落盘。
     *
     * 内核只在启动时装配 tracker（core 的 setTrackerBaseUrl 在 start 之后会被忽略），所以改完
     * 要重启进程才生效；daemon 在 `/api/settings/tracker` 的回包里用 restartRequired 明说。
     * 返回是否真的改了。
     */
    fun applyTracker(enabled: Boolean?, baseUrl: String?): Boolean {
        var changed = false
        if (enabled != null && enabled != trackerEnabled) {
            trackerEnabled = enabled
            changed = true
        }
        if (baseUrl != null) {
            val normalized = normalizeBaseUrl(baseUrl)
            if (normalized != trackerBaseUrl) {
                trackerBaseUrl = normalized
                changed = true
            }
        }
        if (changed) save()
        return changed
    }

    fun notificationOf(groupId: String): String = notifications[groupId] ?: DEFAULT_NOTIFICATION

    fun setNotification(groupId: String, level: String): Boolean {
        val normalized = normalizeNotification(level) ?: return false
        notifications[groupId] = normalized
        save()
        return true
    }

    fun uploadLimitOf(fileId: String): Int = uploadLimits[fileId] ?: 0

    fun setUploadLimit(fileId: String, kbps: Int) {
        uploadLimits[fileId] = kbps.coerceAtLeast(0)
        save()
    }

    /**
     * 本地隐藏（P4「移除」）。只影响 daemon 的投影，core 的清单与别的成员都不受影响。
     * 与 [hideFile] 成对：落盘之后重启仍然隐藏。
     */
    fun isFileHidden(groupId: String, fileId: String): Boolean = hiddenFiles.containsKey(hiddenKey(groupId, fileId))

    fun hideFile(groupId: String, fileId: String) {
        if (hiddenFiles.put(hiddenKey(groupId, fileId), true) != null) return
        save()
    }

    private fun hiddenKey(groupId: String, fileId: String): String = groupId + "/" + fileId

    fun seedPathOf(fileId: String): String? = seedPaths[fileId]

    fun setSeedPath(fileId: String, path: String) {
        seedPaths[fileId] = path
        save()
    }

    companion object {
        const val DEFAULT_NOTIFICATION = "bubble"

        /**
         * 已部署的自建 tracker（Cloudflare Worker，见 docs/deploy-cloudflare.md）。
         * 换部署地址只改这一处：core 里没有任何写死的线上地址，daemon 是唯一的来源。
         */
        const val DEFAULT_TRACKER_BASE_URL = "https://huchuan-relay.autoai233.workers.dev"

        /** 跨网发现默认开：地址内置，用户不需要先配。 */
        const val DEFAULT_TRACKER_ENABLED = true

        /** 地址长度上限：只防手滑贴进来一整段文本，正常 Worker 地址不到 60 字符。 */
        const val MAX_TRACKER_URL_LENGTH = 300

        private val LEVELS = setOf("bubble", "in_app", "muted")

        fun normalizeNotification(level: String): String? = if (LEVELS.contains(level)) level else null

        /** 与 core 的 normalizeTrackerBaseUrl 同一口径：去首尾空白与尾部斜杠。 */
        fun normalizeBaseUrl(raw: String): String = raw.trim().trimEnd('/')

        /** 空串合法（= 不走 tracker）；非空必须是 http(s)。 */
        fun isValidTrackerBaseUrl(url: String): Boolean =
            url.isEmpty() ||
                ((url.startsWith("http://") || url.startsWith("https://")) && url.length <= MAX_TRACKER_URL_LENGTH)
    }
}
