package app.huchuan

import android.app.Application
import app.huchuan.kernel.M1Runtime

/** 进程级持有 M1 内核：Activity 重建不影响内核与传输。 */
class HuchuanApplication : Application() {

    lateinit var runtime: M1Runtime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = M1Runtime(this)
        runtime.start()
    }
}
