package app.huchuan.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.huchuan.Destination
import app.huchuan.HuchuanState
import app.huchuan.R
import app.huchuan.core.Comment
import app.huchuan.core.FileScope
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
    // 开关状态单独存：输入框清空时限制实际为 0，但开关不能自己弹回去。
    var limitEnabled by rememberSaveable(fileId) { mutableStateOf(file.uploadLimitKbps > 0) }

    // 返回键先取消回复目标（不退出页面）；没有目标时交给 HuchuanApp 的全局返回。
    BackHandler(enabled = replyTo != null) { replyTo = null }

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val copiedLabel = stringResource(R.string.p6_copied)
    val applyFileLimit: (Boolean) -> Unit = { enabled ->
        limitEnabled = enabled
        val kbps = if (enabled) limitText.toIntOrNull() ?: 0 else 0
        scope.launch { state.setFileUploadLimit(groupId, file.id, kbps) }
    }

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
        // P4 顶部显示完整名：可换行、最多两行；两行用 titleMedium 才放得进 64dp 的 TopAppBar。
        // 点/横线后补零宽空格，长文件名才有地方断行。
        title = softWrapName(file.name),
        titleMaxLines = 2,
        titleStyle = MaterialTheme.typography.titleMedium,
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
                    // 链接分享（link-share §3 P8）：没配置分享服务时这一项不出现。
                    if (state.linkAvailable) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.p4_share_link)) },
                            onClick = {
                                menuOpen = false
                                state.push(Destination.NewLink(groupId, file.id))
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
                onCancelReply = { replyTo = null },
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
                        highlighted = file.isActive(),
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
                    checked = file.isActive(),
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

            // 开关下面一行「当前后果」：只解释状态，不加控件（ui-ia.md §2.1）。
            Text(
                text = fileConsequenceLabel(file),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // P4 单文件限速：开关「限制速度」，关 = 不限，开 = 才出现 KB/s 输入框。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Spacing.rowMinHeight)
                    .toggleable(
                        value = limitEnabled,
                        role = Role.Switch,
                        onValueChange = applyFileLimit,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.p4_limit_speed),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = limitEnabled,
                    onCheckedChange = null,
                    colors = brandSwitchColors(),
                )
            }
            if (limitEnabled) {
                OutlinedTextField(
                    value = limitText,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }.take(6)
                        limitText = digits
                        // 清空输入框 = 0 = 不限：真的把 handle 上的限制清掉，不是「跳过不设」。
                        scope.launch { state.setFileUploadLimit(groupId, file.id, digits.toIntOrNull() ?: 0) }
                    },
                    // 单位走 trailingIcon 而不是 suffix：M3 的 suffix 在空框时会被吞掉。
                    trailingIcon = { Text(stringResource(R.string.unit_kbps), style = MaterialTheme.typography.bodyMedium) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
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
                val rows = remember(file.comments) { commentRows(file.comments) }
                rows.forEach { row ->
                    CommentRowView(row = row, onReply = { replyTo = row.comment })
                }
            }
        }
    }
}

/**
 * 评论展示行：depth = 1 主评论，depth = 2 回复（缩进 + 左侧竖线）。
 * 超过两级的回复压平到第二级：只按“根评论”分组，不按直接父级递归（GitHub issue 的同款做法）。
 */
private data class CommentRow(val comment: Comment, val depth: Int)

private fun commentRows(comments: List<Comment>): List<CommentRow> {
    if (comments.isEmpty()) return emptyList()
    val byId = comments.associateBy { it.id }
    val rootIds = HashMap<String, String>(comments.size)
    fun rootIdOf(comment: Comment): String {
        rootIds[comment.id]?.let { return it }
        var current = comment
        val seen = HashSet<String>()
        while (seen.add(current.id)) {
            val parent = current.parentId?.let(byId::get) ?: return current.id
            current = parent
        }
        // 父链成环（坏数据）：把当前评论当独立根，避免无限循环。
        return comment.id
    }
    comments.forEach { rootIds[it.id] = rootIdOf(it) }

    val rows = ArrayList<CommentRow>(comments.size)
    comments.forEach { root ->
        if (rootIds[root.id] != root.id) return@forEach
        rows.add(CommentRow(root, depth = 1))
        comments.forEach { child ->
            if (child.id != root.id && rootIds[child.id] == root.id) {
                rows.add(CommentRow(child, depth = 2))
            }
        }
    }
    return rows
}

@Composable
private fun CommentRowView(row: CommentRow, onReply: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // IntrinsicSize.Min 让左侧竖线跟着这一段评论的高度走，而不是撑满整列。
            .height(IntrinsicSize.Min)
            .clickable(onClick = onReply)
            .padding(vertical = 4.dp),
    ) {
        if (row.depth > 1) {
            Spacer(Modifier.width(16.dp))
            Box(
                Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = row.comment.author,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = row.comment.time,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = row.comment.body,
                style = MaterialTheme.typography.bodyMedium,
            )
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
    onCancelReply: () -> Unit,
    onSend: () -> Unit,
) {
    val cancelReplyLabel = stringResource(R.string.p4_reply_cancel)
    Column(
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
    ) {
        val author = replyToAuthor
        if (author != null) {
            // 「回复 @作者名 ×」：点 × 只取消目标，输入内容保留。
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.p4_reply, author),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(
                        onClick = onCancelReply,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.semantics { contentDescription = cancelReplyLabel },
                    ) {
                        Text("×", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = commentText,
                onValueChange = onCommentTextChange,
                placeholder = {
                    Text(
                        text = author?.let { stringResource(R.string.p4_reply, it) }
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
}
