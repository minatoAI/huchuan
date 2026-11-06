package app.huchuan.core.link

/**
 * 信令 WebSocket 的最小抽象（link-share.md §4.3、spike §6）。
 *
 * core 不直接依赖 java.net.http：桌面（daemon / Windows 壳）与 Android（OkHttp 或平台实现）
 * 各自实现这个接口，由调用方在构造 [SignalClient] 时注入。
 *
 * 生命周期约定：
 *   1. [open] 只调用一次；实现可以异步完成，成功/失败都必须回调一次 [Listener.onOpen] 或
 *      [Listener.onClosed]。握手阶段的非 101 应答把 HTTP 状态码放进 onClosed 的 code（例如 410）。
 *   2. [send] 在 onOpen 之后调用；发送失败返回 false，不要抛异常。
 *   3. [close] 幂等；关闭后不再回调 onMessage。
 *   4. 所有回调可以来自任意线程，[SignalClient] 会把它们串到自己的状态机线程上。
 */
interface Transport {

    fun open(url: String, listener: Listener)

    fun send(text: String): Boolean

    fun close(code: Int, reason: String)

    interface Listener {
        fun onOpen()

        fun onMessage(text: String)

        /**
         * 连接结束或握手失败。code 为 WebSocket close code；握手失败时是实现能拿到的
         * HTTP 状态码（拿不到用 -1），reason 是一句可 grep 的简短原因。
         */
        fun onClosed(code: Int, reason: String)
    }
}

/** 每条信令连接新建一个 Transport；实现方负责选择 HTTP 客户端与线程模型。 */
fun interface TransportFactory {
    fun create(): Transport
}
