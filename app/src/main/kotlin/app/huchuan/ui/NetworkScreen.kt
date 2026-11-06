package app.huchuan.ui

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.huchuan.HuchuanState
import app.huchuan.R
import app.huchuan.core.NetworkStatus
import app.huchuan.kernel.LocalNetworkPermission
import app.huchuan.kernel.findActivity
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

    /** 本地网络未授权：停在原地，不自动进入。 */
    NO_PERMISSION,
}

/**
 * P0 自检开屏。入口：冷启动；出口：成功 / 降级自动放行，失败与无权限停在原地只给「重试」。
 *
 * 页面没有按钮（失败态与无权限态的「重试」是唯一例外），也不显示 NAT / IPv6 / IPv4 明细：
 * 用户不需要读懂自检，只需要知道能不能用。
 *
 * 本地网络权限（Android 17 / API 37 起）：自检的第一项就是它。没授权时探针照样能读出
 * 192.168.x.x（那只是枚举网卡），真正的症状是 mDNS 收不到、局域网连不上——用户看到的是
 * 「找不到群」。所以这里先卡权限，未授权直接进「无权限」态并拉起系统授权框；
 * 授权回来重新跑一遍自检（spec §3 P0 状态表：无权限 → 停留，拉起系统授权，授权后自动复检）。
 *
 * 调试入口只走 adb intent extra，界面上不出现任何开关：
 *   --es huchuan.debug.network limited|failed|offline  强制降级 / 检测失败 / 无网络
 *   --es huchuan.debug.hold boot|checking|permission|result  停在指定阶段，供截图取证
 */
@Composable
fun NetworkScreen(
    state: HuchuanState,
    holdAt: String?,
    onEnter: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionRequired = LocalNetworkPermission.enforced

    var attempt by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf(SplashPhase.BOOT) }

    // 用户已明确拒绝过一次，且系统不再允许弹窗（shouldShowRequestPermissionRationale 为 false）
    // 之后，重试只能去应用设置页。
    var permanentDenial by remember { mutableStateOf(false) }

    // 上一次观察到的授权状态：用「变化」来触发复检，避免每次 ON_RESUME 都重跑自检。
    var lastGranted by remember { mutableStateOf(!permissionRequired || LocalNetworkPermission.isGranted(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        lastGranted = granted
        if (granted) {
            LocalNetworkPermission.log("granted", "source=prompt")
            permanentDenial = false
            attempt++
        } else {
            // 回调里 already-denied 且 rationale 为 false，说明这次系统根本没弹窗（或已选不再询问）。
            permanentDenial = !LocalNetworkPermission.shouldShowRationale(activity)
            LocalNetworkPermission.log("denied", "source=prompt permanent=" + permanentDenial)
            phase = SplashPhase.NO_PERMISSION
        }
    }

    // 从系统设置页回来：授权状态一变就自动复检（spec §3 P0：授权后自动复检）。
    // 首次 ON_RESUME 与初始值相等，不会误触发。
    DisposableEffect(lifecycleOwner, permissionRequired) {
        if (!permissionRequired) return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            val granted = LocalNetworkPermission.isGranted(context)
            if (granted == lastGranted) return@LifecycleEventObserver
            lastGranted = granted
            if (granted) {
                LocalNetworkPermission.log("granted", "source=resume")
                permanentDenial = false
                attempt++
            } else {
                LocalNetworkPermission.log("revoked", "source=resume")
                permanentDenial = !LocalNetworkPermission.shouldShowRationale(activity)
                phase = SplashPhase.NO_PERMISSION
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(attempt) {
        phase = SplashPhase.BOOT
        delay(BOOT_MS)
        if (holdAt == "boot") awaitCancellation()

        phase = SplashPhase.CHECKING
        delay(CHECK_MS)
        if (holdAt == "checking") awaitCancellation()

        if (permissionRequired && !LocalNetworkPermission.isGranted(context)) {
            phase = SplashPhase.NO_PERMISSION
            LocalNetworkPermission.log("required", "granted=false permanent=" + permanentDenial)
            Log.i(LocalNetworkPermission.LOG_TAG, "event=p0_result status=no_permission")
            if (holdAt == "result" || holdAt == "permission") awaitCancellation()
            if (!permanentDenial) {
                LocalNetworkPermission.log("request", "auto=true")
                permissionLauncher.launch(LocalNetworkPermission.PERMISSION)
            }
            awaitCancellation()
        }

        // 权限到位了才起内核：内核启动即开 mDNS，赶在授权之前起会让发现层报废
        // （HuchuanApplication 里已经解释）。已启动过的话这一步是空操作。
        state.runtime?.start()

        val report = state.probe.check()
        phase = when {
            report.status == NetworkStatus.PASS -> SplashPhase.PASS
            report.status == NetworkStatus.OFFLINE -> SplashPhase.OFFLINE
            report.status == NetworkStatus.FAIL && report.items.isNotEmpty() -> SplashPhase.DEGRADED
            else -> SplashPhase.FAILED
        }
        Log.i(
            LocalNetworkPermission.LOG_TAG,
            "event=p0_result status=" + phase.name.lowercase() +
                " local_network=" + if (permissionRequired) "granted" else "not_required",
        )

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

    val blocked = phase == SplashPhase.FAILED || phase == SplashPhase.OFFLINE ||
        phase == SplashPhase.NO_PERMISSION

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

            // 卡住前一直保留进度动画；卡住后停转并让位给「重试」。
            AnimatedVisibility(
                visible = !blocked,
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
                visible = blocked,
                enter = fadeIn(animationSpec = tween(180)),
                exit = fadeOut(animationSpec = tween(180)),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(Spacing.section))
                    BrandButton(onClick = {
                        if (phase == SplashPhase.NO_PERMISSION &&
                            permissionRequired &&
                            !LocalNetworkPermission.isGranted(context)
                        ) {
                            if (permanentDenial) {
                                // 系统不会再弹授权框：只能把用户送到应用设置页手动开。
                                LocalNetworkPermission.openAppSettings(context)
                            } else {
                                // 先按规格再请求一次；LaunchedEffect 起来后会拉起系统授权框。
                                attempt++
                            }
                        } else {
                            attempt++
                        }
                    }) {
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
    SplashPhase.NO_PERMISSION -> stringResource(R.string.p0_no_permission)
}

@Composable
private fun statusColor(phase: SplashPhase) = when (phase) {
    SplashPhase.PASS -> MaterialTheme.colorScheme.primary
    SplashPhase.DEGRADED -> MaterialTheme.colorScheme.tertiary
    SplashPhase.FAILED, SplashPhase.OFFLINE, SplashPhase.NO_PERMISSION -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
