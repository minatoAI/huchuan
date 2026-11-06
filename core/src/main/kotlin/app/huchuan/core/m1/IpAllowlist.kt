package app.huchuan.core.m1

import java.net.Inet4Address
import java.net.InetAddress

/**
 * 私有 BT 会话 `ip_filter` 的白名单口径（AGENTS §9「私网段 + 消息层确认的群成员公网地址」）。
 *
 * 只做两件事，全部是纯函数，SelfTest 直接钉住：
 *   1. 把「成员端点」字符串归一成**IP 字面量**（主机名一律不收：ip_filter 是按地址匹配的，
 *      协议 §3.3 又明确不做 DNS 预解析；主机名端点让消息层去解析，BT 这一层只认字面量）。
 *   2. 判断一个字面量是不是「基础私网段」——是的话静态规则已经放行，不必进动态白名单；
 *      不是的话才是要按成员知识动态放行的公网地址。
 *
 * 为什么公网地址必须动态放行：私有会话的 ip_filter 是「先全禁、再放行私网」，跨网 peer
 * （tracker / 手动 IP:port / `peer.announce.btHost` 学到的公网成员）会被这套规则在
 * **libtorrent 内部**拒掉。只放行私网 = 跨网在代码层面永远不可能成功（AGENTS §9）。
 */
object IpAllowlist {

    /** 基础私网 / 回环 / 链路本地段（v4），与 [BtSession] 的静态规则同源。 */
    val BASE_V4: List<Pair<String, String>> = listOf(
        "127.0.0.0" to "127.255.255.255",
        "10.0.0.0" to "10.255.255.255",
        "172.16.0.0" to "172.31.255.255",
        "192.168.0.0" to "192.168.255.255",
        "169.254.0.0" to "169.254.255.255",
    )

    /** 基础私网 / 回环 / 链路本地段（v6），与 [BtSession] 的静态规则同源。 */
    val BASE_V6: List<Pair<String, String>> = listOf(
        "::1" to "::1",
        "fc00::" to "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        "fe80::" to "febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
    )

    private val V4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val V6_CHARS = Regex("^[0-9a-fA-F:.]+$")

    /**
     * 归一化一个端点 host：去掉 `[..]` 方括号与 `%zone`，只接受 IPv4 / IPv6 字面量。
     *
     * 主机名、空串、带端口/带 scheme 的串一律返回 null —— 调用方据此跳过，不在这里做 DNS。
     * IPv4 统一写成去前导零的 `a.b.c.d`；IPv6 用 JDK 的规范压缩形式，保证「判断」与
     * 「写进 ip_filter 的字符串」是同一份口径。
     */
    fun normalizeLiteral(host: String): String? {
        var text = host.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("[")) {
            val end = text.indexOf(']')
            if (end != text.length - 1) return null // [addr]:port 是端点不是 host
            text = text.substring(1, end)
        }
        text = text.substringBefore('%')
        val v4 = V4.matchEntire(text)
        if (v4 != null) {
            val octets = v4.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            if (octets.any { it < 0 || it > 255 }) return null
            return octets.joinToString(".")
        }
        if (!text.contains(':') || !V6_CHARS.matches(text)) return null
        return try {
            val addr = InetAddress.getByName(text)
            // Java 会把 ::ffff:1.2.3.4 这类映射地址解析成 Inet4Address；保留原串即可（libtorrent 认）。
            if (addr is Inet4Address) text.lowercase() else addr.hostAddress?.lowercase()
        } catch (e: Throwable) {
            null
        }
    }

    /** 是不是基础私网段（这些地址由静态 ALLOW 规则覆盖，不需要进动态白名单）。 */
    fun isPrivateLiteral(host: String): Boolean {
        val text = normalizeLiteral(host) ?: return false
        return try {
            inRanges(InetAddress.getByName(text), BASE_V4, BASE_V6)
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 是不是可以进动态白名单的**公网字面量**：能解析、且不属于任何「本地/保留」段。
     *
     * 排掉的不只是基础私网段，还有回环、未指定、组播、CGNAT（100.64/10）、保留段与
     * 已废弃的 v6 site-local —— 这些地址即使写进白名单也没有跨网可达性，写进去只会扩大
     * 一条没有意义的放行规则。文档段（192.0.2/24、198.51.100/24、203.0.113/24、2001:db8::/32）
     * **不**排除：它们就是测试里的「公网 IP」。
     */
    fun isPublicLiteral(host: String): Boolean {
        val text = normalizeLiteral(host) ?: return false
        val addr = try {
            InetAddress.getByName(text)
        } catch (e: Throwable) {
            return false
        }
        return !isLocalOrReserved(addr)
    }

    /**
     * 从一批端点里收集动态白名单：只留公网字面量，去重后按字典序返回（日志与断言都稳定）。
     *
     * 调用方给的是「成员知识」的原始字段（peer.announce.btHost、tracker 候选、手动 IP:port、
     * 上次建链端点），这里不判断来源可信度 —— 来源可信度由各自的入口负责（见 docs/core.md §3.7）。
     */
    fun collect(hosts: Iterable<String?>): Set<String> {
        val out = LinkedHashSet<String>()
        for (raw in hosts) {
            if (raw == null) continue
            val normalized = normalizeLiteral(raw) ?: continue
            if (!isPublicLiteral(normalized)) continue
            out.add(normalized)
        }
        return LinkedHashSet(out.sorted())
    }

    // ------------------------------------------------------------------ 内部

    private fun inRanges(addr: InetAddress, v4: List<Pair<String, String>>, v6: List<Pair<String, String>>): Boolean {
        val bytes = addr.address
        val ranges = if (bytes.size == 4) v4 else v6
        for ((first, last) in ranges) {
            val lo = try { InetAddress.getByName(first) } catch (e: Throwable) { continue }
            val hi = try { InetAddress.getByName(last) } catch (e: Throwable) { continue }
            if (lo.address.size != bytes.size || hi.address.size != bytes.size) continue
            if (compare(bytes, lo.address) >= 0 && compare(bytes, hi.address) <= 0) return true
        }
        return false
    }

    /** 本地 / 保留 / 不可跨网可达的地址（公网白名单的排除集）。 */
    private fun isLocalOrReserved(addr: InetAddress): Boolean {
        val bytes = addr.address
        if (bytes.size == 4) {
            val a = bytes[0].toInt() and 0xff
            val b = bytes[1].toInt() and 0xff
            return when {
                a == 0 -> true                      // 0.0.0.0/8 本网络
                a == 10 -> true                     // 10/8
                a == 100 && b in 64..127 -> true    // 100.64/10 CGNAT
                a == 127 -> true                    // 回环
                a == 169 && b == 254 -> true        // 链路本地
                a == 172 && b in 16..31 -> true     // 172.16/12
                a == 192 && b == 168 -> true        // 192.168/16
                a == 198 && b in 18..19 -> true     // 198.18/15 基准测试
                a >= 224 -> true                    // 组播 + 保留（224/4、240/4）
                else -> false
            }
        }
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val allZero = bytes.all { it.toInt() == 0 }
        // ::1 是「前 15 字节为 0、最后一字节为 1」，不能拿 allZero 再与一次（那会把它算成公网）。
        val loopback = bytes.size == 16 && (0 until 15).all { bytes[it].toInt() == 0 } && bytes[15].toInt() == 1
        return when {
            allZero || loopback -> true
            first == 0xff -> true                                   // ff00::/8 组播
            (first and 0xfe) == 0xfc -> true                        // fc00::/7 ULA
            first == 0xfe && (second and 0xc0) == 0x80 -> true      // fe80::/10 链路本地
            first == 0xfe && (second and 0xc0) == 0xc0 -> true      // fec0::/10 已废弃
            else -> false
        }
    }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val x = a[i].toInt() and 0xff
            val y = b[i].toInt() and 0xff
            if (x != y) return x - y
        }
        return 0
    }
}
