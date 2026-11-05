package app.huchuan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.huchuan.HuchuanState
import app.huchuan.R
import app.huchuan.core.Group

/**
 * P2 首页。入口：冷启动（已有群组）、任意页系统返回一次；
 * 出口：群组行 → P3，「设置」→ P7，「+」→ P5。
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
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.p2_search)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.p2_settings))
                }
                TextButton(onClick = onNewGroup) {
                    Text(stringResource(R.string.p2_new_group))
                }
            }

            if (groups.isEmpty()) {
                Text(
                    text = stringResource(R.string.p2_empty),
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
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
        headlineContent = { Text(group.name) },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.p2_seeding, group.seedingCount))
                if (group.teamSize > 0) {
                    Text(stringResource(R.string.p2_team, group.teamSize))
                }
            }
        },
        trailingContent = if (group.unreadCount > 0) {
            { StateChip(stringResource(R.string.p2_unread, group.unreadCount), highlighted = true) }
        } else {
            null
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
