package app.huchuan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.huchuan.Destination
import app.huchuan.HuchuanState
import app.huchuan.R
import app.huchuan.core.Group
import app.huchuan.link.LinkLive
import app.huchuan.link.LinkPhase
import app.huchuan.link.StoredShare
import kotlinx.coroutines.launch

/**
 * P9 分享管理（link-share.md §3 P9 / §8）：文件名 / 剩余有效期 / 访客状态 / 撤销。
 *
 * 列表只放仍然有效的分享：撤销或过期即移出（不做「已撤销」「已过期」两个 chip）。
 * 撤销是不可逆动作，允许一次确认；失败只把一句话贴在列表下面，不弹窗。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareManageScreen(state: HuchuanState) {
    val controller = state.link
    if (controller == null) {
        PageScaffold(title = stringResource(R.string.p9_title)) { padding ->
            Text(
                text = stringResource(R.string.p8_not_configured),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(padding).padding(Spacing.pageHorizontal, Spacing.pageVertical),
            )
        }
        return
    }

    val shares by controller.shares.collectAsState()
    val live by controller.live.collectAsState()
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<StoredShare?>(null) }
    var failure by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }

    // 进 P9 就把仍然有效的分享接回信令：不然重启 App 后链接会一直显示「对方暂时离线」。
    LaunchedEffect(Unit) {
        controller.refresh()
        controller.shares.value.forEach { share ->
            if (share.active()) controller.ensureSession(share.shareId)
        }
    }

    PageScaffold(
        title = stringResource(R.string.p9_title),
        actions = {
            TextButton(onClick = { picking = true }) {
                Text(stringResource(R.string.p9_new_share))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.pageVertical),
            verticalArrangement = Arrangement.spacedBy(Spacing.pageVertical),
        ) {
            if (shares.isEmpty()) {
                Text(
                    text = stringResource(R.string.p9_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                shares.forEach { share ->
                    ShareRow(
                        share = share,
                        live = live[share.shareId],
                        onRevoke = { pending = share },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            if (failure.isNotEmpty()) {
                Text(
                    text = linkFailureLabel(failure),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    val target = pending
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.p9_revoke_title)) },
            text = { Text(stringResource(R.string.p9_revoke_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending = null
                        failure = ""
                        scope.launch {
                            val result = controller.revoke(target.shareId)
                            if (!result.ok) failure = result.failure.ifEmpty { "revoke" }
                        }
                    },
                ) {
                    Text(stringResource(R.string.p9_revoke))
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text(stringResource(R.string.p9_cancel))
                }
            },
        )
    }

    // P9「新建分享」：列出全部群的文件（按群分组），选一个即走 P8 的创建流程。
    if (picking) {
        ModalBottomSheet(
            onDismissRequest = { picking = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ShareFilePicker(
                groups = state.groupList,
                onPick = { groupId, fileId ->
                    picking = false
                    state.push(Destination.NewLink(groupId, fileId))
                },
            )
        }
    }
}

@Composable
private fun ShareFilePicker(groups: List<Group>, onPick: (String, String) -> Unit) {
    val withFiles = groups.filter { it.files.isNotEmpty() }
    if (withFiles.isEmpty()) {
        Text(
            text = stringResource(R.string.p9_no_files),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
        )
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        withFiles.forEach { group ->
            item(key = "group:" + group.id) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
            items(group.files, key = { "file:" + group.id + ":" + it.id }) { file ->
                ListItem(
                    headlineContent = {
                        MiddleEllipsisText(
                            text = file.name,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    supportingContent = {
                        if (file.sizeBytes > 0) {
                            Text(
                                text = formatSize(file.sizeBytes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(group.id, file.id) },
                )
            }
        }
    }
}

@Composable
private fun ShareRow(
    share: StoredShare,
    live: LinkLive?,
    onRevoke: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.rowMinHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = share.fileName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = expiryLabel(share.expiresAt) + " · " + statusLabel(share, live),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(Spacing.item))
        TextButton(onClick = onRevoke) {
            Text(stringResource(R.string.p9_revoke))
        }
    }
}

/**
 * 行内状态：已结束的分享必须**看起来**不可用（进程被系统杀掉、前台服务被系统停掉），
 * 不能还挂着「被访问 n 次」像是随时能取件。
 */
@Composable
private fun statusLabel(share: StoredShare, live: LinkLive?): String = when {
    share.ended || live?.phase == LinkPhase.ENDED -> stringResource(R.string.p9_ended)
    live?.phase == LinkPhase.SENDING -> stringResource(R.string.p9_visitor_sending, live.percent.coerceIn(0, 100))
    live?.phase == LinkPhase.INCOMING -> stringResource(R.string.p9_visitor_joined)
    else -> stringResource(R.string.p9_visits, share.visitCount)
}
