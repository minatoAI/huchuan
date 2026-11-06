package app.huchuan.daemon

import app.huchuan.core.m1.J
import app.huchuan.core.m1.Json
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本地 HTTP/1.1 + WebSocket 服务端（只监听 127.0.0.1）。
 *
 * 为什么手写而不是 com.sun.net.httpserver：那个只做 HTTP，WebSocket 要另开一个端口，
 * 而壳和页面都需要「一个端口、同源、HTTP 与 WS 都在上面」。RFC 6455 的服务端只需要
 * 握手 + 三种帧（text / ping / close），这里就这些。
 *
 * 约定：
 *   - 每个连接一个线程；响应一律 Connection: close（本地单用户，不需要 keep-alive）。
 *   - 请求体上限 4 MiB，头部上限 32 KiB。
 *   - CORS 全放行 + 允许私有网络（PNA/LNA）：页面正常由本进程同源提供，这两组头是为了
 *     壳内嵌兜底页（https://huchuan.invalid）在需要时也能连上。
 */
class LocalHttpServer(
    private val bindHost: String,
    private val requestedPort: Int,
    private val log: DaemonLogger,
) {
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val connectionSeq = AtomicInteger(0)

    /** 业务路由；返回 null 表示 404。 */
    @Volatile
    var router: ((HttpRequest) -> HttpResponse)? = null

    /** /ws 握手成功后交给这里接管连接。返回时应已登记完毕。 */
    @Volatile
    var webSocketHandler: ((HttpRequest, WsConnection) -> Unit)? = null

    @Volatile
    var port: Int = 0
        private set

    fun start(): Int {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName(bindHost), requestedPort), 64)
        serverSocket = socket
        port = socket.localPort
        running.set(true)
        val thread = Thread {
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (e: Throwable) {
                    if (running.get()) log.event("http_accept_error", mapOf("error" to e.toString()))
                    break
                }
                val name = "huchuan-http-" + connectionSeq.incrementAndGet()
                val worker = Thread({ serve(client) }, name)
                worker.isDaemon = true
                worker.start()
            }
        }
        thread.isDaemon = true
        thread.name = "huchuan-http-accept"
        thread.start()
        return port
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
    }

    // ------------------------------------------------------------------ 连接

    private fun serve(client: Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = REQUEST_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val output = client.getOutputStream()
            val head = readHead(input) ?: return
            val request = parseRequest(head, input, client.inetAddress?.hostAddress ?: "?") ?: run {
                writeResponse(output, HttpResponse.text("bad request", 400))
                return
            }

            if (request.header("upgrade").equals("websocket", ignoreCase = true)) {
                handleWebSocket(client, input, output, request)
                return
            }

            val started = System.currentTimeMillis()
            val response = try {
                dispatch(request)
            } catch (e: Throwable) {
                log.event("http_route_error", mapOf("path" to request.path, "error" to e.toString()))
                HttpResponse.json(apiError("E_INTERNAL", e.toString()), 500)
            }
            writeResponse(output, response)
            log.event(
                "http_request",
                mapOf(
                    "method" to request.method,
                    "path" to request.path,
                    "status" to response.status.toString(),
                    "bytes" to response.body.size.toString(),
                    "ms" to (System.currentTimeMillis() - started).toString(),
                ),
            )
        } catch (e: Throwable) {
            log.event("http_conn_error", mapOf("error" to e.toString()))
        } finally {
            try { client.close() } catch (_: Throwable) {}
        }
    }

    private fun dispatch(request: HttpRequest): HttpResponse {
        if (request.method == "OPTIONS") return HttpResponse(204, "No Content", "text/plain", ByteArray(0))
        if (request.path == "/ws") {
            return HttpResponse.text("websocket handshake required", 400)
        }
        val handler = router ?: return HttpResponse.text("no router", 503)
        return handler(request)
    }

    // ------------------------------------------------------------------ 握手

    private fun handleWebSocket(client: Socket, input: InputStream, output: OutputStream, request: HttpRequest) {
        val key = request.header("sec-websocket-key")
        if (key.isEmpty()) {
            writeResponse(output, HttpResponse.text("missing Sec-WebSocket-Key", 400))
            return
        }
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key.trim() + WS_GUID).toByteArray(Charsets.US_ASCII)),
        )
        val handshake = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: ").append(accept).append("\r\n")
            // 页面由本进程提供时不需要子协议；声明一个空的以免个别客户端较真。
            append("\r\n")
        }
        synchronized(output) {
            output.write(handshake.toByteArray(Charsets.US_ASCII))
            output.flush()
        }
        client.soTimeout = 0
        val connection = WsConnection(client, output, log)
        val handler = webSocketHandler
        if (handler == null) {
            connection.close(1001, "no websocket handler")
            return
        }
        try {
            handler(request, connection)
            connection.readLoop(input)
        } finally {
            connection.close(1001, "server closing")
        }
    }

    // ------------------------------------------------------------------ 解析

    private fun readHead(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(512)
        var matched = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            buffer.write(b)
            when (matched) {
                0 -> matched = if (b == '\r'.code) 1 else 0
                1 -> matched = if (b == '\n'.code) 2 else 0
                2 -> matched = if (b == '\r'.code) 3 else 0
                3 -> {
                    if (b == '\n'.code) return String(buffer.toByteArray(), Charsets.UTF_8)
                    matched = 0
                }
            }
            if (buffer.size() > MAX_HEAD_BYTES) return null
        }
    }

    private fun parseRequest(head: String, input: InputStream, remote: String): HttpRequest? {
        val lines = head.split("\r\n")
        if (lines.isEmpty()) return null
        val first = lines[0].split(' ')
        if (first.size < 2) return null
        val method = first[0].uppercase()
        val target = first[1]

        val headers = LinkedHashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        val questionAt = target.indexOf('?')
        val rawPath = if (questionAt >= 0) target.substring(0, questionAt) else target
        val queryText = if (questionAt >= 0) target.substring(questionAt + 1) else ""
        val query = LinkedHashMap<String, String>()
        if (queryText.isNotEmpty()) {
            for (pair in queryText.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                if (eq < 0) query[decode(pair)] = "" else query[decode(pair.substring(0, eq))] = decode(pair.substring(eq + 1))
            }
        }

        val declared = headers["content-length"]?.toIntOrNull() ?: 0
        if (declared > MAX_BODY_BYTES) return null
        val body = if (declared > 0) {
            val bytes = ByteArray(declared)
            var read = 0
            while (read < declared) {
                val n = input.read(bytes, read, declared - read)
                if (n < 0) break
                read += n
            }
            String(bytes, 0, read, Charsets.UTF_8)
        } else {
            ""
        }

        return HttpRequest(method, rawPath, query, headers, body, remote)
    }

    private fun decode(text: String): String = try {
        java.net.URLDecoder.decode(text, "UTF-8")
    } catch (_: Throwable) {
        text
    }

    private fun writeResponse(output: OutputStream, response: HttpResponse) {
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(response.reason).append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
            append("Content-Length: ").append(response.body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type\r\n")
            append("Access-Control-Allow-Private-Network: true\r\n")
            for ((k, v) in response.extra) append(k).append(": ").append(v).append("\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII))
        if (response.body.isNotEmpty()) output.write(response.body)
        output.flush()
    }
}

/** 一次 HTTP 请求。body 已按 UTF-8 解成字符串（协议与 API 都只传 JSON）。 */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: String,
    val remote: String,
) {
    fun header(name: String): String = headers[name.lowercase()] ?: ""

    fun queryInt(name: String, fallback: Int): Int = query[name]?.toIntOrNull() ?: fallback

    fun json(): J? {
        if (body.isBlank()) return null
        return try {
            Json.parse(body)
        } catch (_: Throwable) {
            null
        }
    }
}

/** 一次 HTTP 响应。 */
class HttpResponse(
    val status: Int,
    val reason: String,
    val contentType: String,
    val body: ByteArray,
    val extra: Map<String, String> = emptyMap(),
) {
    companion object {
        fun json(value: J, status: Int = 200): HttpResponse =
            HttpResponse(status, reasonOf(status), "application/json; charset=utf-8", Json.write(value).toByteArray(Charsets.UTF_8))

        fun text(value: String, status: Int = 200, contentType: String = "text/plain; charset=utf-8"): HttpResponse =
            HttpResponse(status, reasonOf(status), contentType, value.toByteArray(Charsets.UTF_8))

        fun bytes(value: ByteArray, contentType: String, status: Int = 200): HttpResponse =
            HttpResponse(status, reasonOf(status), contentType, value)

        private fun reasonOf(status: Int): String = when (status) {
            101 -> "Switching Protocols"
            200 -> "OK"
            201 -> "Created"
            204 -> "No Content"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "OK"
        }
    }
}

private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC11B65"
private const val MAX_HEAD_BYTES = 32 * 1024
private const val MAX_BODY_BYTES = 4 * 1024 * 1024
const val REQUEST_TIMEOUT_MS = 20_000
