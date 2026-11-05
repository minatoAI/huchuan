package app.huchuan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.huchuan.ui.theme.HuchuanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
}
