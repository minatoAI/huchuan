package app.huchuan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.huchuan.R
import app.huchuan.kernel.MagnetImport
import app.huchuan.core.m1.PublicMagnetResult
import kotlinx.coroutines.launch

/**
 * P3「添加文件」弹层（public-swarm §6.1）：同一入口下的两项 —— 本机文件（现流程）与磁力链接。
 *
 * 不新开页面。本机文件照旧转给 SAF 选择器；磁力链接在这一层里填、点「导入」。
 * 失败一律显示在输入框下一行（内核五个 reason 各一句，§3.1），不弹窗、不要求重试。
 * 导入最多同步等 15 s（[app.huchuan.kernel.M1Runtime.importPublicMagnet]）：进行中按钮变「导入中」，
 * 并且不许下滑关掉弹层。超时不是失败 —— 条目已经建好，列表里显示「等待来源」，元数据到手后自己补上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFileSheet(
    onPickLocal: () -> Unit,
    onImportMagnet: suspend (String) -> MagnetImport,
    onImported: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var magnetMode by rememberSaveable { mutableStateOf(false) }
    var magnet by rememberSaveable { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<MagnetImport?>(null) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !importing },
    )

    ModalBottomSheet(
        onDismissRequest = { if (!importing) onDismiss() },
        sheetState = sheetState,
        // 不要默认拖动手柄：MIX 2S（Android 13）上它会带出一枚悬浮的「拖动手柄」提示气泡，
        // 盖在弹层上；两行内容的弹层也不需要这个装饰（AGENTS §4.3）。下滑关闭照旧可用。
        dragHandle = {},
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(top = Spacing.pageVertical, bottom = Spacing.section),
        ) {
            if (!magnetMode) {
                ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.p3_add_local),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    modifier = Modifier.clickable(onClick = onPickLocal),
                    // 弹层里两行的底色跟随弹层本身：默认 surface 会在白底弹层上切出一条浅色带。
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.p3_add_magnet),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    modifier = Modifier.clickable { magnetMode = true },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.pageHorizontal),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = magnet,
                        onValueChange = {
                            magnet = it
                            result = null
                        },
                        placeholder = { Text(stringResource(R.string.p3_magnet_hint)) },
                        singleLine = true,
                        enabled = !importing,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            val text = magnet.trim()
                            importing = true
                            result = null
                            scope.launch {
                                val outcome = onImportMagnet(text)
                                importing = false
                                result = outcome
                                if (outcome.ok) onImported(outcome.fileId)
                            }
                        },
                        enabled = magnet.isNotBlank() && !importing,
                    ) {
                        Text(
                            stringResource(
                                if (importing) R.string.p3_magnet_importing else R.string.p3_magnet_import,
                            ),
                        )
                    }
                }
                result?.takeIf { !it.ok }?.let { outcome ->
                    Text(
                        text = outcome.message,
                        style = MaterialTheme.typography.bodyMedium,
                        // 非法 / 重复是拒绝；超时与无源是「还没成」，不染红（§3.1 无元数据不报错）。
                        color = if (outcome.reason == PublicMagnetResult.INVALID_LINK ||
                            outcome.reason == PublicMagnetResult.DUPLICATE ||
                            outcome.reason == PublicMagnetResult.SESSION_ERROR ||
                            outcome.reason == PublicMagnetResult.NO_GROUP
                        ) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(
                            start = Spacing.pageHorizontal,
                            end = Spacing.pageHorizontal,
                            top = 8.dp,
                        ),
                    )
                }
            }
        }
    }
}
