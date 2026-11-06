package app.huchuan.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.huchuan.HuchuanState
import app.huchuan.R
import app.huchuan.core.Comment
import app.huchuan.core.FileState
import kotlinx.coroutines.launch

/**
 * P4 文件详情。入口：P3 点文件行、P3「添加文件」完成后直接进入；
 * 出口：系统返回 → P3。
 */
@Composable
fun FileScreen(
    state: HuchuanState,
    groupId: String,
    fileId: String,
) {
    val group = state.groupOrNull(groupId)
    val file = group?.files?.firstOrNull { it.id == fileId }

    if (group == null || file == null) {
        LaunchedEffect(groupId, fileId) { state.pop() }
        return
    }

    // P4 打开即清该文件未读（G4）：游标推到最新一条可见 op，落盘。
    LaunchedEffect(groupId, fileId) { state.markFileRead(groupId, fileId) }

    val scope = rememberCoroutineScope()
    // 激活/休眠要真起真停 BT 任务，不能占界面线程。
    val switchLabel = stringResource(R.string.p4_activate_toggle)
    var commentText by remember(fileId) { mutableStateOf("") }
    var replyTo by remember(fileId) { mutableStateOf<Comment?>(null) }
    var limitText by rememberSaveable(fileId) {
        mutableStateOf(if (file.uploadLimitKbps > 0) file.uploadLimitKbps.toString() else "")
    }

    PageScaffold(title = file.name) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = Spacing.pageHorizontal,
                    vertical = Spacing.pageVertical,
                ),
            verticalArrangement = Arrangement.spacedBy(Spacing.pageVertical),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.item),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StateChip(
                    text = fileStateLabel(file.state),
                    highlighted = file.state != FileState.SLEEPING,
                )
                Text(
                    text = formatSize(file.sizeBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.p4_source, file.sourceMember),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LinearProgressIndicator(
                progress = { file.progressPercent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = file.progressPercent.toString() + "%",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "↓" + formatRate(file.downloadRateBps),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.width(Spacing.item))
                Text(
                    text = "↑" + formatRate(file.uploadRateBps),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (file.inTeam) {
                Text(
                    text = stringResource(R.string.p4_teaming, file.teamSize),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = if (file.seeders > 0) {
                    stringResource(R.string.p4_seeders, file.seeders)
                } else {
                    stringResource(R.string.p4_waiting_seeder)
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Spacing.rowMinHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.p4_activate),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.width(Spacing.item))
                Switch(
                    checked = file.state != FileState.SLEEPING,
                    onCheckedChange = { active ->
                        scope.launch { state.setFileActive(groupId, file.id, active) }
                    },
                    modifier = Modifier.semantics { contentDescription = switchLabel },
                )
                Spacer(Modifier.width(Spacing.item))
                Text(
                    text = stringResource(R.string.p4_sleep),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            ) {
                Text(
                    text = stringResource(R.string.p4_upload_limit),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.weight(1f))
                OutlinedTextField(
                    value = limitText,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }.take(6)
                        limitText = digits
                        state.groups.setFileUploadLimit(groupId, file.id, digits.toIntOrNull() ?: 0)
                        state.touch()
                    },
                    placeholder = { Text(stringResource(R.string.p4_unlimited)) },
                    suffix = { Text(stringResource(R.string.unit_kbps)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(160.dp),
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (file.comments.isEmpty()) {
                Text(
                    text = stringResource(R.string.p4_no_comments),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                file.comments.forEach { comment ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { replyTo = comment }
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = comment.author,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = comment.time,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = comment.body,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = commentText,
                    onValueChange = { commentText = it },
                    placeholder = {
                        Text(
                            text = replyTo?.let { stringResource(R.string.p4_reply, it.author) }
                                ?: stringResource(R.string.p4_comment_hint),
                        )
                    },
                    maxLines = 3,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        val body = commentText
                        val parent = replyTo?.id
                        commentText = ""
                        replyTo = null
                        // 评论要签名并广播给对端：必须离开界面线程，否则 Android 抛
                        // NetworkOnMainThreadException（发送方会因此把连接关掉）。
                        scope.launch { state.addComment(groupId, file.id, body, parent) }
                    },
                    enabled = commentText.isNotBlank(),
                ) {
                    Text(stringResource(R.string.p4_send))
                }
            }
        }
    }
}
