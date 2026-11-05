package app.huchuan.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.huchuan.HuchuanState
import app.huchuan.R
import app.huchuan.core.FileState
import app.huchuan.core.GroupFile

/**
 * P3 群组。入口：P2 点群组行；
 * 出口：文件行 → P4，顶部「成员」→ P6，系统返回 → P2。
 */
@Composable
fun GroupScreen(
    state: HuchuanState,
    groupId: String,
    onOpenFile: (String) -> Unit,
    onOpenMembers: () -> Unit,
) {
    val group = state.groupOrNull(groupId)

    if (group == null) {
        LaunchedEffect(groupId) { state.pop() }
        return
    }

    val scope = rememberCoroutineScope()
    // SAF 只负责给出 URI；拷贝到私有目录、建种、广播都在内核侧的后台线程完成。
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val file = state.shareDocument(groupId, uri)
                state.touch()
                // spec §4.4：添加文件完成后直接进 P4。
                if (file != null) onOpenFile(file.id)
            }
        }
    }

    PageScaffold(
        title = group.name,
        actions = {
            TextButton(onClick = onOpenMembers) {
                Text(
                    text = stringResource(R.string.p3_members, group.members.size),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            BrandButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.pageHorizontal, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.p3_add_file))
            }

            if (group.files.isEmpty()) {
                Text(
                    text = stringResource(R.string.p3_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.section),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = Spacing.pageVertical),
                ) {
                    items(group.files, key = { it.id }) { file ->
                        FileRow(file = file, onClick = { onOpenFile(file.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(file: GroupFile, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                text = file.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.item)) {
                Text(
                    text = formatSize(file.sizeBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.p3_seeders, file.seeders),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (file.state == FileState.DOWNLOADING) {
                    Text(
                        text = file.progressPercent.toString() + "%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        trailingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StateChip(
                    text = fileStateLabel(file.state),
                    highlighted = file.state != FileState.SLEEPING,
                )
                if (file.inTeam) {
                    StateChip(
                        text = stringResource(R.string.p3_team, file.teamSize),
                        highlighted = true,
                    )
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}


