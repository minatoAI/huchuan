package app.huchuan.core.m1

import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * 消息层监听：独立 TCP + TLS1.2+ + ALPN huchuan/1（协议 §5）。
 * 默认 47100，占用顺延 47101–47199，再不行交系统分配。
 */
internal class PeerServer(private val kernel: M1Kernel) {
    private var serverSocket: SSLServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var running = false

    var port: Int = 0
        private set

    fun start(): Int {
        if (running) return port
        val ctx = Tls.serverContext(kernel.tlsIdentity)
        val candidates = ArrayList<Int>()
        val hint = kernel.messagePortHint()
        if (hint > 0) for (i in 0 until 100) candidates.add(hint + i)
        candidates.add(0)

        var lastError: Throwable? = null
        for (p in candidates) {
            try {
                val ss = ctx.serverSocketFactory.createServerSocket() as SSLServerSocket
                ss.reuseAddress = false
                ss.needClientAuth = true
                ss.soTimeout = 1000
                ss.bind(InetSocketAddress("0.0.0.0", p), 64)
                serverSocket = ss
                port = ss.localPort
                break
            } catch (e: Throwable) {
                lastError = e
            }
        }
        val socket = serverSocket ?: throw IllegalStateException("message layer could not bind: " + lastError)
        running = true
        acceptThread = Thread { acceptLoop(socket) }.apply {
            isDaemon = true
            name = "huchuan-accept"
            start()
        }
        return port
    }

    private fun acceptLoop(socket: SSLServerSocket) {
        while (running) {
            val accepted = try {
                socket.accept() as SSLSocket
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                break
            }
            val handler = Thread {
                try {
                    Tls.configure(accepted)
                    accepted.soTimeout = HANDSHAKE_TIMEOUT_MS
                    accepted.startHandshake()
                    val host = accepted.inetAddress?.hostAddress ?: ""
                    accepted.soTimeout = 0
                    val link = PeerLink(kernel, accepted, inbound = true, remoteHost = host)
                    kernel.registerLink(link)
                    link.run()
                } catch (e: Throwable) {
                    try { accepted.close() } catch (_: Throwable) {}
                }
            }
            handler.isDaemon = true
            handler.name = "huchuan-link-in"
            handler.start()
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        acceptThread = null
    }

    companion object {
        const val HANDSHAKE_TIMEOUT_MS = 15_000
    }
}
