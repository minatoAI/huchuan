package app.huchuan.daemon.link

import app.huchuan.core.link.Transport
import app.huchuan.core.link.TransportFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 桌面端的 [Transport] 实现：java.net.http.WebSocket（core 的信令层与平台之间的那一层）。
 *
 * 为什么在 daemon 里实现而不是放进 core：core 的 Transport 注释写得很清楚 —— 桌面与 Android
 * 各自注入自己的实现，core 不依赖 java.net.http（Android API 26 上没有这个模块）。
 *
 * 语义与 core 的约定逐条对齐：
 *   - open 只调一次，成功/失败恰好回调一次 onOpen / onClosed；
 *   - 握手阶段被 Worker 拒绝（分享已撤销回 410、不属于自己回 403）时，把 HTTP 状态码放进
 *     onClosed.code —— SignalClient 靠它区分 "revoked" 与 "网络断了"；
 *   - send 失败返回 false，不抛异常；
 *   - close 幂等；关闭之后不再回调 onMessage。
 *
 * 回调来自 JDK 的 WebSocket 线程，SignalClient 会自己把回调串到状态机线程上，这里不做派发。
 */
class HttpTransport : Transport {

    private val socket = AtomicReference<WebSocket?>(null)
    private val opened = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var listener: Transport.Listener? = null

    override fun open(url: String, listener: Transport.Listener) {
        this.listener = listener
        val uri = try {
            URI(url)
        } catch (e: Throwable) {
            finish(-1, "bad-url:" + describe(e))
            return
        }
        try {
            CLIENT.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
                .buildAsync(uri, Listener())
                .whenComplete { webSocket, error ->
                    if (error != null) {
                        val cause = unwrap(error)
                        if (cause is WebSocketHandshakeException) {
                            // 握手被拒：这是 SignalClient 判断「撤销 / 不是我的分享」的唯一依据。
                            finish(cause.response.statusCode(), "handshake:" + cause.response.statusCode())
                        } else {
                            finish(-1, "connect:" + describe(cause))
                        }
                        return@whenComplete
                    }
                    webSocket?.let { socket.compareAndSet(null, it) }
                }
        } catch (e: Throwable) {
            finish(-1, "connect:" + describe(e))
        }
    }

    override fun send(text: String): Boolean {
        if (finished.get() || closed.get()) return false
        val current = socket.get() ?: return false
        return try {
            current.sendText(text, true).get(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun close(code: Int, reason: String) {
        if (!closed.compareAndSet(false, true)) return
        val current = socket.getAndSet(null)
        if (current != null) {
            try {
                current.sendClose(code, reason.take(120)).get(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
                // 对端已经断了，下面 abort 兜底
            }
            try {
                current.abort()
            } catch (_: Throwable) {
                // abort 失败不影响收尾
            }
        }
        finish(code, reason)
    }

    /** onClosed 只回调一次：握手失败、正常关闭、abort 可能从不同线程一起到。 */
    private fun finish(code: Int, reason: String) {
        if (!finished.compareAndSet(false, true)) return
        val target = listener
        try {
            target?.onClosed(code, clean(reason))
        } catch (_: Throwable) {
            // 监听方自己的异常不能把 WebSocket 线程带崩
        }
    }

    private inner class Listener : WebSocket.Listener {
        private val partial = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            socket.compareAndSet(null, webSocket)
            webSocket.request(1)
            if (!closed.get() && opened.compareAndSet(false, true)) {
                try {
                    listener?.onOpen()
                } catch (_: Throwable) {
                    // 同上
                }
            }
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): java.util.concurrent.CompletionStage<*>? {
            synchronized(partial) {
                partial.append(data)
                if (last) {
                    val text = partial.toString()
                    partial.setLength(0)
                    if (!closed.get() && !finished.get()) {
                        try {
                            listener?.onMessage(text)
                        } catch (_: Throwable) {
                            // 同上
                        }
                    }
                }
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): java.util.concurrent.CompletionStage<*>? {
            finish(statusCode, reason)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            finish(-1, describe(error))
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_SEC = 8L
        private const val SEND_TIMEOUT_MS = 5_000L
        private const val CLOSE_TIMEOUT_MS = 1_000L

        /**
         * 一个进程一个 HttpClient：它自带连接池与线程，每次连都新建会泄漏 selector 线程。
         * 不跟随重定向：Worker 的 /signal 不做跳转，跟随只会把 410 藏成别的结果。
         */
        private val CLIENT: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

        private fun unwrap(error: Throwable): Throwable {
            var current = error
            while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
                current = current.cause!!
            }
            return current
        }

        private fun describe(error: Throwable): String {
            val message = error.message
            return clean(error.javaClass.simpleName + (if (message.isNullOrEmpty()) "" else ":" + message))
        }

        private fun clean(value: String?): String {
            val text = (value ?: "").replace('\n', ' ').replace('\r', ' ').trim()
            return if (text.length <= 160) text else text.substring(0, 160)
        }
    }
}

/** 每条信令连接新建一个 [HttpTransport]；HttpClient 本身是共享的，构造没有成本。 */
class HttpTransportFactory : TransportFactory {
    override fun create(): Transport = HttpTransport()
}
