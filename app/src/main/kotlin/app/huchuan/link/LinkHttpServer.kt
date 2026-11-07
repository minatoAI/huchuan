package app.huchuan.link

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import app.huchuan.core.link.LinkProtocol
import app.huchuan.kernel.M1Runtime
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 页面与文件字节的来源：只监听 127.0.0.1 的极小 HTTP 服务（生产版，源自 spikes/webview-link）。
 *
 * 为什么不走 WebViewAssetLoader：assets 里只有页面，用户文件在应用私有目录，而且可能是 GB 级；
 * 把页面和文件放在**同一个 loopback origin** 上，就不用处理 appassets.androidplatform.net →
 * 127.0.0.1 的跨源。127.0.0.1 属于可信来源，页面因此仍是 secure context（crypto.subtle /
 * RTCPeerConnection 都可用，spike 实测）。
 *
 * 与 spike 的差别（生产要求）：
 *   - 端口随机（bind 0），且**每条路径都带一次性 token**：本机别的进程扫到端口也读不到文件；
 *   - 支持 Range（bytes=a-b / a- / -n），206 + Content-Range，供断点续读与浏览器的分段请求；
 *   - 单个文件、只读、只 GET/HEAD，不做目录与上传；
 *   - stop() 关监听套接字、断掉所有在途连接、关线程池 —— 分享结束不留线程与端口。
 *
 * 明文 HTTP 需要 networkSecurityConfig 对 127.0.0.1 开例外（res/xml/network_security_config.xml），
 * 否则 targetSdk≥28 直接 ERR_CLEARTEXT_NOT_PERMITTED（白屏）。
 */
internal class LinkHttpServer(
    context: Context,
    private val file: File,
    private val fileName: String,
    private val mime: String,
    private val sha256: String,
) {

    private val assets: AssetManager = context.applicationContext.assets

    /** 一次性路径 token：页面与文件的全部 URL 都挂在 /t/<token>/ 下。 */
    private val token: String = LinkProtocol.newOpaqueId(16)
    private val base = "/t/" + token + "/"

    private val clients: MutableSet<Socket> =
        Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    private val pool: ExecutorService = Executors.newCachedThreadPool { job ->
        Thread(job, "huchuan-link-http").apply { isDaemon = true }
    }

    private val stopped = AtomicBoolean(false)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var port: Int = 0

    /** 起服务；返回实际端口（0 = 失败）。 */
    fun start(): Int {
        val socket = try {
            ServerSocket(0, 32, InetAddress.getByName(LOOPBACK))
        } catch (t: Throwable) {
            Log.w(M1Runtime.TAG, "event=link_http_bind_failed error=" + t)
            return 0
        }
        serverSocket = socket
        port = socket.localPort
        val acceptor = Thread({ acceptLoop(socket) }, "huchuan-link-http-accept")
        acceptor.isDaemon = true
        acceptor.start()
        log("listen port=" + port)
        return port
    }

    /** 页面地址：/t/<token>/ 由服务解析成 index.html。 */
    fun pageUrl(): String = "http://" + LOOPBACK + ":" + port + base

    /** 关服务：先把 socket 关掉让 accept 抛出来，再断在途连接、停线程池。可重复调用。 */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        val socket = serverSocket
        serverSocket = null
        try {
            socket?.close()
        } catch (_: IOException) {
            /* 已关 */
        }
        for (client in clients) {
            try {
                client.close()
            } catch (_: IOException) {
                /* 已关 */
            }
        }
        clients.clear()
        pool.shutdownNow()
        log("stopped port=" + port)
    }

    // ------------------------------------------------------------------ 连接

    private fun acceptLoop(socket: ServerSocket) {
        while (!stopped.get() && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (t: Throwable) {
                if (!stopped.get() && !socket.isClosed) {
                    Log.w(M1Runtime.TAG, "event=link_http_accept_failed error=" + t)
                }
                return
            }
            clients.add(client)
            try {
                pool.execute { serve(client) }
            } catch (_: Throwable) {
                closeQuietly(client)
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val head = readHead(input) ?: return
            // 按字符切（不是正则串）：CR / LF 都要断开，空行滤掉。
            // 写成 split("\r?\n") 是字面量 CR+'?'+LF，永远不会命中 —— 头就解析不出来，
            // Range 全部被忽略（verify-range.mjs 的 206 断言就是这么发现它的）。
            val lines = head.split('\r', '\n').filter { it.isNotEmpty() }
            val request = lines.firstOrNull()?.split(" ") ?: return
            if (request.size < 2) return
            val method = request[0].uppercase()
            val target = request[1]
            var path = target.substringBefore('?')
            val headers = HashMap<String, String>()
            for (index in 1 until lines.size) {
                val line = lines[index]
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val headOnly = method == "HEAD"
            when {
                method != "GET" && !headOnly -> writeText(output, 405, "Method Not Allowed", "text/plain; charset=utf-8", "method".toByteArray(), headOnly = false)
                !path.startsWith(base) -> writeText(output, 404, "Not Found", "text/plain; charset=utf-8", "not found".toByteArray(), headOnly = headOnly)
                else -> {
                    path = path.removePrefix(base)
                    route(output, path, headers["range"], headOnly)
                }
            }
            output.flush()
        } catch (t: Throwable) {
            // 页面被 WebView 关掉时会出现 broken pipe / socket closed，属正常收尾，不升级成错误。
            log("conn_end " + (t.message ?: t.javaClass.simpleName))
        } finally {
            clients.remove(client)
            closeQuietly(client)
        }
    }

    private fun route(output: OutputStream, path: String, range: String?, headOnly: Boolean) {
        if (path.contains("..")) {
            writeText(output, 404, "Not Found", "text/plain; charset=utf-8", "not found".toByteArray(), headOnly)
            return
        }
        when (path) {
            "", "index.html" -> writeAsset(output, "link/index.html", "text/html; charset=utf-8", headOnly)
            "sharer.js" -> writeAsset(output, "link/sharer.js", "text/javascript; charset=utf-8", headOnly)
            "meta" -> {
                val json = JSONObject()
                    .put("name", fileName)
                    .put("size", file.length())
                    .put("sha256", sha256)
                    .put("mime", mime)
                    .put("chunkSize", CHUNK)
                    .toString()
                writeText(output, 200, "OK", "application/json; charset=utf-8", json.toByteArray(StandardCharsets.UTF_8), headOnly)
            }

            "file" -> writeFile(output, range, headOnly)
            else -> writeText(output, 404, "Not Found", "text/plain; charset=utf-8", "not found".toByteArray(), headOnly)
        }
    }

    // ------------------------------------------------------------------ 资源

    private fun writeAsset(output: OutputStream, name: String, type: String, headOnly: Boolean) {
        val bytes = try {
            assets.open(name).use { readAll(it) }
        } catch (t: Throwable) {
            Log.w(M1Runtime.TAG, "event=link_http_asset_missing name=" + name + " error=" + t)
            writeText(output, 404, "Not Found", "text/plain; charset=utf-8", "not found".toByteArray(), headOnly)
            return
        }
        writeText(output, 200, "OK", type, bytes, headOnly)
    }

    /**
     * 文件本体：支持 Range（单段）。
     *
     * 206 只在请求头真的带 Range 且能解析时回；解析不了回 200 全量（RFC 9110 允许服务器忽略 Range），
     * 越界回 416，并带一条 Content-Range 说明总长。页面平时整段流式读，Range 留给续读与浏览器行为。
     */
    private fun writeFile(output: OutputStream, range: String?, headOnly: Boolean) {
        val total = file.length()
        if (total <= 0L || !file.isFile) {
            writeText(output, 404, "Not Found", "text/plain; charset=utf-8", "not found".toByteArray(), headOnly)
            return
        }
        var startAt = 0L
        var endAt = total - 1
        var partial = false
        if (!range.isNullOrBlank()) {
            val parsed = parseRange(range, total)
            if (parsed == null) {
                writeHeaders(output, 416, "Range Not Satisfiable", mime, 0, headOnly,
                    extra = "Content-Range: bytes */" + total + "\r\n")
                return
            }
            startAt = parsed.first
            endAt = parsed.last
            partial = true
        }
        val length = endAt - startAt + 1
        writeHeaders(output, if (partial) 206 else 200, if (partial) "Partial Content" else "OK", mime, length, headOnly,
            extra = "Accept-Ranges: bytes\r\nContent-Range: bytes " + startAt + "-" + endAt + "/" + total + "\r\n")
        if (headOnly) return
        RandomAccessFile(file, "r").use { source ->
            source.seek(startAt)
            val buffer = ByteArray(64 * 1024)
            var left = length
            while (left > 0L) {
                val read = source.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read <= 0) break
                output.write(buffer, 0, read)
                left -= read.toLong()
            }
        }
        log("file_served bytes=" + length + " range=" + (if (partial) startAt.toString() + "-" + endAt else "full"))
    }

    /** bytes=a-b / bytes=a- / bytes=-n；返回闭区间，越界或语法错误返回 null。 */
    private fun parseRange(raw: String, total: Long): LongRange? {
        val text = raw.trim()
        if (!text.lowercase().startsWith("bytes=")) return null
        val spec = text.substring("bytes=".length).substringBefore(',')
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val head = spec.substring(0, dash).trim()
        val tail = spec.substring(dash + 1).trim()
        return try {
            when {
                head.isEmpty() && tail.isEmpty() -> null
                head.isEmpty() -> {
                    val suffix = tail.toLong()
                    if (suffix <= 0L) return null
                    val start = (total - suffix).coerceAtLeast(0L)
                    start..(total - 1)
                }

                tail.isEmpty() -> {
                    val start = head.toLong()
                    if (start >= total) return null
                    start..(total - 1)
                }

                else -> {
                    val start = head.toLong()
                    val end = tail.toLong()
                    if (start > end || start >= total) return null
                    start..minOf(end, total - 1)
                }
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun writeText(
        output: OutputStream,
        code: Int,
        reason: String,
        type: String,
        body: ByteArray,
        headOnly: Boolean,
    ) {
        writeHeaders(output, code, reason, type, body.size.toLong(), headOnly)
        if (headOnly) return
        output.write(body)
    }

    private fun writeHeaders(
        output: OutputStream,
        code: Int,
        reason: String,
        type: String,
        length: Long,
        headOnly: Boolean,
        extra: String = "",
    ) {
        val head = StringBuilder()
            .append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            .append("Content-Type: ").append(type).append("\r\n")
            .append("Content-Length: ").append(length).append("\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("X-Content-Type-Options: nosniff\r\n")
            .append(extra)
            .append("Connection: close\r\n\r\n")
            .toString()
        output.write(head.toByteArray(StandardCharsets.ISO_8859_1))
        output.flush()
    }

    /** 读到 CRLFCRLF 为止；上限 [MAX_HEAD_BYTES]，防止本机乱发大包把内存吃满。 */
    private fun readHead(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(512)
        var state = 0
        while (state < 4) {
            val value = input.read()
            if (value < 0) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            buffer.write(value)
            if (buffer.size() > MAX_HEAD_BYTES) return null
            state = when {
                state % 2 == 0 && value == '\r'.code -> state + 1
                state % 2 == 1 && value == '\n'.code -> state + 1
                value == '\r'.code -> 1
                else -> 0
            }
        }
        return buffer.toString("ISO-8859-1")
    }

    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(8192)
        val chunk = ByteArray(8192)
        while (true) {
            val read = input.read(chunk)
            if (read <= 0) break
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Throwable) {
            /* 已关 */
        }
    }

    private fun log(line: String) {
        Log.i(M1Runtime.TAG, "event=link_http " + line)
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val MAX_HEAD_BYTES = 8 * 1024
        private const val CHUNK = 16 * 1024
    }
}
