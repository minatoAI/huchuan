package app.huchuan.link

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import app.huchuan.core.link.SignalClient
import app.huchuan.kernel.M1Runtime
import org.json.JSONObject
import java.io.File

/**
 * WebView 承载的发送端（生产版；协议来自 spikes/webview-link，已真机验过 8 MiB 三方 SHA 一致）。
 *
 * 分工：
 *   - 页面（assets/link/sharer.js，由 [LinkHttpServer] 从 127.0.0.1 出）负责 RTCPeerConnection、
 *     DataChannel、16 KiB 分帧与背压；
 *   - 原生保留设备身份与信令：页面把 offer SDP 交回来，[SignalClient.sendOffer] 用它签名再发；
 *     浏览器回来的 answer / ICE 由原生回灌给页面。**签名域与设备身份一步没动**，
 *     所以访客侧验签、撤销、重连退避的既有逻辑全部照旧。
 *
 * 为什么不让 JS 自己连信令（spike 的做法）：那需要把设备私钥（identity.txt 里的 P-256）交给页面，
 * 或者让页面另生成一把临时钥匙 —— 前者把组身份暴露给渲染进程，后者让 offer 签名与设备身份脱钩
 * （访客按 /api/share 里登记的 pub 验签会失败，撤销也没法用同一把钥匙签）。代价只是每帧 SDP/ICE
 * 多过一次 JavaScript 桥，实测建链 743 ms，比原生实现（1590 ms）还快。
 */
internal class LinkWebSender(
    context: Context,
    private val shareId: String,
    private val file: File,
    private val fileName: String,
    private val mime: String,
    private val sha256: String,
    private val signal: SignalClient,
    private val callbacks: LinkSender.Callbacks,
) : LinkSender {

    private val appContext: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var closed = false

    @Volatile
    private var opened = false

    @Volatile
    private var verified = false

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var server: LinkHttpServer? = null

    @Volatile
    private var watchdog: Thread? = null

    // ------------------------------------------------------------------ LinkSender

    override fun start() {
        if (closed) return
        main.post { startOnMain() }
    }

    override fun onAnswer(sdp: String) {
        if (closed || sdp.isEmpty()) return
        eval("HuPeer.answer(" + JSONObject.quote(sdp) + ")")
    }

    override fun onIce(raw: String) {
        if (closed || raw.isEmpty()) return
        eval("HuPeer.ice(" + JSONObject.quote(raw) + ")")
    }

    override fun close() {
        if (closed) return
        closed = true
        watchdog?.interrupt()
        watchdog = null
        val current = server
        server = null
        // 先停 loopback（在途连接一并断开），再把页面收掉。
        try {
            current?.stop()
        } catch (t: Throwable) {
            log("server_stop_failed " + t)
        }
        main.post { destroy() }
    }

    // ------------------------------------------------------------------ 页面

    private fun startOnMain() {
        if (closed) return
        val listener = LinkHttpServer(appContext, file, fileName, mime, sha256)
        val port = listener.start()
        if (port <= 0) {
            Log.w(M1Runtime.TAG, "event=link_web_bind_failed share=" + shareId.take(8))
            failSend("webview")
            return
        }
        server = listener
        val view = try {
            LinkWebHost.create(appContext)
        } catch (t: Throwable) {
            Log.w(M1Runtime.TAG, "event=link_web_init_failed share=" + shareId.take(8) + " error=" + t)
            listener.stop()
            server = null
            failSend("webview")
            return
        }
        configure(view)
        webView = view
        LinkWebHost.mount(view)
        val info = try {
            WebView.getCurrentWebViewPackage()
        } catch (_: Throwable) {
            null
        }
        log(
            "start port=" + port +
                " webview=" + (info?.packageName ?: "?") + "/" + (info?.versionName ?: "?") +
                " file=" + fileName.replace(' ', '_') + " bytes=" + file.length(),
        )
        // 调试构建才打页面地址（含一次性 loopback token）：chrome://inspect 与 Range 手工验证都要它。
        if (isDebuggable()) log("page_url=" + listener.pageUrl())
        try {
            view.loadUrl(listener.pageUrl())
        } catch (t: Throwable) {
            log("load_failed " + t)
            failSend("webview")
            return
        }
        startChannelWatchdog()
    }

    private fun configure(view: WebView) {
        // 明文 HTTP 只在 127.0.0.1 上开（res/xml/network_security_config.xml）；页面没有别的来源。
        WebView.setWebContentsDebuggingEnabled(isDebuggable())
        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mediaPlaybackRequiresUserGesture = true
        view.addJavascriptInterface(Bridge(), "HuNative")
        view.webViewClient = PageClient()
        view.webChromeClient = PageChrome()
    }

    private fun eval(script: String) {
        main.post {
            val view = webView ?: return@post
            if (closed) return@post
            try {
                view.evaluateJavascript("(function(){if(window.HuPeer){$script}})()", null)
            } catch (t: Throwable) {
                log("eval_failed " + t)
            }
        }
    }

    /** 主线程：停加载 → 关桥 → 摘下来 → destroy。 */
    private fun destroy() {
        val view = webView ?: return
        webView = null
        try {
            view.evaluateJavascript("(function(){if(window.HuPeer){HuPeer.stop()}})()", null)
        } catch (_: Throwable) {
            /* 页面已不可用 */
        }
        try {
            view.stopLoading()
            view.removeJavascriptInterface("HuNative")
            LinkWebHost.unmount(view)
            view.loadUrl("about:blank")
            view.destroy()
        } catch (t: Throwable) {
            log("destroy_failed " + t)
        }
    }

    // ------------------------------------------------------------------ 页面 → 原生

    private inner class Bridge {

        @JavascriptInterface
        fun log(line: String) {
            if (!isDebuggable()) return
            Log.d(M1Runtime.TAG, "event=link_web_js share=" + shareId.take(8) + " " + line.take(240))
        }

        /** 页面自己的能力探测结果：false 说明这台设备的 WebView 内核不支持（启动期也探过一次）。 */
        @JavascriptInterface
        fun capable(ok: Boolean) {
            if (ok) return
            log("capability_missing")
            failSend("unsupported")
        }

        /** 页面读完 meta、准备接受访客了：这时候才建 offer。 */
        @JavascriptInterface
        fun ready() {
            if (closed) return
            log("page_ready")
            eval("HuPeer.nextVisitor()")
        }

        /** 页面的 offer SDP：交给信令层签名后发给访客（签名域 huchuan/share1/v1 不变）。 */
        @JavascriptInterface
        fun offer(sdp: String) {
            if (closed) return
            log("offer_ready sdp_bytes=" + sdp.length)
            if (!signal.sendOffer(sdp)) failSend("offer-send")
        }

        @JavascriptInterface
        fun ice(candidate: String) {
            if (closed) return
            signal.sendIce(candidate)
        }

        @JavascriptInterface
        fun open() {
            if (closed) return
            opened = true
            watchdog?.interrupt()
            watchdog = null
            signal.markTransferring()
            log("channel_open")
            callbacks.onOpen()
        }

        @JavascriptInterface
        fun progress(sent: Long, total: Long) {
            if (closed) return
            signal.noteProgress()
            callbacks.onProgress(sent, total)
        }

        /** 接收页回来的 verify：它与手机侧的 sha256 必须一致，才算真的送到。 */
        @JavascriptInterface
        fun verify(ok: Boolean, sha: String, bytes: Long) {
            if (closed) return
            val match = sha.equals(sha256, ignoreCase = true)
            verified = true
            log("verify ok=" + ok + " match=" + match + " bytes=" + bytes)
            if (ok && match) {
                callbacks.onVerified(true, "")
            } else {
                callbacks.onVerified(false, if (match) "browser" else "sha256")
            }
        }

        @JavascriptInterface
        fun closed() {
            if (closed) return
            log("channel_closed")
            callbacks.onChannelClosed()
        }

        /** 页面侧的失败（脚本异常 / 拿不到文件 / verify 超时）。 */
        @JavascriptInterface
        fun fail(code: String) {
            failSend(if (code.isBlank()) "webview" else code)
        }
    }

    // ------------------------------------------------------------------ WebView 回调

    private inner class PageClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            // ERR_CLEARTEXT_NOT_PERMITTED 会走到这里（networkSecurityConfig 没配时的白屏）。
            log("page_error code=" + error.errorCode + " desc=" + error.description)
            failSend("webview")
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            log("renderer_gone crash=" + detail.didCrash())
            failSend("webview")
            return true
        }
    }

    private inner class PageChrome : WebChromeClient() {

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (isDebuggable()) {
                Log.d(
                    M1Runtime.TAG,
                    "event=link_web_console share=" + shareId.take(8) + " " + message.message().take(240),
                )
            }
            return true
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 访客加入后 30 s 通道没打开：如实失败，别停在「正在连接」（与原生实现同一口径）。 */
    private fun startChannelWatchdog() {
        if (watchdog != null) return
        val thread = Thread({
            try {
                Thread.sleep(CHANNEL_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (closed || opened) return@Thread
            log("channel_timeout")
            failSend("connect-failed")
        }, "huchuan-link-watchdog")
        thread.isDaemon = true
        watchdog = thread
        thread.start()
    }

    private fun failSend(code: String) {
        if (closed) return
        log("failed code=" + code)
        callbacks.onFailed(code)
    }

    private fun isDebuggable(): Boolean =
        (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun log(line: String) {
        Log.i(M1Runtime.TAG, "event=link_web share=" + shareId.take(8) + " " + line)
    }

    companion object {
        private const val CHANNEL_TIMEOUT_MS = 30_000L
    }
}
