package app.huchuan.link

/**
 * 发送端（会话层只认这四个动作 + 一组回调）。
 *
 * 历史：这一层曾经是直连 io.github.webrtc-sdk 的原生实现（PeerConnectionFactory + DataChannel），
 * 为了 APK 里那 12.3 MB 的 libjingle_peerconnection_so.so 换成了系统 WebView 承载
 * （[LinkWebSender]）：PeerConnection / DataChannel / 字节管道都跑在页面 JS 里，
 * 原生只留 WebView 宿主、一个 loopback HTTP 服务和文件读取。
 *
 * 会话层（[LinkSession]）与信令（core 的 SignalClient）不变：SDP 与 ICE 仍由原生信令收发，
 * 因此设备身份、签名域、重连与等待预算全都不动。
 */
internal interface LinkSender {

    /** 访客进来后调用一次：起页面、建 PeerConnection、把 offer 交给信令。 */
    fun start()

    /** 浏览器回来的 answer（SDP 原文）：回灌给页面。 */
    fun onAnswer(sdp: String)

    /** 远端 ICE candidate（字符串形式）：回灌给页面。 */
    fun onIce(raw: String)

    /** 收尾：停页面、停 loopback 服务、释放 WebView。可重复调用。 */
    fun close()

    interface Callbacks {
        fun onOpen()
        fun onProgress(sentBytes: Long, totalBytes: Long)
        fun onVerified(ok: Boolean, detail: String)
        fun onFailed(code: String)
        fun onChannelClosed()
    }
}
