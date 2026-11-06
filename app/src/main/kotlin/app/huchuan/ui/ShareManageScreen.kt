package app.huchuan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.huchuan.HuchuanState
import app.huchuan.R
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

    // 进 P9 就把仍然有效的分享接回信令：不然重启 App 后链接会一直显示「对方暂时离线」。
    LaunchedEffect(Unit) {
        controller.refresh()
        controller.shares.value.forEach { share ->
            if (share.active()) controller.ensureSession(share.shareId)
        }
    }

    PageScaffold(title = stringResource(R.string.p9_title)) { padding ->
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
                text = expiryLabel(share.expiresAt) + " · " + visitorLabel(share, live),
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

@Composable
private fun visitorLabel(share: StoredShare, live: LinkLive?): String = when (live?.phase) {
    LinkPhase.SENDING -> stringResource(R.string.p9_visitor_sending, live.percent.coerceIn(0, 100))
    LinkPhase.INCOMING -> stringResource(R.string.p9_visitor_joined)
    else -> stringResource(R.string.p9_visits, share.visitCount)
}
