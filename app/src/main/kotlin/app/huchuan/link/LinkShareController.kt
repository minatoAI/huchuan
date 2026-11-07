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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * P8 的状态口径：一行字 + 一个百分比。
 *
 * [ENDED] 与 [REVOKED] 必须分开：REVOKED 是用户（或服务端）撤销，链接确实失效了；
 * ENDED 是「没人服务了」—— 进程被系统杀掉、前台服务被系统停掉。把后者说成前者是撒谎，
 * 说成 WAITING 是假装还在分享，两种都不允许。
 */
enum class LinkPhase { CONNECTING, WAITING, INCOMING, SENDING, SENT, REVOKED, ENDED, FAILED }

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

    private val _shares = MutableStateFlow(store.listed())

    /** P9 的列表源：本机有效记录（撤销 / 过期即移出）；已结束的留着，显示成不可用。 */
    val shares: StateFlow<List<StoredShare>> = _shares.asStateFlow()

    /**
     * 冷启动清扫：上一次进程留下的有效记录一律标成已结束。
     *
     * 前台服务能护住进程，但护不住「被用户从最近任务划掉 / 被系统杀 / 断电重启」——
     * 那几种情况下进程连一行日志都写不了。所以「还在不在分享」只在下一次启动时判定：
     * 进程都换了，之前那批分享早就没人服务，不能继续假装在分享（任务验收 4）。
     */
    init {
        val ended = store.endActive(REASON_PROCESS_RESTARTED)
        if (ended > 0) {
            _shares.value = store.listed()
            Log.i(
                M1Runtime.TAG,
                "event=link_end_orphans count=" + ended + " reason=" + REASON_PROCESS_RESTARTED,
            )
        }
    }

    /**
     * 保活：池子里有分享 = 前台服务在跑 = 链接现在真的能取件。
     * 加入 / 移除只发生在 [startSession] 与 [closeSession]，没有第三个入口。
     */
    private val keepAlive = LinkKeepAlive(
        app = app,
        recordOf = { id -> store.get(id) },
        onLost = { ids, reason -> onKeepAliveLost(ids, reason) },
    )

    private val sessions = ConcurrentHashMap<String, LinkSession>()
    private val sessionLock = Any()
    private val io = Executors.newSingleThreadExecutor { job ->
        Thread(job, "huchuan-link-io").apply { isDaemon = true }
    }

    /** 与界面无关的挂起动作（通知栏「结束分享」）：进程级作用域，不需要取消。 */
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    // ---------------------------------------------------------------- 保活（前台服务）

    /**
     * 通知栏「结束分享」：与 P8 页面内的「结束分享」是同一个动作 —— 撤销链接、关会话，
     * 池子空了服务随之停掉、通知消失。撤销要发网络请求，所以在 IO 上跑。
     *
     * 服务端撤销失败（离线、5xx）也必须停：用户已经点了结束，再开着服务就是在
     * 用户不知情的前提下继续分享。这种情况按本机已撤销处理，失败码进日志。
     */
    internal fun endFromNotification(shareId: String) {
        bg.launch {
            val result = revoke(shareId)
            if (result.ok) return@launch
            Log.i(
                M1Runtime.TAG,
                "event=link_end_offline share=" + shareId.take(8) + " reason=" + result.failure,
            )
            store.markRevoked(shareId)
            closeSession(shareId)
            updateLive(shareId, LinkLive(LinkPhase.REVOKED, failure = "revoked"))
            refreshShares()
        }
    }

    /** 服务 onDestroy / onTimeout → 系统把前台服务停了，这几条分享立刻变成不可用。 */
    internal fun onServiceDestroyed() {
        keepAlive.onServiceDestroyed()
    }

    /** 服务被系统停掉时，把还挂在池子里的分享全部收尾（P8/P9 立刻显示不可用）。 */
    private fun onKeepAliveLost(ids: List<String>, reason: String) {
        io.execute {
            for (id in ids) {
                store.markEnded(id, reason)
                closeSession(id)
                updateLive(id, LinkLive(LinkPhase.ENDED, failure = reason))
            }
            refreshShares()
            Log.i(M1Runtime.TAG, "event=link_ended reason=" + reason + " shares=" + ids.size)
        }
    }

    /** 内核按有效期耗尽等待预算（wait-expired）：链接到期，移出 P9 并关会话。 */
    internal fun onExpired(shareId: String) {
        refreshShares()
        io.execute { closeSession(shareId) }
    }

    private fun startSession(record: StoredShare, device: DeviceIdentity, file: File, digest: String) {
        if (!record.active()) return
        synchronized(sessionLock) {
            if (sessions.containsKey(record.shareId)) return@synchronized
            val session = LinkSession(this, app, record, device, file, digest)
            sessions[record.shareId] = session
            updateLive(record.shareId, LinkLive(LinkPhase.CONNECTING))
            session.start()
        }
        // 会话起来了 = 这条链接现在真的能被取件：前台服务与常驻通知跟着会话走。
        keepAlive.add(record)
    }

    private fun closeSession(shareId: String) {
        val session = sessions.remove(shareId)
        try {
            session?.close()
        } catch (t: Throwable) {
            Log.w(M1Runtime.TAG, "event=link_session_close_failed share=" + shareId.take(8) + " error=" + t)
        }
        keepAlive.remove(shareId)
    }

    private fun refreshShares() {
        _shares.value = store.listed()
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
        /** 冷启动清扫给已结束分享写的原因码（见 [LinkStore.endActive]）。 */
        const val REASON_PROCESS_RESTARTED = "process-restarted"

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
