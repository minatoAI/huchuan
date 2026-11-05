package app.huchuan.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.huchuan.R
import app.huchuan.core.FileState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScaffold(
    title: String,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { navigation?.invoke() },
                actions = actions,
            )
        },
        content = content,
    )
}

/** 文件大小：资源管理器写法，1.2 GB。 */
fun formatSize(bytes: Long): String {
    if (bytes < 1024) return bytes.toString() + " B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[index])
}

/** 速率：2.4 MB/s。 */
fun formatRate(bytesPerSecond: Long): String = formatSize(bytesPerSecond) + "/s"

@Composable
fun fileStateLabel(state: FileState): String = when (state) {
    FileState.SEEDING -> stringResource(R.string.p3_state_seeding)
    FileState.DOWNLOADING -> stringResource(R.string.p3_state_downloading)
    FileState.SLEEPING -> stringResource(R.string.p3_state_sleeping)
}

/** 状态 chip：只做展示，不给点击语义。 */
@Composable
fun StateChip(text: String, highlighted: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (highlighted) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (highlighted) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
