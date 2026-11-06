package app.huchuan.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.huchuan.HuchuanState
import app.huchuan.R
import app.huchuan.core.Member
import app.huchuan.core.m1.Proto
import kotlinx.coroutines.launch

/**
 * P6 成员。入口：P3 顶部「成员（n 人）」；出口：系统返回 → P3。
 *
 * 「做种 n」与平台来自 MemberRecord（协议 §10 的 peer.announce.plat / seeds）：
 * 本机是真值，别人是他自己最后一次自报的值；没自报过就是「未知」/ 0。
 */
@Composable
fun MembersScreen(
    state: HuchuanState,
    groupId: String,
) {
    val group = state.groupOrNull(groupId)

    if (group == null) {
        LaunchedEffect(groupId) { state.pop() }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val copiedLabel = stringResource(R.string.p6_copied)

    PageScaffold(
        title = stringResource(R.string.p6_title),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (group.members.isEmpty()) {
                Text(
                    text = stringResource(R.string.p6_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.section),
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(group.members, key = { it.name }) { member ->
                        MemberRow(member)
                    }
                }
            }

            Column(
                modifier = Modifier.padding(
                    horizontal = Spacing.pageHorizontal,
                    vertical = Spacing.pageVertical,
                ),
            ) {
                Text(
                    text = stringResource(R.string.p6_invite_code),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = group.inviteCode,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText(group.inviteCode, group.inviteCode),
                            )
                            scope.launch { snackbarHostState.showSnackbar(copiedLabel) }
                        }
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}

/** 平台标签：android / windows / unknown 三个协议取值 → P6 上显示的字。 */
@Composable
private fun platformLabel(platform: String): String = when (platform) {
    Proto.PLATFORM_ANDROID -> stringResource(R.string.p6_platform_android)
    Proto.PLATFORM_WINDOWS -> stringResource(R.string.p6_platform_windows)
    else -> stringResource(R.string.p6_platform_unknown)
}

@Composable
private fun MemberRow(member: Member) {
    ListItem(
        headlineContent = {
            Text(
                text = member.name,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.item)) {
                if (member.online) {
                    Text(
                        text = stringResource(R.string.p6_online),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.p6_seeding, member.seedingCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Text(
                text = platformLabel(member.platform),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
