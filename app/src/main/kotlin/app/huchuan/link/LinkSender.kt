package app.huchuan.link

import android.content.Context
import android.util.Log
import app.huchuan.core.link.SignalClient
import app.huchuan.kernel.M1Runtime
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 分享者的 WebRTC 发送端（link-share.md §5 第 6–9 步；spike Sender 的生产化）。
 *
 * 一个访客一个 PeerConnection：访客进来时建通道、发 offer；浏览器回 answer 后
 * **必须把 answer 与远端 ICE 回灌 PeerConnection**（spike §2：只 send 永远 open 不了）。
 * 本地候选按 trickle 逐条补发，offer 之前产生的候选先攒着，等 offer 发出后按序补发。
 *
 * 字节协议与浏览器接收页一致：文本帧 meta / done，二进制帧 u32be(index) ‖ 16 KiB payload，
 * 背压 bufferedAmount > 2 MiB 暂停，浏览器每 256 KiB 回 ack（这里忽略），收完回 verify。
 */
class LinkSender(
    context: Context,
    private val shareId: String,
    private val file: File,
    private val fileName: String,
    private val mime: String,
    private val sha256: String,
    private val signal: SignalClient,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun onOpen()
        fun onProgress(sentBytes: Long, totalBytes: Long)
        fun onVerified(ok: Boolean, detail: String)
        fun onFailed(code: String)
        fun onChannelClosed()
    }

    private val appContext: Context = context.applicationContext
    private val lock = Any()

    @Volatile
    private var closed = false

    @Volatile
    private var peer: PeerConnection? = null

    @Volatile
    private var channel: DataChannel? = null

    @Volatile
    private var verified = false

    private var offerSent = false
    private var remoteSet = false
    private val pendingLocal = ArrayList<String>()
    private val pendingRemote = ArrayList<IceCandidate>()
    private var sendThread: Thread? = null

    /** 访客进来后调用一次：建 PeerConnection + DataChannel，createOffer 并发给信令。 */
    fun start() {
        val factory = try {
            PeerConnectionHolder.factory(appContext)
        } catch (t: Throwable) {
            fail("webrtc-init")
            return
        }
        val servers = ArrayList<PeerConnection.IceServer>()
        servers.add(PeerConnection.IceServer.builder(STUN_URL).createIceServer())
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        }
        val connection = try {
            factory.createPeerConnection(config, PeerObserver())
        } catch (t: Throwable) {
            fail("webrtc-peer")
            return
        }
        if (connection == null) {
            fail("webrtc-peer")
            return
        }
        peer = connection
        val init = DataChannel.Init().apply { ordered = true }
        val dataChannel = try {
            connection.createDataChannel("huchuan/1", init)
        } catch (t: Throwable) {
            fail("webrtc-channel")
            return
        }
        channel = dataChannel
        dataChannel.registerObserver(ChannelObserver())
        log("channel_created")
        createOffer(connection)
        startChannelWatchdog()
    }

    /** 访客已加入但通道 30 s 没打开：如实失败，别停在「正在连接」（spike §6-6）。 */
    private fun startChannelWatchdog() {
        val thread = Thread({
            try {
                Thread.sleep(CHANNEL_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (closed) return@Thread
            val state = channel?.state()
            if (state != DataChannel.State.OPEN) {
                log("channel_timeout state=" + state)
                fail("connect-failed")
            }
        }, "huchuan-link-channel")
        thread.isDaemon = true
        thread.start()
    }

    /** 浏览器回 answer：setRemoteDescription；完成后再补 addIceCandidate。 */
    fun onAnswer(sdp: String) {
        if (closed || sdp.isEmpty()) return
        val connection = peer ?: return
        log("answer_recv bytes=" + sdp.toByteArray(StandardCharsets.UTF_8).size)
        connection.setRemoteDescription(
            object : SdpAdapter() {
                override fun onSetSuccess() {
                    remoteSet = true
                    log("remote_set")
                    flushRemoteIce()
                }

                override fun onSetFailure(error: String?) {
                    fail("remote-sdp")
                }
            },
            SessionDescription(SessionDescription.Type.ANSWER, sdp),
        )
    }

    /** 远端 ICE（非 trickle 设计下通常没有；有就回灌）。 */
    fun onIce(raw: String) {
        if (closed || raw.isEmpty()) return
        val candidate = parseCandidate(raw) ?: return
        synchronized(lock) {
            if (!remoteSet) {
                pendingRemote.add(candidate)
                return
            }
        }
        addRemote(candidate)
    }

    fun close() {
        if (closed) return
        closed = true
        val dataChannel = channel
        val connection = peer
        channel = null
        peer = null
        sendThread?.interrupt()
        sendThread = null
        try {
            dataChannel?.unregisterObserver()
        } catch (_: Throwable) {
            /* 已关 */
        }
        try {
            dataChannel?.close()
        } catch (_: Throwable) {
            /* 已关 */
        }
        try {
            connection?.close()
        } catch (_: Throwable) {
            /* 已关 */
        }
    }

    // ------------------------------------------------------------------ offer / ICE

    private fun createOffer(connection: PeerConnection) {
        connection.createOffer(
            object : SdpAdapter() {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (description == null) {
                        fail("offer-null")
                        return
                    }
                    connection.setLocalDescription(
                        object : SdpAdapter() {
                            override fun onSetSuccess() {
                                val sent = signal.sendOffer(description.description)
                                log("offer_sent ok=" + sent + " bytes=" + description.description.length)
                                if (!sent) {
                                    fail("offer-send")
                                    return
                                }
                                synchronized(lock) {
                                    offerSent = true
                                    for (candidate in pendingLocal) {
                                        signal.sendIce(candidate)
                                    }
                                    pendingLocal.clear()
                                }
                            }

                            override fun onSetFailure(error: String?) {
                                fail("local-sdp")
                            }
                        },
                        description,
                    )
                }

                override fun onCreateFailure(error: String?) {
                    fail("offer-create")
                }
            },
            MediaConstraints(),
        )
    }

    private fun onLocalCandidate(candidate: IceCandidate) {
        if (closed) return
        val text = candidate.sdp ?: return
        synchronized(lock) {
            if (!offerSent) {
                pendingLocal.add(text)
                return
            }
        }
        if (!signal.sendIce(text)) log("ice_send_failed")
    }

    private fun flushRemoteIce() {
        val queued: List<IceCandidate>
        synchronized(lock) {
            queued = ArrayList(pendingRemote)
            pendingRemote.clear()
        }
        for (candidate in queued) addRemote(candidate)
    }

    private fun addRemote(candidate: IceCandidate) {
        val connection = peer ?: return
        try {
            connection.addIceCandidate(candidate)
        } catch (t: Throwable) {
            log("remote_ice_error " + (t.message ?: t.javaClass.simpleName))
        }
    }

    private fun parseCandidate(raw: String): IceCandidate? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (!text.startsWith("{")) return IceCandidate("0", 0, text)
        return try {
            val obj = JSONObject(text)
            val value = obj.optString("candidate", "")
            if (value.isEmpty()) null
            else IceCandidate(
                if (obj.isNull("sdpMid")) null else obj.optString("sdpMid"),
                obj.optInt("sdpMLineIndex", 0),
                value,
            )
        } catch (_: Throwable) {
            IceCandidate("0", 0, text)
        }
    }

    // ------------------------------------------------------------------ 字节

    private fun startSending() {
        if (sendThread != null) return
        val thread = Thread({ sendLoop() }, "huchuan-link-send")
        thread.isDaemon = true
        sendThread = thread
        thread.start()
    }

    private fun sendLoop() {
        val total = try {
            file.length()
        } catch (_: Throwable) {
            fail("file-missing")
            return
        }
        if (total <= 0L) {
            fail("file-empty")
            return
        }
        val chunks = ((total + CHUNK - 1) / CHUNK).toInt()
        try {
            val meta = JSONObject()
                .put("t", "meta")
                .put(
                    "files",
                    JSONArray().put(
                        JSONObject()
                            .put("name", fileName)
                            .put("size", total)
                            .put("sha256", sha256)
                            .put("mime", mime),
                    ),
                )
                .put("chunks", chunks)
                .put("chunkSize", CHUNK)
            if (!sendText(meta.toString())) throw IOException("meta rejected")
            log("send_start chunks=" + chunks + " bytes=" + total)

            RandomAccessFile(file, "r").use { raf ->
                val frame = ByteArray(4 + CHUNK)
                var index = 0
                var lastReport = 0L
                while (index < chunks) {
                    if (closed) return
                    val read = raf.read(frame, 4, CHUNK)
                    if (read <= 0) break
                    frame[0] = (index ushr 24).toByte()
                    frame[1] = (index ushr 16).toByte()
                    frame[2] = (index ushr 8).toByte()
                    frame[3] = index.toByte()
                    var waited = 0
                    while (!closed && (channel?.bufferedAmount() ?: Long.MAX_VALUE) > MAX_BUFFERED) {
                        Thread.sleep(1)
                        waited++
                        if (waited > BACKPRESSURE_TIMEOUT_MS) throw IOException("backpressure")
                    }
                    if (closed) return
                    if (!sendBinary(frame, 4 + read)) throw IOException("chunk rejected at " + index)
                    index++
                    val sent = index.toLong() * CHUNK
                    if (sent - lastReport >= REPORT_BYTES || index == chunks) {
                        lastReport = sent
                        callbacks.onProgress(minOf(sent, total), total)
                    }
                }
            }
            val done = JSONObject().put("t", "done").put("chunks", chunks)
            sendText(done.toString())
            callbacks.onProgress(total, total)
            log("send_done chunks=" + chunks)
            startVerifyWatchdog()
        } catch (t: Throwable) {
            if (!closed) {
                log("send_error " + (t.message ?: t.javaClass.simpleName))
                fail("send")
            }
        }
    }

    private fun sendText(text: String): Boolean {
        val current = channel ?: return false
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        return try {
            current.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
        } catch (_: Throwable) {
            false
        }
    }

    private fun sendBinary(bytes: ByteArray, length: Int): Boolean {
        val current = channel ?: return false
        return try {
            current.send(DataChannel.Buffer(ByteBuffer.wrap(bytes, 0, length), true))
        } catch (_: Throwable) {
            false
        }
    }

    private fun startVerifyWatchdog() {
        val thread = Thread({
            try {
                Thread.sleep(VERIFY_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (!closed && !verified) {
                log("verify_timeout")
                fail("verify-timeout")
            }
        }, "huchuan-link-verify")
        thread.isDaemon = true
        thread.start()
    }

    private fun onVerify(text: String) {
        val message = try {
            JSONObject(text)
        } catch (_: Throwable) {
            return
        }
        if (message.optString("t") != "verify") return
        val ok = message.optBoolean("ok")
        val actual = message.optString("sha256")
        val match = actual.equals(sha256, ignoreCase = true)
        verified = true
        log("verify ok=" + ok + " match=" + match + " bytes=" + message.optLong("bytes"))
        if (ok && match) callbacks.onVerified(true, "") else callbacks.onVerified(false, if (!match) "sha256" else "browser")
    }

    private fun fail(code: String) {
        if (closed) return
        log("failed code=" + code)
        callbacks.onFailed(code)
    }

    private fun log(line: String) {
        // 不把 token / 路径写进日志；shareId 前 8 位足够对应（§7）。
        Log.i(M1Runtime.TAG, "event=link_sender share=" + shareId.take(8) + " " + line)
    }

    private inner class ChannelObserver : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) {}

        override fun onStateChange() {
            val current = channel ?: return
            val state = current.state()
            log("channel_state " + state)
            when (state) {
                DataChannel.State.OPEN -> {
                    if (closed) return
                    signal.markTransferring()
                    callbacks.onOpen()
                    startSending()
                }

                DataChannel.State.CLOSED -> {
                    if (closed) return
                    callbacks.onChannelClosed()
                }

                else -> Unit
            }
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            if (buffer.binary) return
            val data = buffer.data
            val bytes = ByteArray(data.remaining())
            data.get(bytes)
            val text = String(bytes, StandardCharsets.UTF_8)
            if (text.contains("\"verify\"")) onVerify(text)
        }
    }

    private inner class PeerObserver : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            log("signaling " + state)
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            log("ice " + state)
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            log("gathering " + state)
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            if (candidate != null) onLocalCandidate(candidate)
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

        override fun onAddStream(stream: org.webrtc.MediaStream?) {}

        override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}

        override fun onDataChannel(dataChannel: DataChannel?) {}

        override fun onRenegotiationNeeded() {}

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
            log("connection " + state)
        }
    }

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    companion object {
        const val CHUNK = 16 * 1024
        const val MAX_BUFFERED = 2L * 1024 * 1024
        const val REPORT_BYTES = 1024L * 1024
        const val BACKPRESSURE_TIMEOUT_MS = 30_000
        const val VERIFY_TIMEOUT_MS = 60_000L
        const val CHANNEL_TIMEOUT_MS = 30_000L
        const val STUN_URL = "stun:stun.cloudflare.com:3478"
    }
}

/** 全进程一个 PeerConnectionFactory；initialize 只能跑一次。 */
object PeerConnectionHolder {

    private val lock = Any()

    @Volatile
    private var factory: PeerConnectionFactory? = null

    fun factory(context: Context): PeerConnectionFactory = synchronized(lock) {
        factory ?: run {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions(),
            )
            val created = PeerConnectionFactory.builder().createPeerConnectionFactory()
                ?: throw IllegalStateException("createPeerConnectionFactory returned null")
            factory = created
            created
        }
    }
}
