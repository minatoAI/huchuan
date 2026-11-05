package app.huchuan.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.huchuan.HuchuanState
import app.huchuan.R
import app.huchuan.core.NetworkStatus
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

/** 自检开屏节奏：启动中 700ms → 检测中 700ms → 成功停留 450ms / 降级停留 1200ms 后自动放行。 */
private const val BOOT_MS = 700L
private const val CHECK_MS = 700L
private const val PASS_HOLD_MS = 450L
private const val DEGRADED_HOLD_MS = 1200L

private enum class SplashPhase {
    BOOT,
    CHECKING,
    PASS,
    DEGRADED,
    FAILED,
    OFFLINE,
}

/**
 * P0 自检开屏。入口：冷启动；出口：成功 / 降级自动放行，失败停在原地只给「重试」。
 *
 * 页面没有按钮（失败态的「重试」是唯一例外），也不显示 NAT / IPv6 / IPv4 明细：
 * 用户不需要读懂自检，只需要知道能不能用。
 *
 * 调试入口只走 adb intent extra，界面上不出现任何开关：
 *   --es huchuan.debug.network limited|failed|offline  强制降级 / 检测失败 / 无网络
 *   --es huchuan.debug.hold boot|checking|result        停在指定阶段，供截图取证
 */
@Composable
fun NetworkScreen(
    state: HuchuanState,
    holdAt: String?,
    onEnter: () -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf(SplashPhase.BOOT) }

    LaunchedEffect(attempt) {
        phase = SplashPhase.BOOT
        delay(BOOT_MS)
        if (holdAt == "boot") awaitCancellation()

        phase = SplashPhase.CHECKING
        delay(CHECK_MS)
        if (holdAt == "checking") awaitCancellation()

        val report = state.probe.check()
        phase = when {
            report.status == NetworkStatus.PASS -> SplashPhase.PASS
            report.status == NetworkStatus.OFFLINE -> SplashPhase.OFFLINE
            report.status == NetworkStatus.FAIL && report.items.isNotEmpty() -> SplashPhase.DEGRADED
            else -> SplashPhase.FAILED
        }

        if (holdAt == "result") awaitCancellation()

        when (phase) {
            SplashPhase.PASS -> {
                delay(PASS_HOLD_MS)
                onEnter()
            }

            SplashPhase.DEGRADED -> {
                delay(DEGRADED_HOLD_MS)
                onEnter()
            }

            else -> awaitCancellation()
        }
    }

    val failed = phase == SplashPhase.FAILED || phase == SplashPhase.OFFLINE

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = Spacing.section),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(Spacing.section))

            // 失败前一直保留进度动画；失败态停转并让位给「重试」。
            AnimatedVisibility(
                visible = !failed,
                enter = fadeIn(animationSpec = tween(180)),
                exit = fadeOut(animationSpec = tween(180)),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    strokeWidth = 4.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(Spacing.pageVertical))

            Crossfade(
                targetState = phase,
                animationSpec = tween(220),
                label = "p0-status",
            ) { current ->
                Text(
                    text = statusText(current),
                    style = MaterialTheme.typography.bodyLarge,
                    color = statusColor(current),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            AnimatedVisibility(
                visible = failed,
                enter = fadeIn(animationSpec = tween(180)),
                exit = fadeOut(animationSpec = tween(180)),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(Spacing.section))
                    BrandButton(onClick = { attempt++ }) {
                        Text(stringResource(R.string.common_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun statusText(phase: SplashPhase): String = when (phase) {
    SplashPhase.BOOT -> stringResource(R.string.p0_booting)
    SplashPhase.CHECKING -> stringResource(R.string.p0_checking)
    SplashPhase.PASS -> stringResource(R.string.p0_pass)
    SplashPhase.DEGRADED -> stringResource(R.string.p0_degraded)
    SplashPhase.FAILED -> stringResource(R.string.p0_failed)
    SplashPhase.OFFLINE -> stringResource(R.string.p0_offline)
}

@Composable
private fun statusColor(phase: SplashPhase) = when (phase) {
    SplashPhase.PASS -> MaterialTheme.colorScheme.primary
    SplashPhase.DEGRADED -> MaterialTheme.colorScheme.tertiary
    SplashPhase.FAILED, SplashPhase.OFFLINE -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
