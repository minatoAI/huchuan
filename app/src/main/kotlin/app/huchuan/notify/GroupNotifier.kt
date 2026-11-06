package app.huchuan.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.huchuan.MainActivity
import app.huchuan.R
import app.huchuan.core.NotificationLevel

/**
 * P7 通知三档的系统侧实现。
 *
 * 渠道按群分：[channelId] = [CHANNEL_PREFIX] + groupId，渠道名就是群名。
 * 改档位只调 [syncChannel] —— 同一个 id 再 createNotificationChannel 在系统里就是"更新"，
 * 不删除不重建：删掉重建会把用户对这个渠道做过的设置一起清掉，已有通知也会变成孤儿。
 *
 * 档位 → 渠道重要性（spec「通知档」）：
 *   系统气泡 SYSTEM_BUBBLE → IMPORTANCE_HIGH：有声音、Vibration、heads-up（气泡），锁屏可见；
 *   静音     MUTED        → IMPORTANCE_LOW：不响、不出横幅，通知本身再加 setSilent(true)；
 *   仅应用内 IN_APP_ONLY  → 渠道同样留一条 LOW，但宿主根本不发通知（未读只走 P2 角标）。
 *
 * 渠道重要性由系统托管：用户若在系统设置里改过这个渠道，系统会锁住并忽略应用侧的更新，
 * 所以 [syncChannel] 每次都把**读回的实际值**打进日志，不假装成功。
 */
class GroupNotifier(
    private val context: Context,
    private val tag: String = "huchuan/m1",
) {

    private val manager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** 渠道 id 必须含 groupId（验收直接按它 grep dumpsys）。 */
    fun channelId(groupId: String): String = CHANNEL_PREFIX + groupId

    fun importanceFor(level: NotificationLevel): Int = when (level) {
        NotificationLevel.SYSTEM_BUBBLE -> NotificationManager.IMPORTANCE_HIGH
        NotificationLevel.IN_APP_ONLY, NotificationLevel.MUTED -> NotificationManager.IMPORTANCE_LOW
    }

    /**
     * 建或更新该群的渠道，返回系统里**实际生效**的重要性（读不回来给 -1）。
     * 每次档位变化、每次发通知前都会走一次，成本是两次 Binder 调用。
     */
    fun syncChannel(groupId: String, groupName: String, level: NotificationLevel): Int {
        val id = channelId(groupId)
        val wanted = importanceFor(level)
        val name = groupName.trim().ifBlank { context.getString(R.string.notify_channel_fallback) }
        val channel = NotificationChannel(id, name, wanted).apply {
            description = context.getString(R.string.notify_channel_desc)
            // 锁屏可见：气泡档在锁屏上也要看得到群名与内容。
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(level != NotificationLevel.IN_APP_ONLY)
            if (wanted >= NotificationManager.IMPORTANCE_HIGH) {
                enableVibration(true)
                enableLights(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            } else {
                enableVibration(false)
                setSound(null, null)
            }
        }
        manager.createNotificationChannel(channel)
        val effective = manager.getNotificationChannel(id)?.importance ?: -1
        Log.i(
            tag,
            "event=notify_channel group_id=" + groupId +
                " channel=" + id +
                " level=" + level.name.lowercase() +
                " want_importance=" + wanted +
                " effective_importance=" + effective +
                " ok=" + (effective == wanted),
        )
        return effective
    }

    /** 当前能不能发系统通知：权限（API 33+）+ 用户有没有在系统设置里关掉本应用通知。 */
    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, NotifyPermission.PERMISSION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * 发一条该群的通知。tag = groupId（一个群只有一条，新的替换旧的），
     * id 固定 —— 验收脚本按 `tag=<group_id>` 就能在 dumpsys 里认出"这个群有没有通知"。
     */
    fun post(
        level: NotificationLevel,
        groupId: String,
        groupName: String,
        fileId: String,
        text: String,
    ): Boolean {
        if (!canPost()) return false
        syncChannel(groupId, groupName, level)
        val silent = level == NotificationLevel.MUTED
        val builder = NotificationCompat.Builder(context, channelId(groupId))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(groupName.trim().ifBlank { context.getString(R.string.notify_channel_fallback) })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(
                if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH,
            )
            .setAutoCancel(true)
            .setContentIntent(contentIntent(groupId, fileId))
        if (silent) {
            // 双保险：渠道已经是 LOW，通知本身再 setSilent(true)，不响不震。
            builder.setSilent(true)
            builder.setDefaults(0)
        }
        NotificationManagerCompat.from(context).notify(groupId, NOTIFICATION_ID, builder.build())
        Log.i(
            tag,
            "event=notify_alert group_id=" + groupId +
                " channel=" + channelId(groupId) +
                " level=" + level.name.lowercase() +
                " silent=" + silent +
                " headsup=" + (!silent && level == NotificationLevel.SYSTEM_BUBBLE) +
                " file_id=" + fileId,
        )
        return true
    }

    /** 进入某个群/文件页时撤掉该群的通知（点通知本身也会 autoCancel）。 */
    fun cancel(groupId: String) {
        NotificationManagerCompat.from(context).cancel(groupId, NOTIFICATION_ID)
    }

    private fun contentIntent(groupId: String, fileId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(NotifyRoute.EXTRA_GROUP, groupId)
            putExtra(NotifyRoute.EXTRA_FILE, fileId)
        }
        return PendingIntent.getActivity(
            context,
            groupId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /** 渠道 id 前缀；实测 dumpsys 里按它 + groupId 直接定位一个群的渠道。 */
        const val CHANNEL_PREFIX = "huchuan-group-"

        /** 一个群只有一条通知，id 固定，靠 tag=groupId 区分。 */
        const val NOTIFICATION_ID = 7
    }
}
