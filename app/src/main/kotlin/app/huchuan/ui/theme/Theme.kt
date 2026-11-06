package app.huchuan.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 品牌主色：深玉青 #1F6F5C。填充按钮在明暗两套里都用它，保证品牌色落在屏幕上最重的控件上。
 * 文本 / 图标 / 开关等需要与背景保持 4.5:1 的角色，深色模式用同色相提亮版（见 DarkScheme.primary）。
 */
val BrandJade = Color(0xFF1F6F5C)

/**
 * 浅色方案：以 #1F6F5C 为种子展开的绿灰中性色。
 * surface / outline / container 全部带一点玉青偏色，不再落回 Material 默认的紫灰。
 */
private val LightScheme = lightColorScheme(
    primary = BrandJade,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA6F2D8),
    onPrimaryContainer = Color(0xFF002117),
    inversePrimary = Color(0xFF82D5B9),
    secondary = Color(0xFF4A635A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8DC),
    onSecondaryContainer = Color(0xFF062019),
    tertiary = Color(0xFF3E6374),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC1E8FB),
    onTertiaryContainer = Color(0xFF001F2B),
    background = Color(0xFFF7FBF8),
    onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF7FBF8),
    onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDBE5DE),
    onSurfaceVariant = Color(0xFF3F4945),
    surfaceTint = BrandJade,
    inverseSurface = Color(0xFF2C322F),
    inverseOnSurface = Color(0xFFEDF1EE),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF6F7974),
    outlineVariant = Color(0xFFBFC9C3),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF7FBF8),
    surfaceDim = Color(0xFFD7DBD8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5F2),
    surfaceContainer = Color(0xFFEBEFEC),
    surfaceContainerHigh = Color(0xFFE5EAE6),
    surfaceContainerHighest = Color(0xFFDFE4E0),
    primaryFixed = Color(0xFFA6F2D8),
    primaryFixedDim = Color(0xFF8AD6BC),
    onPrimaryFixed = Color(0xFF002117),
    onPrimaryFixedVariant = Color(0xFF005141),
    secondaryFixed = Color(0xFFCCE8DC),
    secondaryFixedDim = Color(0xFFB1CCC0),
    onSecondaryFixed = Color(0xFF062019),
    onSecondaryFixedVariant = Color(0xFF334B42),
    tertiaryFixed = Color(0xFFC1E8FB),
    tertiaryFixedDim = Color(0xFFA5CCDF),
    onTertiaryFixed = Color(0xFF001F2B),
    onTertiaryFixedVariant = Color(0xFF254A5A),
)

/**
 * 深色方案：primary 是同色相提亮版 #4FC7A5（对 surface #0F1513 为 8.8:1，正文级对比度）。
 * 填充按钮仍用 BrandJade #1F6F5C：对深底 3.07:1（组件边界达标），白字 6.02:1。
 */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF4FC7A5),
    onPrimary = Color(0xFF00281E),
    primaryContainer = Color(0xFF0F4A3B),
    onPrimaryContainer = Color(0xFFB7F0DC),
    inversePrimary = BrandJade,
    secondary = Color(0xFFB1CCC0),
    onSecondary = Color(0xFF1D352C),
    secondaryContainer = Color(0xFF334B42),
    onSecondaryContainer = Color(0xFFCCE8DC),
    tertiary = Color(0xFFA5CCDF),
    onTertiary = Color(0xFF0A3446),
    tertiaryContainer = Color(0xFF254A5A),
    onTertiaryContainer = Color(0xFFC1E8FB),
    background = Color(0xFF0F1513),
    onBackground = Color(0xFFDFE4E0),
    surface = Color(0xFF0F1513),
    onSurface = Color(0xFFDFE4E0),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBEC9C3),
    surfaceTint = Color(0xFF4FC7A5),
    inverseSurface = Color(0xFFDFE4E0),
    inverseOnSurface = Color(0xFF2C322F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF88938D),
    outlineVariant = Color(0xFF3F4945),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF353B38),
    surfaceDim = Color(0xFF0F1513),
    surfaceContainerLowest = Color(0xFF0A0F0D),
    surfaceContainerLow = Color(0xFF171D1A),
    surfaceContainer = Color(0xFF1B211E),
    surfaceContainerHigh = Color(0xFF252B28),
    surfaceContainerHighest = Color(0xFF303633),
    primaryFixed = Color(0xFFA6F2D8),
    primaryFixedDim = Color(0xFF8AD6BC),
    onPrimaryFixed = Color(0xFF002117),
    onPrimaryFixedVariant = Color(0xFF005141),
    secondaryFixed = Color(0xFFCCE8DC),
    secondaryFixedDim = Color(0xFFB1CCC0),
    onSecondaryFixed = Color(0xFF062019),
    onSecondaryFixedVariant = Color(0xFF334B42),
    tertiaryFixed = Color(0xFFC1E8FB),
    tertiaryFixedDim = Color(0xFFA5CCDF),
    onTertiaryFixed = Color(0xFF001F2B),
    onTertiaryFixedVariant = Color(0xFF254A5A),
)

/**
 * Switch 的品牌容器色：明暗两套的已选轨道都锁 BrandJade #1F6F5C + 白拇指。
 *
 * 不能跟 `colorScheme.primary`：深色主题的 primary 是给文本 / 图标留对比度的提亮版
 * #4FC7A5（薄荷绿），跟锁死的 [BrandButton] 不同源，同页会出现两种绿（走查：P4/P7 深色）。
 * 白拇指对 #1F6F5C 为 6.02:1；轨道对深底 #0F1513 为 3.07:1，组件边界达标。
 */
@Composable
fun brandSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = BrandJade,
    checkedBorderColor = BrandJade,
    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
)

/** Material 3 字号阶梯：只落实现阶段用到的角色，全部按 M3 的 size / lineHeight / tracking。 */
private val HuchuanTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

/** Material 3 圆角阶梯。按钮用 full（M3 默认），输入框 extraSmall，卡片 large。 */
private val HuchuanShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun HuchuanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // dynamic color 刻意关闭：不调用 dynamicLightColorScheme / dynamicDarkColorScheme，
    // 两套配色只从品牌色 #1F6F5C 派生，系统壁纸取色不会覆盖品牌。
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = HuchuanTypography,
        shapes = HuchuanShapes,
        content = content,
    )
}
