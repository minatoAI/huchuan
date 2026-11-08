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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.huchuan.HuchuanState
import app.huchuan.R
import app.huchuan.core.FileScope
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
    // 个人空间没有成员页（docs/design/personal-space.md）：右上不出现「成员」入口。
    val personal = state.isPersonal(groupId)

    if (group == null) {
        LaunchedEffect(groupId) { state.pop() }
        return
    }

    // P3 进入即清未读（G4）：内核按可见 op 与读游标现算，这里只把游标推到最后一条。
    // 从 P4 返回、从 P2 进入都会走到这里 —— 只要人回到了群组页，这个群就没有未读了。
    LaunchedEffect(groupId) { state.markGroupRead(groupId) }

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

    // 「添加文件」一个入口两项（public-swarm §6.1）：本机文件走现流程，磁力链接在同一个弹层里填。
    var adding by remember { mutableStateOf(false) }

    PageScaffold(
        // 标题就是群名：个人空间的名字由内核固定为「我的分享」。
        title = group.name,
        actions = {
            if (!personal) {
                TextButton(onClick = onOpenMembers) {
                    Text(
                        text = stringResource(R.string.p3_members, group.members.size),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            BrandButton(
                onClick = { adding = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.pageHorizontal, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.p3_add_file))
            }

            if (group.files.isEmpty()) {
                Text(
                    // 个人空间的空态要给下一步（加一个文件，链接就在 P4 里）：普通群那句只说「没有文件」。
                    text = stringResource(
                        if (personal) R.string.p3_empty_personal else R.string.p3_empty,
                    ),
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

    if (adding) {
        AddFileSheet(
            onPickLocal = {
                adding = false
                picker.launch(arrayOf("*/*"))
            },
            onImportMagnet = { magnet -> state.importMagnet(groupId, magnet) },
            onImported = { fileId ->
                adding = false
                // spec §4.4：添加完成后直接进 P4（磁力导入成功与选文件一样）。
                onOpenFile(fileId)
            },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun FileRow(file: GroupFile, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            MiddleEllipsisText(
                text = file.name,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.item)) {
                // 公开条目在元数据到手前 size = 0：不显示「0 B」（public-swarm §3.1）。
                if (file.sizeBytes > 0) {
                    Text(
                        text = formatSize(file.sizeBytes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                // 「公开」只在公开条目出现，位置固定在状态 chip 左侧（public-swarm §6.2）；
                // 私有条目没有任何补位 chip，不出现「私有」字样。
                if (file.scope == FileScope.PUBLIC) {
                    StateChip(text = stringResource(R.string.p3_scope_public), highlighted = false)
                }
                StateChip(
                    text = fileStateLabel(file),
                    highlighted = file.isActive(),
                )
                // 队友 ≥ 1 才有这一枚次级 chip；0 队友时整枚不出现（不是显示「组队 1 人」）。
                if (file.showsTeam()) {
                    StateChip(
                        text = stringResource(R.string.p3_team, file.teamSize),
                        highlighted = true,
                    )
                }
            }
        },
        // 整行可点：ListStyle 的 modifier 先撑满宽度，再挂 clickable，点击区域不跟着文字走。
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}


