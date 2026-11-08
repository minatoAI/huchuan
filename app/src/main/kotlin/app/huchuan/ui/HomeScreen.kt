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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
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
    // 首次进入 P2 请求通知权限（Android 13+）。只弹一次：runtime 里按 notifyPermissionAsked 去重，
    // 用户拒绝之后不再打扰，应用内未读照常。
    LaunchedEffect(Unit) { state.requestNotifyPermission("home") }

    var query by rememberSaveable { mutableStateOf("") }
    val personalId = state.personalGroupId
    // 个人空间固定置顶（docs/design/personal-space.md）：内核的 groups() 是插入顺序，
    // 旧库补建出来的个人空间会排在最后，所以排序必须在界面做一次（sortedBy 是稳定排序，
    // 普通群之间的先后不变）。
    val groups = state.groupList
        .filter { group ->
            query.isBlank() || group.name.contains(query.trim(), ignoreCase = true)
        }
        .sortedBy { group -> if (group.id == personalId) 0 else 1 }

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
                        GroupRow(
                            group = group,
                            personal = group.id == personalId,
                            onClick = { onOpenGroup(group.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * P2 的一行。个人空间与普通群的差别只有两处（docs/design/personal-space.md）：
 * 左边多一枚品牌色图标；次级信息写「n 个文件」而不是「做种 n」——个人空间只有本机自己，
 * 成员数 / 做种数没有意义，但这一行必须和普通群等高，否则列表节奏会断。
 */
@Composable
private fun GroupRow(group: Group, personal: Boolean, onClick: () -> Unit) {
    val leading: (@Composable () -> Unit)? = if (personal) {
        { PersonalSpaceBadge() }
    } else {
        null
    }
    val supporting: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.item)) {
            if (personal) {
                Text(
                    text = stringResource(R.string.p2_files, group.files.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
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
        }
    }
    ListItem(
        headlineContent = {
            Text(
                text = group.name,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = supporting,
        leadingContent = leading,
        trailingContent = if (group.unreadCount > 0) {
            { StateChip(stringResource(R.string.p2_unread, group.unreadCount), highlighted = true) }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** 个人空间的图标：品牌容器色的小方块 + 上行箭头（「从这里分享出去」）。 */
@Composable
private fun PersonalSpaceBadge() {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_personal_space),
            contentDescription = stringResource(R.string.p2_personal),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .padding(10.dp)
                .size(20.dp),
        )
    }
}
