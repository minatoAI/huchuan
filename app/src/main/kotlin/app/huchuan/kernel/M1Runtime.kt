package app.huchuan.kernel

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.util.Log
import app.huchuan.core.GroupFile
import app.huchuan.core.m1.FileEntry
import app.huchuan.core.m1.GroupRuntime
import app.huchuan.core.m1.KernelEvents
import app.huchuan.core.m1.M1Kernel
import app.huchuan.core.m1.OpRecord
import app.huchuan.core.m1.SecretBox
import java.io.File

/**
 * Android 侧的 M1 内核宿主：进程内嵌内核 + 组播锁 + 日志出口 + SAF 导入。
 *
 * 生命周期跟 Application 走（整个进程只起一个内核）；界面通过 [store] 读数据。
 * 所有会阻塞的活（内核启动、加入群组、拷贝文件）都在后台线程，界面线程不碰。
 */
class M1Runtime(context: Context) {

    val app: Context = context.applicationContext

    /** 应用私有目录：state.json / identity.txt / bt / share / downloads 都在这里。 */
    val dataDir: File = File(app.filesDir, "m1")
    val shareDir: File = File(dataDir, "share")
    val inboxDir: File = File(dataDir, "inbox")

    val prefs: AppPrefs = AppPrefs(app)
    val probe: RealNetworkProbe = RealNetworkProbe(app)

    /** keySeed 包裹：优先 Android Keystore，不可用时退回身份私钥派生。 */
    val secretBox: SecretBox = try {
        KeystoreSecretBox()
    } catch (t: Throwable) {
        Log.w(TAG, "event=keystore_unavailable error=" + t)
        IdentitySecretBox(File(dataDir, "identity.txt"))
    }

    private val events = LogcatEvents()

    val kernel: M1Kernel = M1Kernel(
        dataDir = dataDir,
        deviceName = prefs.deviceName,
        portHint = MSG_PORT,
        btHint = BT_PORT,
        events = events,
        secretBox = secretBox,
    )

    val store: M1GroupStore = M1GroupStore(this)

    /** adb 调试 extra 给的手动候选（ip:port）；界面上没有入口。 */
    @Volatile
    var manualCandidates: List<String> = emptyList()

    @Volatile
    var started: Boolean = false
        private set

    @Volatile
    var startError: String = ""
        private set

    /** 本机已完成下载的文件（内存标记，进程重启后靠 savedFile 的大小判定兜底）。 */
    private val completed = java.util.Collections.synchronizedSet(HashSet<String>())

    private var multicastLock: WifiManager.MulticastLock? = null
    private var startRequested = false

    // ------------------------------------------------------------------ 生命周期

    fun start() {
        synchronized(this) {
            if (startRequested) return
            startRequested = true
        }
        val thread = Thread {
            try {
                dataDir.mkdirs()
                shareDir.mkdirs()
                inboxDir.mkdirs()
                acquireMulticastLock()
                kernel.start()
                started = true
                Log.i(
                    TAG,
                    "event=app_kernel_ready device_id=" + kernel.deviceId +
                        " device_name=" + prefs.deviceName.replace(' ', '_') +
                        " msg_port=" + kernel.localMessagePort() +
                        " bt_port=" + kernel.localBtPort() +
                        " data_dir=" + dataDir.absolutePath +
                        " keyseed_box=" + secretBox.scheme +
                        " multicast_lock=" + (multicastLock?.isHeld == true),
                )
            } catch (t: Throwable) {
                startError = t.toString()
                Log.e(TAG, "event=app_kernel_failed error=" + t, t)
            }
        }
        thread.isDaemon = true
        thread.name = "huchuan-kernel-start"
        thread.start()
    }

    fun stop() {
        try {
            if (kernel.isStarted()) kernel.stop()
        } catch (_: Throwable) {
        }
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
        multicastLock = null
    }

    /**
     * mDNS 收组播必须持 WifiManager.MulticastLock（Android 默认把组播包丢掉），
     * 拿锁要 CHANGE_WIFI_MULTICAST_STATE。整个进程期间持有。
     */
    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        try {
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val lock = wifi.createMulticastLock(LOCK_TAG)
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
            Log.i(TAG, "event=multicast_lock_acquired")
        } catch (t: Throwable) {
            Log.w(TAG, "event=multicast_lock_failed error=" + t)
        }
    }

    // ------------------------------------------------------------------ 群与文件

    /** SAF 选完文件 → 拷进私有目录 → 做种 + 广播 file.add。 */
    fun importAndShare(groupId: String, uri: Uri): GroupFile? {
        val group = kernel.group(groupId) ?: return null
        val copied = FileImporter.copyTo(app, uri, shareDir)
        Log.i(
            TAG,
            "event=share_import group_id=" + groupId +
                " name=" + copied.name.replace(' ', '_') +
                " bytes=" + copied.length() +
                " path=" + copied.absolutePath,
        )
        return shareFile(group, copied)
    }

    /** 已经在本机文件系统上的文件直接做种（自动化与调试用，与 SAF 走同一条做种路径）。 */
    fun shareFile(group: GroupRuntime, file: File): GroupFile? {
        if (!file.isFile) {
            Log.w(TAG, "event=share_missing path=" + file.absolutePath)
            return null
        }
        val entry = group.addFile(file)
        return store.toGroupFile(group, entry)
    }

    fun isCompleted(fileId: String): Boolean = completed.contains(fileId)

    fun markCompleted(fileId: String) {
        completed.add(fileId)
    }

    // ------------------------------------------------------------------ 事件出口

    /**
     * 内核事件 → logcat（tag = huchuan/m1，每行 event=xxx k=v ...）。
     * 验收脚本直接 grep 这个 tag；结构化、字段值是字符串、不带空格。
     */
    private inner class LogcatEvents : KernelEvents() {

        override fun log(event: String, fields: Map<String, String>) {
            Log.i(TAG, format(event, fields))
        }

        override fun joined(group: GroupRuntime) {
            Log.i(TAG, "event=ui_joined group_id=" + group.groupId + " name=" + clean(group.name))
        }

        override fun memberJoined(group: GroupRuntime, deviceId: String, name: String) {
            Log.i(TAG, "event=ui_member_joined group_id=" + group.groupId + " dev=" + deviceId + " name=" + clean(name))
        }

        override fun fileAdded(group: GroupRuntime, entry: FileEntry) {
            Log.i(
                TAG,
                "event=ui_file_added group_id=" + group.groupId + " file_id=" + entry.fileId +
                    " name=" + clean(entry.name) + " size=" + entry.size,
            )
        }

        override fun fileCompleted(group: GroupRuntime, entry: FileEntry) {
            markCompleted(entry.fileId)
            Log.i(
                TAG,
                "event=ui_file_completed group_id=" + group.groupId + " file_id=" + entry.fileId +
                    " size=" + entry.size + " path=" + (group.savedFile(entry.fileId)?.absolutePath ?: ""),
            )
        }

        override fun opReceived(group: GroupRuntime, op: OpRecord) {
            prefs.bumpUnread(group.groupId)
            Log.i(
                TAG,
                "event=ui_op_received group_id=" + group.groupId + " file_id=" + op.fileId +
                    " op_id=" + op.opId + " author=" + op.author + " body=" + clean(op.body),
            )
        }

        private fun format(event: String, fields: Map<String, String>): String {
            val sb = StringBuilder("event=").append(event)
            for ((key, value) in fields) {
                if (value.isEmpty()) continue
                sb.append(' ').append(key).append('=').append(clean(value))
            }
            return sb.toString()
        }
    }

    companion object {
        const val TAG = "huchuan/m1"
        const val MSG_PORT = 47100
        const val BT_PORT = 51413
        private const val LOCK_TAG = "huchuan-mdns"

        fun clean(value: String): String = value.replace(Regex("[\\s]+"), "_")
    }
}
