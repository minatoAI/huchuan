package app.huchuan.core.m1

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 协议 §1 的字节层：hex、SHA-256、HMAC、Crockford Base32、HKDF-SHA256、signBytes。
 * 全部只用 JDK，不引密码库（协议 §1.1 的取舍）。
 */

internal val HEX_CHARS = "0123456789abcdef".toCharArray()

fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0x0f])
    }
    return sb.toString()
}

fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex length must be even: " + hex.length }
    val out = ByteArray(hex.length / 2)
    var i = 0
    while (i < hex.length) {
        val hi = Character.digit(hex[i], 16)
        val lo = Character.digit(hex[i + 1], 16)
        require(hi >= 0 && lo >= 0) { "bad hex at " + i }
        out[i / 2] = (((hi shl 4) or lo) and 0xff).toByte()
        i += 2
    }
    return out
}

fun sha256(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    for (p in parts) md.update(p)
    return md.digest()
}

fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, "HmacSHA256"))
    for (p in parts) mac.update(p)
    return mac.doFinal()
}

fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}

fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

/** 协议 §2.1：Crockford Base32（大写、无填充、无连字符），位序为大端比特串，末组右补 0。 */
object B32 {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun encode(bytes: ByteArray): String {
        val totalBits = bytes.size * 8
        val outLen = (totalBits + 4) / 5
        val sb = StringBuilder(outLen)
        for (i in 0 until outLen) {
            var v = 0
            for (j in 0 until 5) {
                val bitIndex = i * 5 + j
                var bit = 0
                if (bitIndex < totalBits) {
                    val b = bytes[bitIndex / 8].toInt() and 0xff
                    bit = (b ushr (7 - bitIndex % 8)) and 1
                }
                v = (v shl 1) or bit
            }
            sb.append(ALPHABET[v])
        }
        return sb.toString()
    }

    /** 解码；末组补位必须为 0，否则判非法（§1.2）。 */
    fun decode(text: String): ByteArray {
        val s = normalize(text)
        require(s.isNotEmpty()) { "empty base32" }
        val totalBits = s.length * 5
        val nBytes = totalBits / 8
        val out = ByteArray(nBytes)
        var i = 0
        while (i < totalBits) {
            val ch = s[i / 5]
            val idx = ALPHABET.indexOf(ch)
            require(idx >= 0) { "bad base32 char '" + ch + "'" }
            val bit = (idx ushr (4 - i % 5)) and 1
            if (bit == 1) {
                val bi = i / 8
                require(bi < nBytes) { "base32 padding bits must be zero" }
                out[bi] = (out[bi].toInt() or (1 shl (7 - i % 8))).toByte()
            }
            i++
        }
        return out
    }

    /** 归一化：大写、去连字符与空白、O→0、I/L→1（§2.1）。含 U 或其他非法字符原样留下，交给调用方判错。 */
    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (c0 in raw) {
            if (c0 == '-' || c0 == ' ' || c0 == '\t' || c0 == '\n' || c0 == '\r' || c0 == '\u00a0') continue
            val c = Character.toUpperCase(c0)
            when (c) {
                'O' -> sb.append('0')
                'I', 'L' -> sb.append('1')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}

/** RFC 5869 HKDF-SHA256；协议 §2.2 的参数全部写死在调用处。 */
object Hkdf {
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
        return hmacSha256(effectiveSalt, ikm)
    }

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length > 0 && length <= 255 * 32) { "bad hkdf length " + length }
        val out = ByteArrayOutputStream(length)
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            t = hmacSha256(prk, t, info, byteArrayOf(counter.toByte()))
            out.write(t)
            counter++
        }
        val bytes = out.toByteArray()
        return if (bytes.size == length) bytes else bytes.copyOf(length)
    }

    fun derive(ikm: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray =
        expand(extract(salt, ikm), info.toByteArray(Charsets.UTF_8), length)
}

/**
 * 协议 §1.2 signBytes：UTF8(domain) ‖ 0x00 ‖ 逐个 (u16be(len) ‖ 原始字节)。
 * 整数一律 8 字节无符号大端（长度前缀恒 0x0008），空值只写长度 0。
 */
object SignBytes {
    fun of(domain: String, fields: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(domain.toByteArray(Charsets.UTF_8))
        out.write(0)
        for (f in fields) {
            require(f.size <= 0xffff) { "field too large: " + f.size }
            out.write((f.size ushr 8) and 0xff)
            out.write(f.size and 0xff)
            out.write(f)
        }
        return out.toByteArray()
    }

    fun str(v: String?): ByteArray = nfc(v ?: "").toByteArray(Charsets.UTF_8)

    fun u64(v: Long): ByteArray {
        val buf = ByteBuffer.allocate(8)
        buf.putLong(v)
        return buf.array()
    }

    /** u8 字段按 §1.2 的「整数固定 8 字节」写法编码（kind 等）。 */
    fun u8(v: Int): ByteArray = u64(v.toLong() and 0xffffffffL)

    fun bin(v: ByteArray): ByteArray = v

    fun hex(v: String): ByteArray = hexToBytes(v)
}

/** 协议 §1.2 的 domain 常量，一处定义。 */
object Domains {
    const val HELLO = "huchuan/hello1/v1"

    /** join.req 的 HMAC（协议 §10）。它也当邀请码派生 kJoin 的 HKDF info 用，改这个常量会断掉所有旧码。 */
    const val JOIN = "huchuan/join1/v1"

    /**
     * join.ok 的签名 domain（协议 §10）。
     * 与 [JOIN] 分开：join.req 用 HMAC(kJoin)，join.ok 用应答方的设备私钥，两者不是一回事，
     * 共用一个 domain 会让「拿一条 join.req 的 MAC 去顶 join.ok 的签名」这类域分离错误变得难以发现。
     */
    const val JOIN_OK = "huchuan/joinok1/v1"
    const val NAME = "huchuan/name1/v1"
    const val FILE = "huchuan/file1/v1"
    const val OP = "huchuan/op1/v1"
    const val PEER = "huchuan/peer1/v1"
    const val PROOF = "huchuan/proof1/v1"
    const val TRACKER = "huchuan/trk1/v1"
    const val DEVICE = "huchuan/dev1/v1"
    const val CHECK = "huchuan/chk1/v1"
    const val GROUP = "huchuan/group1/v1"
    const val GROUP_HASH = "huchuan/gh1/v1"
}
