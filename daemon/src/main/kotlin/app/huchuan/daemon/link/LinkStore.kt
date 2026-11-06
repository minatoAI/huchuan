package app.huchuan.daemon.link

import app.huchuan.core.m1.J
import app.huchuan.core.m1.Json
import app.huchuan.core.m1.jnum
import app.huchuan.core.m1.jobj
import app.huchuan.core.m1.jstr
import app.huchuan.daemon.DaemonLogger
import java.io.File

/**
 * daemon 侧的链接分享记录（<data-dir>/links.json）。
 *
 * 为什么 daemon 自己存一份：core 的 ShareRegistry 只跟 Worker 说话，Worker 只回「这台设备
 * 还有哪些有效分享」，不回访客状态、不回本机文件路径、撤销/过期之后也查不到记录。
 * P9 的列表是「本机记录是事实源」（link-share.md §8），所以 daemon 存：
 *   - 服务端给的 shareId / token / createdAt / expiresAt（过期就靠它，不自己算 TTL）；
 *   - 本机文件路径（发送端从盘上读字节，链接分享不经过 BT）；
 *   - 被访问次数、最近一次传输的字节数与 SHA-256（列表的「被访问 n 次」与验收证据）。
 *
 * 写盘与 SettingsStore 同一套：tmp + rename，坏一次不影响 daemon 活着。
 */
class LinkStore(private val file: File, private val log: DaemonLogger) {

    /**
     * 一条分享。可变字段都只由 LinkService 在持有锁时改，读方（HTTP 线程）拿到的是快照值；
     * 单个字段用 @Volatile 保证可见性，不做整体加锁（回包不能等传输线程）。
     */
    class Record(
        val token: String,
        val shareId: String,
        val fileId: String,
        val fileName: String,
        val sizeBytes: Long,
        val mime: String,
        val path: String,
        val createdAt: Long,
        val expiresAt: Long,
        @Volatile var revokedAt: Long = 0L,
        @Volatile var expiredAt: Long = 0L,
        @Volatile var transfers: Int = 0,
        @Volatile var bytesSent: Long = 0L,
        @Volatile var sha256: String = "",
        @Volatile var lastTransferAt: Long = 0L,
        @Volatile var lastGuestSha256: String = "",
        @Volatile var lastGuestBytes: Long = 0L,
    )

    fun load(): List<Record> {
        if (!file.isFile) return emptyList()
        return try {
            val root = Json.parse(file.readText(Charsets.UTF_8))
            val out = ArrayList<Record>()
            for (value in root.arr("shares") ?: emptyList()) {
                val token = value.str("token") ?: continue
                val shareId = value.str("shareId") ?: continue
                val path = value.str("path") ?: continue
                out.add(
                    Record(
                        token = token,
                        shareId = shareId,
                        fileId = value.str("fileId") ?: "",
                        fileName = value.str("fileName") ?: "",
                        sizeBytes = value.num("sizeBytes") ?: 0L,
                        mime = value.str("mime") ?: "",
                        path = path,
                        createdAt = value.num("createdAt") ?: 0L,
                        expiresAt = value.num("expiresAt") ?: 0L,
                        revokedAt = value.num("revokedAt") ?: 0L,
                        expiredAt = value.num("expiredAt") ?: 0L,
                        transfers = (value.num("transfers") ?: 0L).toInt(),
                        bytesSent = value.num("bytesSent") ?: 0L,
                        sha256 = value.str("sha256") ?: "",
                        lastTransferAt = value.num("lastTransferAt") ?: 0L,
                        lastGuestSha256 = value.str("lastGuestSha256") ?: "",
                        lastGuestBytes = value.num("lastGuestBytes") ?: 0L,
                    ),
                )
            }
            out
        } catch (e: Throwable) {
            log.event("link_store_load_error", mapOf("error" to e.toString()))
            emptyList()
        }
    }

    fun save(records: Collection<Record>) {
        val list = ArrayList<J>(records.size)
        for (r in records) {
            list.add(
                jobj(
                    "token" to jstr(r.token),
                    "shareId" to jstr(r.shareId),
                    "fileId" to jstr(r.fileId),
                    "fileName" to jstr(r.fileName),
                    "sizeBytes" to jnum(r.sizeBytes),
                    "mime" to jstr(r.mime),
                    "path" to jstr(r.path),
                    "createdAt" to jnum(r.createdAt),
                    "expiresAt" to jnum(r.expiresAt),
                    "revokedAt" to jnum(r.revokedAt),
                    "expiredAt" to jnum(r.expiredAt),
                    "transfers" to jnum(r.transfers.toLong()),
                    "bytesSent" to jnum(r.bytesSent),
                    "sha256" to jstr(r.sha256),
                    "lastTransferAt" to jnum(r.lastTransferAt),
                    "lastGuestSha256" to jstr(r.lastGuestSha256),
                    "lastGuestBytes" to jnum(r.lastGuestBytes),
                ),
            )
        }
        val body = jobj(
            "format" to jstr("huchuan-daemon-links/1"),
            "shares" to app.huchuan.core.m1.jarr(list),
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
            log.event("link_store_save_error", mapOf("error" to e.toString()))
        }
    }
}
