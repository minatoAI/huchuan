package app.huchuan.share

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.huchuan.kernel.M1Runtime
import java.io.File

/**
 * 系统分享进来的那一批文件：从 [ShareInActivity] 交给界面层的中转站。
 *
 * 与 notify/NotifyRoute 同一个套路：Activity 与 HuchuanApp 不共享调用链，只共享这个对象
 * （它是个 Compose 状态，界面读它就订阅了「有新的分享进来」）。
 *
 * 这里存的已经是**应用私有目录里的副本**，不是 content:// —— 授权在 ShareInActivity
 * 存活期间就被消费掉了，之后一律按路径走（link-share §2.4）。
 */
object ShareInRoute {

    /** [files] 按分享顺序落盘的副本；[bytes] 总字节数（只进日志）。 */
    data class Target(val files: List<File>, val bytes: Long)

    var pending: Target? by mutableStateOf(null)
        private set

    fun offer(files: List<File>) {
        val bytes = files.sumOf { it.length() }
        pending = Target(files, bytes)
        Log.i(
            M1Runtime.TAG,
            "event=share_in_copied count=" + files.size + " bytes=" + bytes +
                " names=" + files.joinToString(",") { it.name.replace(' ', '_') },
        )
    }

    /** 取走并清空（界面线程调用）。null = 没有待处理的分享。 */
    fun take(): Target? {
        val target = pending ?: return null
        pending = null
        return target
    }
}
