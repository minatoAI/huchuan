package app.huchuan

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.huchuan.notify.NotifyRoute
import app.huchuan.share.ShareInTake
import app.huchuan.ui.theme.HuchuanTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 从系统通知点进来：目标群/文件交给 HuchuanApp 的 LaunchedEffect 消费（notify/NotifyRoute.kt）。
        NotifyRoute.handle(intent)
        // 系统分享进来（share/ShareInTake）：**当场**把 content:// 拷成私有副本。
        // 只在首次创建时认：屏幕旋转重建会把同一个 SEND intent 再送一遍，重复拷 = 重复入库。
        if (savedInstanceState == null) ShareInTake.handle(this, intent)
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
        // 同一个入口：App 已经在跑时系统把 SEND 递给栈顶实例（真机实测，见 docs/action-send.md）。
        ShareInTake.handle(this, intent)
    }
}
