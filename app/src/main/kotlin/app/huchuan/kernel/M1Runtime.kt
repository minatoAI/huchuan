package app.huchuan.kernel

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.huchuan.core.GroupFile
import app.huchuan.core.m1.FileEntry
import app.huchuan.core.m1.GroupRuntime
import app.huchuan.core.m1.KernelEvents
import app.huchuan.core.m1.M1Kernel
import app.huchuan.core.m1.OpRecord
import app.huchuan.core.m1.SecretBox
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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

    // ------------------------------------------------------------------ 内核事件 → 界面

    /**
     * 界面刷新的唯一驱动：内核事件（KernelEvents）。
     *
     * 内核在消息层线程 / 巡查线程上回调，这里把它压成一次「界面重新读数据」：
     * 同一节拍里的多条事件只算一次（refreshPending），未读数在独立线程上取
     * （不在内核回调里重入内核查询），改 Compose state 的那一步最后投回主线程。
     * 界面侧因此不再自己起定时器 —— 没有内核事件就没有重绘。
     */
    private val mainHandler = Handler(Looper.getMainLooper())
    private val eventThread = Executors.newSingleThreadExecutor { job ->
        Thread(job, "huchuan-ui-bridge").apply { isDaemon = true }
    }
    private val refreshPending = AtomicBoolean(false)
    private val lastUnread = HashMap<String, Int>()

    @Volatile
    private var uiRefresh: (() -> Unit)? = null

    /** 注册界面刷新（主线程调用，注册即刷一次）；返回注销句柄。 */
    fun attachUiRefresh(listener: () -> Unit): () -> Unit {
        uiRefresh = listener
        listener()
        return { if (uiRefresh === listener) uiRefresh = null }
    }

    private fun requestUiRefresh() {
        if (!refreshPending.compareAndSet(false, true)) return
        try {
            eventThread.execute {
                refreshPending.set(false)
                logUnreadChanges()
                mainHandler.post { uiRefresh?.invoke() }
            }
        } catch (_: Throwable) {
            refreshPending.set(false)
        }
    }

    /** 未读数只在变化时打一行：脚本用 event=ui_unread ... unread=N 断言 P2 角标。 */
    private fun logUnreadChanges() {
        val groups = try {
            kernel.groups()
        } catch (_: Throwable) {
            return
        }
        for (group in groups) {
            val unread = try {
                group.unreadCount()
            } catch (_: Throwable) {
                continue
            }
            val previous = synchronized(lastUnread) { lastUnread.put(group.groupId, unread) }
            if (previous != unread) {
                Log.i(TAG, "event=ui_unread group_id=" + group.groupId + " unread=" + unread)
            }
        }
    }

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

    // ------------------------------------------------------------------ 未读数（G4）

    /** 打开 P3：整群未读清零，返回清掉的条数。要写 state.json，界面线程不许调。 */
    fun markGroupRead(groupId: String): Int {
        val cleared = kernel.markGroupRead(groupId)
        Log.i(TAG, "event=ui_mark_read scope=group group_id=" + groupId + " cleared=" + cleared)
        requestUiRefresh()
        return cleared
    }

    /** 打开 P4：该文件未读清零，返回清掉的条数。同样要离开界面线程。 */
    fun markFileRead(groupId: String, fileId: String): Int {
        val cleared = kernel.markFileRead(groupId, fileId)
        Log.i(
            TAG,
            "event=ui_mark_read scope=file group_id=" + groupId + " file_id=" + fileId +
                " cleared=" + cleared,
        )
        requestUiRefresh()
        return cleared
    }

    // ------------------------------------------------------------------ 事件出口

    /**
     * 内核事件 → logcat（tag = huchuan/m1，每行 event=xxx k=v ...）。
     * 验收脚本直接 grep 这个 tag；结构化、字段值是字符串、不带空格。
     */
    private inner class LogcatEvents : KernelEvents() {

        override fun log(event: String, fields: Map<String, String>) {
            Log.i(TAG, format(event, fields))
            // 除心跳外的每条内核日志都意味着界面上的某个数字变了：op_ingested → 未读，
            // peer_announce → 成员平台/做种数，download_progress → 进度与速率，file_add → 清单。
            // heartbeat 每 2 s 一条，跟界面状态无关，不拿它当刷新节拍。
            if (event != "heartbeat") requestUiRefresh()
        }

        override fun joined(group: GroupRuntime) {
            Log.i(TAG, "event=ui_joined group_id=" + group.groupId + " name=" + clean(group.name))
            requestUiRefresh()
        }

        override fun memberJoined(group: GroupRuntime, deviceId: String, name: String) {
            Log.i(TAG, "event=ui_member_joined group_id=" + group.groupId + " dev=" + deviceId + " name=" + clean(name))
            requestUiRefresh()
        }

        override fun fileAdded(group: GroupRuntime, entry: FileEntry) {
            Log.i(
                TAG,
                "event=ui_file_added group_id=" + group.groupId + " file_id=" + entry.fileId +
                    " name=" + clean(entry.name) + " size=" + entry.size,
            )
            requestUiRefresh()
        }

        override fun fileCompleted(group: GroupRuntime, entry: FileEntry) {
            markCompleted(entry.fileId)
            Log.i(
                TAG,
                "event=ui_file_completed group_id=" + group.groupId + " file_id=" + entry.fileId +
                    " size=" + entry.size + " path=" + (group.savedFile(entry.fileId)?.absolutePath ?: ""),
            )
            requestUiRefresh()
        }

        // 未读数不在这里累加：内核按「可见 op 与读游标」现算，这里只让界面重读一次。
        override fun opReceived(group: GroupRuntime, op: OpRecord) {
            Log.i(
                TAG,
                "event=ui_op_received group_id=" + group.groupId + " file_id=" + op.fileId +
                    " op_id=" + op.opId + " author=" + op.author + " body=" + clean(op.body),
            )
            requestUiRefresh()
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
