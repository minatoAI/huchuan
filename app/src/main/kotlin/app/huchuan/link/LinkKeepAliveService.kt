package app.huchuan.link

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import app.huchuan.HuchuanApplication
import app.huchuan.kernel.M1Runtime

/**
 * 链接分享的前台服务（dataSync）：分享期间把进程钉住，链接才不会因为熄屏 / 切后台 /
 * 回桌面变成 offline。
 *
 * 它自己不干活：WebRTC 与信令都在 [LinkShareController] 里，服务只负责
 *   1. `startForeground` 挂一条常驻通知（标题「正在分享 <文件名>」，正文剩余有效期）；
 *   2. 通知栏「结束分享」→ 交给控制器撤销链接并停服务；
 *   3. 被系统停掉（onDestroy / onTimeout）时让控制器把这几条分享标成已结束。
 *
 * 进程真被杀了的话这里什么都不会执行 —— 那一种由 [LinkShareController] 在下次冷启动
 * 时清扫（见 `endActive`），因为已死的进程写不了任何标记。
 */
class LinkKeepAliveService : Service() {

    private fun controller(): LinkShareController? =
        (application as? HuchuanApplication)?.runtime?.link

    override fun onCreate() {
        super.onCreate()
        LinkNotify.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val shareId = intent?.getStringExtra(LinkNotify.EXTRA_SHARE).orEmpty()
        if (intent?.action == LinkNotify.ACTION_STOP) {
            Log.i(M1Runtime.TAG, "event=link_notify_stop share=" + shareId.take(8))
            controller()?.endFromNotification(shareId)
            return START_NOT_STICKY
        }
        val share = controller()?.share(shareId)
        if (share == null || !share.active()) {
            Log.i(M1Runtime.TAG, "event=link_fgs_no_share share=" + shareId.take(8))
            stopSelf()
            return START_NOT_STICKY
        }
        LinkNotify.ensureChannel(this)
        val notification = LinkNotify.build(this, share)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(LinkNotify.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(LinkNotify.NOTIFICATION_ID, notification)
        }
        Log.i(
            M1Runtime.TAG,
            "event=link_fgs_start share=" + share.shareId.take(8) +
                " channel=" + LinkNotify.CHANNEL_ID +
                " visible=" + LinkNotify.canShow(this),
        )
        return START_NOT_STICKY
    }

    /**
     * Android 15（API 35）起 dataSync 前台服务 6 小时上限，超时系统先回调这里再停服务。
     * 本应用只在分享进行时开服务，7 天有效期意味着真挂满 6 小时就会走到这里：
     * 宁可把分享标成已结束，也不让用户以为链接还活着。
     */
    override fun onTimeout(startId: Int) {
        Log.i(M1Runtime.TAG, "event=link_fgs_timeout start_id=" + startId)
        stopSelf()
    }

    override fun onDestroy() {
        controller()?.onServiceDestroyed()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
