package app.huchuan.link

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import app.huchuan.kernel.M1Runtime

/**
 * 分享保活的进程内开关。不变式只有一条：
 *
 *   **池子里有分享 ⟺ 前台服务在跑 ⟺ 链接真的能取件。**
 *
 * 调用点只有两处：[app.huchuan.link.LinkShareController.startSession] 加入、
 * `closeSession` 移除。池子空了就停服务、撤通知；服务被系统停掉时把还挂着的分享
 * 交给 [onLost] 标成已结束 —— 不许出现「服务没了、P8 还写等待访客」。
 *
 * 线程：add / remove 从信令线程、IO 线程、界面线程都可能来，全部在 [lock] 里改。
 */
internal class LinkKeepAlive(
    private val app: Context,
    private val recordOf: (String) -> StoredShare?,
    private val onLost: (List<String>, String) -> Unit,
) {

    private val lock = Any()

    /** 现在还该保活的分享；末尾那条是通知上显示的那条（最近打开/创建的）。 */
    private val ids = LinkedHashSet<String>()

    /** 前台服务是不是已经请求起来了（[ContextCompat.startForegroundService] 成功调用过）。 */
    private var started = false

    /** 是不是本端主动停的：主动停不算「服务被系统停」，不再把分享标成已结束。 */
    private var expected = false

    fun add(share: StoredShare) {
        val needStart: Boolean
        synchronized(lock) {
            ids.remove(share.shareId)
            ids.add(share.shareId)
            needStart = !started
            if (needStart) {
                started = true
                expected = false
            }
        }
        if (!needStart) {
            // 服务已经在跑：只把通知换成这一条（多条分享时最后打开的那条在前台显示）。
            LinkNotify.post(app, share)
            return
        }
        val intent = Intent(app, LinkKeepAliveService::class.java)
            .putExtra(LinkNotify.EXTRA_SHARE, share.shareId)
        val ok = try {
            ContextCompat.startForegroundService(app, intent)
            true
        } catch (t: Throwable) {
            // Android 12+ 后台起前台服务被拒（本应用只在界面前台时走到这里，理论上到不了）。
            Log.w(M1Runtime.TAG, "event=link_fgs_start_failed share=" + share.shareId.take(8) + " error=" + t)
            false
        }
        if (!ok) synchronized(lock) { started = false }
    }

    fun remove(shareId: String) {
        var stop = false
        var next: StoredShare? = null
        synchronized(lock) {
            if (!ids.remove(shareId)) return
            if (ids.isEmpty()) {
                started = false
                stop = true
            } else {
                next = recordOf(ids.last())
            }
        }
        if (stop) stopService() else next?.let { LinkNotify.post(app, it) }
    }

    /** 本端主动停服务（池子空了 / 分享结束）：onDestroy 里不再把分享标成已结束。 */
    private fun stopService() {
        synchronized(lock) { expected = true }
        try {
            app.stopService(Intent(app, LinkKeepAliveService::class.java))
        } catch (t: Throwable) {
            Log.w(M1Runtime.TAG, "event=link_fgs_stop_failed error=" + t)
        }
        LinkNotify.cancel(app)
    }

    /**
     * 服务的 onDestroy：本端主动停（[expected]）什么都不做；
     * 否则就是系统把它停了（Android 15 起 dataSync 有 6 小时上限、各家 OEM 的后台策略），
     * 这些分享立刻交给 [onLost]。
     */
    fun onServiceDestroyed() {
        val orphan: List<String>
        synchronized(lock) {
            started = false
            if (expected) {
                expected = false
                return
            }
            orphan = ids.toList()
            ids.clear()
        }
        if (orphan.isEmpty()) return
        Log.i(M1Runtime.TAG, "event=link_fgs_lost shares=" + orphan.size)
        onLost(orphan, REASON_SERVICE_STOPPED)
    }

    companion object {
        const val REASON_SERVICE_STOPPED = "service-stopped"
    }
}
