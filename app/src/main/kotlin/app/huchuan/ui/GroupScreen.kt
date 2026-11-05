package app.huchuan.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val (name, size) = documentInfo(context, uri)
            val file = state.groups.addFile(
                groupId = groupId,
                name = name,
                sizeBytes = size,
                sourceMember = localMemberName(state),
            )
            state.touch()
            // spec §4.4：添加文件完成后直接进 P4。
            onOpenFile(file.id)
        }
    }

    PageScaffold(
        title = group.name,
        actions = {
            TextButton(onClick = onOpenMembers) {
                Text(stringResource(R.string.p3_members, group.members.size))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.p3_add_file))
            }

            if (group.files.isEmpty()) {
                Text(
                    text = stringResource(R.string.p3_empty),
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
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
            Text(text = file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(formatSize(file.sizeBytes))
                Text(stringResource(R.string.p3_seeders, file.seeders))
                if (file.state == FileState.DOWNLOADING) {
                    Text(file.progressPercent.toString() + "%")
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

private fun localMemberName(state: HuchuanState): String {
    val device = state.deviceState
    return device.userName.ifBlank { device.deviceName }
}

private fun documentInfo(context: Context, uri: Uri): Pair<String, Long> {
    var name = uri.lastPathSegment.orEmpty()
    var size = 0L
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex) ?: name
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
    }
    return name.ifBlank { "文件" } to size
}
