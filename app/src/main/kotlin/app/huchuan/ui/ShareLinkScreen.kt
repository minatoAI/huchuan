package app.huchuan.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.huchuan.Destination
import app.huchuan.HuchuanState
import app.huchuan.R
import app.huchuan.link.LinkCreate
import app.huchuan.link.LinkLive
import app.huchuan.link.LinkPhase
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.launch

/**
 * P8 链接分享：二维码 + 链接 + 复制 + 有效期 + 状态行（link-share.md §3 P8）。
 *
 * 两种进入方式：
 *   - 从 P4「分享链接」：带 groupId/fileId，进页先创建（状态行「创建中」）；
 *   - 从 P9 点行 / 已有分享：直接按 shareId 显示。
 * 二维码本地生成（zxing 出 BitMatrix，Compose Canvas 画），不调用任何在线二维码服务。
 */
@Composable
fun ShareLinkScreen(
    state: HuchuanState,
    shareId: String,
    pendingGroupId: String? = null,
    pendingFileId: String? = null,
) {
    val controller = state.link
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val copied = stringResource(R.string.p6_copied)

    var currentId by remember(shareId, pendingGroupId, pendingFileId) { mutableStateOf(shareId) }
    var creating by remember(shareId, pendingGroupId, pendingFileId) {
        mutableStateOf(shareId.isEmpty() && pendingGroupId != null && pendingFileId != null)
    }
    var createFailure by remember(shareId, pendingGroupId, pendingFileId) { mutableStateOf("") }
    var retryKey by remember { mutableIntStateOf(0) }
    var record by remember(shareId) { mutableStateOf(controller?.share(shareId)) }

    LaunchedEffect(pendingGroupId, pendingFileId, retryKey) {
        if (currentId.isNotEmpty()) return@LaunchedEffect
        val groupId = pendingGroupId ?: return@LaunchedEffect
        val fileId = pendingFileId ?: return@LaunchedEffect
        creating = true
        createFailure = ""
        val outcome = controller?.create(groupId, fileId)
            ?: LinkCreate(false, failure = "not-configured")
        creating = false
        if (outcome.ok) {
            currentId = outcome.shareId
            record = controller?.share(outcome.shareId)
            state.replaceTop(Destination.LinkShare(outcome.shareId))
            if (controller != null && !controller.networkAddressWarned()) {
                controller.markNetworkAddressWarned()
                Toast.makeText(context, R.string.p8_address_warning, Toast.LENGTH_SHORT).show()
            }
        } else {
            createFailure = outcome.failure.ifEmpty { "create" }
        }
    }

    LaunchedEffect(currentId) {
        if (currentId.isEmpty()) return@LaunchedEffect
        val stored = controller?.share(currentId)
        record = stored
        if (stored != null && stored.active()) controller?.ensureSession(currentId)
    }

    val live = controller?.live?.collectAsState()?.value?.get(currentId)

    PageScaffold(
        title = stringResource(R.string.p8_title),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.pageVertical),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.pageVertical),
        ) {
            val stored = record
            when {
                creating -> Text(
                    text = stringResource(R.string.p8_creating),
                    style = MaterialTheme.typography.bodyLarge,
                )

                stored == null && createFailure.isNotEmpty() -> {
                    Text(
                        text = linkFailureLabel(createFailure),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = { retryKey++ }) {
                        Text(stringResource(R.string.common_retry))
                    }
                }

                stored == null -> Text(
                    text = stringResource(R.string.p8_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )

                stored.revoked || (live?.phase == LinkPhase.REVOKED) -> Text(
                    text = stringResource(R.string.p8_revoked),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )

                else -> {
                    ShareQrCode(
                        text = stored.link,
                        modifier = Modifier
                            .size(240.dp)
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                    )
                    Text(
                        text = stored.link,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    BrandButton(
                        onClick = {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText(stored.link, stored.link))
                            scope.launch { snackbar.showSnackbar(copied) }
                        },
                    ) {
                        Text(stringResource(R.string.p8_copy))
                    }
                    Text(
                        text = expiryLabel(stored.expiresAt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinkStatusLine(live = live)
                }
            }
        }
    }
}

/** 状态行：连接 / 等访客 / 访客已加入 / 发送进度 / 已发送 / 失败一句话。 */
@Composable
private fun LinkStatusLine(live: LinkLive?) {
    val phase = live?.phase ?: LinkPhase.CONNECTING
    val failed = phase == LinkPhase.FAILED || phase == LinkPhase.REVOKED
    val text = when (phase) {
        LinkPhase.CONNECTING -> stringResource(R.string.p8_connecting)
        LinkPhase.WAITING -> stringResource(R.string.p8_waiting)
        LinkPhase.INCOMING -> stringResource(R.string.p8_incoming)
        LinkPhase.SENDING -> {
            val percent = live?.percent ?: 0
            if (percent >= 0) stringResource(R.string.p8_sending, percent) else stringResource(R.string.p8_sending_plain)
        }

        LinkPhase.SENT -> stringResource(R.string.p8_sent)
        LinkPhase.REVOKED -> stringResource(R.string.p8_revoked)
        LinkPhase.FAILED -> linkFailureLabel(live?.failure.orEmpty())
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (phase == LinkPhase.SENDING) {
            LinearProgressIndicator(
                progress = { ((live?.percent ?: 0).coerceIn(0, 100)) / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 本地二维码：zxing 只负责出 BitMatrix，像素由 Canvas 画；不联网。 */
@Composable
fun ShareQrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        try {
            QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(
                    EncodeHintType.MARGIN to 2,
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.CHARACTER_SET to "UTF-8",
                ),
            )
        } catch (_: Throwable) {
            null
        }
    }
    if (matrix == null) return
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val side = minOf(size.width, size.height)
            val module = side / matrix.width
            val left = (size.width - module * matrix.width) / 2f
            val top = (size.height - module * matrix.height) / 2f
            drawRect(Color.White, topLeft = Offset.Zero, size = size)
            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) {
                    if (!matrix.get(x, y)) continue
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(left + x * module, top + y * module),
                        size = Size(module + 0.5f, module + 0.5f),
                    )
                }
            }
        }
    }
}

/** 剩余有效期：P8 与 P9 同一口径（天数 / 小时 / 分钟）。 */
@Composable
fun expiryLabel(expiresAt: Long): String {
    val left = expiresAt - System.currentTimeMillis()
    return when {
        left <= 0L -> stringResource(R.string.p8_expired)
        // 向上取整：刚创建（7 天差一分钟）显示「7 天后失效」，不是「6 天」。
        left >= DAY_MS -> stringResource(R.string.p8_expires_days, ((left + DAY_MS - 1) / DAY_MS).toInt())
        left >= HOUR_MS -> stringResource(R.string.p8_expires_hours, ((left + HOUR_MS - 1) / HOUR_MS).toInt())
        else -> stringResource(
            R.string.p8_expires_minutes,
            ((left + MINUTE_MS - 1) / MINUTE_MS).toInt().coerceAtLeast(1),
        )
    }
}

/** 失败码 → 一句话（创建失败与信令失败共用；不弹窗）。 */
@Composable
fun linkFailureLabel(code: String): String = when {
    code == "revoked" || code == "wait-expired" -> stringResource(R.string.p8_revoked)
    code == "missing-file" -> stringResource(R.string.p8_file_missing)
    code == "file-not-ready" -> stringResource(R.string.p8_file_not_ready)
    code == "file-empty" -> stringResource(R.string.p8_file_empty)
    code == "file-read" -> stringResource(R.string.p8_file_read_failed)
    code == "not-configured" -> stringResource(R.string.p8_not_configured)
    code == "not-started" -> stringResource(R.string.p8_not_started)
    code == "too-many" -> stringResource(R.string.p8_too_many)
    code.startsWith("network:") || code.startsWith("http_") -> stringResource(R.string.p8_server_unreachable)
    code == "connect-failed" || code == "network" || code == "reconnect-exhausted" ||
        code == "no-transport" || code == "bad-endpoint" -> stringResource(R.string.p8_no_direct)
    code == "busy" -> stringResource(R.string.p8_busy)
    code == "peer-left" -> stringResource(R.string.p8_peer_left)
    code == "transfer-timeout" -> stringResource(R.string.p8_transfer_timeout)
    code == "verify" || code == "verify-timeout" -> stringResource(R.string.p8_verify_failed)
    code == "send" -> stringResource(R.string.p8_send_failed)
    else -> stringResource(R.string.p8_connect_failed)
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60L * MINUTE_MS
private const val DAY_MS = 24L * HOUR_MS
