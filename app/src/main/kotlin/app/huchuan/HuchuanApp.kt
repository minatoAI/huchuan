package app.huchuan

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.huchuan.core.Group
import app.huchuan.core.GroupFile
import app.huchuan.core.GroupStore
import app.huchuan.core.InMemoryGroupStore
import app.huchuan.core.InMemoryLocalDeviceStore
import app.huchuan.core.InMemoryNetworkProbe
import app.huchuan.core.LocalDevice
import app.huchuan.core.LocalDeviceStore
import app.huchuan.core.NetworkCheckItem
import app.huchuan.core.NetworkCheckKind
import app.huchuan.core.NetworkProbe
import app.huchuan.core.NetworkReport
import app.huchuan.core.NetworkStatus
import app.huchuan.kernel.M1Runtime
import app.huchuan.kernel.MagnetImport
import app.huchuan.kernel.PublicPublish
import app.huchuan.link.LinkNotifyRoute
import app.huchuan.link.LinkShareController
import app.huchuan.notify.NotifyPermission
import app.huchuan.notify.NotifyRoute
import app.huchuan.share.ShareInRoute
import app.huchuan.ui.FileScreen
import app.huchuan.ui.GroupScreen
import app.huchuan.ui.HomeScreen
import app.huchuan.ui.MembersScreen
import app.huchuan.ui.NetworkScreen
import app.huchuan.ui.NewGroupScreen
import app.huchuan.ui.SettingsDrawer
import app.huchuan.ui.ShareLinkScreen
import app.huchuan.ui.ShareManageScreen
import app.huchuan.ui.UserNameScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 仅 adb 可用的调试入口，界面上不出现任何开关。 */
object DebugIntent {
    const val NETWORK = "huchuan.debug.network"
    const val HOLD = "huchuan.debug.hold"

    /** 手动 IP:port 候选，逗号分隔；给 mDNS 不通的局域网兜底（协议 §3.3）。 */
    const val MANUAL = "huchuan.debug.manual"
}

/**
 * 调试用网络探针：只替换检测结果，不改核心接口。
 * limited = 降级（仅局域网可用）；failed = 检测失败；offline = 无网络连接。
 */
private class DebugNetworkProbe(
    private val delegate: NetworkProbe,
    private val mode: String?,
) : NetworkProbe {
    override fun check(): NetworkReport = when (mode) {
        "limited" -> NetworkReport(
            status = NetworkStatus.FAIL,
            items = listOf(
                NetworkCheckItem(NetworkCheckKind.NAT_TYPE, passed = false),
                NetworkCheckItem(NetworkCheckKind.IPV6, passed = true),
                NetworkCheckItem(NetworkCheckKind.IPV4, passed = true),
            ),
        )

        "failed" -> NetworkReport(status = NetworkStatus.FAIL)
        "offline" -> NetworkReport(status = NetworkStatus.OFFLINE)
        else -> delegate.check()
    }
}

/** 内核失败码：对方是个人空间，不能加入（docs/core.md §3.13）。 */
private const val JOIN_ERR_PERSONAL = "E_JOIN_PERSONAL"

/** 页面（spec §2）。P7 设置是 P2 上的抽屉，不占独立目的地。 */
sealed interface Destination {
    data class Network(val fromSettings: Boolean) : Destination

    data object UserName : Destination

    data object Home : Destination

    data class GroupPage(val groupId: String) : Destination

    data class FilePage(val groupId: String, val fileId: String) : Destination

    data class NewGroup(val firstLaunch: Boolean) : Destination

    data class Members(val groupId: String) : Destination

    /** P8：已有的分享（创建成功后 NewLink 换成它）。 */
    data class LinkShare(val shareId: String) : Destination

    /** P8：从 P4「分享链接」进来，先创建。 */
    data class NewLink(val groupId: String, val fileId: String) : Destination

    /** P9：分享管理（P7 入口）。 */
    data object ShareManage : Destination
}

/**
 * 界面状态：数据全部来自 [GroupStore]（真机上是 M1 内核的投影），另加一个返回栈。
 * revision 是刷新版本号：内核事件推一次就 +1（见 HuchuanApp 的 DisposableEffect），
 * 页面读它就订阅了数据变化。
 */
class HuchuanState(
    val groups: GroupStore,
    val device: LocalDeviceStore,
    val probe: NetworkProbe,
    val runtime: M1Runtime? = null,
) {
    private val stack = mutableStateListOf<Destination>(Destination.Network(fromSettings = false))

    var revision by mutableIntStateOf(0)
        private set

    val current: Destination get() = stack.last()

    val canGoBack: Boolean get() = stack.size > 1

    val deviceState: LocalDevice
        get() {
            @Suppress("UNUSED_EXPRESSION")
            revision
            return device.device()
        }

    val groupList: List<Group>
        get() {
            @Suppress("UNUSED_EXPRESSION")
            revision
            return groups.groups()
        }

    fun groupOrNull(groupId: String): Group? {
        @Suppress("UNUSED_EXPRESSION")
        revision
        return groups.group(groupId)
    }

    /**
     * 个人空间的 gid（docs/design/personal-space.md）；内核没起来或静态壳时是 null。
     *
     * 只读这一个值就能回答「这个群是不是个人空间」：P2 置顶、P3 去掉「成员」入口与换空态文案
     * 都靠它，界面不再按群名猜。
     */
    val personalGroupId: String?
        get() {
            @Suppress("UNUSED_EXPRESSION")
            revision
            return runtime?.kernel?.personalSpace()?.groupId
        }

    fun isPersonal(groupId: String): Boolean = groupId == personalGroupId

    /**
     * 「已有群组」的唯一判据：**除个人空间外**还有群。
     *
     * 个人空间常驻 [groupList]（docs/design/personal-space.md），用 `groupList.isNotEmpty()` 判
     * 会把每个新用户都当成老用户：首启直接落 P2，P5 只剩 P2「+」一条路。
     * 认个人空间只认内核的 isPersonal 标记（内核 helloGroupOrNull 也按它过滤「可加入的群」），
     * 不拿群名或 gid 字符串猜。
     */
    val hasRealGroups: Boolean
        get() {
            @Suppress("UNUSED_EXPRESSION")
            revision
            val kernel = runtime?.kernel
            // 没有内核（静态壳）：groupList 里不可能有个人空间，按列表非空判。
            if (kernel == null) return groupList.isNotEmpty()
            return kernel.groups().any { group -> !group.isPersonal }
        }

    /** 最近一次加入失败的原因（内核失败码 / 一句话）；成功或还没试过时是空串。 */
    val lastJoinError: String
        get() = runtime?.kernel?.lastJoinError().orEmpty()

    /** 最近一次加入是不是被对方以「个人空间不可加入」明确拒绝。 */
    val lastJoinWasPersonal: Boolean
        get() = lastJoinError == JOIN_ERR_PERSONAL

    fun push(destination: Destination) {
        stack.add(destination)
        syncActiveView()
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        syncActiveView()
    }

    /** 把栈顶换成另一个目的地（P8 创建成功：NewLink → LinkShare，不再重复创建）。 */
    fun replaceTop(destination: Destination) {
        if (stack.isEmpty()) return
        stack[stack.size - 1] = destination
        syncActiveView()
    }

    fun enterHome() {
        stack.clear()
        stack.add(Destination.Home)
        syncActiveView()
    }

    /** 首次启动且无群组：P1 保存后、冷启动放行后直接进 P5，此页无返回（spec §3 P5 出口）。 */
    fun enterNewGroupFirstLaunch() {
        stack.clear()
        stack.add(Destination.NewGroup(firstLaunch = true))
        syncActiveView()
    }

    /**
     * 冷启动放行前等内核把 state.json 读完：群列表（连同里面的个人空间标记）在
     * [M1Runtime.started] 之前不是定论，早判一帧会把老用户扔回 P5。没有内核（静态壳）时立刻返回。
     * 上限 5 s —— 内核起不来也照常放行，落点判断按「没有群」处理。
     */
    suspend fun awaitGroupsLoaded(timeoutMs: Long = 5_000) {
        val rt = runtime ?: return
        var waited = 0L
        while (!rt.started && rt.startError.isEmpty() && waited < timeoutMs) {
            delay(100)
            waited += 100
        }
    }

    /** 加入或创建成功 → P3，且 P3 的系统返回必须落到 P2。 */
    fun openGroup(groupId: String) {
        stack.clear()
        stack.add(Destination.Home)
        stack.add(Destination.GroupPage(groupId))
        syncActiveView()
    }

    /** 点通知进 P4：P2 是返回栈底，系统返回一次落到 P3、再一次落到 P2。 */
    fun openFile(groupId: String, fileId: String) {
        stack.clear()
        stack.add(Destination.Home)
        stack.add(Destination.GroupPage(groupId))
        stack.add(Destination.FilePage(groupId, fileId))
        syncActiveView()
    }

    /**
     * 把「人在哪一页」告诉内核：系统通知只在人不在该群/该文件页时才发
     * （M1Runtime.isViewing）。P6 成员页不算"在该群页面"——那里看不到新评论。
     */
    private fun syncActiveView() {
        val runtime = runtime ?: return
        when (val destination = current) {
            is Destination.GroupPage -> runtime.setActiveView(destination.groupId, null)
            is Destination.FilePage -> runtime.setActiveView(destination.groupId, destination.fileId)
            else -> runtime.setActiveView(null, null)
        }
    }

    /**
     * 请界面层弹一次 POST_NOTIFICATIONS 授权框（Android 13+）。
     * 首次进入 P2 走这里；首次要发通知但没权限时由 M1Runtime 自己发同一个信号。
     */
    fun requestNotifyPermission(reason: String) {
        runtime?.requestNotifyPermission(reason)
    }

    fun touch() {
        revision++
    }

    // ------------------------------------------------------------------ 需要内核的动作（一律离开界面线程）

    /** P5「加入」：只给后 8 位，内核自己去发现 + 连接。 */
    suspend fun joinGroup(tail8: String): Group? = withContext(Dispatchers.IO) {
        groups.joinGroup(tail8)
    }

    /**
     * P5「新建群组」。内核要注册 mDNS 服务、写 state.json —— 都是网络/磁盘动作，
     * 放到界面线程会被 Android 直接抛 NetworkOnMainThreadException。
     */
    suspend fun createGroup(name: String): Group = withContext(Dispatchers.IO) {
        groups.createGroup(name)
    }

    /**
     * P3 进入群组：整群未读清零（G4）。推进读游标要写 state.json，不能占界面线程。
     * 游标由内核按「可见 op 的顺序键」推进，重连补齐、乱序到达都不会算错。
     */
    suspend fun markGroupRead(groupId: String) = withContext(Dispatchers.IO) {
        runtime?.markGroupRead(groupId)
        Unit
    }

    /** P4 打开文件：该文件未读清零；群级未读跟着减。 */
    suspend fun markFileRead(groupId: String, fileId: String) = withContext(Dispatchers.IO) {
        runtime?.markFileRead(groupId, fileId)
        Unit
    }

    /** P4 发评论：签名后要广播给对端，同样不能占界面线程。 */
    suspend fun addComment(groupId: String, fileId: String, body: String, parentId: String?) {
        withContext(Dispatchers.IO) {
            groups.addComment(groupId, fileId, body, parentId)
        }
        touch()
    }

    /** P3「添加文件」：SAF URI → 私有目录副本 → 做种 → 广播 file.add。 */
    suspend fun shareDocument(groupId: String, uri: Uri): GroupFile? = withContext(Dispatchers.IO) {
        runtime?.importAndShare(groupId, uri)
    }

    /**
     * 系统分享进来的副本直接进群：share/ShareInActivity 已经把 content:// 读完并落盘
     * （授权只在那个 Activity 活着时有效，见 link-share §2.4），这里只按路径做种，
     * 与 P3「添加文件」走同一个 [M1Runtime.shareFile]。
     *
     * 返回按分享顺序落地的 fileId；空 = 一个都没进（群没了 / 文件不见了）。
     */
    suspend fun shareFiles(groupId: String, files: List<File>): List<String> = withContext(Dispatchers.IO) {
        val rt = runtime ?: return@withContext emptyList()
        val group = rt.kernel.group(groupId) ?: return@withContext emptyList()
        files.mapNotNull { file ->
            val shared = rt.shareFile(group, file)
            Log.i(
                M1Runtime.TAG,
                "event=share_in_import group_id=" + groupId +
                    " name=" + file.name.replace(' ', '_') +
                    " bytes=" + file.length() +
                    " file_id=" + (shared?.id ?: "-"),
            )
            shared?.id
        }
    }

    /**
     * P3 弹层「磁力链接」：内核要等元数据（默认 60 s），**必须离开界面线程**。
     * 结果原样回给界面（一句话，显示在输入框下一行，不弹窗）。
     */
    suspend fun importMagnet(groupId: String, magnet: String): MagnetImport {
        val result = withContext(Dispatchers.IO) {
            runtime?.importPublicMagnet(groupId, magnet)
                ?: MagnetImport(ok = false, reason = "", message = "", fileId = "")
        }
        touch()
        return result
    }

    /**
     * P4「移除」：只在本机藏起来（不广播、不删文件），正在跑的任务先休眠。
     * 藏完之后这个条目就不在 [groupOrNull] 里了，P4 自己按「文件没了」退回 P3。
     */
    suspend fun removeLocalFile(groupId: String, fileId: String) {
        withContext(Dispatchers.IO) { runtime?.removeLocalFile(groupId, fileId) }
        touch()
    }

    // ------------------------------------------------------------------ 公开到群外（P4）

    /** 正在「公开到群外」的 fileId：建种要对整个源文件做一次全量哈希，10 GB 级不可能是瞬间。 */
    private val publishingIds = mutableStateOf<Set<String>>(emptySet())

    /** 上一次发布失败时内核给的中文（成功 / 没试过时没有这个 key）。 */
    private val publishMessages = mutableStateOf<Map<String, String>>(emptyMap())

    fun isPublishing(fileId: String): Boolean = publishingIds.value.contains(fileId)

    fun publishMessage(fileId: String): String = publishMessages.value[fileId].orEmpty()

    /**
     * P4「公开到群外」：新建一条公开种子（原私有条目不动）。
     *
     * 三条界面口径：① [isPublishing] 期间 P4 的状态位显示「发布中」；② 失败只把内核那句中文
     * 交给界面显示在正文里，不弹窗；③ 幂等 —— 连点 / 重试不会起第二个任务，内核同一私有 id
     * 第二次也直接回同一条公开条目，不会多出一条。
     */
    suspend fun publishPublic(groupId: String, fileId: String) {
        if (isPublishing(fileId)) return
        publishingIds.value = publishingIds.value + fileId
        publishMessages.value = publishMessages.value - fileId
        val outcome = try {
            withContext(Dispatchers.IO) {
                runtime?.publishPublic(groupId, fileId)
                    ?: PublicPublish(ok = false, reason = "", message = "", fileId = fileId)
            }
        } finally {
            publishingIds.value = publishingIds.value - fileId
        }
        if (!outcome.ok && outcome.message.isNotEmpty()) {
            publishMessages.value = publishMessages.value + (fileId to outcome.message)
        }
        touch()
    }

    /**
     * P4「取消公开」：停种 + 把公开条目从本机清单移除，不广播、不删磁盘文件。
     * 完成之后这个条目就不在 [groupOrNull] 里了，P4 自己按「文件没了」退回 P3（私有条目还在）。
     */
    suspend fun unpublishPublic(groupId: String, fileId: String) {
        withContext(Dispatchers.IO) { runtime?.unpublishPublic(groupId, fileId) }
        touch()
    }

    /** P4 激活/休眠：真起真停 BT 任务。 */
    suspend fun setFileActive(groupId: String, fileId: String, active: Boolean) {
        withContext(Dispatchers.IO) {
            groups.setFileActive(groupId, fileId, active)
        }
        touch()
    }

    /**
     * P4 单文件限速（KB/s，0 = 不限）。走 [M1Runtime.limitDispatcher]：界面上每敲一个数字
     * 下发一次，必须按输入顺序落地，而且要离开界面线程（内核要碰 libtorrent handle）。
     */
    suspend fun setFileUploadLimit(groupId: String, fileId: String, kbps: Int) {
        val runtime = runtime
        if (runtime == null) {
            groups.setFileUploadLimit(groupId, fileId, kbps)
        } else {
            withContext(runtime.limitDispatcher) {
                groups.setFileUploadLimit(groupId, fileId, kbps)
            }
        }
        touch()
    }

    // ------------------------------------------------------------------ P7 全局限速（内核 session 级）

    suspend fun setGlobalUploadLimit(kbps: Int) = applyGlobal(GlobalLimitKind.UPLOAD, kbps)

    suspend fun setGlobalDownloadLimit(kbps: Int) = applyGlobal(GlobalLimitKind.DOWNLOAD, kbps)

    suspend fun setGlobalUnlimited(unlimited: Boolean) {
        val runtime = runtime
        if (runtime == null) {
            device.setUnlimited(unlimited)
        } else {
            withContext(runtime.limitDispatcher) { runtime.setGlobalUnlimited(unlimited) }
        }
        touch()
    }

    // ------------------------------------------------------------------ P7 跨网发现（tracker）

    /** 链接分享：构建期配置了 http(s) 基址才可用（link-share §10.3）。 */
    val link: LinkShareController? get() = runtime?.link

    val linkAvailable: Boolean get() = link?.enabled == true

    /** 只有接了内核的壳才有这一项：静态壳里没有任何「跨网」行为可以开关。 */
    val trackerAvailable: Boolean get() = runtime != null

    /** P7「跨网发现」开关的当前值（落盘值；内核在冷启动时读它）。 */
    val trackerDiscoveryEnabled: Boolean
        get() {
            @Suppress("UNUSED_EXPRESSION")
            revision
            return runtime?.prefs?.trackerDiscoveryEnabled ?: false
        }

    /** 设置与运行中的内核不一致时提示「重启后生效」。 */
    val trackerRestartNeeded: Boolean
        get() {
            @Suppress("UNUSED_EXPRESSION")
            revision
            return runtime?.trackerRestartNeeded ?: false
        }

    /** 改开关只落盘 + 记日志；真正生效在下一次冷启动（内核只接受启动前的基址）。 */
    suspend fun setTrackerDiscoveryEnabled(enabled: Boolean) {
        val runtime = runtime
        if (runtime != null) {
            withContext(Dispatchers.IO) { runtime.setTrackerDiscoveryEnabled(enabled) }
        }
        touch()
    }

    private suspend fun applyGlobal(kind: GlobalLimitKind, kbps: Int) {
        val runtime = runtime
        if (runtime == null) {
            when (kind) {
                GlobalLimitKind.UPLOAD -> device.setUploadLimit(kbps)
                GlobalLimitKind.DOWNLOAD -> device.setDownloadLimit(kbps)
            }
        } else {
            withContext(runtime.limitDispatcher) {
                when (kind) {
                    GlobalLimitKind.UPLOAD -> runtime.setGlobalUploadLimit(kbps)
                    GlobalLimitKind.DOWNLOAD -> runtime.setGlobalDownloadLimit(kbps)
                }
            }
        }
        touch()
    }
}

/** P7 全局限速的两个方向；界面上是两个输入框，下发路径相同。 */
private enum class GlobalLimitKind { UPLOAD, DOWNLOAD }

@Composable
fun HuchuanApp(
    runtime: M1Runtime? = null,
    debugNetwork: String? = null,
    debugHold: String? = null,
    state: HuchuanState = remember(runtime, debugNetwork) {
        if (runtime != null) {
            HuchuanState(
                groups = runtime.store,
                device = runtime.prefs,
                probe = DebugNetworkProbe(runtime.probe, debugNetwork),
                runtime = runtime,
            )
        } else {
            HuchuanState(
                groups = InMemoryGroupStore(),
                device = InMemoryLocalDeviceStore(),
                probe = DebugNetworkProbe(InMemoryNetworkProbe(), debugNetwork),
            )
        }
    },
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // 内核事件驱动刷新：KernelEvents（op_ingested / peer_announce / download_progress /
    // file_add …）一有变化就重读一次数据，界面自己不起定时器。没有内核时是静态壳，
    // 本来就不会变，不需要刷新。
    DisposableEffect(state, runtime) {
        val detach = runtime?.attachUiRefresh { state.touch() }
        onDispose { detach?.invoke() }
    }

    // 通知权限（Android 13+）：首次进 P2 或首次要发通知时由 runtime 发信号，这里弹一次系统框。
    // 全局只在 HuchuanApp 里放一个 launcher：Popup 在哪个页面被触发不重要，宿主 Activity 只有一个。
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var notifyRequestSeq by remember { mutableIntStateOf(0) }
    LaunchedEffect(runtime) {
        runtime?.notifyRequests?.collect { notifyRequestSeq = it }
    }
    val notificationPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        NotifyPermission.log(if (granted) "granted" else "denied", "source=prompt")
    }
    LaunchedEffect(notifyRequestSeq) {
        val rt = runtime ?: return@LaunchedEffect
        if (notifyRequestSeq <= 0) return@LaunchedEffect
        if (!NotifyPermission.enforced) return@LaunchedEffect
        if (NotifyPermission.isGranted(context)) return@LaunchedEffect
        // 后台不能弹系统框：等下一次前台（比如进 P2）再说，信号不消费。
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return@LaunchedEffect
        }
        rt.prefs.notifyPermissionAsked = true
        NotifyPermission.log("request", "source=prompt seq=" + notifyRequestSeq)
        notificationPermission.launch(NotifyPermission.PERMISSION)
    }

    // 前后台：退到后台就不再压制通知（人已经不在看那个页面了）。
    DisposableEffect(lifecycleOwner, runtime) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> runtime?.setAppVisible(true)
                Lifecycle.Event.ON_STOP -> runtime?.setAppVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 点系统通知进来：冷启动时内核还没读完 state.json，群数据要等一会儿才有；
    // 直接跳会让 P3/P4 因为 group == null 立刻弹回 P2，所以最多等 15 s。
    val notifyTarget = NotifyRoute.pending
    LaunchedEffect(notifyTarget, runtime) {
        val target = notifyTarget ?: return@LaunchedEffect
        var waitedMs = 0
        while (runtime != null && state.groupOrNull(target.groupId) == null && waitedMs < 15_000) {
            delay(500)
            waitedMs += 500
        }
        NotifyRoute.consume()
        if (target.fileId != null) {
            state.openFile(target.groupId, target.fileId)
        } else {
            state.openGroup(target.groupId)
        }
        Log.i(
            M1Runtime.TAG,
            "event=notify_open group_id=" + target.groupId +
                " file_id=" + (target.fileId ?: "-") +
                " waited_ms=" + waitedMs,
        )
    }

    // 点常驻通知回 P8（link/LinkNotifyRoute）：与群通知同一条路子 —— 冷启动时内核还在读
    // state.json，先等它起来（最多 15 s），再把 P8 压到栈顶。
    val linkTarget = LinkNotifyRoute.pending
    LaunchedEffect(linkTarget, runtime) {
        val shareId = linkTarget ?: return@LaunchedEffect
        var waitedMs = 0
        while (runtime != null && !runtime.started && waitedMs < 15_000) {
            delay(200)
            waitedMs += 200
        }
        LinkNotifyRoute.consume()
        state.push(Destination.LinkShare(shareId))
        Log.i(
            M1Runtime.TAG,
            "event=link_notify_route share=" + shareId.take(8) + " waited_ms=" + waitedMs,
        )
    }

    // 系统分享进来（link-share §2.3）：落点只选群，不选「链接还是群」，也没有确认页。
    //   0 个群 → P5 建群（还没用户名就先 P1）；1 个群 → 直接进 P3；多个群 → P2 点一个。
    // P0 自检照常跑，自检放行与这里是并行的两件事：谁先到都不会把对方顶掉（P0 一旦离开
    // 组合，它自己的 onEnter 就不会再触发）。
    val shareTarget = ShareInRoute.pending
    val shareAt = state.current
    var shareRouted by remember { mutableStateOf<ShareInRoute.Target?>(null) }
    var shareGroup by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(shareTarget, shareAt, runtime) {
        val target = shareTarget ?: return@LaunchedEffect
        if (shareRouted === target) return@LaunchedEffect
        // 冷启动时内核还没读完 state.json：等它启动完（start() 返回后 group 列表才是定论），
        // 没有内核（静态壳）就跳过。最多等 15 s，与通知入口同口径。
        var waitedMs = 0
        while (runtime != null && !runtime.started && waitedMs < 15_000) {
            delay(200)
            waitedMs += 200
        }
        val groups = state.groupList
        shareRouted = target
        when {
            groups.isEmpty() -> {
                if (state.deviceState.userNameUnset) {
                    state.push(Destination.UserName)
                } else {
                    // P5 建群；创建成功 → onJoined → P3，导入由下面那段接手。
                    state.enterNewGroupFirstLaunch()
                }
            }

            groups.size == 1 -> {
                shareGroup = groups.first().id
                state.openGroup(groups.first().id)
            }

            else -> {
                state.enterHome()
                Toast.makeText(context, R.string.share_in_pick_group, Toast.LENGTH_SHORT).show()
            }
        }
        Log.i(
            M1Runtime.TAG,
            "event=share_in_route groups=" + groups.size + " waited_ms=" + waitedMs,
        )
    }

    // 到了该落的那个群页面，就把待处理的分享导入进去。
    // 先 take() 再 launch：take 会让上面的 key 变空，但导入跑在 composition scope 里，
    // 不会因为这次重组被取消。
    LaunchedEffect(shareTarget, shareAt, runtime) {
        val target = shareTarget ?: return@LaunchedEffect
        if (shareRouted !== target) return@LaunchedEffect
        // 读实时位置而不是本帧的 shareAt：上面那段可能刚刚把页面改成 P2（多个群时不进门）。
        val destination = state.current as? Destination.GroupPage ?: return@LaunchedEffect
        val fixed = shareGroup
        if (fixed != null && fixed != destination.groupId) return@LaunchedEffect
        val taken = ShareInRoute.take() ?: return@LaunchedEffect
        scope.launch {
            val ids = state.shareFiles(destination.groupId, taken.files)
            Log.i(
                M1Runtime.TAG,
                "event=share_in_import_done group_id=" + destination.groupId +
                    " ok=" + ids.size + "/" + taken.files.size,
            )
            if (ids.isEmpty()) {
                Toast.makeText(context, R.string.share_in_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * P0 自检放行后的落点（spec §3 P0 出口）：
     * 没设用户名 → P1；有普通群 → P2；只有个人空间（等于还没加群）→ P5。
     */
    fun enterAfterSplash() {
        scope.launch {
            state.awaitGroupsLoaded()
            when {
                state.deviceState.userNameUnset -> state.push(Destination.UserName)
                state.hasRealGroups -> state.enterHome()
                else -> state.enterNewGroupFirstLaunch()
            }
            Log.i(
                M1Runtime.TAG,
                "event=p0_route has_real_groups=" + state.hasRealGroups +
                    " user_name_set=" + !state.deviceState.userNameUnset,
            )
        }
    }

    fun goBack() {
        val leaving = state.current
        state.pop()
        // 从 P7 进 P0 的人，返回必须回到 P7（spec §3 P0 出口）。
        if (leaving is Destination.Network && leaving.fromSettings) {
            scope.launch { drawerState.open() }
        }
    }

    BackHandler(enabled = drawerState.isOpen || state.canGoBack) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else {
            goBack()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SettingsDrawer(
                state = state,
                onOpenShareManage = {
                    scope.launch {
                        drawerState.close()
                        state.push(Destination.ShareManage)
                    }
                },
            )
        },
    ) {
        when (val destination = state.current) {
            is Destination.Network -> NetworkScreen(
                state = state,
                holdAt = debugHold,
                onEnter = { enterAfterSplash() },
            )

            Destination.UserName -> UserNameScreen(
                state = state,
                onSaved = { hasRealGroups ->
                    if (hasRealGroups) state.enterHome() else state.enterNewGroupFirstLaunch()
                },
            )

            Destination.Home -> HomeScreen(
                state = state,
                onOpenSettings = { scope.launch { drawerState.open() } },
                onNewGroup = { state.push(Destination.NewGroup(firstLaunch = false)) },
                onOpenGroup = {
                    state.openGroup(it)
                },
            )

            is Destination.GroupPage -> GroupScreen(
                state = state,
                groupId = destination.groupId,
                onOpenFile = { state.push(Destination.FilePage(destination.groupId, it)) },
                onOpenMembers = { state.push(Destination.Members(destination.groupId)) },
            )

            is Destination.FilePage -> FileScreen(
                state = state,
                groupId = destination.groupId,
                fileId = destination.fileId,
            )

            is Destination.NewGroup -> NewGroupScreen(
                state = state,
                onJoined = { state.openGroup(it) },
            )

            is Destination.Members -> MembersScreen(
                state = state,
                groupId = destination.groupId,
            )

            is Destination.LinkShare -> ShareLinkScreen(
                state = state,
                shareId = destination.shareId,
            )

            is Destination.NewLink -> ShareLinkScreen(
                state = state,
                shareId = "",
                pendingGroupId = destination.groupId,
                pendingFileId = destination.fileId,
            )

            Destination.ShareManage -> ShareManageScreen(state = state)
        }
    }
}


