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

    private val notifications = ConcurrentHashMap<String, String>()
    private val uploadLimits = ConcurrentHashMap<String, Int>()
    private val seedPaths = ConcurrentHashMap<String, String>()

    fun load() {
        if (!file.isFile) return
        try {
            val root = Json.parse(file.readText(Charsets.UTF_8))
            userName = root.str("userName") ?: ""
            uploadLimitKbps = (root.num("uploadLimitKbps") ?: 0L).toInt()
            downloadLimitKbps = (root.num("downloadLimitKbps") ?: 0L).toInt()
            unlimited = root.bool("unlimited") ?: true
            root.obj("notifications")?.fields?.forEach { (gid, value) ->
                (value as? J.Str)?.let { notifications[gid] = it.value }
            }
            root.obj("uploadLimits")?.fields?.forEach { (fileId, value) ->
                (value as? J.Num)?.let { uploadLimits[fileId] = it.value.toInt() }
            }
            root.obj("seedPaths")?.fields?.forEach { (fileId, value) ->
                (value as? J.Str)?.let { seedPaths[fileId] = it.value }
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
            "notifications" to J.Obj(notifications.entries.associate { it.key to jstr(it.value) }),
            "uploadLimits" to J.Obj(uploadLimits.entries.associate { it.key to jnum(it.value.toLong()) }),
            "seedPaths" to J.Obj(seedPaths.entries.associate { it.key to jstr(it.value) }),
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

    fun seedPathOf(fileId: String): String? = seedPaths[fileId]

    fun setSeedPath(fileId: String, path: String) {
        seedPaths[fileId] = path
        save()
    }

    companion object {
        const val DEFAULT_NOTIFICATION = "bubble"
        private val LEVELS = setOf("bubble", "in_app", "muted")

        fun normalizeNotification(level: String): String? = if (LEVELS.contains(level)) level else null
    }
}
