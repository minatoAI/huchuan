package app.huchuan.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.huchuan.HuchuanState
import app.huchuan.R
import app.huchuan.core.Member
import kotlinx.coroutines.launch

/** P6 成员。入口：P3 顶部「成员（n 人）」；出口：系统返回 → P3。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MembersScreen(
    state: HuchuanState,
    groupId: String,
) {
    val group = state.groupOrNull(groupId)

    if (group == null) {
        LaunchedEffect(groupId) { state.pop() }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val copiedLabel = stringResource(R.string.p6_copied)

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.p6_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (group.members.isEmpty()) {
                Text(
                    text = stringResource(R.string.p6_empty),
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(group.members, key = { it.name }) { member ->
                        MemberRow(member)
                    }
                }
            }

            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.p6_invite_code),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = group.inviteCode,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText(group.inviteCode, group.inviteCode),
                            )
                            scope.launch { snackbarHostState.showSnackbar(copiedLabel) }
                        }
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun MemberRow(member: Member) {
    ListItem(
        headlineContent = { Text(member.name) },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (member.online) {
                    Text(stringResource(R.string.p6_online))
                }
                Text(stringResource(R.string.p6_seeding, member.seedingCount))
            }
        },
        trailingContent = { Text(member.platform, style = MaterialTheme.typography.labelMedium) },
    )
}
