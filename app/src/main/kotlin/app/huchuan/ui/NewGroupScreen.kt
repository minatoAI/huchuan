package app.huchuan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import app.huchuan.HuchuanState
import app.huchuan.R
import kotlinx.coroutines.delay

/**
 * P5 新群组。入口：P2「+」、首次启动时由 P1 直接进入；
 * 出口：加入或创建成功 → P3，系统返回 → P2（首次启动无返回）。
 */
@Composable
fun NewGroupScreen(
    state: HuchuanState,
    onJoined: (String) -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }

    val device = state.deviceState
    val localName = device.userName.ifBlank { device.deviceName }
    val defaultGroupName = stringResource(R.string.p5_group_name_default, localName)

    LaunchedEffect(joining) {
        if (!joining) return@LaunchedEffect
        delay(250)
        val joined = state.groups.joinGroup(code)
        joining = false
        if (joined == null) {
            wrong = true
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
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = code,
                onValueChange = { input ->
                    code = input.filter { it.isLetterOrDigit() }.uppercase().take(8)
                    wrong = false
                },
                placeholder = { Text(stringResource(R.string.p5_invite_hint)) },
                singleLine = true,
                readOnly = joining,
                isError = wrong,
                supportingText = if (wrong) {
                    { Text(stringResource(R.string.p5_invite_wrong)) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    if (code.length != 8) wrong = true else joining = true
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
                Text(stringResource(R.string.p5_create_group))
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
                        val created = state.groups.createGroup(groupName.ifBlank { defaultGroupName })
                        state.touch()
                        showCreate = false
                        onJoined(created.id)
                    },
                ) {
                    Text(stringResource(R.string.p5_create))
                }
            },
        )
    }
}
