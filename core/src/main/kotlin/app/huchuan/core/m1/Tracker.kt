package app.huchuan.core.m1

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 跨网发现：自建 tracker（协议 §3.2）的客户端。
 *
 * 报给 tracker 的 info_hash 槽位是 groupHash20 —— 由邀请码 codeBytes 派生的 20 字节伪 swarm 标识，
 * **不是**文件的 infohash。文件 swarm 那一侧的 DHT / PEX / LSD 仍然全关（协议 §8），
 * 所以私有群的 infohash 一个字都不会出现在任何公网发现面上；对外只有 tracker 这一跳，
 * 而它的地址只来自宿主配置（[M1Kernel.trackerBaseUrl]），代码里没有、也不许有写死的线上地址。
 *
 * 分工：
 *   [TrackerProtocol]  纯计算（groupHash20 / passkey / peer_id / 日志打码），SelfTest 在字节层钉住；
 *   [TrackerHttp]      很薄的一层 HTTP（HttpURLConnection：Android 26 与桌面 JDK 都有，不引库）；
 *   [TrackerReply]     announce 的 bencode 应答解析（BEP 3）；
 *   [GroupTracker]     每个群一条线程：注册 → announce → 券商候选 → 按服务端 interval 续；
 *   [TrackerService]   内核里的装配点：baseUrl 为空就整个不存在（只失去跨网，局域网照常）。
 */
object TrackerProtocol {

    /** 协议 §3.2 的 passkey 轮换周期：「当前小时整数」。 */
    const val HOUR_MS = 3_600_000L

    /** 服务端没下发 interval 时的默认值（与 worker/src/limits.js 的 ANNOUNCE_INTERVAL_SEC 一致）。 */
    const val DEFAULT_INTERVAL_SEC = 600

    /** 下发值再小也不能比它更密（BEP 3 的 min interval 语义）。 */
    const val MIN_INTERVAL_SEC = 300

    /** 下发值再大也不超过它（服务端降频档的上限）。 */
    const val MAX_INTERVAL_SEC = 3_600

    /** 协议 §3.2 的 numwant。 */
    const val NUMWANT = 32

    /** peer_id 前缀：BEP 3 的「-XX1234-」约定，后 12 字符是本机 deviceId 的前缀。 */
    private const val PEER_ID_PREFIX = "-HC0100-"

    /**
     * groupHash20 = SHA-256(UTF8("huchuan/gh1/v1") ‖ codeBytes)[0..20]（协议 §2.2）。
     *
     * 用 codeBytes 而不是 secretBytes：前 8 字符的校验段也进来，换码即换 infoHash，
     * 与「轮换后旧 swarm 作废」的口径一致。
     */
    fun groupHash20(codeBytes: ByteArray): ByteArray =
        sha256(Domains.GROUP_HASH.toByteArray(Charsets.UTF_8), codeBytes).copyOf(20)

    fun groupHash20Hex(codeBytes: ByteArray): String = groupHash20(codeBytes).toHex()

    /** 当前小时整数 = floor(unix 毫秒 / 3600_000)，UTC，与本地时区无关。 */
    fun hourOf(epochMs: Long): Long = Math.floorDiv(epochMs, HOUR_MS)

    /**
     * passkey = hex(HMAC-SHA256(kGrp, UTF8("huchuan/trk1/v1") ‖ UTF8(gid) ‖ UTF8(小时整数十进制)))。
     *
     * 拼接口径与 worker 侧 `tokenHashHex` / `passkeyHashHex` 一致：domain 是一段普通 UTF-8 字节，
     * 不是 signBytes 的长度前缀格式；小时整数按十进制 ASCII 写，无前导零、无填充。
     * 服务端只存 SHA-256("huchuan/trk1/v1" ‖ 这个 hex 串) 的哈希，从不落 passkey 原文。
     */
    fun passkeyHex(kGrp: ByteArray, gid: String, hour: Long): String = hmacSha256(
        kGrp,
        Domains.TRACKER.toByteArray(Charsets.UTF_8),
        gid.toByteArray(Charsets.UTF_8),
        hour.toString().toByteArray(Charsets.UTF_8),
    ).toHex()

    /** 按时刻取 passkey（同一小时里恒定）。 */
    fun passkeyHexAt(kGrp: ByteArray, gid: String, epochMs: Long): String = passkeyHex(kGrp, gid, hourOf(epochMs))

    /**
     * BEP 3 的 peer_id：20 字节，`-HC0100-` + deviceId 前 12 字符。
     *
     * 用 deviceId 前缀而不是随机值：同一台设备重启后仍是同一个 peer_id，服务端按 peer_id 去重时
     * 不会把「上一轮的我」当成另一个 peer 留在 swarm 里。
     */
    fun peerId(deviceId: String): ByteArray {
        val tail = deviceId.filter { it.isLetterOrDigit() }.take(12).padEnd(12, '0')
        val text = PEER_ID_PREFIX + tail
        val out = text.toByteArray(Charsets.US_ASCII)
        return if (out.size == 20) out else out.copyOf(20)
    }

    /**
     * 日志里的 passkey 打码：只留前 4 个字符。
     *
     * 任何日志 / 事件 / 异常文案都不许出现 passkey 原文（协议 §8：日志不打码派生值），
     * 需要看「是不是同一把钥匙」时打码值就够了 —— 同一小时同一群，前 4 位相同即同一把。
     */
    fun mask(passkey: String?): String = when {
        passkey.isNullOrEmpty() -> ""
        passkey.length <= 4 -> "****"
        else -> passkey.substring(0, 4) + "****"
    }
}

/** 一次 announce 的有效内容（bencode 应答解析结果）。 */
internal class AnnounceReply(
    val intervalSec: Int,
    val minIntervalSec: Int,
    val peers: List<Candidate>,
    val failure: String,
    val externalIp: String,
)

/**
 * announce 应答的解析（BEP 3）。只读这几项：interval / min interval / peers / peers6 /
 * external ip / failure reason —— worker 多给的 complete、incomplete 这里不看。
 */
internal object TrackerReply {

    fun parse(body: ByteArray): AnnounceReply {
        val dict = BencodeReader(body).read() as? Map<*, *>
            ?: throw IllegalArgumentException("announce reply is not a bencoded dict")
        val failure = (dict["failure reason"] as? ByteArray)?.toString(Charsets.UTF_8) ?: ""
        val peers = ArrayList<Candidate>()
        collectPeers(dict["peers"], 4, peers)
        collectPeers(dict["peers6"], 6, peers)
        return AnnounceReply(
            intervalSec = intOf(dict["interval"], TrackerProtocol.DEFAULT_INTERVAL_SEC),
            minIntervalSec = intOf(dict["min interval"], TrackerProtocol.MIN_INTERVAL_SEC),
            peers = peers,
            failure = failure,
            externalIp = (dict["external ip"] as? ByteArray)?.toString(Charsets.UTF_8) ?: "",
        )
    }

    private fun intOf(value: Any?, fallback: Int): Int =
        (value as? Long)?.let { if (it in 1..Int.MAX_VALUE) it.toInt() else fallback } ?: fallback

    /** compact=1 时 peers 是 6 字节一条、peers6 是 18 字节一条；compact=0 时是字典列表。 */
    private fun collectPeers(value: Any?, family: Int, out: MutableList<Candidate>) {
        when (value) {
            is ByteArray -> {
                val stride = if (family == 4) 6 else 18
                var offset = 0
                while (offset + stride <= value.size) {
                    val host = if (family == 4) ipv4(value, offset) else ipv6(value, offset)
                    val port = ((value[offset + stride - 2].toInt() and 0xff) shl 8) or
                        (value[offset + stride - 1].toInt() and 0xff)
                    if (host.isNotEmpty() && port > 0) out.add(Candidate(host, port, "tracker"))
                    offset += stride
                }
            }
            is List<*> -> {
                for (item in value) {
                    val peer = item as? Map<*, *> ?: continue
                    val host = (peer["ip"] as? ByteArray)?.toString(Charsets.UTF_8) ?: continue
                    val port = (peer["port"] as? Long)?.toInt() ?: continue
                    if (host.isNotEmpty() && port > 0) out.add(Candidate(host, port, "tracker"))
                }
            }
            else -> {}
        }
    }

    private fun ipv4(src: ByteArray, offset: Int): String =
        (src[offset].toInt() and 0xff).toString() + "." +
            (src[offset + 1].toInt() and 0xff) + "." +
            (src[offset + 2].toInt() and 0xff) + "." +
            (src[offset + 3].toInt() and 0xff)

    private fun ipv6(src: ByteArray, offset: Int): String {
        val groups = IntArray(8)
        for (i in 0 until 8) {
            groups[i] = ((src[offset + i * 2].toInt() and 0xff) shl 8) or (src[offset + i * 2 + 1].toInt() and 0xff)
        }
        // 最长的一段 0 压成 ::（RFC 5952 的写法，够给 Candidate 当 host 用）
        var bestStart = -1
        var bestLen = 1
        var i = 0
        while (i < 8) {
            if (groups[i] != 0) {
                i++
                continue
            }
            var j = i
            while (j < 8 && groups[j] == 0) j++
            if (j - i > bestLen) {
                bestStart = i
                bestLen = j - i
            }
            i = j
        }
        if (bestStart < 0) return groups.joinToString(":") { Integer.toHexString(it) }
        val head = (0 until bestStart).joinToString(":") { Integer.toHexString(groups[it]) }
        val tail = (bestStart + bestLen until 8).joinToString(":") { Integer.toHexString(groups[it]) }
        return head + "::" + tail
    }
}

/** BEP 3 的最小 bencode 解码器：只认字典 / 列表 / 整数 / 字节串，够读 announce 应答。 */
internal class BencodeReader(private val src: ByteArray) {

    private var pos = 0

    fun read(): Any? {
        if (pos >= src.size) throw IllegalArgumentException("truncated bencode")
        return when (val ch = src[pos].toInt().toChar()) {
            'i' -> readInt()
            'l' -> readList()
            'd' -> readDict()
            in '0'..'9' -> readString()
            else -> throw IllegalArgumentException("bad bencode byte '" + ch + "' at " + pos)
        }
    }

    private fun readInt(): Long {
        pos++
        val start = pos
        while (pos < src.size && src[pos].toInt().toChar() != 'e') pos++
        if (pos >= src.size) throw IllegalArgumentException("unterminated bencode integer")
        val text = String(src, start, pos - start, Charsets.US_ASCII)
        pos++
        return text.toLongOrNull() ?: throw IllegalArgumentException("bad bencode integer '" + text + "'")
    }

    private fun readString(): ByteArray {
        val start = pos
        while (pos < src.size && src[pos].toInt().toChar() != ':') pos++
        if (pos >= src.size) throw IllegalArgumentException("unterminated bencode string length")
        val text = String(src, start, pos - start, Charsets.US_ASCII)
        val length = text.toIntOrNull() ?: throw IllegalArgumentException("bad bencode string length '" + text + "'")
        if (length < 0 || pos + 1 + length > src.size) throw IllegalArgumentException("truncated bencode string")
        pos++
        val out = src.copyOfRange(pos, pos + length)
        pos += length
        return out
    }

    private fun readList(): List<Any?> {
        pos++
        val out = ArrayList<Any?>()
        while (true) {
            if (pos >= src.size) throw IllegalArgumentException("unterminated bencode list")
            if (src[pos].toInt().toChar() == 'e') {
                pos++
                return out
            }
            out.add(read())
        }
    }

    private fun readDict(): Map<String, Any?> {
        pos++
        val out = LinkedHashMap<String, Any?>()
        while (true) {
            if (pos >= src.size) throw IllegalArgumentException("unterminated bencode dict")
            if (src[pos].toInt().toChar() == 'e') {
                pos++
                return out
            }
            val key = readString().toString(Charsets.UTF_8)
            out[key] = read()
        }
    }
}

/** HTTP 应答（tracker 的错误也是 HTTP 200 + failure reason，所以状态码与正文一起回）。 */
internal class HttpReply(val code: Int, val body: ByteArray) {
    fun text(): String = body.toString(Charsets.UTF_8)
}

/**
 * tracker 的 HTTP 面。地址只由宿主配置给，本类不认识任何默认线上地址：
 * [enabled] 为 false 时内核根本不会创建 announcer（配置为空 = 功能关闭）。
 */
internal class TrackerHttp(rawBaseUrl: String) {

    /** 规范化后的基址（尾部斜杠去掉）；非法或为空时是空串。 */
    val baseUrl: String

    /** 配置错误的原因（空串表示没问题）。 */
    val configError: String

    val enabled: Boolean get() = baseUrl.isNotEmpty() && configError.isEmpty()

    init {
        val trimmed = rawBaseUrl.trim().trimEnd('/')
        baseUrl = trimmed
        configError = when {
            trimmed.isEmpty() -> ""
            !(trimmed.startsWith("http://") || trimmed.startsWith("https://")) -> "base_url_must_be_http"
            else -> ""
        }
    }

    fun register(infoHashHex: String, gid: String, passkey: String, prevPasskey: String?): HttpReply {
        val fields = LinkedHashMap<String, J>()
        fields["infoHash"] = jstr(infoHashHex)
        fields["gid"] = jstr(gid)
        fields["passkey"] = jstr(passkey)
        if (prevPasskey != null) fields["prevPasskey"] = jstr(prevPasskey)
        val body = Json.write(J.Obj(fields)).toByteArray(Charsets.UTF_8)
        return request("POST", "/api/tracker/register", body, REGISTER_TIMEOUT_MS)
    }

    /**
     * announce。info_hash 与 peer_id 是二进制，按字节逐个 %XX 编码（大写十六进制）：
     * URLSearchParams / URLEncoder 会按 UTF-8 解坏它，所以整条查询串自己拼。
     * 事件名、端口、left=0（群级伪 swarm 没有「还剩多少」）、compact=1、numwant 都按协议 §3.2 写死。
     */
    fun announce(
        infoHash: ByteArray,
        peerId: ByteArray,
        port: Int,
        passkey: String,
        event: String,
        timeoutMs: Int = ANNOUNCE_TIMEOUT_MS,
    ): HttpReply {
        val query = "/announce?info_hash=" + percentEncode(infoHash) +
            "&peer_id=" + percentEncode(peerId) +
            "&port=" + port +
            "&left=0&compact=1&numwant=" + TrackerProtocol.NUMWANT +
            "&event=" + event +
            "&passkey=" + passkey
        return request("GET", query, null, timeoutMs)
    }

    private fun request(method: String, pathAndQuery: String, body: ByteArray?, timeoutMs: Int): HttpReply {
        val connection = URL(baseUrl + pathAndQuery).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("user-agent", "huchuan-core/1")
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

    companion object {
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val REGISTER_TIMEOUT_MS = 8_000
        private const val ANNOUNCE_TIMEOUT_MS = 8_000

        /** stopped 事件用短超时：内核正在关，不能为一句道别卡住退出。 */
        const val STOP_TIMEOUT_MS = 2_000

        private const val HEX = "0123456789ABCDEF"

        /** 十六进制固定的 %XX 编码；不对任何字符做「可以留原文」的优化，省掉一类转义错误。 */
        fun percentEncode(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size * 3)
            for (b in bytes) {
                val v = b.toInt() and 0xff
                sb.append('%').append(HEX[v ushr 4]).append(HEX[v and 0x0f])
            }
            return sb.toString()
        }
    }
}

/** 一次注册的结果。 */
internal class RegisterOutcome(
    val ok: Boolean,
    val conflict: Boolean,
    val reason: String,
    val created: Boolean,
    val rotated: Boolean,
)

/**
 * 一个群的 announce 线程：注册 → announce → 把 peers 交给群 → 按服务端下发的 interval 续。
 *
 * 三件事按顺序做，任一步失败都只退避重来，绝不抛出：
 *   1. passkey 与「服务端已记着的那一把」不一致时先注册（小时轮换就发生在这一跳）；
 *   2. 用服务端记着的那把 passkey announce（自己算值但还没注册成功时不 announce，否则必然 401）；
 *   3. 应答里的 peers 直接进群的候选表（[GroupRuntime.setTrackerCandidates]，§3.4 里优先级最低）。
 *
 * 断线留下的连续性缺口：worker 只认「上一把 passkey」的哈希，超过一小时没注册就得补证明。
 * kGrp 在手里，任意小时的 passkey 都算得出来，所以注册冲突时按最近 6 小时逐个补一次 ——
 * 这是协议「先到先得 + 每小时轮换」的固有代价，不是绕过。
 */
internal class GroupTracker(
    private val kernel: M1Kernel,
    private val group: GroupRuntime,
    private val http: TrackerHttp,
) {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    /** 服务端当前记着的 passkey（null = 还没注册成功）。 */
    @Volatile
    private var registeredPasskey: String? = null

    @Volatile
    private var intervalSec: Int = TrackerProtocol.DEFAULT_INTERVAL_SEC

    private var firstAnnounce = true

    private val gid: String get() = group.groupId

    private val infoHash: ByteArray by lazy { TrackerProtocol.groupHash20(group.meta.invite.codeBytes) }

    private val peerId: ByteArray by lazy { TrackerProtocol.peerId(kernel.deviceId) }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val worker = Thread { loop() }
        worker.isDaemon = true
        worker.name = "huchuan-tracker-" + gid.take(4)
        worker.start()
        thread = worker
        kernel.events.log(
            "tracker_started",
            mapOf(
                "gid" to gid,
                "base" to http.baseUrl,
                "info_hash" to infoHash.toHex(),
                "interval_hint" to TrackerProtocol.DEFAULT_INTERVAL_SEC.toString(),
            ),
        )
    }

    fun close() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread = null
        // 道个别：让别的成员别再来拨这个端点。内核正在关，短超时、失败就当没发生。
        val passkey = registeredPasskey
        val port = kernel.localMessagePort()
        if (passkey != null && port > 0) {
            try {
                http.announce(infoHash, peerId, port, passkey, "stopped", TrackerHttp.STOP_TIMEOUT_MS)
                kernel.events.log(
                    "tracker_stopped",
                    mapOf("gid" to gid, "passkey_masked" to TrackerProtocol.mask(passkey)),
                )
            } catch (_: Throwable) {
                // 关机途中发不出去是常态，不是错误
            }
        }
    }

    private fun loop() {
        while (running.get() && kernel.isStarted()) {
            var delay = RETRY_MS
            try {
                delay = tick(System.currentTimeMillis())
            } catch (e: InterruptedException) {
                break
            } catch (e: Throwable) {
                kernel.events.log("tracker_tick_error", mapOf("gid" to gid, "error" to e.toString()))
            }
            if (!pause(delay)) break
        }
    }

    /** 一拍：该轮换就轮换，然后 announce。返回距离下一拍的毫秒数。 */
    private fun tick(now: Long): Long {
        val port = kernel.localMessagePort()
        if (port <= 0) {
            kernel.events.log("tracker_no_message_port", mapOf("gid" to gid))
            return RETRY_MS
        }
        val hour = TrackerProtocol.hourOf(now)
        val current = TrackerProtocol.passkeyHex(group.meta.kGrp, gid, hour)
        if (registeredPasskey != current) ensureRegistered(hour, current)

        val passkey = registeredPasskey ?: return RETRY_MS
        val reply = try {
            http.announce(infoHash, peerId, port, passkey, if (firstAnnounce) "started" else "none")
        } catch (e: Throwable) {
            kernel.events.log(
                "tracker_announce_failed",
                mapOf("gid" to gid, "reason" to (e.message ?: e.toString()), "passkey_masked" to TrackerProtocol.mask(passkey)),
            )
            return RETRY_MS
        }
        if (reply.code !in 200..299) {
            kernel.events.log("tracker_announce_failed", mapOf("gid" to gid, "http" to reply.code.toString()))
            return RETRY_MS
        }
        val parsed = try {
            TrackerReply.parse(reply.body)
        } catch (e: Throwable) {
            kernel.events.log("tracker_announce_failed", mapOf("gid" to gid, "reason" to "bad_reply"))
            return RETRY_MS
        }
        if (parsed.failure.isNotEmpty()) {
            // bad passkey 说明服务端记着的不是我们手上这把（多半是别的成员先轮换了）：
            // 清掉记住的值，下一拍重新注册（注册时会走到「当前小时值 + 补连续性」那一步）。
            kernel.events.log(
                "tracker_announce_rejected",
                mapOf("gid" to gid, "reason" to parsed.failure, "passkey_masked" to TrackerProtocol.mask(passkey)),
            )
            if (parsed.failure.contains("passkey", ignoreCase = true)) registeredPasskey = null
            return RETRY_MS
        }

        intervalSec = parsed.intervalSec.coerceIn(
            maxOf(parsed.minIntervalSec, TrackerProtocol.MIN_INTERVAL_SEC),
            TrackerProtocol.MAX_INTERVAL_SEC,
        )
        var merged = 0
        try {
            group.setTrackerCandidates(parsed.peers)
            merged = group.candidates().size
        } catch (e: Throwable) {
            kernel.events.log("tracker_candidates_error", mapOf("gid" to gid, "error" to e.toString()))
        }
        kernel.events.log(
            "tracker_announce",
            mapOf(
                "gid" to gid,
                "peers" to parsed.peers.size.toString(),
                "candidates" to merged.toString(),
                "interval" to intervalSec.toString(),
                "external_ip" to parsed.externalIp,
                "passkey_masked" to TrackerProtocol.mask(passkey),
                "event" to if (firstAnnounce) "started" else "none",
            ),
        )
        firstAnnounce = false
        return nextDelay(now)
    }

    /**
     * 注册当前小时的 passkey。成功就把 [registeredPasskey] 换成它；
     * 冲突（服务端记着更早那一小时的值）时按最近 6 小时补连续性证明。
     */
    private fun ensureRegistered(hour: Long, current: String) {
        val remembered = registeredPasskey
        var outcome = safeRegister(current, remembered)
        if (outcome.ok) {
            registeredPasskey = current
            logRegistered(current, remembered, outcome)
            return
        }
        if (!outcome.conflict) {
            kernel.events.log(
                "tracker_register_failed",
                mapOf(
                    "gid" to gid,
                    "reason" to outcome.reason,
                    "passkey_masked" to TrackerProtocol.mask(current),
                ),
            )
            return
        }
        for (back in 1..MAX_ROTATION_BACKFILL_HOURS) {
            val prev = TrackerProtocol.passkeyHex(group.meta.kGrp, gid, hour - back)
            if (prev == remembered) continue
            outcome = safeRegister(current, prev)
            if (outcome.ok) {
                registeredPasskey = current
                logRegistered(current, prev, outcome)
                kernel.events.log(
                    "tracker_register_backfilled",
                    mapOf("gid" to gid, "hours_back" to back.toString()),
                )
                return
            }
            if (!outcome.conflict) break
        }
        kernel.events.log(
            "tracker_register_failed",
            mapOf(
                "gid" to gid,
                "reason" to outcome.reason,
                "passkey_masked" to TrackerProtocol.mask(current),
            ),
        )
    }

    private fun safeRegister(passkey: String, prev: String?): RegisterOutcome = try {
        val reply = http.register(infoHash.toHex(), gid, passkey, prev)
        val json = try { Json.parse(reply.text()) } catch (_: Exception) { null }
        val ok = reply.code in 200..299 && json?.bool("ok") == true
        val error = json?.str("error") ?: ""
        RegisterOutcome(
            ok = ok,
            conflict = reply.code == 409 || error == "conflict",
            reason = json?.str("reason") ?: error.ifEmpty { "http_" + reply.code },
            created = json?.bool("created") == true,
            rotated = json?.bool("rotated") == true,
        )
    } catch (e: Throwable) {
        RegisterOutcome(false, false, e.message ?: e.toString(), false, false)
    }

    private fun logRegistered(passkey: String, prev: String?, outcome: RegisterOutcome) {
        kernel.events.log(
            "tracker_register",
            mapOf(
                "gid" to gid,
                "passkey_masked" to TrackerProtocol.mask(passkey),
                "prev_masked" to TrackerProtocol.mask(prev),
                "created" to outcome.created.toString(),
                "rotated" to outcome.rotated.toString(),
                "hour" to TrackerProtocol.hourOf(System.currentTimeMillis()).toString(),
            ),
        )
    }

    /**
     * 下一拍等多久：服务端 interval 与「本小时还剩多少」取小（轮换要赶在整点之后第一拍完成），
     * 但至少 [MIN_TICK_MS] —— 时刻正好卡在边界上时不要空转。
     */
    private fun nextDelay(now: Long): Long {
        val hourEnd = (TrackerProtocol.hourOf(now) + 1) * TrackerProtocol.HOUR_MS
        val untilRotation = hourEnd - now + ROTATION_GRACE_MS
        return minOf(intervalSec * 1000L, untilRotation).coerceAtLeast(MIN_TICK_MS)
    }

    /** 分段睡：内核一关就能在 [PAUSE_STEP_MS] 内退出，不用等满 10 分钟。 */
    private fun pause(totalMs: Long): Boolean {
        var left = totalMs
        while (left > 0 && running.get() && kernel.isStarted()) {
            val step = minOf(left, PAUSE_STEP_MS)
            try {
                Thread.sleep(step)
            } catch (e: InterruptedException) {
                return false
            }
            left -= step
        }
        return running.get() && kernel.isStarted()
    }

    companion object {
        /** 失败重试的间隔。10 分钟的正常节拍下，失败不需要等满一个 interval。 */
        const val RETRY_MS = 30_000L

        /** 睡觉得分段，内核关掉时 ≈0.5 s 内能退。 */
        const val PAUSE_STEP_MS = 500L

        /** 即便 interval 很短也不空转。 */
        const val MIN_TICK_MS = 1_000L

        /** 整点之后的宽限：留 1.5 s 给「上小时的请求刚回来」这种情况。 */
        const val ROTATION_GRACE_MS = 1_500L

        /** 断线后最多补几小时的连续性证明（协议的先到先得固有代价）。 */
        const val MAX_ROTATION_BACKFILL_HOURS = 6L
    }
}

/**
 * 内核里的 tracker 装配点：一个 [GroupTracker] 一个群。
 *
 * [enabled] 为 false（baseUrl 为空 / 非法）时它根本不会被创建 —— 于是「跨网发现」这一档
 * 静默缺席，局域网与手动 IP:port 完全照旧，也不会产生任何网络请求。
 */
internal class TrackerService(private val kernel: M1Kernel, rawBaseUrl: String) {

    private val http = TrackerHttp(rawBaseUrl)
    private val trackers = ConcurrentHashMap<String, GroupTracker>()

    @Volatile
    private var closed = false

    val enabled: Boolean get() = http.enabled && !closed

    val baseUrl: String get() = http.baseUrl

    val configError: String get() = http.configError

    fun start() {
        kernel.events.log(
            "tracker_service_started",
            mapOf("base" to http.baseUrl, "interval_hint" to TrackerProtocol.DEFAULT_INTERVAL_SEC.toString()),
        )
    }

    fun watch(group: GroupRuntime) {
        if (!enabled) return
        // 个人空间**不注册、不 announce**（docs/design/personal-space.md「同样跳过 tracker 注册」）：
        // 它不可加入，注册上去只会让服务端多一条永远不会有 peer 的 swarm，
        // 还会把「本机有一个不能被加入的群」这条事实泄给跨网的服务端。
        if (group.isPersonal) {
            kernel.events.log("personal_space_tracker_skip", mapOf("gid" to group.groupId))
            return
        }
        val gid = group.groupId
        if (trackers.containsKey(gid)) return
        val tracker = GroupTracker(kernel, group, http)
        trackers[gid] = tracker
        tracker.start()
    }

    fun forget(gid: String) {
        trackers.remove(gid)?.close()
    }

    fun close() {
        if (closed) return
        closed = true
        for (tracker in trackers.values) {
            try { tracker.close() } catch (_: Throwable) {}
        }
        trackers.clear()
    }
}
