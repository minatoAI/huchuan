package app.huchuan.core.link

import app.huchuan.core.m1.B32
import app.huchuan.core.m1.DeviceIdentity
import app.huchuan.core.m1.Domains
import app.huchuan.core.m1.J
import app.huchuan.core.m1.Json
import app.huchuan.core.m1.jarr
import app.huchuan.core.m1.jnum
import app.huchuan.core.m1.jobj
import app.huchuan.core.m1.jstr
import app.huchuan.core.m1.toHex
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 分享注册表的客户端（worker/src/share.js，link-share.md §6/§8）。
 *
 * 只做三件对外的事：创建、列出、撤销；另加访客要用的 get（GET /api/share/{token}）。
 * 地址只来自配置：构造参数为空串时回落到 [LinkDefaults.BASE_URL]，非法时整层不可用
 * （enabled=false，等价于 §10.3 的「不部署 Worker」）。HTTP 用 HttpURLConnection，
 * Android 26 与桌面 JDK 都有，不引库；JSON 用内核自己的 Json。
 *
 * 四态（available / offline / revoked / expired）由 Worker 合成一次，这里不重复判过期；
 * 只有「请求本身没成功」才归到 ok=false 与 reason。
 */
class ShareRegistry(
    rawBaseUrl: String,
    private val identity: DeviceIdentity,
    private val events: LinkEvents,
) {

    constructor(identity: DeviceIdentity) : this(LinkDefaults.BASE_URL, identity, LinkEvents())

    constructor(identity: DeviceIdentity, events: LinkEvents) : this(LinkDefaults.BASE_URL, identity, events)

    constructor(rawBaseUrl: String, identity: DeviceIdentity) : this(rawBaseUrl, identity, LinkEvents())

    /** 规范化后的基址；空串表示配置非法。 */
    val baseUrl: String = LinkProtocol.baseUrlOf(rawBaseUrl)

    val configError: String = if (baseUrl.isEmpty()) "base_url_must_be_http" else ""

    val enabled: Boolean get() = baseUrl.isNotEmpty()

    /**
     * 创建分享。token 由客户端生成（§6）；调用方可以传一个已有的 26 字符值。
     * 失败不抛异常：看 [ShareCreateResult.ok] 与 reason（too-many / files-empty / network / http_xxx）。
     */
    fun create(files: List<ShareFile>?, ttlSec: Long): ShareCreateResult = create(files, ttlSec, null)

    fun create(files: List<ShareFile>?, ttlSec: Long, requestedToken: String?): ShareCreateResult {
        if (!enabled) return createFailure("not-configured")
        val token = if (requestedToken.isNullOrEmpty()) LinkProtocol.newToken() else B32.normalize(requestedToken)
        if (!LinkProtocol.validToken(token)) return createFailure("invalid-token")
        val issue = LinkProtocol.filesIssue(files)
        if (issue != null) return createFailure(issue)
        val safeFiles = files ?: return createFailure("files-empty")
        val ttl = if (ttlSec <= 0L) LinkDefaults.TTL_DEFAULT_SEC
        else ttlSec.coerceIn(LinkDefaults.TTL_MIN_SEC, LinkDefaults.TTL_MAX_SEC)

        val fields = LinkedHashMap<String, J>()
        fields["token"] = jstr(token)
        fields["deviceId"] = jstr(identity.deviceId)
        fields["pub"] = jstr(identity.pub64.toHex())
        val fileValues = ArrayList<J>(safeFiles.size)
        for (file in safeFiles) {
            fileValues.add(
                jobj(
                    "name" to jstr(file.name),
                    "size" to jnum(file.size),
                    "mime" to jstr(file.mime),
                ),
            )
        }
        fields["files"] = jarr(fileValues)
        fields["ttlSec"] = jnum(ttl)
        val body = Json.write(J.Obj(fields)).toByteArray(Charsets.UTF_8)

        val reply = try {
            request("POST", LinkDefaults.SHARE_PATH, body)
        } catch (e: Throwable) {
            return createFailure("network:" + (e.message ?: e.toString()))
        }
        if (reply.code == 429) return createFailure("too-many")
        if (reply.code !in 200..299) {
            val parsed = parseObject(reply.text())
            return createFailure(parsed?.str("error") ?: ("http_" + reply.code))
        }
        val json = parseObject(reply.text()) ?: return createFailure("bad-reply")
        if (json.bool("ok") != true) return createFailure(json.str("error") ?: "bad-reply")

        val shareId = json.str("shareId") ?: return createFailure("bad-reply")
        if (!RE_SHARE_ID.matches(shareId)) return createFailure("bad-reply")
        val tokenOut = B32.normalize(json.str("token") ?: token)
        if (tokenOut != token) return createFailure("token-mismatch")
        val createdAt = json.num("createdAt") ?: 0L
        val expiresAt = json.num("expiresAt") ?: 0L
        if (expiresAt <= createdAt) return createFailure("bad-reply")

        events.log(
            "share_create",
            mapOf(
                "share" to shareId.take(8),
                "files" to safeFiles.size.toString(),
                "ttl_sec" to ttl.toString(),
                "token_masked" to LinkProtocol.mask(token),
            ),
        )
        return ShareCreateResult(
            ok = true,
            reason = "",
            baseUrl = baseUrl,
            shareId = shareId,
            token = token,
            createdAt = createdAt,
            expiresAt = expiresAt,
            files = safeFiles,
            guestLimit = LinkDefaults.MAX_GUESTS,
        )
    }

    /** GET /api/shares?deviceId=：只用来把「服务端已撤销」的条目对齐回本机（§8）。 */
    fun list(): ShareListResult {
        if (!enabled) return ShareListResult(false, "not-configured", emptyList())
        val path = LinkDefaults.SHARES_PATH + "?deviceId=" + LinkProtocol.urlEncode(identity.deviceId)
        val reply = try {
            request("GET", path, null)
        } catch (e: Throwable) {
            return ShareListResult(false, "network:" + (e.message ?: e.toString()), emptyList())
        }
        val json = parseObject(reply.text())
        if (reply.code !in 200..299 || json == null) {
            return ShareListResult(false, json?.str("error") ?: ("http_" + reply.code), emptyList())
        }
        val shares = ArrayList<ShareSummary>()
        val values = json.arr("shares") ?: emptyList()
        for (value in values) {
            val shareId = value.str("shareId") ?: continue
            shares.add(
                ShareSummary(
                    shareId = shareId,
                    files = parseFiles(value.arr("files")),
                    createdAt = value.num("createdAt") ?: 0L,
                    expiresAt = value.num("expiresAt") ?: 0L,
                    visitCount = (value.num("visitCount") ?: 0L).toInt(),
                ),
            )
        }
        events.log("share_list", mapOf("shares" to shares.size.toString()))
        return ShareListResult(true, "", shares)
    }

    /**
     * 撤销：DELETE /api/share/{shareId 或 token}，body 带签名
     * signBytes("huchuan/revoke1/v1", [deviceId, token, ts, nonce])（§5-12）。
     * 必须签名，否则拿到链接的人能替你撤销；服务端不删记录，只标 revoked。
     */
    fun revoke(token: String, shareId: String?): ShareRevokeResult {
        if (!enabled) return ShareRevokeResult(false, "not-configured", shareId ?: "", 0L, false)
        val normToken = B32.normalize(token)
        if (!LinkProtocol.validToken(normToken)) return ShareRevokeResult(false, "invalid-token", shareId ?: "", 0L, false)
        val resolved = if (shareId != null && RE_SHARE_ID.matches(shareId)) shareId else normToken
        val ts = System.currentTimeMillis() / 1000L
        val nonce = LinkProtocol.newOpaqueId(12)
        val sig = identity.sign(
            Domains.LINK_REVOKE,
            LinkProtocol.revokeFields(identity.deviceId, normToken, ts, nonce),
        )
        val fields = LinkedHashMap<String, J>()
        fields["deviceId"] = jstr(identity.deviceId)
        fields["token"] = jstr(normToken)
        fields["ts"] = jnum(ts)
        fields["nonce"] = jstr(nonce)
        fields["sig"] = jstr(sig)
        val body = Json.write(J.Obj(fields)).toByteArray(Charsets.UTF_8)

        val reply = try {
            request("DELETE", LinkDefaults.SHARE_PATH + "/" + LinkProtocol.urlEncode(resolved), body)
        } catch (e: Throwable) {
            return ShareRevokeResult(false, "network:" + (e.message ?: e.toString()), resolved, 0L, false)
        }
        val json = parseObject(reply.text())
        if (reply.code !in 200..299 || json == null || json.bool("ok") != true) {
            return ShareRevokeResult(
                false,
                json?.str("error") ?: ("http_" + reply.code),
                resolved,
                0L,
                false,
            )
        }
        val outShare = json.str("shareId") ?: resolved
        val revokedAt = json.num("revokedAt") ?: 0L
        val already = json.bool("alreadyRevoked") ?: false
        events.log(
            "share_revoke",
            mapOf(
                "share" to outShare.take(8),
                "already_revoked" to already.toString(),
                "token_masked" to LinkProtocol.mask(normToken),
            ),
        )
        return ShareRevokeResult(true, "", outShare, revokedAt, already)
    }

    /** GET /api/share/{token}：访客在连信令之前拿 pub / deviceId / expiresAt（§5-4）。 */
    fun get(token: String): ShareLookupResult {
        val normToken = B32.normalize(token)
        if (!enabled) return lookupFailure("not-configured", normToken)
        if (!LinkProtocol.validToken(normToken)) return lookupFailure("invalid-token", normToken)
        val reply = try {
            request("GET", LinkDefaults.SHARE_PATH + "/" + LinkProtocol.urlEncode(normToken), null)
        } catch (e: Throwable) {
            return lookupFailure("network:" + (e.message ?: e.toString()), normToken)
        }
        if (reply.code == 404) return lookupFailure("not-found", normToken)
        val json = parseObject(reply.text()) ?: return lookupFailure("bad-reply", normToken)
        val status = json.str("status") ?: ""
        val usable = status == "available" || status == "offline"
        val files = parseFiles(json.arr("files"))
        return ShareLookupResult(
            ok = usable,
            status = status,
            reason = if (usable) "" else status.ifEmpty { "bad-reply" },
            baseUrl = baseUrl,
            shareId = json.str("shareId") ?: "",
            token = normToken,
            deviceId = LinkProtocol.normalizeDeviceId(json.str("deviceId")),
            pubHex = json.str("pub") ?: "",
            files = files,
            createdAt = json.num("createdAt") ?: 0L,
            expiresAt = json.num("expiresAt") ?: 0L,
            visitCount = (json.num("visitCount") ?: 0L).toInt(),
        ).also { result ->
            events.log(
                "share_get",
                mapOf(
                    "status" to result.status,
                    "files" to result.files.size.toString(),
                    "token_masked" to LinkProtocol.mask(normToken),
                ),
            )
        }
    }

    /* ---------------------------------------------------------------- 内部 */

    private fun createFailure(reason: String): ShareCreateResult {
        events.log("share_create_failed", mapOf("reason" to reason))
        return ShareCreateResult(
            ok = false,
            reason = reason,
            baseUrl = baseUrl,
            shareId = "",
            token = "",
            createdAt = 0L,
            expiresAt = 0L,
            files = emptyList(),
            guestLimit = LinkDefaults.MAX_GUESTS,
        )
    }

    private fun lookupFailure(reason: String, token: String): ShareLookupResult = ShareLookupResult(
        ok = false,
        status = reason,
        reason = reason,
        baseUrl = baseUrl,
        shareId = "",
        token = token,
        deviceId = "",
        pubHex = "",
        files = emptyList(),
        createdAt = 0L,
        expiresAt = 0L,
        visitCount = 0,
    )

    private fun parseObject(text: String): J? = try {
        Json.parse(text)
    } catch (_: Throwable) {
        null
    }

    private fun parseFiles(values: List<J>?): List<ShareFile> {
        if (values == null) return emptyList()
        val out = ArrayList<ShareFile>(values.size)
        for (value in values) {
            val name = value.str("name") ?: continue
            out.add(
                ShareFile(
                    fileId = "",
                    name = name,
                    size = value.num("size") ?: 0L,
                    mime = value.str("mime") ?: "",
                ),
            )
        }
        return out
    }

    private fun request(method: String, pathAndQuery: String, body: ByteArray?): HttpReply {
        val connection = URL(baseUrl + pathAndQuery).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("user-agent", "huchuan-link/1")
            connection.setRequestProperty("accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("content-type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream: InputStream? = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return HttpReply(code, bytes)
        } finally {
            try { connection.disconnect() } catch (_: Throwable) {}
        }
    }

    private class HttpReply(val code: Int, val body: ByteArray) {
        fun text(): String = body.toString(Charsets.UTF_8)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 8_000
        private val RE_SHARE_ID = Regex("^[0-9a-f]{32}$")
    }
}
