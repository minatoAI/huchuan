package app.huchuan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import app.huchuan.HuchuanState
import app.huchuan.R
import kotlinx.coroutines.launch

/**
 * P5 新群组。入口：P2「+」、首次启动时由 P1 直接进入；
 * 出口：加入或创建成功 → P3，系统返回 → P2（首次启动无返回）。
 */
@Composable
fun NewGroupScreen(
    state: HuchuanState,
    onJoined: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    // 输入框下那一行错误：长度不对 / 加入失败 / 对方是个人空间，三句都能落到这里。
    var error by remember { mutableStateOf<String?>(null) }
    var joining by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }

    val device = state.deviceState
    val localName = device.userName.ifBlank { device.deviceName }
    val defaultGroupName = stringResource(R.string.p5_group_name_default, localName)
    val inviteWrong = stringResource(R.string.p5_invite_wrong)
    val joinFailed = stringResource(R.string.p5_join_failed)
    val joinPersonal = stringResource(R.string.p5_join_personal)

    LaunchedEffect(joining) {
        if (!joining) return@LaunchedEffect
        // 真加入：内核去 mDNS 发现 / 手动候选连接，可能要十几秒，全在后台线程。
        val joined = state.joinGroup(code)
        joining = false
        if (joined == null) {
            // 失败分两种（docs/design/personal-space.md）：对方是个人空间是**明确拒绝**，
            // 内核把失败码 E_JOIN_PERSONAL 原样留给宿主；其余（没找到 / 连不上 / 码不对）
            // 一律一句话，不拿「邀请码不对」冒充。
            error = if (state.lastJoinWasPersonal) joinPersonal else joinFailed
        } else {
            state.touch()
            onJoined(joined.id)
        }
    }

    PageScaffold(title = stringResource(R.string.p5_title)) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(Spacing.section),
            verticalArrangement = Arrangement.spacedBy(Spacing.pageVertical),
        ) {
            OutlinedTextField(
                value = code,
                onValueChange = { input ->
                    code = input.filter { it.isLetterOrDigit() }.uppercase().take(8)
                    error = null
                },
                placeholder = { Text(stringResource(R.string.p5_invite_hint)) },
                singleLine = true,
                readOnly = joining,
                isError = error != null,
                supportingText = if (error != null) {
                    { Text(error!!) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )

            BrandButton(
                onClick = {
                    if (code.length != 8) error = inviteWrong else joining = true
                },
                enabled = !joining,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (joining) {
                        stringResource(R.string.p5_joining)
                    } else {
                        stringResource(R.string.p5_join)
                    },
                )
            }

            TextButton(
                onClick = { showCreate = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.p5_create_group),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }

    if (showCreate) {
        var groupName by remember { mutableStateOf(defaultGroupName) }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(stringResource(R.string.p5_create_group)) },
            text = {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it.take(20) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = groupName.ifBlank { defaultGroupName }
                        showCreate = false
                        // 建群要注册 mDNS + 落盘，不能占界面线程。
                        scope.launch {
                            val created = state.createGroup(name)
                            state.touch()
                            onJoined(created.id)
                        }
                    },
                ) {
                    Text(stringResource(R.string.p5_create))
                }
            },
        )
    }
}
