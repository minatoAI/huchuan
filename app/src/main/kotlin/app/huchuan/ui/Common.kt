package app.huchuan.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.huchuan.R
import app.huchuan.core.FileScope
import app.huchuan.core.FileState
import app.huchuan.core.GroupFile
import app.huchuan.ui.theme.BrandJade
import java.util.Locale

/** 8dp 栅格上的四档间距；页面只用这几个值，避免 12/16/24 随手混用。 */
object Spacing {
    val pageHorizontal = 16.dp
    val pageVertical = 16.dp
    val section = 24.dp
    val item = 12.dp
    val rowMinHeight = 48.dp
}

/**
 * 页面骨架。bottomBar 给「固定在底部的操作行」用（P4 评论输入行）：
 * 正文拿到的 innerPadding.bottom 会等于 bottomBar 的实测高度，因此正文滚动区
 * 自动缩短，不会钻到条下面；bottomBar 自己负责吃窗口 Insets。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScaffold(
    title: String,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    // P4 长文件名要两行且语义必须是原文：标题槽可换成自绘内容，默认仍是单行 Text。
    titleContent: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (titleContent != null) {
                        titleContent()
                    } else {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = { navigation?.invoke() },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        },
        snackbarHost = snackbarHost,
        bottomBar = bottomBar,
        content = content,
    )
}

/**
 * 主操作按钮：明暗两套都用品牌深玉青 #1F6F5C + 白字。
 * M3 的 primary 在深色模式是提亮版（给文本 / 开关留对比度），按钮单独锁品牌色，
 * 避免「深色模式按钮变薄荷绿」。
 */
@Composable
fun BrandButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = BrandJade,
            contentColor = Color.White,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
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

/**
 * P4 顶栏文件名：按实测宽度折两行，不往字符串里塞零宽字符。
 *
 * 视觉上是两个 Text，但语义只留外层一个节点，text 就是真实文件名；
 * 这样 uiautomator / TalkBack 读到的标题不会被断行辅助字符污染。
 */
@Composable
fun FileNameTitle(name: String, style: TextStyle, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .semantics { text = AnnotatedString(name) },
    ) {
        val lines = remember(name, constraints.maxWidth, style, density, measurer) {
            splitFileName(name, style, constraints.maxWidth, measurer)
        }
        Column(modifier = Modifier.clearAndSetSemantics {}) {
            Text(
                text = lines.first(),
                style = style,
                maxLines = 1,
                softWrap = false,
            )
            if (lines.size > 1) {
                Text(
                    text = lines[1],
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun splitFileName(
    name: String,
    style: TextStyle,
    maxWidth: Int,
    measurer: TextMeasurer,
): List<String> {
    if (maxWidth <= 0 || maxWidth == Constraints.Infinity) return listOf(name)
    fun fits(candidate: String): Boolean =
        measurer.measure(candidate, style = style, maxLines = 1, softWrap = false).size.width <= maxWidth
    if (fits(name)) return listOf(name)

    val dot = name.lastIndexOf('.')
    val hasExtension = dot > 0 && dot < name.length - 1
    val lastSplit = (if (hasExtension) dot else name.length - 1).coerceAtLeast(1)

    // 优先在扩展名前最后一个分隔符处折行，第二行尽量是完整的“x265.mkv”。
    for (i in lastSplit downTo 1) {
        if (name[i - 1] == '.' || name[i - 1] == '-' || name[i - 1] == '_' || name[i - 1] == ' ') {
            if (fits(name.substring(0, i)) && fits(name.substring(i))) {
                return listOf(name.substring(0, i), name.substring(i))
            }
        }
    }
    // 没有可用分隔符：首行取能放下的最长前缀，次行交给 TextOverflow 兜底。
    var split = lastSplit
    while (split > 1 && !fits(name.substring(0, split))) split--
    return listOf(name.substring(0, split), name.substring(split))
}

/**
 * 单行中间省略，保留扩展名（P3 文件行）：先把尾部（至少扩展名）留住，再用剩余宽度补头部。
 * Compose 的 TextOverflow 只有末尾省略，中间省略要靠实测宽度自己切。
 */
@Composable
fun MiddleEllipsisText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        val maxWidth = constraints.maxWidth
        val display = remember(text, maxWidth, style, density, measurer) {
            if (maxWidth <= 0 || maxWidth == Constraints.Infinity) {
                text
            } else {
                middleEllipsis(text, style, maxWidth, measurer)
            }
        }
        Text(
            text = display,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun middleEllipsis(
    text: String,
    style: TextStyle,
    maxWidth: Int,
    measurer: TextMeasurer,
): String {
    fun fits(candidate: String): Boolean =
        measurer.measure(
            text = candidate,
            style = style,
            maxLines = 1,
            softWrap = false,
        ).size.width <= maxWidth

    if (fits(text)) return text
    val dot = text.lastIndexOf('.')
    val hasExtension = dot > 0 && dot < text.length - 1 && text.length - dot <= 12
    val extension = if (hasExtension) text.substring(dot) else ""
    val stem = if (hasExtension) text.substring(0, dot) else text
    if (stem.isEmpty()) return text
    if (!fits("…" + extension)) return text

    // 宽度对字符数基本单调：二分“最多能留几个字符”，尾部多分一点，尾部才认得出是哪个文件。
    var low = 0
    var high = stem.length - 1
    var best = 0
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (fits(middleCandidate(stem, mid, extension))) {
            best = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return middleCandidate(stem, best, extension)
}

private fun middleCandidate(stem: String, keep: Int, extension: String): String {
    if (keep <= 0) return "…" + extension
    val safeKeep = keep.coerceAtMost(stem.length - 1)
    // 头部多留一点（文件名前面通常是片名），尾部只保留扩展名附近那几字符。
    val tail = (safeKeep / 4)
        .coerceAtLeast(extension.length + 2)
        .coerceAtMost(safeKeep)
    val head = safeKeep - tail
    return stem.take(head) + "…" + stem.takeLast(tail) + extension
}

/**
 * 公开条目的元数据还没到手：内核把 size 记 0，等 ut_metadata 回来再本地回填
 * （public-swarm §1.1 / §3.1）。只看它一个字段，不去猜状态机。
 */
fun GroupFile.awaitingSource(): Boolean = scope == FileScope.PUBLIC && sizeBytes <= 0

/**
 * 开关是不是「开」：在传（做种 / 下载）或者用户已经点了激活、内核还在找源。
 *
 * 寻找来源那一段内核读数仍然是「没在传」（[GroupFile.state] 是 SLEEPING、百分比 0），
 * 但用户刚拨过开关，开关不能自己弹回去 —— 那是「点了激活没反应」的另一半
 * （docs/reports/public-swarm-recheck.md 问题 1）。
 */
fun GroupFile.isActive(): Boolean = state != FileState.SLEEPING || searchingSource

/**
 * 状态文案。三种原有状态不变；公开条目多出的两种都在「没有元数据」这一段（public-swarm §6.2）——
 * 放着等来源说「等待来源」，用户点了激活、内核在找源说「正在寻找来源」；
 * 没有元数据就既不能下载也不能做种，说「休眠」等于撒谎。
 */
@Composable
fun fileStateLabel(file: GroupFile): String = when {
    file.searchingSource -> stringResource(R.string.p3_finding_source)
    file.awaitingSource() -> stringResource(R.string.p3_waiting_source)
    file.state == FileState.SEEDING -> stringResource(R.string.p3_state_seeding)
    file.state == FileState.DOWNLOADING -> stringResource(R.string.p3_state_downloading)
    else -> stringResource(R.string.p3_state_sleeping)
}

/**
 * P4 激活/休眠开关下面那一行「当前后果」：开关本身只表达状态，后果得用一句人话写出来。
 * 四种文案与内核状态一一对应，不新增控件（ui-ia.md §2.1）。
 */
@Composable
fun fileConsequenceLabel(file: GroupFile): String = when {
    file.searchingSource -> stringResource(R.string.p4_consequence_finding)
    file.state == FileState.SEEDING -> stringResource(R.string.p4_consequence_seeding)
    file.state == FileState.DOWNLOADING -> stringResource(R.string.p4_consequence_downloading)
    else -> stringResource(R.string.p4_consequence_sleeping)
}

/**
 * 磁力摘要（P4 一行）：只留 xt 的前 16 位 infohash，够认出是哪一条；点一下复制整条。
 * 认不出的形状原样返回，不假装截断过。
 */
fun magnetDigest(magnet: String): String {
    val prefix = "magnet:?xt=urn:btih:"
    if (!magnet.startsWith(prefix, ignoreCase = true)) return magnet
    val infohash = magnet.substring(prefix.length).substringBefore('&')
    return if (infohash.length > 16) "btih:" + infohash.take(16) + "…" else magnet
}

/**
 * 组队是否该出现在界面上（P3 的 chip 与 P4 的「正在组队」行共用这一个判定）。
 *
 * 口径（spec §306）：队友数 = 内核观测到的「真互连、非做种者、本机仍处于部分」的成员数；
 * 本机到 100% 即退出组队，所以这里再卡一次 progress < 100 —— 内核那边 isFinished 为真时
 * teammates 已经回到 0，这一条是给「进度已经打满、状态还没翻页」的那一拍兜底，保证立刻消失。
 */
fun GroupFile.showsTeam(): Boolean = teammates > 0 && progressPercent < 100

/** 状态 chip：只做展示，不给点击语义。强调态用 primaryContainer，跟品牌色同族。 */
@Composable
fun StateChip(text: String, highlighted: Boolean = false) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (highlighted) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
