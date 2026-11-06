package app.huchuan.core.m1

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 磁力链接（BEP 9 的 `xt=urn:btih:` 加 dn / xl / tr）。
 *
 * 只认 v1：M3 公开种子用 v1（AGENTS §9），`urn:btmh:`（v2）一律当非法链接，
 * 因为 info 字典的哈希算法不同，混进来会让 fileId 与 swarm 对不上。
 *
 * 规范化口径（public-swarm §1.1）：`xt=urn:btih:<40 位小写 hex>` 打头，dn / xl 原样回写，
 * **tr 原样保留** —— 导入的磁力带不带 tracker 是发布者的选择，不是我们的（§2.2）；
 * 我们自己发布的磁力从不添加 tr，也从不把公开 infohash 交给自带 tracker（§2.2）。
 */
class MagnetLink private constructor(
    /** 40 位小写 hex 的 v1 infohash = 群里的 fileId。 */
    val fileId: String,
    /** dn（可能为空；一个中文名要 URL 解码）。 */
    val displayName: String,
    /** xl 字节数；0 = 未知。 */
    val sizeHint: Long,
    /** 自带的 tracker（原样，可能为空）。 */
    val trackers: List<String>,
    /** 规范化之后可以直接入库 / 广播的链接。 */
    val normalized: String,
) {
    companion object {
        private const val PREFIX = "magnet:?"
        private const val BTIH = "urn:btih:"

        /** 解析任意链接；不是磁力 / 没有 v1 xt / hex 长度不对一律返回 null（不抛异常）。 */
        @JvmStatic
        fun parse(raw: String): MagnetLink? {
            val text = raw.trim()
            if (text.length <= PREFIX.length) return null
            if (!text.regionMatches(0, PREFIX, 0, PREFIX.length, ignoreCase = true)) return null
            var infoHash = ""
            var name = ""
            var size = 0L
            val trackers = ArrayList<String>()
            for (pair in text.substring(PREFIX.length).split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val key = if (eq < 0) pair else pair.substring(0, eq)
                val value = if (eq < 0) "" else pair.substring(eq + 1)
                when (key.lowercase()) {
                    "xt" -> {
                        val decoded = decode(value).trim()
                        if (decoded.startsWith(BTIH, ignoreCase = true)) {
                            infoHash = normalizeInfoHash(decoded.substring(BTIH.length)) ?: return null
                        } else if (decoded.startsWith("urn:btmh:", ignoreCase = true)) {
                            return null // v2：M3 不接
                        }
                    }
                    "dn" -> name = decode(value).trim()
                    "xl" -> size = decode(value).trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    "tr" -> decode(value).trim().takeIf { it.isNotEmpty() }?.let { trackers.add(it) }
                    else -> {}
                }
            }
            if (infoHash.isEmpty()) return null
            val sb = StringBuilder(PREFIX)
            sb.append("xt=").append(BTIH).append(infoHash)
            if (name.isNotEmpty()) sb.append("&dn=").append(encode(name))
            if (size > 0) sb.append("&xl=").append(size)
            for (tr in trackers) sb.append("&tr=").append(encode(tr))
            return MagnetLink(infoHash, name, size, trackers, sb.toString())
        }

        /** 40 位 hex 或 32 位 RFC 4648 base32（BEP 9 允许两种写法）→ 40 位小写 hex。 */
        private fun normalizeInfoHash(value: String): String? {
            val v = value.trim()
            if (v.length == 40) {
                if (!v.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                return v.lowercase()
            }
            if (v.length == 32) {
                val bytes = base32Decode(v) ?: return null
                if (bytes.size != 20) return null
                val sb = StringBuilder(40)
                for (b in bytes) sb.append(HEX[(b.toInt() shr 4) and 0xf]).append(HEX[b.toInt() and 0xf])
                return sb.toString()
            }
            return null
        }

        private val HEX = "0123456789abcdef".toCharArray()

        /** RFC 4648 base32（A–Z 2–7）。不能用 B32（Crockford 会把 I/L/O 改写）。 */
        private fun base32Decode(text: String): ByteArray? {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            var buffer = 0
            var bits = 0
            val out = ByteArrayOutputStream()
            for (c0 in text) {
                val c = Character.toUpperCase(c0)
                val idx = alphabet.indexOf(c)
                if (idx < 0) return null
                buffer = (buffer shl 5) or idx
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    out.write((buffer shr bits) and 0xff)
                }
            }
            return out.toByteArray()
        }

        private fun decode(value: String): String = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (e: Exception) {
            value
        }

        private fun encode(value: String): String = try {
            URLEncoder.encode(value, "UTF-8")
        } catch (e: Exception) {
            value
        }
    }
}

/**
 * 磁力导入的结果（public-swarm §3.1）。
 *
 * **永不抛异常**：五种失败各有一句中文（[messageFor]），宿主直接把 [message] 显示在输入框下一行 /
 * P3 行上，不弹窗、不要求用户点重试。
 */
data class PublicMagnetResult(
    /** 元数据到手（能下载 / 能做种）。 */
    val ok: Boolean,
    /** 40 位 hex 的 infohash；链接非法时为空串。 */
    val fileId: String,
    /** "" 表示成功；否则是下面七个常量之一。 */
    val reason: String,
    /** 一句中文，直接给 UI 用；成功时为 ""。 */
    val message: String,
    /** 入库的条目；链接非法 / 重复 / 群不存在时为 null（其余失败也建条目，等来源）。 */
    val entry: FileEntry?,
    /** .torrent 缓存路径；元数据没到手时为空串。 */
    val metadataPath: String,
    /** 元数据里的名字（dn 缺省时为 ""）。 */
    val name: String = "",
    /** 元数据里的总字节数；未知为 0。 */
    val size: Long = 0L,
    /** 元数据里的分片大小；未知为 0。 */
    val pieceSize: Int = 0,
) {
    companion object {
        const val OK = ""
        const val INVALID_LINK = "invalid_link"
        const val DUPLICATE = "duplicate"
        const val TIMEOUT = "timeout"
        const val NO_SOURCE = "no_source"
        const val BAD_METADATA = "bad_metadata"
        const val NO_GROUP = "no_group"
        const val SESSION_ERROR = "session_error"

        /**
         * 失败原因 → 一句话（public-swarm §3.1 的表）。
         *
         * 纯函数：SelfTest 在字节层钉「五种失败各有一句话、彼此不同、都不为空」，
         * 不用为了验文案去起一个真的 swarm。
         */
        @JvmStatic
        fun messageFor(reason: String): String = when (reason) {
            INVALID_LINK -> "链接无法识别"
            DUPLICATE -> "这个种子已经在群里"
            TIMEOUT -> "等文件信息超时，会继续重试"
            NO_SOURCE -> "找不到来源，先挂着等别人上线"
            BAD_METADATA -> "文件信息无效"
            NO_GROUP -> "群不存在"
            SESSION_ERROR -> "公开会话没起来"
            else -> ""
        }

        /** 五种「磁力失败」的原因码，顺序与 §3.1 的表一致（超时 / 无源 / 元数据 / 重复 / 非法）。 */
        @JvmStatic
        fun failureReasons(): Array<String> =
            arrayOf(TIMEOUT, NO_SOURCE, BAD_METADATA, DUPLICATE, INVALID_LINK)
    }
}
