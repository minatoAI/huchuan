package app.huchuan.kernel

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.huchuan.R
import app.huchuan.core.GroupFile
import app.huchuan.core.NotificationLevel
import app.huchuan.core.m1.FileEntry
import app.huchuan.core.m1.GroupRuntime
import app.huchuan.core.m1.KernelEvents
import app.huchuan.core.m1.M1Kernel
import app.huchuan.core.m1.OpRecord
import app.huchuan.core.m1.SecretBox
import app.huchuan.notify.GroupNotifier
import app.huchuan.notify.NotifyPermission
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // ------------------------------------------------------------------ 通知（P7 三档）

    /** 系统通知的落地实现：按群分渠道 + 三档语义（见 notify/GroupNotifier.kt）。 */
    val notifier: GroupNotifier = GroupNotifier(app, TAG)

    /**
     * 通知判定与投递都在这一条线程上：内核事件线程只排队。
     *
     * 单个线程保证「先把档位改成静音、再收到这条评论」这种顺序不会乱：渠道更新与 notify
     * 都在同一队列里按到达顺序执行。内核回调里做 Binder IPC 也会把消息层线程拖慢。
     */
    private val notifyThread = Executors.newSingleThreadExecutor { job ->
        Thread(job, "huchuan-notify").apply { isDaemon = true }
    }

    /** 当前界面位置（主线程写，通知线程读）；空串 = 不在任何群/文件页。 */
    @Volatile
    private var activeGroupId: String = ""

    @Volatile
    private var activeFileId: String = ""

    /** 应用是否在前台（ON_START/ON_STOP）。退到后台就不再压通知。 */
    @Volatile
    private var appVisible: Boolean = false

    /**
     * 加入群组时的"追赶"窗口。
     *
     * 入群那一刻拿到的 file.list / 历史 op 不是"刚发生的新事"，它们只是这个群本来就有；
     * 逐条按新文件推通知会变成入群即刷屏（实测：入群瞬间就为那份 256 KiB 素材弹了一次，
     * 还顺手把 POST_NOTIFICATIONS 授权框顶到了 P3 上面）。窗口从发起加入开始，
     * 到 joined 回调后再留 [JOIN_CATCHUP_TAIL_MS]，覆盖随后的 sync 补齐。
     */
    private val joinCatchup = AtomicBoolean(false)
    private val joinCatchupUntil = java.util.concurrent.atomic.AtomicLong(0L)

    private val _notifyRequests = MutableStateFlow(0)

    /** 权限请求信号：界面层收这个数，涨了就弹一次系统授权框（见 HuchuanApp）。 */
    val notifyRequests: StateFlow<Int> = _notifyRequests.asStateFlow()

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

    /**
     * 限速下发串行化。
     *
     * P4/P7 的输入框每敲一个数字就下发一次（5 → 51 → 512）：丢进同一个单线程，
     * 保证「最后一次输入」也是最后一次落地，不会因为线程池乱序把旧值写回去。
     */
    private val limitThread = Executors.newSingleThreadExecutor { job ->
        Thread(job, "huchuan-limits").apply { isDaemon = true }
    }
    val limitDispatcher: CoroutineDispatcher = limitThread.asCoroutineDispatcher()

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
                // 跨网发现（tracker）的基址必须在 kernel.start() 之前交给内核：内核只在这一刻
                // 建 TrackerService，起来之后再改是被忽略的（core 记 tracker_config_ignored）。
                // 开关关掉就传空串 —— 内核口径是「空串 = 整个功能关闭」，不建线程、不发请求。
                applyTrackerBaseUrl()
                kernel.start()
                started = true
                // 内核不持久化限速：设置留在 SharedPreferences，进程一起来就重新下发。
                reapplyStoredLimits()
                // 通知渠道不随进程消失，但群名/档位可能在上次运行后改过：同 id 更新一次（不重建）。
                restoreNotifyChannels()
                Log.i(
                    TAG,
                    "event=app_kernel_ready device_id=" + kernel.deviceId +
                        " device_name=" + prefs.deviceName.replace(' ', '_') +
                        " msg_port=" + kernel.localMessagePort() +
                        " bt_port=" + kernel.localBtPort() +
                        " data_dir=" + dataDir.absolutePath +
                        " keyseed_box=" + secretBox.scheme +
                        " multicast_lock=" + (multicastLock?.isHeld == true) +
                        " tracker=" + if (kernel.trackerEnabled) "on" else "off",
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

    // ------------------------------------------------------------------ 跨网发现（tracker）

    /**
     * 把 P7 的「跨网发现」设置交给内核，**只能在 kernel.start() 之前**调。
     *
     * 内核的 tracker 基址是启动期配置：起来之后再改会被忽略（core 记 tracker_config_ignored），
     * 所以这里一次冷启动只读一次盘，没有热更新。开关关掉 → 空串 → 内核只留一条
     * `tracker_disabled reason=base_url_empty`，不建 TrackerService、一条请求都不发。
     */
    private fun applyTrackerBaseUrl() {
        val base = prefs.trackerBaseUrlForKernel()
        kernel.setTrackerBaseUrl(base)
        Log.i(
            TAG,
            "event=tracker_config enabled=" + (if (base.isEmpty()) "off" else "on") +
                " base=" + (base.ifEmpty { "-" }) +
                " configured_base=" + (prefs.trackerBaseUrl.ifEmpty { "-" }),
        )
    }

    /**
     * P7「跨网发现」开关：落盘 + 一条日志。
     *
     * 改的是**下一次冷启动**的行为：内核只接受启动前给基址，运行中的 tracker 不动
     * （中途换基址会打断 passkey 连续性，core 明确不支持）。界面用 [trackerRestartNeeded]
     * 提示「下次启动生效」，不假装已生效。
     */
    fun setTrackerDiscoveryEnabled(enabled: Boolean) {
        prefs.trackerDiscoveryEnabled = enabled
        Log.i(
            TAG,
            "event=tracker_setting enabled=" + (if (enabled) "on" else "off") +
                " apply=next_start base=" + (prefs.trackerBaseUrl.ifEmpty { "-" }),
        )
    }

    /** 设置与运行中的内核不一致（改了开关但还没冷启动）。P7 据此显示「下次启动生效」。 */
    val trackerRestartNeeded: Boolean
        get() = started && kernel.trackerBaseUrl != prefs.trackerBaseUrlForKernel()

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
        // 人已经在这个群里了：该群的通知没有留存意义。
        try {
            notifier.cancel(groupId)
        } catch (_: Throwable) {
        }
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
        try {
            notifier.cancel(groupId)
        } catch (_: Throwable) {
        }
        requestUiRefresh()
        return cleared
    }

    // ------------------------------------------------------------------ 限速（P7 全局 / P4 单文件）

    /**
     * P7 全局限速：界面只改设置，真正生效的是内核 session 级限速。
     *
     * 单位 KB/s（界面上标的就是 KB/s），0 = 不限；「不限」开关打开时两项都按 0 下发。
     * 每次输入后由界面调用，冷启动时由 [reapplyStoredLimits] 按落盘值再调一次。
     */
    fun applyGlobalLimits() {
        val device = prefs.device()
        val up = if (device.unlimited) 0 else device.uploadLimitKbps.coerceAtLeast(0)
        val down = if (device.unlimited) 0 else device.downloadLimitKbps.coerceAtLeast(0)
        Log.i(
            TAG,
            "event=global_limit_set up_kbps=" + up + " down_kbps=" + down +
                " unlimited=" + device.unlimited,
        )
        pushSessionLimits(up, down)
    }

    fun setGlobalUploadLimit(kbps: Int) {
        prefs.setUploadLimit(kbps)
        applyGlobalLimits()
    }

    fun setGlobalDownloadLimit(kbps: Int) {
        prefs.setDownloadLimit(kbps)
        applyGlobalLimits()
    }

    fun setGlobalUnlimited(unlimited: Boolean) {
        prefs.setUnlimited(unlimited)
        applyGlobalLimits()
    }

    /**
     * 下发到内核 session 级：M1Kernel.setSessionUploadLimit / setSessionDownloadLimit。
     *
     * 内核走 libtorrent 的 applySettings，运行期立即生效，对已经在传的 torrent 同样有效 ——
     * 不是「逐个 torrent 下发」的近似（那样多文件同时传时总上限会变成 N 倍）。
     * 下发完立刻读回**会话里生效的值**：读到的与要的不一样就记 ok=false，不假装成功。
     */
    private fun pushSessionLimits(upKbps: Int, downKbps: Int): Boolean {
        kernel.setSessionUploadLimit(upKbps)
        kernel.setSessionDownloadLimit(downKbps)
        val up = kernel.sessionUploadLimitKbps()
        val down = kernel.sessionDownloadLimitKbps()
        val ok = up == upKbps && down == downKbps
        Log.i(
            TAG,
            "event=global_limit_applied up_kbps=" + upKbps + " down_kbps=" + downKbps +
                " session_up_kbps=" + up + " session_down_kbps=" + down + " ok=" + ok,
        )
        return ok
    }

    /** 冷启动：session 限速与每个文件的限速都按落盘值重新下发（内核不持久化这两项）。 */
    private fun reapplyStoredLimits() {
        applyGlobalLimits()
        var files = 0
        for (group in kernel.groups()) {
            for (entry in group.files()) {
                val kbps = prefs.fileUploadLimitKbps(entry.fileId)
                if (kbps <= 0) continue
                group.setUploadLimitKbps(entry.fileId, kbps)
                files++
            }
        }
        Log.i(TAG, "event=limits_restored files=" + files)
    }

    // ------------------------------------------------------------------ 通知：三档判定

    /** 发起加入群组：这段时间进来的文件/评论都算"群本来就有"，不推通知。 */
    fun beginJoinCatchup() {
        joinCatchup.set(true)
        joinCatchupUntil.set(System.currentTimeMillis() + JOIN_CATCHUP_MAX_MS)
    }

    /** 加入结束（成功或失败）：保留一小段尾巴，覆盖 join.ok 之后的 sync 补齐。 */
    fun endJoinCatchup() {
        joinCatchup.set(false)
        joinCatchupUntil.set(System.currentTimeMillis() + JOIN_CATCHUP_TAIL_MS)
    }

    private fun inJoinCatchup(): Boolean =
        joinCatchup.get() || System.currentTimeMillis() < joinCatchupUntil.get()

    /** 当前界面位置（P3 群页 / P4 文件页），由 HuchuanState 每次导航时写入。 */
    fun setActiveView(groupId: String?, fileId: String?) {
        activeGroupId = groupId ?: ""
        activeFileId = fileId ?: ""
        logActiveView()
    }

    /** 前后台切换：退到后台后即使还停在 P4 也要通知（人已经不看了）。 */
    fun setAppVisible(visible: Boolean) {
        if (appVisible == visible) return
        appVisible = visible
        logActiveView()
    }

    private fun logActiveView() {
        Log.i(
            TAG,
            "event=ui_view group_id=" + activeGroupId.ifEmpty { "-" } +
                " file_id=" + activeFileId.ifEmpty { "-" } +
                " visible=" + appVisible,
        )
    }

    /**
     * 这条通知该不该压住：人正在看这个群 / 这个文件就不发。
     *
     * 「在群页面」= P3（activeFileId 为空）；「在该文件页面」= P4 且文件对得上。
     * 在**别的**文件页上时，这个群的新评论仍然要通知。应用退到后台时一律不发压制。
     */
    private fun isViewing(groupId: String, fileId: String): Boolean {
        if (!appVisible) return false
        if (activeGroupId != groupId) return false
        return activeFileId.isEmpty() || activeFileId == fileId
    }

    /**
     * 请求 POST_NOTIFICATIONS（Android 13+）。
     *
     * 两个入口共用：首次进入 P2（HomeScreen 的 LaunchedEffect）与首次要发通知但没有权限
     * （[dispatchNotify]）。这里只发信号不弹窗 —— 系统授权框必须在界面层由
     * ActivityResultLauncher 弹（见 HuchuanApp）。已经问过一次就不再自动问
     * （AppPrefs.notifyPermissionAsked），用户拒绝后由系统设置接管；拒绝不影响应用内未读。
     */
    fun requestNotifyPermission(reason: String) {
        if (!NotifyPermission.enforced) return
        if (NotifyPermission.isGranted(app)) return
        if (prefs.notifyPermissionAsked) return
        _notifyRequests.value = _notifyRequests.value + 1
        Log.i(
            TAG,
            "event=notify_permission state=request reason=" + reason +
                " sdk=" + android.os.Build.VERSION.SDK_INT,
        )
    }

    /** 冷启动把每个群的渠道按落盘的档位同步一次（群名可能改过，档位也可能换过）。 */
    private fun restoreNotifyChannels() {
        try {
            var n = 0
            for (group in kernel.groups()) {
                notifier.syncChannel(group.groupId, group.name, prefs.notificationLevel(group.groupId))
                n++
            }
            Log.i(TAG, "event=notify_channels_restored n=" + n)
        } catch (t: Throwable) {
            Log.w(TAG, "event=notify_channels_restored error=" + t)
        }
    }

    /** 触发条件一：群内出现别人加的新文件。 */
    private fun notifyFileAdded(group: GroupRuntime, entry: FileEntry) {
        val groupId = group.groupId
        if (inJoinCatchup()) {
            Log.i(
                TAG,
                "event=notify_skip group_id=" + groupId + " file_id=" + entry.fileId +
                    " kind=file reason=catchup",
            )
            return
        }
        if (entry.addedBy == kernel.deviceId) {
            Log.i(
                TAG,
                "event=notify_skip group_id=" + groupId + " file_id=" + entry.fileId +
                    " kind=file reason=self",
            )
            return
        }
        if (isViewing(groupId, entry.fileId)) {
            Log.i(
                TAG,
                "event=notify_skip group_id=" + groupId + " file_id=" + entry.fileId +
                    " kind=file reason=viewing",
            )
            return
        }
        val text = app.getString(R.string.notify_new_file, entry.name)
        dispatchNotify(groupId, group.name, entry.fileId, "file", text)
    }

    /** 触发条件二：收到别人对我参与文件的评论（"参与"的口径见 [participates]）。 */
    private fun notifyComment(group: GroupRuntime, op: OpRecord) {
        val groupId = group.groupId
        if (op.author == kernel.deviceId) return
        if (inJoinCatchup()) {
            Log.i(
                TAG,
                "event=notify_skip group_id=" + groupId + " file_id=" + op.fileId +
                    " kind=comment reason=catchup",
            )
            return
        }
        val entry = try {
            group.file(op.fileId)
        } catch (_: Throwable) {
            null
        }
        if (entry == null) {
            Log.i(
                TAG,
                "event=notify_skip group_id=" + groupId + " file_id=" + op.fileId +
                    " kind=comment reason=file_missing",
            )
            return
        }
        if (!participates(group, entry)) {
            Log.i(
                TAG,
                "event=notify_skip group_id=" + groupId + " file_id=" + op.fileId +
                    " kind=comment reason=not_participating",
            )
            return
        }
        if (isViewing(groupId, op.fileId)) {
            Log.i(
                TAG,
                "event=notify_skip group_id=" + groupId + " file_id=" + op.fileId +
                    " kind=comment reason=viewing",
            )
            return
        }
        val author = group.memberName(op.author).ifBlank { shortDevice(op.author) }
        val text = app.getString(R.string.notify_comment, author, preview(op.body))
        dispatchNotify(groupId, group.name, op.fileId, "comment", text)
    }

    /**
     * 「我参与的文件」：我加的 / 我在做种或下载 / 我下过（本地有副本）/ 我评论过。
     *
     * 只看群内所有人的新文件不构成通知条件 —— 那样每个群都等于全量推送，跟"三档"要表达的
     * "只提醒跟我有关的事"相反（spec §3 P7「群级提醒」）。
     */
    private fun participates(group: GroupRuntime, entry: FileEntry): Boolean {
        if (entry.addedBy == kernel.deviceId) return true
        if (completed.contains(entry.fileId)) return true
        val role = try {
            group.localRole(entry.fileId)
        } catch (_: Throwable) {
            GroupRuntime.LocalRole.NONE
        }
        if (role != GroupRuntime.LocalRole.NONE) return true
        val saved = try {
            group.savedFile(entry.fileId)
        } catch (_: Throwable) {
            null
        }
        if (saved != null && saved.isFile) return true
        return try {
            group.visibleOps(entry.fileId).any { it.author == kernel.deviceId }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 三档的真正分叉点。
     *
     * - 仅应用内：一条系统通知都不发（未读由内核累加，P2 角标自己会变）；
     * - 系统气泡 / 静音：走 GroupNotifier（重要性分别是 HIGH / LOW）；
     * - 没有权限：不发、不假装发出去，记 notify_blocked 并顺手要一次权限。
     */
    private fun dispatchNotify(
        groupId: String,
        groupName: String,
        fileId: String,
        kind: String,
        text: String,
    ) {
        notifyThread.execute {
            try {
                val level = prefs.notificationLevel(groupId)
                if (level == NotificationLevel.IN_APP_ONLY) {
                    Log.i(
                        TAG,
                        "event=notify_decision group_id=" + groupId + " file_id=" + fileId +
                            " kind=" + kind + " level=in_app_only action=in_app_only text=" + clean(text),
                    )
                    return@execute
                }
                if (!notifier.canPost()) {
                    Log.i(
                        TAG,
                        "event=notify_blocked group_id=" + groupId + " file_id=" + fileId +
                            " kind=" + kind + " level=" + level.name.lowercase() +
                            " reason=permission_denied",
                    )
                    requestNotifyPermission("notify")
                    return@execute
                }
                val posted = notifier.post(level, groupId, groupName, fileId, text)
                Log.i(
                    TAG,
                    "event=notify_decision group_id=" + groupId + " file_id=" + fileId +
                        " kind=" + kind + " level=" + level.name.lowercase() +
                        " action=" + (if (posted) "post" else "drop"),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "event=notify_failed group_id=" + groupId + " error=" + t)
            }
        }
    }

    /** 通知正文里的一行预览：空格压成下划线（日志口径），超长截断。 */
    private fun preview(body: String): String {
        val text = clean(body)
        return if (text.length > 60) text.take(60) + "…" else text
    }

    private fun shortDevice(deviceId: String): String =
        if (deviceId.length > 6) deviceId.takeLast(6) else deviceId

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
            // 入群成功：追赶窗口进入尾巴阶段（见 beginJoinCatchup）。
            endJoinCatchup()
            requestUiRefresh()
        }

        override fun memberJoined(group: GroupRuntime, deviceId: String, name: String) {
            Log.i(TAG, "event=ui_member_joined group_id=" + group.groupId + " dev=" + deviceId + " name=" + clean(name))
            requestUiRefresh()
        }

        /**
         * 群内在线成员集合变了（M1.4）：链路绑定 / 断开各一次，冷启动自动重连成功也算一次 online=true。
         *
         * 这是 P6「在线」的唯一驱动 —— 收到就重读一次群数据，界面侧没有任何定时器。
         * 离线成员不在这里删除：连接表变了只是一次刷新，成员记录还在，界面按 online=false 灰显。
         */
        override fun peersChanged(group: GroupRuntime, deviceId: String, online: Boolean) {
            Log.i(
                TAG,
                "event=ui_peers_changed group_id=" + group.groupId + " dev=" + deviceId +
                    " online=" + online,
            )
            requestUiRefresh()
        }

        override fun fileAdded(group: GroupRuntime, entry: FileEntry) {
            Log.i(
                TAG,
                "event=ui_file_added group_id=" + group.groupId + " file_id=" + entry.fileId +
                    " name=" + clean(entry.name) + " size=" + entry.size,
            )
            requestUiRefresh()
            notifyFileAdded(group, entry)
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
        // 系统通知（P7 三档）在同一个回调里分叉，见 notifyComment。
        override fun opReceived(group: GroupRuntime, op: OpRecord) {
            Log.i(
                TAG,
                "event=ui_op_received group_id=" + group.groupId + " file_id=" + op.fileId +
                    " op_id=" + op.opId + " author=" + op.author + " body=" + clean(op.body),
            )
            requestUiRefresh()
            notifyComment(group, op)
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

        /** 加入群组的追赶窗口上限（比内核的加入超时略长即可）。 */
        private const val JOIN_CATCHUP_MAX_MS = 180_000L

        /** joined 之后的尾巴：覆盖 join.ok 之后才到的 sync 补齐。 */
        private const val JOIN_CATCHUP_TAIL_MS = 10_000L

        fun clean(value: String): String = value.replace(Regex("[\\s]+"), "_")
    }
}
