package app.huchuan.daemon

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 服务端 WebSocket 连接（RFC 6455 的子集：text / ping / pong / close，支持分片）。
 * 只给本地页面用，不做扩展协商、不做 permessage-deflate。
 */
class WsConnection(
    private val socket: Socket,
    private val output: OutputStream,
    private val log: DaemonLogger,
) {
    private val writeLock = Any()
    private val clientId = SEQ.incrementAndGet()

    @Volatile
    private var open = true

    /** 连接彻底关掉后回调一次（服务端用它把自己从 WsHub 里摘掉）。 */
    @Volatile
    var onClose: (() -> Unit)? = null

    val id: Long get() = clientId

    fun isOpen(): Boolean = open

    fun send(text: String): Boolean {
        if (!open) return false
        val payload = text.toByteArray(Charsets.UTF_8)
        return try {
            synchronized(writeLock) {
                if (!open) return false
                writeFrame(0x1, payload)
                true
            }
        } catch (e: Throwable) {
            open = false
            log.event("ws_send_error", mapOf("client" to clientId.toString(), "error" to e.toString()))
            false
        }
    }

    fun close(code: Int = 1000, reason: String = "") {
        if (!open) return
        val reasonBytes = reason.toByteArray(Charsets.UTF_8).copyOf(minOf(reason.toByteArray(Charsets.UTF_8).size, 120))
        try {
            synchronized(writeLock) {
                if (open) {
                    val payload = ByteArray(2 + reasonBytes.size)
                    payload[0] = ((code ushr 8) and 0xff).toByte()
                    payload[1] = (code and 0xff).toByte()
                    System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.size)
                    writeFrame(0x8, payload)
                }
            }
        } catch (_: Throwable) {
            // 对端已经断了就没什么可关的
        }
        val wasOpen = open
        open = false
        try { socket.close() } catch (_: Throwable) {}
        if (wasOpen) {
            val hook = onClose
            onClose = null
            try { hook?.invoke() } catch (_: Throwable) {}
        }
    }

    /** 读循环；对端关闭或出错时返回。 */
    fun readLoop(input: InputStream) {
        val fragments = ByteArrayOutputStream()
        var fragmentsOpcode = 0
        while (open) {
            val frame = try {
                readFrame(input)
            } catch (e: Throwable) {
                log.event("ws_read_error", mapOf("client" to clientId.toString(), "error" to e.toString()))
                null
            } ?: break

            when (frame.opcode) {
                0x8 -> {
                    close(1000, "bye")
                    return
                }
                0x9 -> {
                    try {
                        synchronized(writeLock) { writeFrame(0xA, frame.payload) }
                    } catch (_: Throwable) {
                        return
                    }
                }
                0xA -> Unit
                0x0 -> {
                    fragments.write(frame.payload)
                    if (frame.fin) {
                        handleMessage(fragmentsOpcode, fragments.toByteArray())
                        fragments.reset()
                    }
                }
                0x1, 0x2 -> {
                    if (frame.fin) {
                        handleMessage(frame.opcode, frame.payload)
                    } else {
                        fragmentsOpcode = frame.opcode
                        fragments.reset()
                        fragments.write(frame.payload)
                    }
                }
                else -> log.event("ws_unknown_frame", mapOf("opcode" to frame.opcode.toString()))
            }
        }
    }

    private fun handleMessage(opcode: Int, payload: ByteArray) {
        if (opcode != 0x1) return
        val text = String(payload, Charsets.UTF_8)
        when (text.trim()) {
            "", "ping" -> send("{\"type\":\"pong\"}")
            else -> log.event("ws_message", mapOf("client" to clientId.toString(), "len" to text.length.toString()))
        }
    }

    // ------------------------------------------------------------------ 帧

    private class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    private fun readFrame(input: InputStream): Frame? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) return null
        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0f
        val masked = (b1 and 0x80) != 0
        var length = (b1 and 0x7f).toLong()
        if (length == 126L) {
            val hi = input.read()
            val lo = input.read()
            if (hi < 0 || lo < 0) return null
            length = ((hi.toLong() shl 8) or lo.toLong())
        } else if (length == 127L) {
            length = 0
            for (i in 0 until 8) {
                val b = input.read()
                if (b < 0) return null
                length = (length shl 8) or b.toLong()
            }
        }
        if (length < 0 || length > MAX_CLIENT_FRAME) throw IllegalStateException("frame too large: " + length)
        val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
        val payload = ByteArray(length.toInt())
        readFully(input, payload)
        if (mask != null) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return Frame(fin, opcode, payload)
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var read = 0
        while (read < target.size) {
            val n = input.read(target, read, target.size - read)
            if (n < 0) throw IllegalStateException("eof while reading frame")
            read += n
        }
    }

    private fun writeFrame(opcode: Int, payload: ByteArray) {
        output.write(0x80 or opcode)
        when {
            payload.size < 126 -> output.write(payload.size)
            payload.size < 65536 -> {
                output.write(126)
                output.write((payload.size ushr 8) and 0xff)
                output.write(payload.size and 0xff)
            }
            else -> {
                output.write(127)
                var remaining = payload.size.toLong()
                for (i in 7 downTo 0) {
                    output.write(((remaining ushr (8 * i)) and 0xff).toInt())
                }
            }
        }
        if (payload.isNotEmpty()) output.write(payload)
        output.flush()
    }

    companion object {
        private val SEQ = AtomicLong(0)
        private const val MAX_CLIENT_FRAME = 1024 * 1024
    }
}

/** 所有 WS 客户端 + 推送频率控制。 */
class WsHub(private val log: DaemonLogger) {
    private val clients = CopyOnWriteArrayList<WsConnection>()

    val size: Int get() = clients.size

    fun add(connection: WsConnection) {
        clients.add(connection)
        log.event("ws_open", mapOf("client" to connection.id.toString(), "clients" to clients.size.toString()))
    }

    fun remove(connection: WsConnection) {
        clients.remove(connection)
        log.event("ws_close", mapOf("client" to connection.id.toString(), "clients" to clients.size.toString()))
    }

    fun broadcast(text: String) {
        if (clients.isEmpty()) return
        for (connection in clients) {
            if (!connection.isOpen() || !connection.send(text)) {
                clients.remove(connection)
            }
        }
    }

    fun closeAll() {
        for (connection in clients) connection.close(1001, "daemon stopping")
        clients.clear()
    }
}
