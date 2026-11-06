package app.huchuan.link

import android.content.Context
import android.util.Log
import app.huchuan.core.link.LinkDefaults
import app.huchuan.core.link.LinkEvents
import app.huchuan.core.link.LinkProtocol
import app.huchuan.core.link.ShareFile
import app.huchuan.core.link.ShareRegistry
import app.huchuan.core.m1.DeviceIdentity
import app.huchuan.kernel.M1Runtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** P8 的状态口径：一行字 + 一个百分比。 */
enum class LinkPhase { CONNECTING, WAITING, INCOMING, SENDING, SENT, REVOKED, FAILED }

/**
 * 一条分享的实时状态。[failure] 是可 grep 的失败码（revoked / connect-failed / peer-left /
 * missing-file …），界面层负责翻成一句中文；不放中文是为了让控制器不依赖资源。
 */
data class LinkLive(
    val phase: LinkPhase = LinkPhase.CONNECTING,
    val percent: Int = -1,
    val failure: String = "",
    val visitorId: String = "",
)

/** 创建结果：ok 时带 shareId，失败时带原因码。 */
data class LinkCreate(val ok: Boolean, val shareId: String = "", val failure: String = "")

/** 撤销结果。 */
data class LinkRevoke(val ok: Boolean, val failure: String = "")

/**
 * 链接分享在 Android 侧的唯一入口（P4 / P8 / P9 共用）。
 *
 * 职责：
 *   1. 创建分享（ShareRegistry.create）并把本机记录落盘；
 *   2. 为每条有效分享维持一条 SignalClient + 按访客建 WebRTC 发送端（[LinkSession]）；
 *   3. 撤销（ShareRegistry.revoke）后关闭会话、把记录标 revoked；
 *   4. 给界面一个 StateFlow（哪条分享现在是什么状态）。
 *
 * 线程：create / revoke 是 suspend，调用方用 IO；会话内部自己管线程。
 */
class LinkShareController(private val runtime: M1Runtime) {

    private val app: Context = runtime.app

    /** 构建期配置的分享服务；非法（非 http(s)）时整层不可用（link-share §10.3）。 */
    val baseUrl: String = LinkProtocol.baseUrlOf(LinkDefaults.BASE_URL)

    val enabled: Boolean get() = baseUrl.isNotEmpty()

    private val store = LinkStore(File(runtime.dataDir, "link/shares.json"))

    private val _live = MutableStateFlow<Map<String, LinkLive>>(emptyMap())

    /** shareId → 实时状态；界面只读。 */
    val live: StateFlow<Map<String, LinkLive>> = _live.asStateFlow()

    private val _shares = MutableStateFlow(store.active())

    /** P9 的列表源：本机有效记录（撤销 / 过期即移出）。 */
    val shares: StateFlow<List<StoredShare>> = _shares.asStateFlow()

    private val sessions = ConcurrentHashMap<String, LinkSession>()
    private val sessionLock = Any()
    private val io = Executors.newSingleThreadExecutor { job ->
        Thread(job, "huchuan-link-io").apply { isDaemon = true }
    }

    private val prefs = app.getSharedPreferences("huchuan-link", Context.MODE_PRIVATE)

    @Volatile
    private var identity: DeviceIdentity? = null

    private val identityLock = Any()

    private val registryEvents = object : LinkEvents() {
        override fun log(event: String, fields: Map<String, String>) {
            val extra = if (fields.isEmpty()) {
                ""
            } else {
                fields.entries.joinToString(" ") { it.key + "=" + it.value }
            }
            Log.i(M1Runtime.TAG, "event=" + event + " " + extra)
        }
    }

    // ------------------------------------------------------------------ 查询

    /** P9 进入时重算有效列表（过期即移出）。 */
    fun refresh() {
        refreshShares()
    }

    fun share(shareId: String): StoredShare? = store.get(shareId)

    fun liveOf(shareId: String): LinkLive = _live.value[shareId] ?: LinkLive()

    /** 首次创建链接的一次性告知（link-share §12-6）：toast 只弹一次。 */
    fun networkAddressWarned(): Boolean = prefs.getBoolean(KEY_WARNED, false)

    fun markNetworkAddressWarned() {
        prefs.edit().putBoolean(KEY_WARNED, true).apply()
    }

    // ------------------------------------------------------------------ 创建

    /**
     * P4「分享链接」：取本机文件 → 算 SHA-256（浏览器校验用）→ 登记 → 落盘 → 起会话。
     * 失败只回一句原因码，不抛异常、不弹窗。
     */
    suspend fun create(groupId: String, fileId: String): LinkCreate = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext LinkCreate(false, failure = "not-configured")
        val device = identityOrNull() ?: return@withContext LinkCreate(false, failure = "not-started")
        val group = runtime.kernel.group(groupId) ?: return@withContext LinkCreate(false, failure = "missing-file")
        val entry = group.file(fileId) ?: return@withContext LinkCreate(false, failure = "missing-file")
        val path = group.savedFile(fileId) ?: return@withContext LinkCreate(false, failure = "missing-file")
        if (!path.isFile) return@withContext LinkCreate(false, failure = "missing-file")
        if (entry.size > 0L && path.length() != entry.size) {
            return@withContext LinkCreate(false, failure = "file-not-ready")
        }
        val size = path.length()
        if (size <= 0L) return@withContext LinkCreate(false, failure = "file-empty")
        val digest = try {
            sha256(path)
        } catch (t: Throwable) {
            Log.w(M1Runtime.TAG, "event=link_sha_failed error=" + t)
            return@withContext LinkCreate(false, failure = "file-read")
        }
        val registry = ShareRegistry(baseUrl, device, registryEvents)
        val files = listOf(ShareFile(fileId, entry.name, size, mimeOf(entry.name)))
        val created = registry.create(files, LinkDefaults.TTL_DEFAULT_SEC)
        if (!created.ok) {
            Log.i(
                M1Runtime.TAG,
                "event=link_create_failed reason=" + created.reason,
            )
            return@withContext LinkCreate(false, failure = created.reason.ifEmpty { "create" })
        }
        val now = System.currentTimeMillis()
        val record = StoredShare(
            shareId = created.shareId,
            token = created.token,
            baseUrl = created.baseUrl,
            createdAt = if (created.createdAt > 0L) created.createdAt else now,
            expiresAt = if (created.expiresAt > 0L) created.expiresAt else now + LinkDefaults.TTL_DEFAULT_SEC * 1000L,
            groupId = groupId,
            fileId = fileId,
            fileName = entry.name,
            fileSize = size,
            mime = mimeOf(entry.name),
            localPath = path.absolutePath,
            sha256 = digest,
        )
        store.upsert(record)
        refreshShares()
        startSession(record, device, path, digest)
        Log.i(
            M1Runtime.TAG,
            "event=link_created share=" + record.shareId.take(8) +
                " bytes=" + size +
                " expires_at=" + record.expiresAt,
        )
        LinkCreate(true, shareId = record.shareId)
    }

    /** P9 点行回到 P8 / P8 打开时确保会话在线；已经在线就什么都不做。 */
    fun ensureSession(shareId: String) {
        if (!enabled) return
        if (sessions.containsKey(shareId)) return
        val record = store.get(shareId) ?: return
        if (!record.active()) {
            updateLive(shareId, LinkLive(LinkPhase.REVOKED, failure = "revoked"))
            return
        }
        val file = File(record.localPath)
        if (record.localPath.isEmpty() || !file.isFile) {
            updateLive(shareId, LinkLive(LinkPhase.FAILED, failure = "missing-file"))
            return
        }
        val device = identityOrNull()
        if (device == null) {
            updateLive(shareId, LinkLive(LinkPhase.FAILED, failure = "not-started"))
            return
        }
        if (record.sha256.isNotEmpty()) {
            startSession(record, device, file, record.sha256)
            return
        }
        // 旧记录没存 SHA-256：后台补算，不占界面线程。
        io.execute {
            val digest = try {
                sha256(file)
            } catch (_: Throwable) {
                ""
            }
            if (digest.isEmpty()) {
                updateLive(shareId, LinkLive(LinkPhase.FAILED, failure = "file-read"))
                return@execute
            }
            store.upsert(record.copy(sha256 = digest))
            startSession(record.copy(sha256 = digest), device, file, digest)
        }
    }

    // ------------------------------------------------------------------ 撤销

    /**
     * P9「撤销」：签名 DELETE → 本机标 revoked → 关掉该分享的所有通道 → 从 P9 移出。
     * 服务端已经撤销（alreadyRevoked）也算成功。
     */
    suspend fun revoke(shareId: String): LinkRevoke = withContext(Dispatchers.IO) {
        val record = store.get(shareId) ?: return@withContext LinkRevoke(false, "not-found")
        if (!record.revoked) {
            if (!enabled) return@withContext LinkRevoke(false, "not-configured")
            val device = identityOrNull() ?: return@withContext LinkRevoke(false, "not-started")
            val registry = ShareRegistry(baseUrl, device, registryEvents)
            val result = registry.revoke(record.token, record.shareId)
            if (!result.ok) {
                Log.i(
                    M1Runtime.TAG,
                    "event=link_revoke_failed share=" + shareId.take(8) + " reason=" + result.reason,
                )
                return@withContext LinkRevoke(false, result.reason.ifEmpty { "revoke" })
            }
        }
        store.markRevoked(shareId)
        closeSession(shareId)
        updateLive(shareId, LinkLive(LinkPhase.REVOKED, failure = "revoked"))
        refreshShares()
        Log.i(M1Runtime.TAG, "event=link_revoked share=" + shareId.take(8))
        LinkRevoke(true)
    }

    // ------------------------------------------------------------------ 会话内部

    internal fun updateLive(shareId: String, live: LinkLive) {
        _live.update { it + (shareId to live) }
    }

    internal fun bumpVisit(shareId: String) {
        io.execute {
            store.bumpVisit(shareId)
            refreshShares()
        }
    }

    /** 信令收到 410 / bye gone：服务端已撤销，本机对齐（不再重连）。 */
    internal fun onServerRevoked(shareId: String) {
        store.markRevoked(shareId)
        refreshShares()
        io.execute { closeSession(shareId) }
    }

    private fun startSession(record: StoredShare, device: DeviceIdentity, file: File, digest: String) {
        if (!record.active()) return
        synchronized(sessionLock) {
            if (sessions.containsKey(record.shareId)) return
            val session = LinkSession(this, app, record, device, file, digest)
            sessions[record.shareId] = session
            updateLive(record.shareId, LinkLive(LinkPhase.CONNECTING))
            session.start()
        }
    }

    private fun closeSession(shareId: String) {
        val session = sessions.remove(shareId) ?: return
        session.close()
    }

    private fun refreshShares() {
        _shares.value = store.active()
    }

    private fun identityOrNull(): DeviceIdentity? = synchronized(identityLock) {
        identity ?: run {
            val file = File(runtime.dataDir, "identity.txt")
            if (!file.isFile) return null
            try {
                DeviceIdentity.loadOrCreate(file).also { identity = it }
            } catch (t: Throwable) {
                Log.w(M1Runtime.TAG, "event=link_identity_failed error=" + t)
                null
            }
        }
    }

    companion object {
        private const val KEY_WARNED = "network_address_warned"

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val bytes = digest.digest()
            val out = StringBuilder(bytes.size * 2)
            for (byte in bytes) out.append(String.format("%02x", byte.toInt() and 0xff))
            return out.toString()
        }

        private fun mimeOf(name: String): String = when (name.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "txt", "md" -> "text/plain"
            "json" -> "application/json"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }
}
