package app.huchuan.kernel

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.huchuan.core.NetworkCheckItem
import app.huchuan.core.NetworkCheckKind
import app.huchuan.core.NetworkProbe
import app.huchuan.core.NetworkReport
import app.huchuan.core.NetworkStatus
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * P0 的真实网络自检：只看「这台机器现在能不能被别人连上」。
 *
 * - 没有默认网络 → 无网络连接（OFFLINE）。
 * - 有局域网 IPv4（10/172.16/192.168/169.254）→ 可互传（PASS）。
 * - 只有公网地址 / 只有 IPv6 → 降级（FAIL + 明细），M1 的局域网发现走不通，
 *   仍可手动 IP:port 兜底，所以页面照样自动放行。
 */
class RealNetworkProbe(context: Context) : NetworkProbe {

    private val app = context.applicationContext

    override fun check(): NetworkReport {
        val manager = app.getSystemService(ConnectivityManager::class.java)
            ?: return NetworkReport(NetworkStatus.OFFLINE)

        val network = manager.activeNetwork ?: return NetworkReport(NetworkStatus.OFFLINE)
        val caps = manager.getNetworkCapabilities(network)
            ?: return NetworkReport(NetworkStatus.OFFLINE)
        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!online) return NetworkReport(NetworkStatus.OFFLINE)

        val v4 = ArrayList<String>()
        val v6 = ArrayList<String>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    when (addr) {
                        is Inet4Address -> v4.add(addr.hostAddress ?: "")
                        is Inet6Address -> v6.add(addr.hostAddress ?: "")
                    }
                }
            }
        } catch (e: Throwable) {
            return NetworkReport(NetworkStatus.FAIL)
        }

        val lanV4 = v4.any { isPrivateIpv4(it) }
        val items = listOf(
            // M1 的发现只在本机可达的局域网里成立；NAT 类型要 M2 的 STUN 才有结论。
            NetworkCheckItem(NetworkCheckKind.NAT_TYPE, passed = lanV4),
            NetworkCheckItem(NetworkCheckKind.IPV6, passed = v6.isNotEmpty()),
            NetworkCheckItem(NetworkCheckKind.IPV4, passed = v4.isNotEmpty()),
        )
        val status = when {
            lanV4 -> NetworkStatus.PASS
            v4.isNotEmpty() || v6.isNotEmpty() -> NetworkStatus.FAIL
            else -> NetworkStatus.OFFLINE
        }
        return NetworkReport(status, items)
    }

    private fun isPrivateIpv4(text: String): Boolean {
        val parts = text.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return when {
            a == 10 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            a == 169 && b == 254 -> true
            else -> false
        }
    }
}
