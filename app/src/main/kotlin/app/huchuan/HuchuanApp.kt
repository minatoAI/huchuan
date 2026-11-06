package app.huchuan

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import app.huchuan.ui.FileScreen
import app.huchuan.ui.GroupScreen
import app.huchuan.ui.HomeScreen
import app.huchuan.ui.MembersScreen
import app.huchuan.ui.NetworkScreen
import app.huchuan.ui.NewGroupScreen
import app.huchuan.ui.SettingsDrawer
import app.huchuan.ui.UserNameScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/** 页面（spec §2）。P7 设置是 P2 上的抽屉，不占独立目的地。 */
sealed interface Destination {
    data class Network(val fromSettings: Boolean) : Destination

    data object UserName : Destination

    data object Home : Destination

    data class GroupPage(val groupId: String) : Destination

    data class FilePage(val groupId: String, val fileId: String) : Destination

    data class NewGroup(val firstLaunch: Boolean) : Destination

    data class Members(val groupId: String) : Destination
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

    fun push(destination: Destination) {
        stack.add(destination)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun enterHome() {
        stack.clear()
        stack.add(Destination.Home)
    }

    /** 首次启动且无群组：P1 保存后直接进 P5，此页无返回（spec §3 P5 出口）。 */
    fun enterNewGroupFirstLaunch() {
        stack.clear()
        stack.add(Destination.NewGroup(firstLaunch = true))
    }

    /** 加入或创建成功 → P3，且 P3 的系统返回必须落到 P2。 */
    fun openGroup(groupId: String) {
        stack.clear()
        stack.add(Destination.Home)
        stack.add(Destination.GroupPage(groupId))
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
            SettingsDrawer(state = state)
        },
    ) {
        when (val destination = state.current) {
            is Destination.Network -> NetworkScreen(
                state = state,
                holdAt = debugHold,
                onEnter = {
                    if (state.deviceState.userNameUnset) {
                        state.push(Destination.UserName)
                    } else {
                        state.enterHome()
                    }
                },
            )

            Destination.UserName -> UserNameScreen(
                state = state,
                onSaved = { hasGroups ->
                    if (hasGroups) state.enterHome() else state.enterNewGroupFirstLaunch()
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
        }
    }
}


