package app.huchuan.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 主色：深玉青 #1F6F5C（AGENTS.md §9）。关闭 dynamic color，明暗跟随系统，不设主题开关。
 */
private val JadeGreen = Color(0xFF1F6F5C)
private val JadeMint = Color(0xFFAFE3CC)
private val JadeDeep = Color(0xFF0C362A)

private val LightScheme = lightColorScheme(
    primary = JadeGreen,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = JadeMint,
    onPrimaryContainer = JadeDeep,
    secondary = Color(0xFF4A635A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8DA),
    onSecondaryContainer = JadeDeep,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF85CBA9),
    onPrimary = JadeDeep,
    primaryContainer = Color(0xFF0F4A3B),
    onPrimaryContainer = JadeMint,
    secondary = Color(0xFFB1CCC0),
    onSecondary = JadeDeep,
    secondaryContainer = Color(0xFF32463E),
    onSecondaryContainer = Color(0xFFCCE8DA),
)

@Composable
fun HuchuanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
