package app.huchuan.core.m1

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/** 协议 §3.4 的候选结构。 */
data class Candidate(val host: String, val port: Int, val source: String) {
    val key: String get() = host + ":" + port
}

/** 协议 §3.3 手动 IP:port 的语法。 */
object ManualCandidate {
    fun parse(raw: String): Candidate? {
        var text = raw.trim().replace('\uFF1A', ':')
        if (text.isEmpty()) return null
        if (text.contains("://")) return null
        val host: String
        val portText: String
        if (text.startsWith("[")) {
            val end = text.indexOf(']')
            if (end < 0 || end + 1 >= text.length || text[end + 1] != ':') return null
            host = text.substring(1, end).substringBefore('%').ifEmpty { return null }
            portText = text.substring(end + 2)
        } else {
            val idx = text.lastIndexOf(':')
            if (idx <= 0 || idx == text.length - 1) return null
            val maybeHost = text.substring(0, idx)
            if (maybeHost.contains(':')) return null
            host = maybeHost
            portText = text.substring(idx + 1)
        }
        val port = portText.toIntOrNull() ?: return null
        if (port < 1 || port > 65535) return null
        return Candidate(host, port, "manual")
    }
}

/**
 * 局域网发现（协议 §3.1）：_huchuan._tcp，TXT 只发 v/gid/bt/r —— 绝不广播任何码派生值。
 * 用 JmDNS（Maven Central org.jmdns:jmdns）。拿不到组播时由手动 IP:port 兜底（§3.3）。
 */
class LanDiscovery : AutoCloseable {
    private var jmDns: JmDNS? = null
    private val registrations = HashMap<String, ServiceInfo>()
    private val lock = Any()

    @Volatile
    var lastError: String = ""
        private set

    fun start(): Boolean {
        synchronized(lock) {
            if (jmDns != null) return true
            return try {
                // 先试「挑一张真实局域网网卡」：多网卡机器（VPN 常在 172.16/12 里、且是 /32 点对点）
                // 让 JmDNS 自己挑接口时可能挑到 VPN，服务就广播到了没人听的网络上。
                val preferred = preferredBindAddress()
                jmDns = if (preferred != null) JmDNS.create(preferred) else JmDNS.create()
                true
            } catch (e: Throwable) {
                lastError = e.toString()
                false
            }
        }
    }

    /** 私有 IPv4、支持组播、且不是 /32 点对点的那张网卡；找不到返回 null 交给 JmDNS 自己挑。 */
    private fun preferredBindAddress(): InetAddress? {
        var best: InetAddress? = null
        var bestScore = 0
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                if (!nif.supportsMulticast()) continue
                for (ia in nif.interfaceAddresses) {
                    val addr = ia.address
                    if (addr !is Inet4Address) continue
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress || !addr.isSiteLocalAddress) continue
                    // 真实网段（/8–/31）优先于 /32 点对点链路
                    val score = if (ia.networkPrefixLength in 1..31) 10 else 1
                    if (score > bestScore) {
                        bestScore = score
                        best = addr
                    }
                }
            }
        } catch (e: Throwable) {
            return null
        }
        return best
    }

    fun register(group: GroupMeta, messagePort: Int, btPort: Int): Boolean {
        val jm = jmDns ?: return false
        return try {
            val props = mapOf(
                "v" to "1",
                "gid" to group.groupId,
                "bt" to btPort.toString(),
                "r" to if (group.joinOpen) "1" else "0",
            )
            val info = ServiceInfo.create(SERVICE_TYPE, "hv1-" + group.gHandle, messagePort, 0, 0, props)
            jm.registerService(info)
            synchronized(lock) { registrations[group.groupId] = info }
            true
        } catch (e: Throwable) {
            lastError = e.toString()
            false
        }
    }

    fun unregister(groupId: String) {
        val jm = jmDns ?: return
        val info = synchronized(lock) { registrations.remove(groupId) } ?: return
        try { jm.unregisterService(info) } catch (_: Throwable) {}
    }

    /** 浏览 LAN 候选；只收 TXT r=1 的实例（协议 §3.4）。 */
    fun browse(timeoutMs: Long): List<Candidate> {
        val jm = jmDns ?: return emptyList()
        val found = LinkedHashMap<String, ServiceInfo>()
        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                try { event.dns.requestServiceInfo(event.type, event.name, true, 1) } catch (_: Throwable) {}
            }

            override fun serviceRemoved(event: ServiceEvent) {
                synchronized(found) { found.remove(event.name) }
            }

            override fun serviceResolved(event: ServiceEvent) {
                synchronized(found) { found[event.name] = event.info }
            }
        }
        try {
            jm.addServiceListener(SERVICE_TYPE, listener)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
                val snapshot = synchronized(found) { found.values.toList() }
                if (snapshot.any { openForJoin(it) }) {
                    // 拿到 TXT 就再等半个轮次，让其余实例的 TXT 也补齐
                    Thread.sleep(300)
                    break
                }
            }
            jm.removeServiceListener(SERVICE_TYPE, listener)
        } catch (e: Throwable) {
            lastError = e.toString()
        }
        val out = ArrayList<Candidate>()
        val snapshot = synchronized(found) { found.values.toList() }
        for (info in snapshot) {
            if (!openForJoin(info)) continue
            val addresses = info.inetAddresses ?: continue
            // 一台机器可能有多张网卡（VPN 常是 172.16/12 的点对点地址）。只取第一个地址会
            // 拿到不可达的那个，所以每个地址都出一个候选：连不上的自然被下一个候选顶掉。
            val ordered = addresses.sortedByDescending { scoreAddress(it) }
            for (addr in ordered) {
                val host = addr.hostAddress ?: continue
                out.add(Candidate(host, info.port, "lan"))
            }
        }
        return out
    }

    /** 地址排序：IPv4 私有段最优先，其次是其它 IPv4，IPv6 最后。 */
    private fun scoreAddress(addr: InetAddress): Int = when {
        addr is Inet4Address && addr.isSiteLocalAddress -> 3
        addr is Inet4Address -> 2
        else -> 1
    }

    private fun openForJoin(info: ServiceInfo): Boolean {
        val r = info.getPropertyString("r") ?: "1"
        return r == "1"
    }

    override fun close() {
        synchronized(lock) {
            try { jmDns?.close() } catch (_: Throwable) {}
            jmDns = null
            registrations.clear()
        }
    }

    companion object {
        /** DNS-SD 标签限 15 字符：_huchuan._tcp = 7 字符标签。 */
        const val SERVICE_TYPE = "_huchuan._tcp.local."
    }
}
