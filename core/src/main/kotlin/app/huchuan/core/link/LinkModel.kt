package app.huchuan.core.link

import app.huchuan.core.m1.B32
import app.huchuan.core.m1.SignBytes
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

/**
 * 链接分享的协议常量与值对象（docs/design/link-share.md §6、worker/README.md）。
 *
 * 只放「服务端交互层」：分享登记、撤销、信令。WebRTC 实现（PeerConnection / DataChannel）
 * 由平台适配层提供，core 只通过 Transport 与信令服务器说话，不依赖 java.net.http / OkHttp。
 */

enum class LinkRole { SHARER, GUEST }

/**
 * 信令状态机（link-share.md §5）。
 *
 *   IDLE → SIGNALING → CONNECTED → TRANSFERRING → DONE
 *                  ↘          ↘            ↘
 *                   FAILED ← FAILED ← FAILED
 *
 * CONNECTED 的口径是「SDP offer/answer 交换完成」；CONNECTED → TRANSFERRING 由平台适配层在
 * DataChannel 打开后调 SignalClient.markTransferring 推进。掉线时 GUEST 可以从
 * CONNECTED/TRANSFERRING 回到 SIGNALING 重新 join（spike §4：Worker 不重放 share-incoming，
 * 访客必须重新 join），其余跳转一律走 FAILED，终态不再变。
 */
enum class LinkState { IDLE, SIGNALING, CONNECTED, TRANSFERRING, DONE, FAILED }

object LinkDefaults {
    /** L0 已部署的入口。构建期没配 base URL 时用这个；空串 = 显式关闭（§10.3）。 */
    const val BASE_URL = "https://huchuan-a9f.pages.dev"

    /** worker/src/index.js 的信令路径（别名 /ws，但客户端只认 /signal）。 */
    const val SIGNAL_PATH = "/signal"
    const val SHARE_PATH = "/api/share"
    const val SHARES_PATH = "/api/shares"

    /** 房间最多 1 个访客（link-share.md §12-3，worker/src/limits.js SIGNAL_ROOM_MAX_GUESTS）。 */
    const val MAX_GUESTS = 1

    /** 有效期 7 天（§12-1）。服务端会把入参夹到 [MIN, MAX]。 */
    const val TTL_DEFAULT_SEC = 7L * 24 * 3600
    const val TTL_MIN_SEC = 60L
    const val TTL_MAX_SEC = 7L * 24 * 3600

    /** 单条分享的文件边界（§12-4 / §12-8）。 */
    const val MAX_FILES = 10
    const val MAX_FILE_BYTES = 1L shl 30
    const val MAX_FILE_NAME_BYTES = 255

    /**
     * 访客口径的建链预算 15 s（§5-7）：从 join 起算，握手必须在这个窗口内完成。
     * 分享者只在「房里已经有访客」时按这个口径算；挂着等访客走 [SHARER_WAIT_FROM_EXPIRY]。
     */
    const val GUEST_CONNECT_BUDGET_MS = 15_000L

    /** 旧名，等价于 [GUEST_CONNECT_BUDGET_MS]（宿主以前把它当成「一个连接的总时长」）。 */
    const val SIGNAL_BUDGET_MS = GUEST_CONNECT_BUDGET_MS

    /** 分享者等待预算的默认值：0 = 按分享剩余有效期推（见 [LinkTimeouts.sharerWaitMs]）。 */
    const val SHARER_WAIT_FROM_EXPIRY = 0L

    /** 传输期同一访客 90 s 无进展即收回（§5-10）。 */
    const val TRANSFER_IDLE_MS = 90_000L

    /** 断线重连的退避（spike §4：没有 resume 帧，只能重新 join）：1/2/4/8/16 s，30 s 封顶。 */
    const val RECONNECT_BASE_MS = 1_000L
    const val RECONNECT_MAX_MS = 30_000L
    const val RECONNECT_MAX_ATTEMPTS = 5

    /** 进入终态后多久关传输（给最后一帧留窗口）。 */
    const val CLOSE_DELAY_MS = 100L
}

/**
 * 链接分享的三类预算。**访客口径和分享者口径是两件事，不要合成一个数**：
 *
 *   - [guestConnectBudgetMs]（默认 15 s，§5-7）：建链预算。访客从 join 起算；分享者在房里
 *     出现访客的那一刻起算。握手（offer / answer / DTLS）必须在这个窗口内完成。
 *   - [sharerWaitBudgetMs]（默认 0 = 按分享剩余有效期）：分享者「挂着等访客」的总时长。
 *     等待期不判 connect-failed；信令连接保持，掉线按 1/2/4/8/16 s 退避重连，可反复接入访客。
 *     访客在场时暂停计时，访客走了按剩余额度接着等；单次重连不续满、不耗尽这笔预算。
 *   - [transferIdleMs]（默认 90 s，§5-10）：传输期同一访客无进展即收回。
 *
 * 测试可以缩短。[reconnectDelayMs] 与 [sharerWaitMs] 是纯函数，harness 直接断言。
 */
class LinkTimeouts(
    val guestConnectBudgetMs: Long = LinkDefaults.GUEST_CONNECT_BUDGET_MS,
    val sharerWaitBudgetMs: Long = LinkDefaults.SHARER_WAIT_FROM_EXPIRY,
    val transferIdleMs: Long = LinkDefaults.TRANSFER_IDLE_MS,
    val reconnectBaseMs: Long = LinkDefaults.RECONNECT_BASE_MS,
    val reconnectMaxMs: Long = LinkDefaults.RECONNECT_MAX_MS,
    val maxReconnectAttempts: Int = LinkDefaults.RECONNECT_MAX_ATTEMPTS,
    val closeDelayMs: Long = LinkDefaults.CLOSE_DELAY_MS,
) {

    /**
     * 兼容旧写法：以前只有一个「建链预算」，宿主把分享剩余有效期塞进来当分享者的等待时长
     * （app/LinkSession.kt 的临时补丁）。现在它只表示 [sharerWaitBudgetMs]，
     * 访客建链预算固定走 [guestConnectBudgetMs]，宿主不必再自己塞 TTL。
     */
    constructor(signalBudgetMs: Long) : this(sharerWaitBudgetMs = signalBudgetMs)

    /**
     * 分享者的等待预算：显式 [sharerWaitBudgetMs] > 0 就用它，否则等于分享剩余有效期
     * （expiresAt - now），有效期缺失时回落到默认 TTL。永不为负、最少 1 ms ——
     * 已经过期的分享交给 Worker 回 410，不在这里判死。
     */
    @JvmOverloads
    fun sharerWaitMs(expiresAt: Long, nowMs: Long = System.currentTimeMillis()): Long {
        if (sharerWaitBudgetMs > 0L) return sharerWaitBudgetMs
        if (expiresAt > 0L) return (expiresAt - nowMs).coerceAtLeast(1L)
        return (LinkDefaults.TTL_DEFAULT_SEC * 1000L).coerceAtLeast(1L)
    }

    /** 第 attempt 次（1 基）重连的退避：默认 1/2/4/8/16 s，[reconnectMaxMs] 封顶。 */
    fun reconnectDelayMs(attempt: Int): Long {
        var delay = reconnectBaseMs.coerceAtLeast(1L)
        repeat((attempt - 1).coerceAtLeast(0)) {
            delay = if (delay > reconnectMaxMs / 2L) reconnectMaxMs else delay * 2L
        }
        return delay.coerceIn(1L, reconnectMaxMs)
    }
}

/**
 * 一条分享里的文件元信息。fileId 是本机内核的条目 id（P9 用它回到 P8 / 找到本地文件），
 * **不上线**：worker 只收 name / size / mime（share.js normalizeFiles）。
 */
class ShareFile(
    val fileId: String,
    val name: String,
    val size: Long,
    val mime: String,
) {
    constructor(name: String, size: Long, mime: String) : this("", name, size, mime)
}

/**
 * 建立一条信令连接所需的完整上下文：分享服务地址、shareId、token、有效期与分享者身份。
 * 分享者从 ShareRegistry.create 的结果拿；访客从 ShareRegistry.get 拿。
 */
class ShareEndpoint(
    val baseUrl: String,
    val shareId: String,
    val token: String,
    val expiresAt: Long,
    val sharerDeviceId: String,
    val sharerPubHex: String,
    val guestLimit: Int,
)

/** 分享者的创建结果。 */
class ShareCreateResult(
    val ok: Boolean,
    val reason: String,
    val baseUrl: String,
    val shareId: String,
    val token: String,
    val createdAt: Long,
    val expiresAt: Long,
    val files: List<ShareFile>,
    val guestLimit: Int,
) {
    /** 二维码/文本里装的那条链接：https://<域名>/#s=<26 字符>（link-share.md §6）。 */
    val link: String get() = if (token.isEmpty()) "" else baseUrl + "/#s=" + token

    fun endpoint(): ShareEndpoint? {
        if (!ok || shareId.isEmpty() || token.isEmpty()) return null
        return ShareEndpoint(baseUrl, shareId, token, expiresAt, "", "", guestLimit)
    }
}

/** 访客解析分享（GET /api/share/{token}）的结果。 */
class ShareLookupResult(
    val ok: Boolean,
    val status: String,
    val reason: String,
    val baseUrl: String,
    val shareId: String,
    val token: String,
    val deviceId: String,
    val pubHex: String,
    val files: List<ShareFile>,
    val createdAt: Long,
    val expiresAt: Long,
    val visitCount: Int,
) {
    /** 四态里的 available / offline 都能给出端点；revoked / expired 返回 null。 */
    fun endpoint(): ShareEndpoint? {
        if (!ok || shareId.isEmpty() || pubHex.isEmpty() || deviceId.isEmpty()) return null
        return ShareEndpoint(baseUrl, shareId, token, expiresAt, deviceId, pubHex, LinkDefaults.MAX_GUESTS)
    }
}

class ShareSummary(
    val shareId: String,
    val files: List<ShareFile>,
    val createdAt: Long,
    val expiresAt: Long,
    val visitCount: Int,
)

class ShareListResult(
    val ok: Boolean,
    val reason: String,
    val shares: List<ShareSummary>,
)

class ShareRevokeResult(
    val ok: Boolean,
    val reason: String,
    val shareId: String,
    val revokedAt: Long,
    val alreadyRevoked: Boolean,
)

/**
 * 宿主事件出口（与 m1 的 KernelEvents 同一风格：abstract/open，Java 只覆盖关心的方法）。
 * 日志字段一律 snake_case；不打 token 全文、不打 SDP、不打访客地址（§7）。
 */
open class LinkEvents {
    open fun log(event: String, fields: Map<String, String>) {}

    open fun stateChanged(from: LinkState, to: LinkState) {}

    /** 分享者收到访客 join（share-incoming）。 */
    open fun incoming(shareId: String, visitorId: String, epPub: String) {}

    /** 访客收到 offer（已完成签名与 DTLS 指纹校验）。 */
    open fun offer(sdp: String, sig: String) {}

    /** 分享者收到 answer。 */
    open fun answer(sdp: String) {}

    open fun ice(candidate: String) {}

    open fun failure(code: String) {}
}

/** 协议纯函数：token、Base32、URL、签名域字段、SDP 指纹。SelfTest 在字节层钉住。 */
object LinkProtocol {

    /** 空/空白 = 默认入口；非空且不是 http(s) = 配置错误（返回空串）。 */
    fun baseUrlOf(raw: String?): String {
        val trimmed = (raw ?: "").trim().trimEnd('/')
        if (trimmed.isEmpty()) return LinkDefaults.BASE_URL
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else ""
    }

    /** https → wss、http → ws，再拼 /signal。 */
    fun signalUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        val ws = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> "ws://" + base
        }
        return ws + LinkDefaults.SIGNAL_PATH
    }

    /** 128 bit token：16 字节 → 26 字符 Crockford B32（§6，客户端生成）。 */
    @JvmOverloads
    fun newToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return B32.encode(bytes)
    }

    fun validToken(raw: String?): Boolean {
        val text = B32.normalize(raw ?: "")
        if (text.length != 26) return false
        if (!text.all { B32.ALPHABET.indexOf(it) >= 0 }) return false
        return try {
            B32.decode(text).size == 16
        } catch (_: Throwable) {
            false
        }
    }

    /** 16 字符匿名 id（访客 ep / 撤销 nonce / WS nonce）：12 字节 base64url，无填充。 */
    @JvmOverloads
    fun newOpaqueId(bytes: Int = 12, random: SecureRandom = SecureRandom()): String {
        val raw = ByteArray(bytes)
        random.nextBytes(raw)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    /** 日志打码：只留前 4 字符（§7）。 */
    fun mask(secret: String?): String {
        if (secret.isNullOrEmpty()) return ""
        return if (secret.length <= 4) "****" else secret.substring(0, 4) + "****"
    }

    fun normalizeDeviceId(raw: String?): String = B32.normalize(raw ?: "")

    fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** SDP 里的 a=fingerprint: 整段（§5-6 的 dtlsFp）。没有则返回空串。 */
    private val FINGERPRINT = Regex("(?m)^a=fingerprint:\\s*(.+?)\\s*$")

    fun fingerprintOfSdp(sdp: String?): String =
        FINGERPRINT.find(sdp ?: "")?.groupValues?.get(1)?.trim() ?: ""

    fun offerFields(token: String, dtlsFp: String, expiresAt: Long): List<ByteArray> = listOf(
        SignBytes.str(token),
        SignBytes.str(dtlsFp),
        SignBytes.u64(expiresAt),
    )

    fun wsFields(deviceId: String, nonce: String, ts: Long): List<ByteArray> = listOf(
        SignBytes.str(deviceId),
        SignBytes.str(nonce),
        SignBytes.u64(ts),
    )

    fun revokeFields(deviceId: String, token: String, ts: Long, nonce: String): List<ByteArray> = listOf(
        SignBytes.str(deviceId),
        SignBytes.str(token),
        SignBytes.u64(ts),
        SignBytes.str(nonce),
    )

    /** 分享文件是否满足 worker 的边界；合法返回 null，否则返回一句可 grep 的原因。 */
    fun fileIssue(file: ShareFile): String? {
        if (file.name.isEmpty()) return "file-name-empty"
        if (file.name.toByteArray(Charsets.UTF_8).size > LinkDefaults.MAX_FILE_NAME_BYTES) return "file-name-too-long"
        if (file.size < 0 || file.size > LinkDefaults.MAX_FILE_BYTES) return "file-size"
        return null
    }

    fun filesIssue(files: List<ShareFile>?): String? {
        if (files.isNullOrEmpty()) return "files-empty"
        if (files.size > LinkDefaults.MAX_FILES) return "files-too-many"
        for (file in files) {
            val issue = fileIssue(file)
            if (issue != null) return issue
        }
        return null
    }
}
