package app.huchuan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.huchuan.HuchuanState
import app.huchuan.R
import app.huchuan.core.Group
import app.huchuan.ui.theme.BrandJade

/**
 * P2 首页。入口：冷启动（已有群组）、任意页系统返回一次；
 * 出口：群组行 → P3，「设置」→ P7，「+」→ P5。
 *
 * 顶部一行只属于导航：搜索框是次级控件（填充色底、无描边、56dp 高），
 * 「设置」是低权重文本动作，「+」是品牌色圆形按钮，三者不再互相挤在屏幕边缘。
 */
@Composable
fun HomeScreen(
    state: HuchuanState,
    onOpenSettings: () -> Unit,
    onNewGroup: () -> Unit,
    onOpenGroup: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val groups = state.groupList.filter { group ->
        query.isBlank() || group.name.contains(query.trim(), ignoreCase = true)
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.pageHorizontal, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.p2_search),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onOpenSettings,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.p2_settings),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                FilledIconButton(
                    onClick = onNewGroup,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = BrandJade,
                        contentColor = Color.White,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.p2_new_group),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }

            if (groups.isEmpty()) {
                Text(
                    text = stringResource(R.string.p2_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.section),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = Spacing.pageVertical),
                ) {
                    items(groups, key = { it.id }) { group ->
                        GroupRow(group = group, onClick = { onOpenGroup(group.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupRow(group: Group, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                text = group.name,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.item)) {
                Text(
                    text = stringResource(R.string.p2_seeding, group.seedingCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (group.teamSize > 0) {
                    Text(
                        text = stringResource(R.string.p2_team, group.teamSize),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        trailingContent = if (group.unreadCount > 0) {
            { StateChip(stringResource(R.string.p2_unread, group.unreadCount), highlighted = true) }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
