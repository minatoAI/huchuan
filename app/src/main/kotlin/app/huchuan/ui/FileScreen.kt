package app.huchuan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.huchuan.HuchuanState
import app.huchuan.R
import app.huchuan.core.Comment
import app.huchuan.core.FileState

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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StateChip(
                    text = fileStateLabel(file.state),
                    highlighted = file.state != FileState.SLEEPING,
                )
                Text(formatSize(file.sizeBytes))
                Text(stringResource(R.string.p4_source, file.sourceMember))
            }

            LinearProgressIndicator(
                progress = { file.progressPercent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(file.progressPercent.toString() + "%")
                Spacer(Modifier.weight(1f))
                Text("↓" + formatRate(file.downloadRateBps))
                Text("↑" + formatRate(file.uploadRateBps))
            }

            if (file.inTeam) {
                Text(stringResource(R.string.p4_teaming, file.teamSize))
            }
            Text(
                if (file.seeders > 0) {
                    stringResource(R.string.p4_seeders, file.seeders)
                } else {
                    stringResource(R.string.p4_waiting_seeder)
                },
            )

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.p4_activate))
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = file.state != FileState.SLEEPING,
                    onCheckedChange = { active ->
                        state.groups.setFileActive(groupId, file.id, active)
                        state.touch()
                    },
                )
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.p4_sleep))
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.p4_upload_limit))
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
                    modifier = Modifier.width(168.dp),
                )
            }

            HorizontalDivider()

            if (file.comments.isEmpty()) {
                Text(stringResource(R.string.p4_no_comments))
            } else {
                file.comments.forEach { comment ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { replyTo = comment }
                            .padding(vertical = 4.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(comment.author, style = MaterialTheme.typography.labelLarge)
                            Text(comment.time, style = MaterialTheme.typography.labelMedium)
                        }
                        Text(comment.body, style = MaterialTheme.typography.bodyMedium)
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
                        state.groups.addComment(groupId, file.id, commentText, replyTo?.id)
                        commentText = ""
                        replyTo = null
                        state.touch()
                    },
                    enabled = commentText.isNotBlank(),
                ) {
                    Text(stringResource(R.string.p4_send))
                }
            }
        }
    }
}
