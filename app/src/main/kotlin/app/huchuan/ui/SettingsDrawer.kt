package app.huchuan.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.huchuan.HuchuanState
import app.huchuan.R
import app.huchuan.core.Group
import app.huchuan.core.NetworkStatus
import app.huchuan.core.NotificationLevel
import app.huchuan.ui.theme.brandSwitchColors

/**
 * P7 设置抽屉。入口：P2 右上「设置」；出口：系统返回或下滑 → P2。
 * 只留四项：用户名 / 全局限速 / 通知 / 网络诊断（spec §3 P7、§6）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDrawer(
    state: HuchuanState,
) {
    val device = state.deviceState
    val networkReport = remember { state.probe.check() }
    val networkLabel = when {
        networkReport.status == NetworkStatus.PASS -> stringResource(R.string.p0_pass)
        networkReport.status == NetworkStatus.OFFLINE -> stringResource(R.string.p0_offline)
        networkReport.status == NetworkStatus.FAIL && networkReport.items.isNotEmpty() ->
            stringResource(R.string.p0_degraded)

        else -> stringResource(R.string.p0_failed)
    }

    // 限速要真的下发内核（session 级），调用一律离开界面线程；输入框每敲一位就下发一次。
    val scope = rememberCoroutineScope()
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
                .padding(
                    horizontal = Spacing.pageHorizontal,
                    vertical = Spacing.section,
                ),
            verticalArrangement = Arrangement.spacedBy(Spacing.pageVertical),
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
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.item)) {
                OutlinedTextField(
                    value = uploadText,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }.take(6)
                        uploadText = digits
                        scope.launch { state.setGlobalUploadLimit(digits.toIntOrNull() ?: 0) }
                    },
                    label = { Text(stringResource(R.string.p7_upload)) },
                    // 单位走 trailingIcon：M3 的 suffix 在「disabled / 空框」下不渲染，走查里两个灰框没单位。
                    trailingIcon = { Text(stringResource(R.string.unit_kbps), style = MaterialTheme.typography.bodyMedium) },
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
                        scope.launch { state.setGlobalDownloadLimit(digits.toIntOrNull() ?: 0) }
                    },
                    label = { Text(stringResource(R.string.p7_download)) },
                    trailingIcon = { Text(stringResource(R.string.unit_kbps), style = MaterialTheme.typography.bodyMedium) },
                    singleLine = true,
                    enabled = !device.unlimited,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            // 「不限」开关：关掉 → 按输入框里的值限速；打开 → 两项都按 0 下发（触及内核，不是只改一个布尔）。
            // 整行可点（M3 的 Row + toggleable）：脚本按文案点这一行就够，不用去戳开关那一小块。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Spacing.rowMinHeight)
                    .toggleable(
                        value = device.unlimited,
                        role = Role.Switch,
                        onValueChange = { scope.launch { state.setGlobalUnlimited(it) } },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.p7_unlimited))
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = device.unlimited,
                    onCheckedChange = null,
                    colors = brandSwitchColors(),
                )
            }

            Text(
                text = stringResource(R.string.p7_notification),
                style = MaterialTheme.typography.titleSmall,
            )
            state.groupList.forEach { group ->
                NotificationSection(state = state, group = group)
            }

            // 只读状态行：P7 不再跳回 P0（P0 是开机自检，不是诊断页）。
            Text(
                text = stringResource(R.string.p7_network_status, networkLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationSection(state: HuchuanState, group: Group) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = group.name,
            style = MaterialTheme.typography.labelLarge,
        )
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
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
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
