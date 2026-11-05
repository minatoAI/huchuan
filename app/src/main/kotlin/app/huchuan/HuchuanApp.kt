package app.huchuan

import androidx.activity.compose.BackHandler
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.huchuan.core.Group
import app.huchuan.core.GroupStore
import app.huchuan.core.InMemoryGroupStore
import app.huchuan.core.InMemoryLocalDeviceStore
import app.huchuan.core.InMemoryNetworkProbe
import app.huchuan.core.LocalDevice
import app.huchuan.core.LocalDeviceStore
import app.huchuan.core.NetworkProbe
import app.huchuan.ui.FileScreen
import app.huchuan.ui.GroupScreen
import app.huchuan.ui.HomeScreen
import app.huchuan.ui.MembersScreen
import app.huchuan.ui.NetworkScreen
import app.huchuan.ui.NewGroupScreen
import app.huchuan.ui.SettingsDrawer
import app.huchuan.ui.UserNameScreen
import kotlinx.coroutines.launch

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
 * 界面状态：内存假数据 + 返回栈。revision 是数据变更的版本号，
 * 页面通过读取它订阅内存 store 的变化（M1 用内存实现，不需要 Flow）。
 */
class HuchuanState(
    val groups: GroupStore,
    val device: LocalDeviceStore,
    val probe: NetworkProbe,
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
}

@Composable
fun HuchuanApp(
    state: HuchuanState = remember {
        HuchuanState(
            groups = InMemoryGroupStore(),
            device = InMemoryLocalDeviceStore(),
            probe = InMemoryNetworkProbe(),
        )
    },
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

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
                onDiagnostics = {
                    scope.launch { drawerState.close() }
                    state.push(Destination.Network(fromSettings = true))
                },
            )
        },
    ) {
        when (val destination = state.current) {
            is Destination.Network -> NetworkScreen(
                state = state,
                fromSettings = destination.fromSettings,
                onEnter = {
                    if (state.deviceState.userNameUnset) {
                        state.push(Destination.UserName)
                    } else {
                        state.enterHome()
                    }
                },
                onBack = { goBack() },
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
                onOpenGroup = { state.push(Destination.GroupPage(it)) },
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
