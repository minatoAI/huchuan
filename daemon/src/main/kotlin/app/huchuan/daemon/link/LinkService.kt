package app.huchuan.daemon.link

import app.huchuan.core.link.LinkDefaults
import app.huchuan.core.link.LinkEvents
import app.huchuan.core.link.LinkProtocol
import app.huchuan.core.link.LinkTimeouts
import app.huchuan.core.link.ShareCreateResult
import app.huchuan.core.link.ShareEndpoint
import app.huchuan.core.link.ShareFile
import app.huchuan.core.link.ShareRegistry
import app.huchuan.core.link.ShareRevokeResult
import app.huchuan.core.link.SignalClient
import app.huchuan.core.m1.DeviceIdentity
import app.huchuan.core.m1.FileEntry
import app.huchuan.core.m1.J
import app.huchuan.core.m1.jarr
import app.huchuan.core.m1.jbool
import app.huchuan.core.m1.jnum
import app.huchuan.core.m1.jobj
import app.huchuan.core.m1.jstr
import app.huchuan.daemon.Daemon
import app.huchuan.daemon.DaemonLogger
import dev.onvoid.webrtc.client.WebRtc
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 链接分享（W1）在 daemon 里的全部业务：创建 / 列表 / 状态 / 撤销 / 过期，以及「一个分享
 * 一条 PeerConnection」的发送端生命周期。
 *
 * 分工（与 core 的边界，见 docs/core.md §3.11）：
 *   - core 的 [ShareRegistry]：跟 Worker 登记 / 撤销 / 查询（HTTP）。
 *   - core 的 [SignalClient]：/signal 房间与状态机（offer/answer/ice、重连、90 s 空闲预算）。
 *   - 这里：[HttpTransport] 把 JDK WebSocket 接给 core；[WebRtcSender] 把 webrtc-java 的
 *     PeerConnection/DataChannel 接给 core 的事件；本机记录、过期、访客状态也在这里。
 *
 * 线程模型：
 *   - HTTP 线程只调本类的公开方法，不阻塞传输（创建时最多等一次 Worker 的 HTTP 往返）；
 *   - 每条分享一个 [SignalClient]（core 自己的 huchuan-link 线程）与一个会话对象；
 *   - 传输跑在 huchuan-link-worker 线程池上，一个访客一个 [WebRtcSender]；
 *   - 过期扫描跑在 huchuan-link-timer 上（1 s 一次，只做时间比较）。
 *
 * 日志（docs/daemon-api.md §5，脚本直接 grep 这四行）：
 *   link_share_created token=… link=…
 *   link_visitor_join    share=… visitor=…
 *   link_transfer_done   bytes=… sha256=…
 *   link_share_revoked   token=… remote=…
 */
class LinkService(
    private val daemon: Daemon,
    private val log: DaemonLogger,
) {

    private val identity: DeviceIdentity = DeviceIdentity.loadOrCreate(File(daemon.options.dataDir, "identity.txt"))
    private val registry = ShareRegistry(LinkDefaults.BASE_URL, identity, registryEvents())
    private val store = LinkStore(File(daemon.options.dataDir, "links.json"), log)
    private val transportFactory = HttpTransportFactory()

    private val records = LinkedHashMap<String, LinkStore.Record>()
    private val recordLock = Any()
    /** links.json 只有一个写入口：并发 save 会抢同一个 .tmp，必须串行。 */
    private val saveLock = Any()
    private val sessions = ConcurrentHashMap<String, Session>()

    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "huchuan-link-worker").apply { isDaemon = true }
    }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "huchuan-link-timer").apply { isDaemon = true }
    }

    private val runtimeLock = Any()

    @Volatile
    private var runtime: WebRtc? = null

    @Volatile
    private var runtimeError: String = ""

    @Volatile
    private var stopped: Boolean = false

    private var sweepFuture: ScheduledFuture<*>? = null

    // ------------------------------------------------------------------ 生命周期

    /** 读本机记录、给仍然有效的分享重新连信令、起过期扫描。daemon 启动时调一次。 */
    fun start() {
        val loaded = store.load()
        synchronized(recordLock) { for (record in loaded) records[record.token] = record }
        log.event(
            "link_ready",
            mapOf(
                "base" to registry.baseUrl,
                "available" to WebRtcProbe.jarPresent().toString(),
                "shares" to loaded.size.toString(),
                "active" to loaded.count { it.revokedAt == 0L && it.expiresAt > System.currentTimeMillis() }.toString(),
            ),
        )
        sweepFuture = scheduler.scheduleAtFixedRate(
            { runQuietly("sweep") { sweepExpired() } },
            1L,
            1L,
            TimeUnit.SECONDS,
        )
        for (record in loaded) {
            if (record.revokedAt > 0L || record.expiresAt <= System.currentTimeMillis()) continue
            if (!File(record.path).isFile) {
                log.event("link_session_skipped", mapOf("token" to LinkProtocol.mask(record.token), "reason" to "file-missing"))
                continue
            }
            openSession(record)
        }
    }

    fun stop() {
        stopped = true
        sweepFuture?.cancel(false)
        sweepFuture = null
        for (session in sessions.values) {
            try {
                session.close("daemon-stop")
            } catch (_: Throwable) {
                // 收尾阶段不让单条分享把进程拖住
            }
        }
        sessions.clear()
        scheduler.shutdownNow()
        workers.shutdownNow()
        // 这里**故意不调 WebRtc.close()**。
        // webrtc-java 1.0.0 的 PeerConnectionFactory.disposeInternal() 在「PeerConnection 刚被 close」
        // 的窗口里会崩在 webrtc-java-windows-x86_64.dll（实测：webrtc-client-close 线程，
        // EXCEPTION_ACCESS_VIOLATION；daemon 每次收尾必崩，见 artifacts/link-share-win/webrtc-dispose-crash.txt）。
        // 这个入口只在进程退出时走（Main 随后就 exitProcess），让操作系统回收原生内存比显式 dispose 安全；
        // PeerConnection 已经在上面逐条关掉了，数据面没有残留。
        runtime = null
        log.event("link_stopped", mapOf("shares" to size().toString(), "webrtc" to "keepalive-until-exit"))
    }

    // ------------------------------------------------------------------ 可用性

    /** 可用性探测结果：不可用时带一句可 grep 的原因（其余接口把它原样回给调用方）。 */
    class Availability(val available: Boolean, val reason: String)

    /**
     * 真可用 = webrtc-java 的类在 + 原生库第一次加载成功。
     *
     * 原生库只有 22.8 MB，按需加载：第一次调用本方法（一般是 POST /api/shares 或
     * GET /api/shares）才 WebRtc.create()，之后缓存；失败一次就固定降级，不再反复尝试。
     */
    fun availability(): Availability {
        if (!WebRtcProbe.jarPresent()) return Availability(false, "webrtc-java-missing")
        synchronized(runtimeLock) {
            if (runtimeError.isNotEmpty()) return Availability(false, runtimeError)
            val existing = runtime
            if (existing != null) return Availability(true, "")
            return try {
                val created = WebRtc.create()
                runtime = created
                log.event("link_webrtc_ready", mapOf("dll" to "webrtc-java-windows-x86_64"))
                Availability(true, "")
            } catch (e: Throwable) {
                runtimeError = "webrtc-native:" + describe(e)
                log.event("link_webrtc_unavailable", mapOf("reason" to runtimeError))
                Availability(false, runtimeError)
            }
        }
    }

    // ------------------------------------------------------------------ 创建 / 列表 / 状态 / 撤销

    /**
     * 创建一条分享。
     *
     * 流程与失败口径：
     *   - 先探可用性（不可用 503 E_LINK_UNAVAILABLE）；
     *   - 找到本机有副本的文件（否则 404/409）；
     *   - 让 Worker 登记（core 的 ShareRegistry，token 客户端生成、ttl 会被夹到 60 s–7 d）；
     *   - 落本机记录、开信令、推 WS。
     *
     * ttlSec 由 Api 按 expireDays 换算好传进来（两种情况都不在这里再判产品语义）。
     */
    fun create(fileId: String, ttlSec: Long): LinkCreateResult {
        val availability = availability()
        if (!availability.available) {
            return LinkCreateResult.Failure("E_LINK_UNAVAILABLE", "链接分享不可用：" + availability.reason, 503)
        }
        val lookup = localFile(fileId)
        if (lookup.file == null) {
            val status = when (lookup.reason) {
                "no-entry" -> 404
                else -> 409
            }
            val code = when (lookup.reason) {
                "no-entry" -> "E_NO_FILE"
                "no-copy" -> "E_NO_LOCAL_FILE"
                "changed" -> "E_FILE_CHANGED"
                else -> "E_NO_LOCAL_FILE"
            }
            return LinkCreateResult.Failure(code, lookup.message, status)
        }
        val path = lookup.file
        val size = path.length()
        val mime = mimeOf(path.name)
        val shareFile = ShareFile(fileId, path.name, size, mime)
        val created: ShareCreateResult = registry.create(listOf(shareFile), ttlSec)
        if (!created.ok) {
            val status = when {
                created.reason == "too-many" -> 429
                created.reason.startsWith("network:") -> 503
                else -> 409
            }
            val code = when {
                created.reason == "too-many" -> "E_LINK_TOO_MANY"
                created.reason.startsWith("network:") -> "E_LINK_REGISTRY"
                created.reason == "not-configured" -> "E_LINK_UNAVAILABLE"
                else -> "E_LINK_CREATE"
            }
            return LinkCreateResult.Failure(code, "创建分享失败：" + created.reason, status)
        }
        val record = LinkStore.Record(
            token = created.token,
            shareId = created.shareId,
            fileId = fileId,
            fileName = shareFile.name,
            sizeBytes = size,
            mime = mime,
            path = path.absolutePath,
            createdAt = created.createdAt,
            expiresAt = created.expiresAt,
        )
        synchronized(recordLock) { records[record.token] = record }
        save()
        log.event(
            "link_share_created",
            mapOf(
                "token" to record.token,
                "link" to linkOf(record),
                "share" to record.shareId.take(8),
                "file_id" to record.fileId,
                "name" to record.fileName,
                "size" to record.sizeBytes.toString(),
                "ttl_sec" to ((record.expiresAt - record.createdAt) / 1000L).toString(),
            ),
        )
        daemon.bump("link_share_created")
        openSession(record)
        return LinkCreateResult.Success(record)
    }

    fun list(): List<LinkStore.Record> = synchronized(recordLock) {
        records.values.sortedByDescending { it.createdAt }.toList()
    }

    /** token 大小写不敏感（B32 的字母集只有大写；长度/字符集不合法时自然查不到）。 */
    fun find(token: String): LinkStore.Record? {
        if (token.isEmpty()) return null
        val normalized = token.uppercase()
        return synchronized(recordLock) { records[normalized] ?: records[token] }
    }

    fun size(): Int = synchronized(recordLock) { records.size }

    /** DELETE /api/shares/{token}：本机先断流，再让 Worker 标 revoked。 */
    fun revoke(token: String): RevokeOutcome? {
        val record = find(token) ?: return null
        val already = record.revokedAt > 0L
        sessions.remove(record.token)?.close("revoke")
        var remote: ShareRevokeResult? = null
        if (!already) {
            remote = registry.revoke(record.token, record.shareId)
            record.revokedAt = System.currentTimeMillis()
        }
        save()
        log.event(
            "link_share_revoked",
            mapOf(
                "token" to record.token,
                "share" to record.shareId.take(8),
                "remote" to (if (already) "already" else if (remote?.ok == true) "true" else "false"),
                "reason" to (remote?.reason ?: ""),
            ),
        )
        daemon.bump("link_share_revoked")
        return RevokeOutcome(record, remote, already)
    }

    class RevokeOutcome(val record: LinkStore.Record, val remote: ShareRevokeResult?, val alreadyRevoked: Boolean)

    // ------------------------------------------------------------------ 视图

    fun listJson(): J.Obj {
        val availability = availability()
        return jobj(
            "shares" to jarr(list().map { shareJson(it) }),
            "available" to jbool(availability.available),
            "reason" to jstr(availability.reason),
            "baseUrl" to jstr(registry.baseUrl),
            "webRtc" to jstr(if (WebRtcProbe.jarPresent()) "present" else "missing"),
        )
    }

    fun shareJson(record: LinkStore.Record): J.Obj {
        val status = statusOf(record)
        val session = sessions[record.token]
        val visitor = session?.visitorJson()
        val progress = progressOf(record)
        return jobj(
            "token" to jstr(record.token),
            "shareId" to jstr(record.shareId),
            "link" to jstr(linkOf(record)),
            // 二维码就编码这一段（与 link 同值；#s= 只进 fragment，不发往服务器）
            "qrText" to jstr(linkOf(record)),
            "fileId" to jstr(record.fileId),
            "fileName" to jstr(record.fileName),
            "sizeBytes" to jnum(record.sizeBytes),
            "mime" to jstr(record.mime),
            "createdAt" to jnum(record.createdAt),
            "expiresAt" to jnum(record.expiresAt),
            "revokedAt" to jnum(record.revokedAt),
            "expiredAt" to jnum(record.expiredAt),
            "status" to jstr(status),
            "phase" to jstr(if (activeStatus(status)) "active" else "finished"),
            "active" to jbool(activeStatus(status)),
            "bytesSent" to jnum(progress.first),
            "bytesTotal" to jnum(record.sizeBytes),
            "progressPercent" to jnum(progress.second.toLong()),
            "transfers" to jnum(record.transfers.toLong()),
            "sha256" to jstr(record.sha256),
            "lastTransferAt" to jnum(record.lastTransferAt),
            "visitor" to (visitor ?: J.Null),
        )
    }

    fun statusOf(record: LinkStore.Record): String {
        if (record.revokedAt > 0L) return STATUS_REVOKED
        if (record.expiresAt > 0L && record.expiresAt <= System.currentTimeMillis()) return STATUS_EXPIRED
        if (record.expiredAt > 0L) return STATUS_EXPIRED
        if (!File(record.path).isFile) return STATUS_FILE_MISSING
        return sessions[record.token]?.phase ?: STATUS_WAITING
    }

    fun isActive(record: LinkStore.Record): Boolean = activeStatus(statusOf(record))

    fun linkOf(record: LinkStore.Record): String = registry.baseUrl + "/#s=" + record.token

    // ------------------------------------------------------------------ 会话

    /** 一条分享的会话：一条 SignalClient + 当前访客的 PeerConnection（没有访客时为空）。 */
    private inner class Session(val record: LinkStore.Record) {

        @Volatile var client: SignalClient? = null
        @Volatile var sender: WebRtcSender? = null
        @Volatile var phase: String = STATUS_WAITING
        @Volatile var visitorId: String = ""
        @Volatile var joinedAt: Long = 0L
        @Volatile var finishedAt: Long = 0L
        @Volatile var sentBytes: Long = 0L
        @Volatile var guestSha256: String = ""
        @Volatile var error: String = ""

        private val lock = Any()
        private val events = SessionEvents(this)
        private var heartbeat: ScheduledFuture<*>? = null
        private var resumeFuture: ScheduledFuture<*>? = null
        private var pendingAnswer: String? = null
        private val pendingIce = ArrayList<String>(4)

        @Volatile
        private var closing = false

        fun open() {
            synchronized(lock) {
                if (closing || stopped) return
                closeClient()
                val endpoint = ShareEndpoint(
                    registry.baseUrl,
                    record.shareId,
                    record.token,
                    record.expiresAt,
                    "",
                    "",
                    LinkDefaults.MAX_GUESTS,
                )
                val created = try {
                    SignalClient.forSharer(endpoint, identity, transportFactory, events, LinkTimeouts())
                } catch (e: Throwable) {
                    log.event("link_signal_create_failed", mapOf("share" to record.shareId.take(8), "error" to describe(e)))
                    return
                }
                client = created
                created.start()
                if (heartbeat == null) {
                    // 心跳只续核心的 90 s 空闲预算（传输完成、访客还挂在页面上时不至于被判超时）；
                    // 每次重新读 client，断线重连换了实例也仍然有效。
                    heartbeat = scheduler.scheduleAtFixedRate(
                        { runQuietly("heartbeat") { client?.noteProgress() } },
                        30L,
                        30L,
                        TimeUnit.SECONDS,
                    )
                }
            }
        }

        fun onIncoming(visitor: String) {
            if (closing || stopped) return
            synchronized(lock) {
                if (closing) return
                if (sender != null) {
                    log.event(
                        "link_visitor_busy",
                        mapOf("share" to record.shareId.take(8), "visitor" to visitor.take(8)),
                    )
                    return
                }
                phase = STATUS_CONNECTING
                visitorId = visitor
                joinedAt = System.currentTimeMillis()
                finishedAt = 0L
                sentBytes = 0L
                guestSha256 = ""
                error = ""
                log.event(
                    "link_visitor_join",
                    mapOf(
                        "share" to record.shareId.take(8),
                        "visitor" to visitor.take(8),
                        "token_masked" to LinkProtocol.mask(record.token),
                    ),
                )
                daemon.bump("link_visitor_join")
            }
            submit("transfer") { transfer() }
        }

        fun onAnswer(sdp: String) {
            val current = sender
            if (current == null) {
                synchronized(lock) { if (sender == null) pendingAnswer = sdp }
                return
            }
            current.deliverAnswer(sdp)
        }

        fun onIce(candidate: String) {
            val current = sender
            if (current == null) {
                synchronized(lock) { if (pendingIce.size < 32) pendingIce.add(candidate) }
                return
            }
            current.deliverIce(candidate)
        }

        fun onFailure(code: String) {
            if (closing) return
            // 访客已经进来过才叫「这次传输失败」；还没访客就断线只是信令没连上，分享本身仍然有效。
            // 已经传完（done）的分享不被后续的信令断开改写成失败：结果留着，等新访客再覆盖。
            if (phase != STATUS_DONE) {
                phase = if (joinedAt > 0L) STATUS_FAILED else STATUS_WAITING
                error = code
            }
            closeSender()
            if (code == "revoked" || code == "gone") {
                if (record.revokedAt == 0L) {
                    record.revokedAt = System.currentTimeMillis()
                    save()
                }
                log.event("link_share_revoked", mapOf("token" to record.token, "share" to record.shareId.take(8), "remote" to "true", "reason" to code))
                daemon.bump("link_share_revoked")
                close("revoked")
                return
            }
            scheduleResume()
        }

        fun visitorJson(): J.Obj? {
            if (joinedAt == 0L) return null
            return jobj(
                "id" to jstr(visitorId.take(8)),
                "status" to jstr(phase),
                "joinedAt" to jnum(joinedAt),
                "finishedAt" to jnum(finishedAt),
                "bytesSent" to jnum(sentBytes),
                "sha256" to jstr(guestSha256),
                "error" to jstr(error),
            )
        }

        fun close(reason: String) {
            synchronized(lock) {
                if (closing) return
                closing = true
                heartbeat?.cancel(false)
                heartbeat = null
                resumeFuture?.cancel(false)
                resumeFuture = null
                closeSender()
                closeClient()
            }
            sessions.remove(record.token, this)
            log.event(
                "link_session_closed",
                mapOf("share" to record.shareId.take(8), "reason" to reason, "phase" to phase),
            )
        }

        /* ------------------------------------------------------------ 传输 */

        private fun transfer() {
            val path = File(record.path)
            if (!path.isFile) {
                failTransfer("file-missing")
                return
            }
            val size = path.length()
            if (record.sizeBytes > 0L && size != record.sizeBytes) {
                failTransfer("file-changed")
                return
            }
            val sha = try {
                if (record.sha256.isEmpty()) {
                    val computed = WebRtcSender.sha256Of(path)
                    record.sha256 = computed
                    save()
                    computed
                } else {
                    record.sha256
                }
            } catch (e: Throwable) {
                failTransfer("hash:" + describe(e))
                return
            }
            val currentRuntime = runtime
            if (currentRuntime == null) {
                failTransfer("webrtc-not-ready")
                return
            }
            val created = try {
                WebRtcSender(currentRuntime, "huchuan-sharer", log, SenderOutbound(this))
            } catch (e: Throwable) {
                failTransfer("webrtc:" + describe(e))
                return
            }
            synchronized(lock) {
                if (closing) {
                    created.close()
                    return
                }
                sender = created
            }
            created.onProgress = { sent ->
                if (sent - sentBytes >= PROGRESS_STEP_BYTES || sent >= size) {
                    sentBytes = sent
                    daemon.bump("link_transfer_progress")
                }
            }
            created.onAck = { _ ->
                // 访客每 256 KiB 回一次 ack：核心的 90 s 空闲预算要跟着续，否则长文件会被判超时。
                client?.noteProgress()
            }
            deliverPending(created)
            val opened = try {
                created.open(CHANNEL_OPEN_TIMEOUT_MS)
            } catch (e: Throwable) {
                log.event("link_channel_error", mapOf("share" to record.shareId.take(8), "error" to describe(e)))
                false
            }
            if (!opened) {
                failTransfer("channel-open")
                return
            }
            client?.markTransferring()
            log.event(
                "link_transfer_start",
                mapOf(
                    "share" to record.shareId.take(8),
                    "bytes" to size.toString(),
                    "sha256" to sha,
                    "channel" to WebRtcSender.CHANNEL_LABEL,
                ),
            )
            val outcome = created.sendFile(path, record.fileName, size, record.mime, sha)
            if (outcome.ok) {
                val now = System.currentTimeMillis()
                synchronized(lock) {
                    phase = STATUS_DONE
                    finishedAt = now
                    sentBytes = outcome.bytes
                    guestSha256 = outcome.guestSha256
                    error = ""
                }
                record.transfers += 1
                record.bytesSent += outcome.bytes
                record.lastTransferAt = now
                record.lastGuestSha256 = outcome.guestSha256
                record.lastGuestBytes = outcome.guestBytes
                record.sha256 = sha
                save()
                log.event(
                    "link_transfer_done",
                    mapOf(
                        "bytes" to outcome.bytes.toString(),
                        "sha256" to sha,
                        "guest_sha256" to outcome.guestSha256,
                        "chunks" to outcome.chunks.toString(),
                        "ms" to outcome.sendMs.toString(),
                        "guest_bytes" to outcome.guestBytes.toString(),
                        "guest_ms" to outcome.guestTransferMs.toString(),
                        "share" to record.shareId.take(8),
                    ),
                )
                daemon.bump("link_transfer_done")
            } else {
                failTransfer(if (outcome.error.isEmpty()) "transfer-failed" else outcome.error)
            }
            closeSender()
        }

        private fun deliverPending(created: WebRtcSender) {
            val answer: String?
            val ice: List<String>
            synchronized(lock) {
                answer = pendingAnswer
                pendingAnswer = null
                ice = pendingIce.toList()
                pendingIce.clear()
            }
            if (answer != null) created.deliverAnswer(answer)
            for (candidate in ice) created.deliverIce(candidate)
        }

        private fun failTransfer(reason: String) {
            if (closing) return
            synchronized(lock) {
                phase = STATUS_FAILED
                finishedAt = System.currentTimeMillis()
                error = reason
            }
            log.event(
                "link_transfer_failed",
                mapOf("share" to record.shareId.take(8), "reason" to reason, "sent" to sentBytes.toString()),
            )
            daemon.bump("link_transfer_failed")
            closeSender()
        }

        private fun closeSender() {
            val current = synchronized(lock) {
                val value = sender
                sender = null
                value
            }
            try {
                current?.close()
            } catch (_: Throwable) {
                // 通道可能已经被对端关掉
            }
        }

        private fun closeClient() {
            val current = client
            client = null
            try {
                current?.close()
            } catch (_: Throwable) {
                // 同上
            }
        }

        private fun scheduleResume() {
            if (closing || stopped) return
            synchronized(lock) {
                if (resumeFuture != null || closing) return
                val delay = RESUME_DELAY_MS
                try {
                    resumeFuture = scheduler.schedule(
                        {
                            resumeFuture = null
                            if (!closing && !stopped) {
                                if (phase != STATUS_DONE) phase = STATUS_WAITING
                                open()
                            }
                        },
                        delay,
                        TimeUnit.MILLISECONDS,
                    )
                } catch (_: RejectedExecutionException) {
                    // 线程池已经关了：收尾中
                }
            }
        }
    }

    /** 每个会话一个事件对象：core 的 answer/ice 回调不带 shareId，只能按实例分流。 */
    private inner class SessionEvents(private val session: Session) : LinkEvents() {
        override fun log(event: String, fields: Map<String, String>) {
            this@LinkService.log.event(event, fields)
        }

        override fun incoming(shareId: String, visitorId: String, epPub: String) {
            session.onIncoming(visitorId)
        }

        override fun answer(sdp: String) {
            session.onAnswer(sdp)
        }

        override fun ice(candidate: String) {
            session.onIce(candidate)
        }

        override fun failure(code: String) {
            session.onFailure(code)
        }

    }

    /** webrtc-java 要往外发的 offer / candidate：接到 core 的 SignalClient 上（签名与队列都归它）。 */
    private inner class SenderOutbound(private val session: Session) : WebRtcSender.Outbound {
        override fun offer(sdp: String): Boolean = session.client?.sendOffer(sdp) ?: false

        override fun ice(candidate: String): Boolean = session.client?.sendIce(candidate) ?: false

        override fun peerState(state: String) {
            if (state == "failed") {
                log.event("link_peer_failed", mapOf("share" to session.record.shareId.take(8)))
            }
        }
    }

    private fun registryEvents(): LinkEvents = object : LinkEvents() {
        override fun log(event: String, fields: Map<String, String>) {
            this@LinkService.log.event(event, fields)
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun openSession(record: LinkStore.Record) {
        if (stopped || record.revokedAt > 0L || record.expiresAt <= System.currentTimeMillis()) return
        val session = Session(record)
        val previous = sessions.putIfAbsent(record.token, session)
        if (previous != null) return
        try {
            session.open()
        } catch (e: Throwable) {
            sessions.remove(record.token, session)
            log.event("link_session_open_failed", mapOf("share" to record.shareId.take(8), "error" to describe(e)))
        }
    }

    private fun sweepExpired() {
        if (stopped) return
        val now = System.currentTimeMillis()
        var changed = false
        for (record in snapshot()) {
            if (record.revokedAt > 0L || record.expiredAt > 0L || record.expiresAt > now) continue
            record.expiredAt = now
            changed = true
            sessions.remove(record.token)?.close("expired")
            log.event(
                "link_share_expired",
                mapOf("token" to record.token, "share" to record.shareId.take(8), "expires_at" to record.expiresAt.toString()),
            )
            daemon.bump("link_share_expired")
        }
        if (changed) save()
    }

    private class LocalLookup(val file: File?, val reason: String, val message: String)

    private fun localFile(fileId: String): LocalLookup {
        for (group in daemon.kernelOrNull()?.groups() ?: emptyList()) {
            val entry: FileEntry = daemon.visibleFile(group, fileId) ?: continue
            val path = daemon.settings.seedPathOf(fileId) ?: group.savedFile(fileId)?.absolutePath
            if (path.isNullOrEmpty()) {
                return LocalLookup(null, "no-copy", "这个文件本机还没有副本，先下载完再分享")
            }
            val file = File(path)
            if (!file.isFile) {
                return LocalLookup(null, "missing", "文件不在原来的位置：" + path)
            }
            if (entry.size > 0L && file.length() != entry.size) {
                return LocalLookup(null, "changed", "磁盘上的文件内容变了，和群里的条目对不上：" + path)
            }
            return LocalLookup(file, "", "")
        }
        return LocalLookup(null, "no-entry", "找不到这个文件：" + fileId)
    }

    private fun progressOf(record: LinkStore.Record): Pair<Long, Int> {
        val session = sessions[record.token]
        val sent = session?.sentBytes ?: 0L
        val total = if (record.sizeBytes > 0L) record.sizeBytes else 0L
        val percent = if (total > 0L) ((sent * 100L) / total).coerceIn(0L, 100L).toInt() else 0
        return sent to percent
    }

    private fun snapshot(): List<LinkStore.Record> = synchronized(recordLock) { records.values.toList() }

    private fun save() {
        val copy = snapshot()
        synchronized(saveLock) { store.save(copy) }
    }

    private fun submit(name: String, task: () -> Unit) {
        if (stopped) return
        try {
            workers.execute { runQuietly(name) { task() } }
        } catch (_: RejectedExecutionException) {
            // 收尾中
        }
    }

    private fun runQuietly(name: String, task: () -> Unit) {
        try {
            task()
        } catch (e: Throwable) {
            log.event("link_task_error", mapOf("task" to name, "error" to describe(e)))
        }
    }

    companion object {
        const val STATUS_WAITING = "waiting"
        const val STATUS_CONNECTING = "connecting"
        const val STATUS_TRANSFERRING = "transferring"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"
        const val STATUS_EXPIRED = "expired"
        const val STATUS_REVOKED = "revoked"
        const val STATUS_FILE_MISSING = "file-missing"

        private const val CHANNEL_OPEN_TIMEOUT_MS = 20_000L
        private const val RESUME_DELAY_MS = 3_000L
        private const val PROGRESS_STEP_BYTES = 1L * 1024 * 1024

        fun activeStatus(status: String): Boolean =
            status == STATUS_WAITING || status == STATUS_CONNECTING || status == STATUS_TRANSFERRING

        fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "7z" -> "application/x-7z-compressed"
            "rar" -> "application/vnd.rar"
            "txt" -> "text/plain; charset=utf-8"
            "json" -> "application/json"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }

        private fun describe(error: Throwable): String {
            var cause = error
            while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
            val message = cause.message
            val text = if (message.isNullOrEmpty()) cause.javaClass.simpleName else message
            val clean = text.replace('\n', ' ').replace('\r', ' ').trim()
            return if (clean.length <= 160) clean else clean.substring(0, 160)
        }
    }
}

/** 创建分享的结果：失败带 HTTP 状态码与可 grep 的 code，成功带本机记录。 */
sealed class LinkCreateResult {
    class Failure(val code: String, val message: String, val status: Int) : LinkCreateResult()

    class Success(val record: LinkStore.Record) : LinkCreateResult()
}
