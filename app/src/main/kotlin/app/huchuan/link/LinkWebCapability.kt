package app.huchuan.link

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import app.huchuan.kernel.M1Runtime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 能力探测：这台设备的 WebView 内核到底能不能承载分享链接。
 *
 * 为什么要探：WebView 是系统组件，版本跟随厂商（本机 Mi MIX 2S 是 AOSP 117，没有 Play 更新通道）；
 * 内核过旧、被禁用、被「停用」时 `RTCPeerConnection` 可能根本不存在，页面会白屏或直接报错。
 * 探测在**启动时**做一次（[probeAsync]），结果给 P8 显示一句话，不弹窗、不打断任何流程。
 *
 * 判定与采集分开：[Probe] 是纯数据，[reason] 是纯函数 —— 分支可以用 [selfCheck] 逐条断言，
 * 不依赖真机上恰好有一台坏设备（这种场景在验收里不可复现）。
 */
internal object LinkWebCapability {

    /** 探测结果。四项都取自真实的 WebView，不是版本号猜的。 */
    data class Probe(
        val webViewPresent: Boolean,
        val peerConnection: Boolean,
        val dataChannel: Boolean,
        val cryptoSubtle: Boolean,
    )

    /**
     * 空串 = 支持；否则是原因码（进日志、也进 P8 的失败码）。
     *
     * 只卡两件事：有没有 `RTCPeerConnection`、有没有 `RTCDataChannel` / `createDataChannel`。
     * `crypto.subtle` 不作为门槛：P-256 签名在原生侧（core 的 DeviceIdentity），页面不需要它；
     * 它只进日志（比如探测页面不是 secure context 时会是 false，那是探测环境的事，不是能力缺失）。
     */
    fun reason(probe: Probe): String = when {
        !probe.webViewPresent -> "no-webview"
        !probe.peerConnection -> "no-rtc"
        !probe.dataChannel -> "no-channel"
        else -> ""
    }

    fun supports(probe: Probe): Boolean = reason(probe).isEmpty()

    private val _supported = MutableStateFlow<Boolean?>(null)

    /** null = 还没探完（不拦、也不算失败）；false = 内核不支持。P8 订它。 */
    val supported: StateFlow<Boolean?> = _supported.asStateFlow()

    /** 创建 / 起会话前的判定：只有**明确探到不支持**时才拦。 */
    fun supportedNow(): Boolean = _supported.value != false

    private val started = AtomicBoolean(false)

    private val main = Handler(Looper.getMainLooper())

    /** 启动时探一次（幂等）。Application.onCreate 调。 */
    fun probeAsync(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        main.post { runProbe(app) }
    }

    /**
     * 分支自检：把 [reason] 的每条判定走一遍，落一行
     * `event=link_capability_selftest ok=true`。验收脚本 grep 这一行：
     * 有人改坏任意一条分支，ok 就变 false（真机上造不出"内核不支持"，只能这样断言分支存在）。
     */
    fun selfCheck(): Boolean {
        val cases = listOf(
            Probe(true, true, true, true) to "",
            Probe(true, false, true, true) to "no-rtc",
            Probe(true, true, false, true) to "no-channel",
            Probe(false, false, false, false) to "no-webview",
        )
        val bad = cases.filter { reason(it.first) != it.second }
        val ok = bad.isEmpty()
        Log.i(
            M1Runtime.TAG,
            "event=link_capability_selftest ok=" + ok + " cases=" + cases.size +
                (if (ok) "" else " bad=" + bad.map { it.second.ifEmpty { "supported" } }),
        )
        return ok
    }

    // ------------------------------------------------------------------ 采集

    private fun runProbe(app: Context) {
        val pkg = try {
            WebView.getCurrentWebViewPackage()
        } catch (t: Throwable) {
            Log.w(M1Runtime.TAG, "event=link_capability_package_failed error=" + t)
            null
        }
        if (pkg == null) {
            // 系统里没有可用的 WebView（未安装 / 被停用）。
            settle(Probe(false, false, false, false), "no-webview", "?")
            return
        }
        val version = pkg.packageName + "/" + pkg.versionName
        val view = try {
            WebView(app)
        } catch (t: Throwable) {
            Log.w(M1Runtime.TAG, "event=link_capability_init_failed error=" + t)
            settle(Probe(false, false, false, false), "init-failed", version)
            return
        }
        val settled = AtomicBoolean(false)
        fun finish(raw: String?) {
            if (!settled.compareAndSet(false, true)) return
            try {
                view.destroy()
            } catch (_: Throwable) {
                /* 已销毁 */
            }
            val probe = parse(raw)
            settle(probe, reason(probe), version)
        }
        try {
            view.settings.javaScriptEnabled = true
            // 探测页必须与真页面同口径：**127.0.0.1 是可信来源**（secure context）。
            // about:blank / data: 是 opaque origin，实测 crypto.subtle 直接没有 —— 那不是能力缺失，
            // 是探测环境不对（本机第一次就是这么误判成 no-crypto 的）。
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(web: WebView, url: String?) {
                    try {
                        web.evaluateJavascript(SCRIPT) { raw -> finish(raw) }
                    } catch (t: Throwable) {
                        Log.w(M1Runtime.TAG, "event=link_capability_eval_failed error=" + t)
                        finish(null)
                    }
                }
            }
            view.loadDataWithBaseURL(LOOPBACK_BASE, "<html><body></body></html>", "text/html", "utf-8", null)
            // 兜底：探测本身卡住不能把功能判死（宁可放行，让真实失败在 P8 上如实报）。
            main.postDelayed({ finish(null) }, PROBE_TIMEOUT_MS)
        } catch (t: Throwable) {
            Log.w(M1Runtime.TAG, "event=link_capability_eval_failed error=" + t)
            finish(null)
        }
    }

    private fun parse(raw: String?): Probe {
        // 探不出来（脚本没跑 / 结果不是对象）时不拦：让真实失败在页面上如实报出来，
        // 也好过把一台本来能用的设备判死。
        if (raw.isNullOrBlank() || raw == "null") return Probe(true, true, true, true)
        val parsed = try {
            JSONObject(raw)
        } catch (_: Throwable) {
            // evaluateJavascript 可能把结果包成 JSON 字符串（"{\"rtc\":..}"），脱一层再试。
            try {
                JSONObject(JSONArray("[".plus(raw).plus("]")).getString(0))
            } catch (_: Throwable) {
                return Probe(true, true, true, true)
            }
        }
        return Probe(
            webViewPresent = true,
            peerConnection = parsed.optBoolean("rtc", true),
            dataChannel = parsed.optBoolean("dc", true),
            cryptoSubtle = parsed.optBoolean("crypto", true),
        )
    }

    private fun settle(probe: Probe, detail: String, webView: String) {
        val ok = supports(probe)
        _supported.value = ok
        Log.i(
            M1Runtime.TAG,
            "event=link_capability supported=" + ok +
                " reason=" + detail.ifEmpty { "-" } +
                " webview=" + webView +
                " rtc=" + probe.peerConnection +
                " dc=" + probe.dataChannel +
                " crypto=" + probe.cryptoSubtle,
        )
    }

    private const val PROBE_TIMEOUT_MS = 3_000L

    /** 探测页的 base URL：与分享页面同源（127.0.0.1 = 可信来源），只用来算能力，不发任何请求。 */
    private const val LOOPBACK_BASE = "http://127.0.0.1/"

    /**
     * 只读地看三个东西：`RTCPeerConnection` 是不是函数、`RTCDataChannel` 与
     * `createDataChannel` 在不在、`crypto.subtle` 在不在（P-256 签名与 SHA-256 都要它）。
     * 返回对象，evaluateJavascript 会把它编码成 JSON 交给原生。
     */
    private const val SCRIPT = "(function(){try{" +
        "var p=window.RTCPeerConnection&&window.RTCPeerConnection.prototype;" +
        "return {" +
        "rtc:typeof window.RTCPeerConnection==='function'," +
        "dc:!!(window.RTCDataChannel&&p&&typeof p.createDataChannel==='function')," +
        "crypto:!!(window.crypto&&window.crypto.subtle)};" +
        "}catch(e){return {rtc:false,dc:false,crypto:false};}})()"
}
