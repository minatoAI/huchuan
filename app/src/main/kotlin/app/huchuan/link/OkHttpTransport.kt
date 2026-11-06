package app.huchuan.link

import app.huchuan.core.link.Transport
import app.huchuan.core.link.TransportFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * core [Transport] 的 Android 实现（link-share.md §4.3）。
 *
 * Android 没有 java.net.http，所以 WebSocket 用 OkHttp；这一层只做「打开 / 发文本 / 关闭 /
 * 三个回调」，帧语义全在 core 的 SignalClient 里。握手阶段的非 101 应答从 OkHttp 的
 * onFailure(response) 拿到 HTTP 状态码（Worker 撤销时是 410），交给 core 归到 FAILED(revoked)。
 *
 * 线程：OkHttp 的回调在它自己的线程上，core 会把它们串到状态机线程；这里只保证
 * close 幂等、关闭后不再回调。
 */
class OkHttpTransport(private val client: OkHttpClient) : Transport {

    private val closed = AtomicBoolean(false)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var listener: Transport.Listener? = null

    override fun open(url: String, listener: Transport.Listener) {
        if (closed.get()) {
            listener.onClosed(-1, "closed")
            return
        }
        this.listener = listener
        val request = try {
            Request.Builder().url(url).build()
        } catch (t: Throwable) {
            listener.onClosed(-1, "bad-url")
            return
        }
        try {
            socket = client.newWebSocket(request, Bridge())
        } catch (t: Throwable) {
            listener.onClosed(-1, "open:" + (t.message ?: t.javaClass.simpleName))
        }
    }

    override fun send(text: String): Boolean {
        if (closed.get()) return false
        val current = socket ?: return false
        return try {
            current.send(text)
        } catch (_: Throwable) {
            false
        }
    }

    override fun close(code: Int, reason: String) {
        if (!closed.compareAndSet(false, true)) return
        val current = socket
        socket = null
        listener = null
        if (current == null) return
        // OkHttp 只接受 1000 或 3000..4999；core 传的失败码原样保留在 reason 里。
        val safeCode = if (code == 1000 || code in 3000..4999) code else 1000
        try {
            if (!current.close(safeCode, reason.take(120))) current.cancel()
        } catch (_: Throwable) {
            try {
                current.cancel()
            } catch (_: Throwable) {
                /* 已经关了 */
            }
        }
    }

    private inner class Bridge : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (closed.get()) {
                try {
                    webSocket.close(1000, "closed")
                } catch (_: Throwable) {
                    /* 已经关了 */
                }
                return
            }
            socket = webSocket
            listener?.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!closed.get()) listener?.onMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            try {
                webSocket.close(1000, null)
            } catch (_: Throwable) {
                /* 已经关了 */
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            if (closed.get()) return
            listener?.onClosed(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socket = null
            if (closed.get()) return
            // 握手被拒（410 撤销 / 403 非本人 / 400 参数）时 response 非空，状态码就是 core 要的 code。
            listener?.onClosed(response?.code ?: -1, "io:" + (t.message ?: t.javaClass.simpleName))
        }
    }

    companion object {
        fun newClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/** 每条信令连接新建一个 Transport；OkHttpClient（连接池 / 线程池）全进程共享。 */
object OkHttpTransportFactory : TransportFactory {

    private val client: OkHttpClient by lazy { OkHttpTransport.newClient() }

    override fun create(): Transport = OkHttpTransport(client)
}
