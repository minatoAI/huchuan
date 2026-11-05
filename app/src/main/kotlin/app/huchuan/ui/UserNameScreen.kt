package app.huchuan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.huchuan.HuchuanState
import app.huchuan.R
import kotlinx.coroutines.delay

/** P1 用户名。入口：P0「进入」（首次启动）；出口：「保存」→ 无群组进 P5、有群组进 P2。 */
@Composable
fun UserNameScreen(
    state: HuchuanState,
    onSaved: (hasGroups: Boolean) -> Unit,
) {
    val device = state.deviceState
    var name by rememberSaveable { mutableStateOf(device.deviceName) }
    var emptyError by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(saving) {
        if (!saving) return@LaunchedEffect
        delay(250)
        state.device.saveUserName(name)
        state.touch()
        saving = false
        onSaved(state.groupList.isNotEmpty())
    }

    PageScaffold(title = stringResource(R.string.p1_title)) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(Spacing.section),
            verticalArrangement = Arrangement.spacedBy(Spacing.pageVertical),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it.take(20)
                    emptyError = false
                },
                singleLine = true,
                isError = emptyError,
                supportingText = {
                    Text(
                        if (emptyError) {
                            stringResource(R.string.p1_empty)
                        } else {
                            stringResource(R.string.p1_max_length)
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            BrandButton(
                onClick = {
                    if (name.isBlank()) emptyError = true else saving = true
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (saving) {
                        stringResource(R.string.p1_saving)
                    } else {
                        stringResource(R.string.p1_save)
                    },
                )
            }
        }
    }
}
