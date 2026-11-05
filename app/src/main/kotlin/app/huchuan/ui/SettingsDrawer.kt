package app.huchuan.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.huchuan.HuchuanState
import app.huchuan.R
import app.huchuan.core.Group
import app.huchuan.core.NotificationLevel

/**
 * P7 设置抽屉。入口：P2 右上「设置」；出口：系统返回或下滑 → P2。
 * 只留四项：用户名 / 全局限速 / 通知 / 网络诊断（spec §3 P7、§6）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDrawer(
    state: HuchuanState,
    onDiagnostics: () -> Unit,
) {
    val device = state.deviceState

    var name by remember(device.userName) { mutableStateOf(device.userName) }
    var uploadText by remember(device.uploadLimitKbps) {
        mutableStateOf(if (device.uploadLimitKbps > 0) device.uploadLimitKbps.toString() else "")
    }
    var downloadText by remember(device.downloadLimitKbps) {
        mutableStateOf(if (device.downloadLimitKbps > 0) device.downloadLimitKbps.toString() else "")
    }

    ModalDrawerSheet {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.p7_title),
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { input ->
                    name = input.take(20)
                    state.device.saveUserName(name)
                    state.touch()
                },
                label = { Text(stringResource(R.string.p7_user_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = stringResource(R.string.p7_global_limit),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = uploadText,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }.take(6)
                        uploadText = digits
                        state.device.setUploadLimit(digits.toIntOrNull() ?: 0)
                        state.touch()
                    },
                    label = { Text(stringResource(R.string.p7_upload)) },
                    suffix = { Text(stringResource(R.string.unit_kbps)) },
                    singleLine = true,
                    enabled = !device.unlimited,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = downloadText,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }.take(6)
                        downloadText = digits
                        state.device.setDownloadLimit(digits.toIntOrNull() ?: 0)
                        state.touch()
                    },
                    label = { Text(stringResource(R.string.p7_download)) },
                    suffix = { Text(stringResource(R.string.unit_kbps)) },
                    singleLine = true,
                    enabled = !device.unlimited,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.p7_unlimited))
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = device.unlimited,
                    onCheckedChange = {
                        state.device.setUnlimited(it)
                        state.touch()
                    },
                )
            }

            Text(
                text = stringResource(R.string.p7_notification),
                style = MaterialTheme.typography.titleSmall,
            )
            state.groupList.forEach { group ->
                NotificationSection(state = state, group = group)
            }

            TextButton(onClick = onDiagnostics) {
                Text(stringResource(R.string.p7_network_diagnostics))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationSection(state: HuchuanState, group: Group) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = group.name, style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NotificationLevel.entries.forEach { level ->
                FilterChip(
                    selected = group.notificationLevel == level,
                    onClick = {
                        state.groups.setNotificationLevel(group.id, level)
                        state.touch()
                    },
                    label = { Text(notificationLabel(level)) },
                )
            }
        }
    }
}

@Composable
private fun notificationLabel(level: NotificationLevel): String = when (level) {
    NotificationLevel.SYSTEM_BUBBLE -> stringResource(R.string.p7_notify_bubble)
    NotificationLevel.IN_APP_ONLY -> stringResource(R.string.p7_notify_in_app)
    NotificationLevel.MUTED -> stringResource(R.string.p7_notify_muted)
}
