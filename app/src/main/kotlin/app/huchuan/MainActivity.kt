package app.huchuan

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.huchuan.notify.NotifyRoute
import app.huchuan.ui.theme.HuchuanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 从系统通知点进来：目标群/文件交给 HuchuanApp 的 LaunchedEffect 消费（notify/NotifyRoute.kt）。
        NotifyRoute.handle(intent)
        // 仅 adb 可用的调试入口：intent extra 不落任何界面控件。
        val debugNetwork = intent.getStringExtra(DebugIntent.NETWORK)
        val debugHold = intent.getStringExtra(DebugIntent.HOLD)
        val debugManual = intent.getStringExtra(DebugIntent.MANUAL)
        val runtime = (application as HuchuanApplication).runtime
        if (!debugManual.isNullOrBlank()) {
            // 手动 IP:port 候选（协议 §3.3）。界面没有输入口，只有 adb 能给。
            runtime.manualCandidates = debugManual.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
        setContent {
            HuchuanTheme {
                HuchuanApp(
                    runtime = runtime,
                    debugNetwork = debugNetwork,
                    debugHold = debugHold,
                )
            }
        }
    }

    /**
     * launchMode=singleTop：Activity 已经活着时点通知不会再走 onCreate（也不重建 Compose），
     * 新目标只能从这里拿。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NotifyRoute.handle(intent)
    }
}
