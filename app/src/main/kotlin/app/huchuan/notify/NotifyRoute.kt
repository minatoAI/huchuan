package app.huchuan.notify

import android.content.Intent
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 通知点击 → 页面跳转的中转站。
 *
 * 通知的 contentIntent 指向 MainActivity：Activity 已经活着时只会走 onNewIntent，
 * 不会重建 Compose，所以这里存一个 pending 目标，由 HuchuanApp 的 LaunchedEffect 消费。
 */
object NotifyRoute {

    const val EXTRA_GROUP = "huchuan.notify.group"
    const val EXTRA_FILE = "huchuan.notify.file"

    data class Target(val groupId: String, val fileId: String?)

    var pending: Target? by mutableStateOf(null)
        private set

    /** 从 Activity 的 intent 里取目标；没有本应用的 extra 就当普通冷启动。 */
    fun handle(intent: Intent?) {
        val groupId = intent?.getStringExtra(EXTRA_GROUP) ?: return
        val fileId = intent.getStringExtra(EXTRA_FILE)?.ifBlank { null }
        pending = Target(groupId, fileId)
        Log.i("huchuan/m1", "event=notify_intent group_id=" + groupId + " file_id=" + (fileId ?: "-"))
    }

    fun consume() {
        pending = null
    }
}
