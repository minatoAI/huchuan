package app.huchuan.daemon.link

import app.huchuan.core.m1.J
import app.huchuan.core.m1.Json
import app.huchuan.core.m1.jarr
import app.huchuan.core.m1.jobj
import app.huchuan.core.m1.jnum
import app.huchuan.core.m1.jstr
import app.huchuan.daemon.DaemonLogger
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.client.DataChannel
import dev.onvoid.webrtc.client.Peer
import dev.onvoid.webrtc.client.PeerOptions
import dev.onvoid.webrtc.client.PeerState
import dev.onvoid.webrtc.client.SignalMessage
import dev.onvoid.webrtc.client.Signaling
import dev.onvoid.webrtc.client.Subscription
import dev.onvoid.webrtc.client.WebRtc
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/**
 * 链接分享的发送端：用 webrtc-java 建一条 PeerConnection + DataChannel，把本地文件分块推给浏览器。
 *
 * 与浏览器接收页（pages/public/recv/app.js）之间的线格式（link-share.md §5-7/§5-9）：
 *
 *   text   {"t":"meta","files":[{name,size,sha256,mime}],"chunks":N,"chunkSize":16384}
 *   binary u32be(index) || 16 KiB 数据（最后一块可以短）
 *   text   {"t":"done","chunks":N}
 *   <-     {"t":"ack","upTo":bytes}      每 256 KiB 一次
 *   <-     {"t":"verify","ok":true,"sha256":"…","bytes":N,"chunks":N,"transferMs":N}
 *
 * 一个访客一个 [WebRtcSender]（= 一条 PeerConnection），传输结束由调用方 [close] 释放；
 * [WebRtc] 运行期由 daemon 共享，不在这里创建/关闭。
 *
 * trickle 是这里唯一的坑（docs/spikes/webrtc-link.md §4）：webrtc-java 的 offer 里不带候选，
 * 访客的 answer / ice 必须回灌进 [Signaling.attach] 给的那个入口，否则通道永远打不开。
 * 回灌的两个入口是 [deliverAnswer] / [deliverIce]，由 SignalClient 的事件回调驱动。
 */
class WebRtcSender(
    private val runtime: WebRtc,
    private val label: String,
    private val log: DaemonLogger,
    private val outbound: Outbound,
) : AutoCloseable {

    /** 信令出口：offer / ice 往外发。由 LinkService 接到 core 的 SignalClient 上。 */
    interface Outbound {
        fun offer(sdp: String): Boolean

        fun ice(candidate: String): Boolean

        fun peerState(state: String)
    }

    /** 一次传输的结果。sha256 是**本机**算的，guestSha256 是浏览器回执里算的。 */
    class Outcome(
        val ok: Boolean,
        val bytes: Long,
        val chunks: Int,
        val sha256: String,
        val sendMs: Long,
        val verified: Boolean,
        val guestSha256: String,
        val guestBytes: Long,
        val guestTransferMs: Long,
        val error: String,
    )

    private val closed = AtomicBoolean(false)
    private val signaling = PeerSignaling()
    private val inboundLock = Any()
    private val pendingInbound = ArrayDeque<SignalMessage>()
    private val verifyFuture = CompletableFuture<J>()
    private val channelClosed = AtomicBoolean(false)

    @Volatile
    private var inbound: Consumer<SignalMessage>? = null

    @Volatile
    private var peer: Peer? = null

    @Volatile
    private var channel: DataChannel? = null

    /** 传输进度（已写进通道的字节数）与 ack 回调；LinkService 用它续 90 s 空闲预算、更新状态。 */
    @Volatile
    var onProgress: ((Long) -> Unit)? = null

    @Volatile
    var onAck: ((Long) -> Unit)? = null

    /**
     * 建 PeerConnection 与 DataChannel，阻塞到通道打开或超时。
     * offer 会在 createChannel 之后由 webrtc-java 回调 [Outbound.offer] 发出去。
     */
    fun open(timeoutMs: Long): Boolean {
        if (closed.get()) return false
        val options = PeerOptions.builder()
            .name(label)
            .signaling(signaling)
            .gatheringTimeout(Duration.ofSeconds(5))
            // 与 spike 的发送窗口一致：8 MiB 上限、接收窗口 1 MiB（访客只回控制帧）。
            .channelSendLimit(SEND_LIMIT_BYTES)
            .channelReceiveLimit(1L * 1024 * 1024)
            .iceServer(STUN_URL)
            .build()
        val created = runtime.createPeer(options)
        peer = created
        created.onStateChange { state -> onPeerState(state) }
        created.onError { error ->
            log.event("link_webrtc_error", mapOf("error" to safe(error.message ?: error.toString())))
        }
        val opened = created.createChannel(CHANNEL_LABEL)
        channel = opened
        opened.onMessage { message -> onChannelMessage(message.isText, if (message.isText) message.text() else "", message.bytes()) }
        opened.onStateChange { state ->
            log.event("link_data_channel", mapOf("state" to state.name.lowercase()))
            if (state == dev.onvoid.webrtc.client.ChannelState.CLOSED) channelClosed.set(true)
        }
        return try {
            opened.whenOpen().get(timeoutMs, TimeUnit.MILLISECONDS)
            true
        } catch (e: Throwable) {
            log.event(
                "link_channel_open_failed",
                mapOf("error" to safe(describe(e)), "timeout_ms" to timeoutMs.toString()),
            )
            false
        }
    }

    /**
     * 发一个文件。阻塞到浏览器回 verify 或超时；失败不抛异常（看 [Outcome.ok] 与 error）。
     * [sha256] 由调用方预先算好（清单要带它，边发边算就来不及）。
     */
    fun sendFile(file: File, name: String, size: Long, mime: String, sha256: String): Outcome {
        val started = System.nanoTime()
        val current = channel
            ?: return Outcome(false, 0L, 0, sha256, 0L, false, "", 0L, 0L, "channel-not-open")
        val chunks = ((size + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
        try {
            val meta = jobj(
                "t" to jstr("meta"),
                "files" to jarr(
                    listOf(
                        jobj(
                            "name" to jstr(name),
                            "size" to jnum(size),
                            "sha256" to jstr(sha256),
                            "mime" to jstr(mime),
                        ),
                    ),
                ),
                "chunks" to jnum(chunks.toLong()),
                "chunkSize" to jnum(CHUNK_SIZE.toLong()),
            )
            current.send(Json.write(meta)).get(SEND_FUTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Throwable) {
            return Outcome(false, 0L, 0, sha256, 0L, false, "", 0L, 0L, "meta:" + describe(e))
        }

        var sent = 0L
        var index = 0
        val pending = ArrayDeque<CompletableFuture<Void>>()
        try {
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { source ->
                val readBuffer = ByteBuffer.allocate(CHUNK_SIZE)
                val frame = ByteArray(4 + CHUNK_SIZE)
                while (!closed.get()) {
                    readBuffer.clear()
                    val read = source.read(readBuffer)
                    if (read < 0) break
                    if (read == 0) continue
                    readBuffer.flip()
                    frame[0] = (index ushr 24).toByte()
                    frame[1] = (index ushr 16).toByte()
                    frame[2] = (index ushr 8).toByte()
                    frame[3] = index.toByte()
                    readBuffer.get(frame, 4, read)
                    pending.addLast(current.send(ByteBuffer.wrap(frame, 0, 4 + read)))
                    sent += read.toLong()
                    onProgress?.invoke(sent)
                    while (pending.size >= SEND_WINDOW) {
                        pending.removeFirst().get(SEND_FUTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    }
                    index++
                }
                while (pending.isNotEmpty()) {
                    pending.removeFirst().get(SEND_FUTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
            }
            val doneFrame = jobj("t" to jstr("done"), "chunks" to jnum(index.toLong()))
            current.send(Json.write(doneFrame)).get(SEND_FUTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)

            val reply = try {
                verifyFuture.get(VERIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
                null
            }
            val sendMs = (System.nanoTime() - started) / 1_000_000L
            if (reply == null) {
                return Outcome(false, sent, index, sha256, sendMs, false, "", 0L, 0L, "verify-timeout")
            }
            val guestSha = (reply.str("sha256") ?: "").lowercase()
            val guestBytes = reply.num("bytes") ?: 0L
            val guestMs = reply.num("transferMs") ?: 0L
            val verified = reply.bool("ok") == true && guestSha.equals(sha256, ignoreCase = true) && guestBytes == size
            return Outcome(
                ok = verified,
                bytes = sent,
                chunks = index,
                sha256 = sha256,
                sendMs = sendMs,
                verified = verified,
                guestSha256 = guestSha,
                guestBytes = guestBytes,
                guestTransferMs = guestMs,
                error = if (verified) "" else "verify-mismatch",
            )
        } catch (e: Throwable) {
            val sendMs = (System.nanoTime() - started) / 1_000_000L
            return Outcome(false, sent, index, sha256, sendMs, false, "", 0L, 0L, describe(e))
        }
    }

    /** 访客的 answer：回灌给 webrtc-java（trickle 必须走这一步，否则通道永远不 open）。 */
    fun deliverAnswer(sdp: String) {
        deliver(SignalMessage.of(RTCSessionDescription(RTCSdpType.ANSWER, sdp)))
    }

    /** 访客的 ICE candidate（JSON 对象或裸 candidate 字符串，worker 两种都可能传）。 */
    fun deliverIce(candidateJson: String) {
        val candidate = parseCandidate(candidateJson) ?: run {
            log.event("link_ice_unparsed", mapOf("bytes" to candidateJson.length.toString()))
            return
        }
        deliver(SignalMessage.of(candidate))
    }

    private fun deliver(message: SignalMessage) {
        if (closed.get()) return
        synchronized(inboundLock) {
            val target = inbound
            if (target == null) {
                // attach 还没被 webrtc-java 调过：先排队，attach 时按顺序补投。
                if (pendingInbound.size < 64) pendingInbound.addLast(message)
                return
            }
            target.accept(message)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val currentChannel = channel
        channel = null
        try {
            currentChannel?.close()
        } catch (_: Throwable) {
            // 对端已经走了
        }
        val currentPeer = peer
        peer = null
        try {
            currentPeer?.close()
        } catch (_: Throwable) {
            // 同上
        }
    }

    /* ---------------------------------------------------------------- 回调 */

    private fun onPeerState(state: PeerState) {
        log.event("link_peer_state", mapOf("state" to state.name.lowercase()))
        outbound.peerState(state.name.lowercase())
    }

    private fun onChannelMessage(isText: Boolean, text: String, bytes: ByteArray) {
        if (!isText) return
        val message = try {
            Json.parse(text)
        } catch (_: Throwable) {
            return
        }
        when (message.str("t")) {
            "verify" -> {
                if (!verifyFuture.isDone) verifyFuture.complete(message)
            }
            "ack" -> {
                val upTo = message.num("upTo") ?: 0L
                onAck?.invoke(upTo)
            }
            else -> Unit
        }
    }

    /* ---------------------------------------------------------------- 内部 */

    /**
     * webrtc-java 的信令适配器。
     *
     * send：webrtc-java 要往外发的 offer / candidate，交给 core 的 SignalClient（它负责签名、
     *       队列与状态机）。
     * attach：webrtc-java 把「answer / candidate 往哪里灌」的回调交给我们；daemon 把
     *       [deliverAnswer] / [deliverIce] 接在这里。
     */
    private inner class PeerSignaling : Signaling {
        override fun send(message: SignalMessage) {
            when (message.type()) {
                SignalMessage.Type.OFFER -> outbound.offer(message.description().sdp)
                SignalMessage.Type.CANDIDATE -> message.candidate()?.sdp?.let { outbound.ice(it) }
                SignalMessage.Type.END_OF_CANDIDATES -> log.event("link_ice_end", emptyMap())
                SignalMessage.Type.ANSWER -> log.event("link_peer_unexpected_answer", emptyMap())
            }
        }

        override fun attach(inbound: Consumer<SignalMessage>): Subscription {
            val queued: List<SignalMessage>
            synchronized(inboundLock) {
                this@WebRtcSender.inbound = inbound
                queued = pendingInbound.toList()
                pendingInbound.clear()
            }
            for (message in queued) {
                try {
                    inbound.accept(message)
                } catch (e: Throwable) {
                    log.event("link_peer_inbound_error", mapOf("error" to safe(describe(e))))
                }
            }
            return Subscription {
                synchronized(inboundLock) {
                    if (this@WebRtcSender.inbound === inbound) this@WebRtcSender.inbound = null
                }
            }
        }
    }

    companion object {
        const val CHANNEL_LABEL = "huchuan/1"
        const val CHUNK_SIZE = 16 * 1024

        /** 与 spike 同一个发送窗口：128 帧 ≈ 2 MiB，实测峰值 3.16 MB 未触发 ChannelFull。 */
        const val SEND_WINDOW = 128
        const val SEND_LIMIT_BYTES = 8L * 1024 * 1024

        /** 桌面分享者用的唯一一个 STUN；访客页硬编码同一个（pages/public/recv/app.js）。 */
        const val STUN_URL = "stun:stun.cloudflare.com:3478"

        private const val SEND_FUTURE_TIMEOUT_MS = 30_000L
        private const val VERIFY_TIMEOUT_MS = 30_000L

        /** 算文件 SHA-256：浏览器的 verify 回执与日志里的 sha256 都用它。 */
        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { source ->
                val buffer = ByteBuffer.allocate(256 * 1024)
                while (true) {
                    buffer.clear()
                    val read = source.read(buffer)
                    if (read < 0) break
                    buffer.flip()
                    digest.update(buffer)
                }
            }
            val out = StringBuilder(64)
            for (b in digest.digest()) out.append(String.format("%02x", b))
            return out.toString()
        }

        fun parseCandidate(jsonText: String): RTCIceCandidate? {
            val element = try {
                Json.parse(jsonText)
            } catch (_: Throwable) {
                return RTCIceCandidate(null, 0, jsonText)
            }
            if (element is J.Str) return RTCIceCandidate(null, 0, element.value)
            val candidate = element.str("candidate") ?: return null
            val mid = element.str("sdpMid")
            val index = (element.num("sdpMLineIndex") ?: 0L).toInt()
            return RTCIceCandidate(mid, index, candidate)
        }

        private fun describe(error: Throwable): String {
            var cause = error
            while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
            val message = cause.message
            return (if (message.isNullOrEmpty()) cause.javaClass.simpleName else message) +
                " (" + cause.javaClass.simpleName + ")"
        }

        private fun safe(value: String?): String {
            val text = (value ?: "").replace('\n', ' ').replace('\r', ' ').trim()
            return if (text.length <= 160) text else text.substring(0, 160)
        }
    }
}
