package app.huchuan.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.huchuan.HuchuanState
import app.huchuan.R
import app.huchuan.core.Comment
import app.huchuan.core.FileScope
import app.huchuan.core.FileState
import app.huchuan.kernel.M1Runtime
import app.huchuan.ui.theme.brandSwitchColors
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

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val copiedLabel = stringResource(R.string.p6_copied)

    // 顶栏「…」：私有条目一项「公开到群外」，公开条目一项「取消公开」（public-swarm §6.1）。
    // 发布期间菜单项置灰 —— 状态位已经写着「发布中」，这里不再多给一个入口。
    var menuOpen by remember(fileId) { mutableStateOf(false) }
    val publishing = state.isPublishing(file.id)
    val publishMessage = state.publishMessage(file.id)
    val moreLabel = stringResource(R.string.p4_more_desc)

    // 状态位真的换过一遍的证据：8 MiB 的发布只有一两百毫秒，截图抓不住，日志抓得住。
    LaunchedEffect(publishing, file.id) {
        Log.i(
            M1Runtime.TAG,
            "event=ui_p4_publishing file_id=" + file.id +
                " state=" + (if (publishing) "on" else "off"),
        )
    }

    PageScaffold(
        title = file.name,
        // 「移除」只对公开条目出现（public-swarm §5.2）：私有条目行为不变。
        actions = {
            if (file.scope == FileScope.PUBLIC) {
                TextButton(
                    onClick = { scope.launch { state.removeLocalFile(groupId, file.id) } },
                ) {
                    Text(stringResource(R.string.p4_remove))
                }
            }
            Box {
                TextButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.semantics { contentDescription = moreLabel },
                ) {
                    Text(stringResource(R.string.p4_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (file.scope == FileScope.PRIVATE) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.p4_publish_public)) },
                            enabled = !publishing,
                            onClick = {
                                menuOpen = false
                                // 建种要离开界面线程：内核要对整个文件做一次全量哈希。
                                scope.launch { state.publishPublic(groupId, file.id) }
                            },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.p4_unpublish)) },
                            onClick = {
                                menuOpen = false
                                scope.launch { state.unpublishPublic(groupId, file.id) }
                            },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 输入行脱离正文流，固定到底部（见 docs/ui-notes.md §3）：正文再高也不把它挤走，
        // 正文自己 verticalScroll，两者互不影响。
        bottomBar = {
            CommentInputBar(
                commentText = commentText,
                onCommentTextChange = { commentText = it },
                replyToAuthor = replyTo?.author,
                onSend = {
                    val body = commentText
                    val parent = replyTo?.id
                    commentText = ""
                    replyTo = null
                    // 评论要签名并广播给对端：必须离开界面线程，否则 Android 抛
                    // NetworkOnMainThreadException（发送方会因此把连接关掉）。
                    scope.launch { state.addComment(groupId, file.id, body, parent) }
                },
            )
        },
    ) { padding ->
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
                if (file.scope == FileScope.PUBLIC) {
                    StateChip(text = stringResource(R.string.p3_scope_public), highlighted = false)
                }
                // 状态位只有一格：发布期间写「发布中」，做完就回到原来的状态文案。
                if (publishing) {
                    StateChip(text = stringResource(R.string.p4_publishing), highlighted = true)
                } else {
                    StateChip(
                        text = fileStateLabel(file),
                        highlighted = file.state != FileState.SLEEPING,
                    )
                }
                // 元数据没到手时没有尺寸可写：左上的状态 chip 已经是「等待来源」，
                // 这里不再重复一遍（public-swarm §3.1：不是 0 B）。
                if (!file.awaitingSource()) {
                    Text(
                        text = formatSize(file.sizeBytes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.p4_source, file.sourceMember),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 发布失败：内核那句中文原样贴在正文里一行，不弹窗、不给「重试」按钮（§3.1 / §3.9）。
            if (publishMessage.isNotEmpty()) {
                Text(
                    text = publishMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // 公开条目的磁力摘要：一行，点一下复制整条链接（public-swarm §6.1 的 P4 信息区）。
            if (file.scope == FileScope.PUBLIC && file.magnet.isNotEmpty()) {
                Text(
                    text = magnetDigest(file.magnet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText(file.magnet, file.magnet),
                            )
                            scope.launch { snackbarHostState.showSnackbar(copiedLabel) }
                        }
                        .padding(vertical = 4.dp),
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

            // 组队通告：只显示人数，不动片调度（spec §4.6 / M2）。到 100% 的同一拍就消失。
            if (file.showsTeam()) {
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
                    colors = brandSwitchColors(),
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
                        // 清空输入框 = 0 = 不限：真的把 handle 上的限制清掉，不是「跳过不设」。
                        scope.launch { state.setFileUploadLimit(groupId, file.id, digits.toIntOrNull() ?: 0) }
                    },
                    placeholder = { Text(stringResource(R.string.p4_unlimited)) },
                    // 单位走 trailingIcon 而不是 suffix：M3 的 suffix 只在「有焦点 / 有文字」时出现，
                    // 空框（P7 disabled、P7 空框）会把单位吞掉。trailingIcon 两种状态都画。
                    trailingIcon = { Text(stringResource(R.string.unit_kbps), style = MaterialTheme.typography.bodyMedium) },
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
        }
    }
}

/**
 * P4 评论输入行：固定在 Scaffold 的 bottomBar 上，不再跟着正文流走。
 *
 * Insets：`.navigationBarsPadding()` 让开手势条 / 导航条（实测 OPPO 16dp、MIX 2S 24dp）；
 * 键盘弹出时 `.imePadding()` 接管，与 navigationBars 重叠的那一段由 Compose 的 Insets
 * 消费链去重，整条行落在键盘上沿，宿主窗口不再走 adjustPan 整体上推（标题栏因此不动）。
 * 下内边距刻意留 0：输入框底边正好落在导航条 / 键盘上沿，配合底栏自身的 Insets 即"贴底"。
 */
@Composable
private fun CommentInputBar(
    commentText: String,
    onCommentTextChange: (String) -> Unit,
    replyToAuthor: String?,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(
                start = Spacing.pageHorizontal,
                end = Spacing.pageHorizontal,
                top = Spacing.item,
                bottom = 0.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = commentText,
            onValueChange = onCommentTextChange,
            placeholder = {
                Text(
                    text = replyToAuthor?.let { stringResource(R.string.p4_reply, it) }
                        ?: stringResource(R.string.p4_comment_hint),
                )
            },
            maxLines = 3,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSend, enabled = commentText.isNotBlank()) {
            Text(stringResource(R.string.p4_send))
        }
    }
}
