package app.huchuan.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.huchuan.MainActivity
import app.huchuan.R
import app.huchuan.kernel.M1Runtime
import app.huchuan.notify.NotifyPermission

/**
 * 链接分享的常驻通知 —— 前台服务在系统里唯一看得见的那一面。
 *
 * 渠道单独一条（[CHANNEL_ID]）：与群通知（GroupNotifier 的 huchuan-group-*）分开。
 * 用户在系统设置里关掉群通知时不会顺手关掉这一条 —— 关掉它就等于关掉「分享还在跑」
 * 的唯一可见信号与唯一结束入口。
 *
 * 通知内容只有两行：标题「正在分享 <文件名>」、正文剩余有效期（与 P8 的 `expiryLabel`
 * 同一口径，文案只留一份）。点通知回 P8，[NotificationCompat] 的 action 结束分享。
 */
object LinkNotify {

    /** 渠道 id：验收脚本按它在 dumpsys 里认这条通知。 */
    const val CHANNEL_ID = "huchuan-link-active"

    /** GroupNotifier 用 7（按群分 tag）；保活通知全局只有一条，id 固定。 */
    const val NOTIFICATION_ID = 8

    /** 通知与前台服务之间传分享 id；点通知进 MainActivity 也读这个 key。 */
    const val EXTRA_SHARE = "huchuan.link.share"

    /** 通知栏「结束分享」→ 服务收到这个 action。 */
    const val ACTION_STOP = "app.huchuan.link.STOP"

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60L * MINUTE_MS
    private const val DAY_MS = 24L * HOUR_MS

    /** 建或更新渠道。重复调用在系统里就是"更新"，不会清掉用户对这条渠道的设置。 */
    fun ensureChannel(context: Context) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.link_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.link_channel_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
        Log.i(M1Runtime.TAG, "event=link_channel channel=" + CHANNEL_ID + " importance=" + channel.importance)
    }

    /**
     * 剩余有效期（与 P8/P9 同一套文案）：天 / 小时 / 分钟，向上取整。
     * 界面侧 [app.huchuan.ui.expiryLabel] 就是这一句，不另写一份。
     */
    fun expiryText(context: Context, expiresAt: Long): String {
        val left = expiresAt - System.currentTimeMillis()
        return when {
            left <= 0L -> context.getString(R.string.p8_expired)
            left >= DAY_MS ->
                context.getString(R.string.p8_expires_days, ((left + DAY_MS - 1) / DAY_MS).toInt())

            left >= HOUR_MS ->
                context.getString(R.string.p8_expires_hours, ((left + HOUR_MS - 1) / HOUR_MS).toInt())

            else -> context.getString(
                R.string.p8_expires_minutes,
                ((left + MINUTE_MS - 1) / MINUTE_MS).toInt().coerceAtLeast(1),
            )
        }
    }

    /**
     * 这条通知现在看不看得见：POST_NOTIFICATIONS（API 33+）与「本应用通知」总开关。
     * 看不见时服务照样能跑（前台服务不依赖通知权限），但用户既看不到也停不了 ——
     * 所以 P8 在这种情况下必须写明「离开此页可能中断分享」。
     */
    fun canShow(context: Context): Boolean = NotifyPermission.isGranted(context) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun build(context: Context, share: StoredShare): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.link_notify_title, share.fileName))
            .setContentText(expiryText(context, share.expiresAt))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // 常驻：分享没结束就不该被划掉（用户只能点「结束分享」）。
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openIntent(context, share.shareId))
            .addAction(0, context.getString(R.string.link_notify_stop), stopIntent(context, share.shareId))
            .build()

    /** 服务已经在跑时换一条通知（多条分享里换显示哪条）；不在跑时只当更新。 */
    fun post(context: Context, share: StoredShare) {
        ensureChannel(context)
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(context, share))
        } catch (t: Throwable) {
            Log.w(M1Runtime.TAG, "event=link_notify_failed share=" + share.shareId.take(8) + " error=" + t)
        }
    }

    fun cancel(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        } catch (_: Throwable) {
            /* 没有就没有 */
        }
    }

    /** 点通知回 P8（与群通知同一条路子：MainActivity singleTop + 待消费目标）。 */
    private fun openIntent(context: Context, shareId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_SHARE, shareId)
        }
        return PendingIntent.getActivity(
            context,
            shareId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopIntent(context: Context, shareId: String): PendingIntent {
        val intent = Intent(context, LinkKeepAliveService::class.java).apply {
            action = ACTION_STOP
            putExtra(EXTRA_SHARE, shareId)
        }
        return PendingIntent.getService(
            context,
            shareId.hashCode() + 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
