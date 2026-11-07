package app.huchuan.link

import android.content.Intent
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.huchuan.kernel.M1Runtime

/**
 * 保活通知点击 → P8 的中转站（与 notify/NotifyRoute 同一个写法）。
 *
 * MainActivity 是 singleTop：通知点进来时 Activity 若已活着只走 onNewIntent，
 * 不会重建 Compose，所以这里存一个待消费的 shareId，由 HuchuanApp 的 LaunchedEffect 消费。
 */
object LinkNotifyRoute {

    var pending: String? by mutableStateOf(null)
        private set

    fun handle(intent: Intent?) {
        if (intent == null) return
        val shareId = intent.getStringExtra(LinkNotify.EXTRA_SHARE) ?: return
        if (shareId.isEmpty()) return
        pending = shareId
        Log.i(M1Runtime.TAG, "event=link_notify_open share=" + shareId.take(8))
    }

    fun consume() {
        pending = null
    }
}
