package app.huchuan.daemon.link

/**
 * webrtc-java 的可用性探测（不加载任何 WebRTC 类型）。
 *
 * 这里只做一件事：用反射看类在不在。真正的原生库加载在 LinkService 第一次需要时做
 * （WebRtc.create()），失败会被捕获并降级成「链接分享不可用」—— daemon 不会因为缺一个
 * 可选依赖而起不来（link-share.md §10.3 的同一条原则）。
 *
 * 单独一个对象的意义：LinkService 到 WebRtcSender 的引用只在这个探测通过之后才会被执行到，
 * 所以没有 webrtc-java 的机器上，WebRtcSender 这个类根本不会被加载（NoClassDefFoundError
 * 也就不会在别的路径上冒出来）。
 */
object WebRtcProbe {

    /** webrtc-java 的运行期入口类（spike 用的同一个）。 */
    const val RUNTIME_CLASS = "dev.onvoid.webrtc.client.WebRtc"

    fun jarPresent(): Boolean = try {
        Class.forName(RUNTIME_CLASS, false, WebRtcProbe::class.java.classLoader)
        true
    } catch (_: Throwable) {
        false
    }
}
