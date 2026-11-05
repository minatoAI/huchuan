package app.huchuan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.huchuan.HuchuanState
import app.huchuan.R
import app.huchuan.core.NetworkCheckKind
import app.huchuan.core.NetworkReport
import app.huchuan.core.NetworkStatus
import kotlinx.coroutines.delay

/** P0 网络检测。入口：冷启动、P7「网络诊断」；出口：达标「进入」，未达标留在本页。 */
@Composable
fun NetworkScreen(
    state: HuchuanState,
    fromSettings: Boolean,
    onEnter: () -> Unit,
    onBack: () -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var report by remember { mutableStateOf<NetworkReport?>(null) }
    var checking by remember { mutableStateOf(true) }

    LaunchedEffect(attempt) {
        checking = true
        report = null
        delay(350)
        report = state.probe.check()
        checking = false
    }

    val status = report?.status
    PageScaffold(
        title = stringResource(R.string.p0_title),
        navigation = if (fromSettings) {
            { TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) } }
        } else {
            null
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.p0_checking))
                }

                status == NetworkStatus.OFFLINE -> Text(stringResource(R.string.p0_offline))
                status == NetworkStatus.FAIL -> Text(stringResource(R.string.p0_failed))

                else -> {
                    report?.items?.forEach { item ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(checkKindLabel(item.kind))
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = if (item.passed) {
                                    stringResource(R.string.p0_pass)
                                } else {
                                    stringResource(R.string.p0_limited)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    if (status == NetworkStatus.PASS) {
                        Text(stringResource(R.string.p0_pass))
                    } else {
                        Text(stringResource(R.string.p0_limited))
                        Text(stringResource(R.string.p0_change_network))
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when (status) {
                    NetworkStatus.FAIL -> TextButton(onClick = { attempt++ }) {
                        Text(stringResource(R.string.common_retry))
                    }

                    NetworkStatus.OFFLINE -> TextButton(onClick = { attempt++ }) {
                        Text(stringResource(R.string.p0_retest))
                    }

                    else -> if (!checking) {
                        TextButton(onClick = { attempt++ }) {
                            Text(stringResource(R.string.p0_retest))
                        }
                    }
                }

                if (status != NetworkStatus.OFFLINE) {
                    Button(onClick = onEnter, enabled = status == NetworkStatus.PASS) {
                        Text(stringResource(R.string.p0_enter))
                    }
                }
            }
        }
    }
}

@Composable
private fun checkKindLabel(kind: NetworkCheckKind): String = when (kind) {
    NetworkCheckKind.NAT_TYPE -> stringResource(R.string.p0_item_nat)
    NetworkCheckKind.IPV6 -> stringResource(R.string.p0_item_ipv6)
    NetworkCheckKind.IPV4 -> stringResource(R.string.p0_item_ipv4)
}
