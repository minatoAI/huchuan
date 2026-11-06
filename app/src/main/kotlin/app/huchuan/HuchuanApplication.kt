package app.huchuan

import android.app.Application
import android.util.Log
import app.huchuan.kernel.LocalNetworkPermission
import app.huchuan.kernel.M1Runtime

/** 进程级持有 M1 内核：Activity 重建不影响内核与传输。 */
class HuchuanApplication : Application() {

    lateinit var runtime: M1Runtime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = M1Runtime(this)
        // Android 17（API 37）起没有 ACCESS_LOCAL_NETWORK 就没有局域网：内核一启动就开
        // mDNS 组播套接字，赶在授权之前起，发现层会带着错误状态活到进程结束，之后就算
        // 用户点了「允许」也不会自己恢复（实测：授权后再 join，lan 候选 0 条）。
        // 所以没授权时先不起内核，由 P0 在拿到权限的那一刻调用 start()（见 ui/NetworkScreen）。
        // API < 37 或已授权时行为不变，仍在 Application 里起。
        if (!LocalNetworkPermission.enforced || LocalNetworkPermission.isGranted(this)) {
            runtime.start()
        } else {
            Log.i(LocalNetworkPermission.LOG_TAG, "event=app_kernel_deferred reason=local_network_denied")
        }
    }
}
