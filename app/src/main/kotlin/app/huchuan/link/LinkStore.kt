package app.huchuan.link

import android.util.Log
import app.huchuan.kernel.M1Runtime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 本机的一条分享记录（link-share.md §8：本机记录是事实源，离线也能看）。
 *
 * fileId / groupId / localPath 只留本机：worker 只收 name / size / mime（ShareRegistry 的
 * ShareFile 同样不上线 fileId）。[link] 是二维码里装的那条链接。
 */
data class StoredShare(
    val shareId: String,
    val token: String,
    val baseUrl: String,
    val createdAt: Long,
    val expiresAt: Long,
    val groupId: String,
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val mime: String,
    val localPath: String,
    val sha256: String = "",
    val visitCount: Int = 0,
    val revoked: Boolean = false,
) {
    val link: String get() = baseUrl + "/#s=" + token

    fun active(now: Long = System.currentTimeMillis()): Boolean = !revoked && expiresAt > now
}

/**
 * 分享记录的落盘（filesDir/m1/link/shares.json）。
 *
 * 只做本机持久化：创建后马上落盘、撤销后标 revoked（不删行 —— 服务端也不删记录，
 * P9 只把 revoked / expired 从列表里过滤掉）。写文件走临时文件 + 原子替换，
 * 进程被杀不会留下半截 JSON。
 */
class LinkStore(private val file: File) {

    private val lock = Any()

    private val records: MutableList<StoredShare> = load()

    fun all(): List<StoredShare> = synchronized(lock) { records.toList() }

    fun active(now: Long = System.currentTimeMillis()): List<StoredShare> =
        synchronized(lock) { records.filter { it.active(now) }.sortedByDescending { it.createdAt } }

    fun get(shareId: String): StoredShare? = synchronized(lock) { records.firstOrNull { it.shareId == shareId } }

    fun upsert(record: StoredShare) {
        synchronized(lock) {
            val index = records.indexOfFirst { it.shareId == record.shareId }
            if (index >= 0) records[index] = record else records.add(record)
            saveLocked()
        }
    }

    fun markRevoked(shareId: String): StoredShare? = update(shareId) { it.copy(revoked = true) }

    fun bumpVisit(shareId: String): StoredShare? = update(shareId) { it.copy(visitCount = it.visitCount + 1) }

    fun setLocalPath(shareId: String, path: String): StoredShare? = update(shareId) { it.copy(localPath = path) }

    private fun update(shareId: String, change: (StoredShare) -> StoredShare): StoredShare? {
        synchronized(lock) {
            val index = records.indexOfFirst { it.shareId == shareId }
            if (index < 0) return null
            val next = change(records[index])
            records[index] = next
            saveLocked()
            return next
        }
    }

    private fun load(): MutableList<StoredShare> {
        if (!file.isFile) return ArrayList()
        return try {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            val out = ArrayList<StoredShare>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val shareId = item.optString("shareId")
                val token = item.optString("token")
                if (shareId.length != 32 || token.length != 26) continue
                out.add(
                    StoredShare(
                        shareId = shareId,
                        token = token,
                        baseUrl = item.optString("baseUrl"),
                        createdAt = item.optLong("createdAt"),
                        expiresAt = item.optLong("expiresAt"),
                        groupId = item.optString("groupId"),
                        fileId = item.optString("fileId"),
                        fileName = item.optString("fileName"),
                        fileSize = item.optLong("fileSize"),
                        mime = item.optString("mime", "application/octet-stream"),
                        localPath = item.optString("localPath"),
                        sha256 = item.optString("sha256"),
                        visitCount = item.optInt("visitCount"),
                        revoked = item.optBoolean("revoked"),
                    ),
                )
            }
            out
        } catch (t: Throwable) {
            Log.w(M1Runtime.TAG, "event=link_store_corrupt path=" + file.absolutePath + " error=" + t)
            ArrayList()
        }
    }

    private fun saveLocked() {
        try {
            file.parentFile?.mkdirs()
            val array = JSONArray()
            for (record in records) {
                array.put(
                    JSONObject()
                        .put("shareId", record.shareId)
                        .put("token", record.token)
                        .put("baseUrl", record.baseUrl)
                        .put("createdAt", record.createdAt)
                        .put("expiresAt", record.expiresAt)
                        .put("groupId", record.groupId)
                        .put("fileId", record.fileId)
                        .put("fileName", record.fileName)
                        .put("fileSize", record.fileSize)
                        .put("mime", record.mime)
                        .put("localPath", record.localPath)
                        .put("sha256", record.sha256)
                        .put("visitCount", record.visitCount)
                        .put("revoked", record.revoked),
                )
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(array.toString(), Charsets.UTF_8)
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (t: Throwable) {
            Log.w(M1Runtime.TAG, "event=link_store_write_failed path=" + file.absolutePath + " error=" + t)
        }
    }
}
