package app.huchuan.link

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import java.lang.ref.WeakReference

/**
 * 分享链接的 WebView 宿主。
 *
 * 一个进程里所有发送端页面都住在这一个隐藏容器里；容器由 MainActivity 每次 onCreate 挂到
 * Activity 的 content 视图上（放在最底层，1×1，不接收触摸）。
 *
 * 为什么要挂在窗口里：Chromium 按窗口可见性调度页面，脱离窗口的 WebView 在部分机型上会被
 * 降频甚至暂停；挂在 Activity 上等于给它一个正常的宿主。
 *
 * 为什么要 detach 而不是销毁：Activity 重建（旋转 / 主题变更）时页面**不重建** ——
 * 容器换了，WebView 只是被摘下来、再挂回新容器，PeerConnection 与 DataChannel 不中断。
 * 真正的销毁由发送端自己在 close() 里做（[LinkWebSender.destroy]）。
 *
 * 线程：全部方法只在主线程调用。
 */
internal object LinkWebHost {

    private var container: WeakReference<FrameLayout>? = null

    /** 暂时没有窗口可挂的页面（Activity 还没建好 / 正在重建）。 */
    private val detached = ArrayList<WebView>()

    /** 建一个尺寸 1×1、不吃焦点、不吃触摸的 WebView。 */
    fun create(context: Context): WebView {
        val view = WebView(context.applicationContext)
        view.layoutParams = FrameLayout.LayoutParams(1, 1).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        view.isClickable = false
        view.isLongClickable = false
        view.isHapticFeedbackEnabled = false
        return view
    }

    /** Activity 建好：挂一个新容器，把等着挂的页面补上。 */
    fun attach(host: ViewGroup) {
        detach()
        val frame = FrameLayout(host.context)
        frame.layoutParams = ViewGroup.LayoutParams(1, 1)
        // 放在最底层：ComposeView 在上，隐藏页面永远收不到触摸。
        host.addView(frame, 0)
        container = WeakReference(frame)
        val restore = ArrayList(detached)
        detached.clear()
        for (view in restore) {
            (view.parent as? ViewGroup)?.removeView(view)
            frame.addView(view)
        }
    }

    /** Activity 销毁：只摘下来，不销毁页面；下次 attach 自动挂回去。 */
    fun detach() {
        val frame = container?.get() ?: return
        container = null
        for (index in frame.childCount - 1 downTo 0) {
            val child = frame.getChildAt(index)
            frame.removeViewAt(index)
            if (child is WebView && !detached.contains(child)) detached.add(child)
        }
        (frame.parent as? ViewGroup)?.removeView(frame)
    }

    /** 把页面挂进当前容器；没有容器就排队等下一次 attach。 */
    fun mount(view: WebView) {
        val frame = container?.get()
        if (frame == null) {
            if (!detached.contains(view)) detached.add(view)
            return
        }
        detached.remove(view)
        if (view.parent === frame) return
        (view.parent as? ViewGroup)?.removeView(view)
        frame.addView(view)
    }

    /** 页面要销毁了：先摘下来，别留在待挂队列里。 */
    fun unmount(view: WebView) {
        detached.remove(view)
        (view.parent as? ViewGroup)?.removeView(view)
    }
}
