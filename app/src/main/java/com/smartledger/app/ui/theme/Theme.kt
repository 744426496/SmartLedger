package com.smartledger.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** 统一圆角：小件 12、卡片 20、对话框/弹层 28，整体更柔和一致 */
private val AppShapes = Shapes(
    small = RoundedCornerShape(Dimens.cornerSm),
    medium = RoundedCornerShape(Dimens.cornerLg),
    large = RoundedCornerShape(Dimens.cornerXl),
)

/** 主题模式：多种预设主题色 + 系统莫奈取色 */
enum class ThemeMode(val label: String, val previewColor: Color?) {
    GREEN("绿色", Color(0xFF2E7D32)),
    BLUE("蓝色", Color(0xFF1565C0)),
    PURPLE("紫色", Color(0xFF6A1B9A)),
    ORANGE("橙色", Color(0xFFE65100)),
    PINK("粉色", Color(0xFFC2185B)),
    TEAL("青色", Color(0xFF00696B)),
    MONET("莫奈取色", null),
}

private data class Palette(
    val primary: Long, val onPrimary: Long,
    val primaryContainer: Long, val onPrimaryContainer: Long,
    val secondary: Long, val onSecondary: Long,
    val secondaryContainer: Long, val onSecondaryContainer: Long,
    val tertiary: Long, val onTertiary: Long,
    val tertiaryContainer: Long, val onTertiaryContainer: Long,
)

private val palettes = mapOf(
    ThemeMode.GREEN to Palette(
        0xFF2E7D32, 0xFFFFFFFF, 0xFFB7E4B9, 0xFF0B3D0F,
        0xFF558B2F, 0xFFFFFFFF, 0xFFC8E6B7, 0xFF1B330D,
        0xFF00796B, 0xFFFFFFFF, 0xFFB2DFDB, 0xFF00332E,
    ),
    ThemeMode.BLUE to Palette(
        0xFF1565C0, 0xFFFFFFFF, 0xFFD3E3FD, 0xFF041E49,
        0xFF0277BD, 0xFFFFFFFF, 0xFFC6E7FF, 0xFF00344F,
        0xFF00639B, 0xFFFFFFFF, 0xFFCEE5FF, 0xFF001D33,
    ),
    ThemeMode.PURPLE to Palette(
        0xFF6A1B9A, 0xFFFFFFFF, 0xFFF2DAFF, 0xFF2E0054,
        0xFF7B1FA2, 0xFFFFFFFF, 0xFFEFB6FF, 0xFF300049,
        0xFFAD1457, 0xFFFFFFFF, 0xFFFFD9E2, 0xFF3E001D,
    ),
    ThemeMode.ORANGE to Palette(
        0xFFE65100, 0xFFFFFFFF, 0xFFFFDBBF, 0xFF361100,
        0xFFEF6C00, 0xFFFFFFFF, 0xFFFFDCC0, 0xFF311300,
        0xFFB26500, 0xFFFFFFFF, 0xFFFFDEBC, 0xFF381D00,
    ),
    ThemeMode.PINK to Palette(
        0xFFC2185B, 0xFFFFFFFF, 0xFFF8BBD0, 0xFF4A0B24,
        0xFFD81B60, 0xFFFFFFFF, 0xFFF48FB1, 0xFF4A0B24,
        0xFF00838F, 0xFFFFFFFF, 0xFFB2EBF2, 0xFF00363D,
    ),
    ThemeMode.TEAL to Palette(
        0xFF00696B, 0xFFFFFFFF, 0xFF9CF0F2, 0xFF002021,
        0xFF4A6364, 0xFFFFFFFF, 0xFFCCE8E9, 0xFF062021,
        0xFF51606E, 0xFFFFFFFF, 0xFFD4E4F6, 0xFF0E1C29,
    ),
)

private fun lightScheme(p: Palette) = lightColorScheme(
    primary = Color(p.primary),
    onPrimary = Color(p.onPrimary),
    primaryContainer = Color(p.primaryContainer),
    onPrimaryContainer = Color(p.onPrimaryContainer),
    secondary = Color(p.secondary),
    onSecondary = Color(p.onSecondary),
    secondaryContainer = Color(p.secondaryContainer),
    onSecondaryContainer = Color(p.onSecondaryContainer),
    tertiary = Color(p.tertiary),
    onTertiary = Color(p.onTertiary),
    tertiaryContainer = Color(p.tertiaryContainer),
    onTertiaryContainer = Color(p.onTertiaryContainer),
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    error = ExpenseColor,
    onError = Color.White,
)

private fun darkScheme(p: Palette) = darkColorScheme(
    primary = Color(p.primaryContainer),
    onPrimary = Color(p.onPrimaryContainer),
    primaryContainer = Color(p.primary),
    onPrimaryContainer = Color(p.onPrimary),
    secondary = Color(p.secondaryContainer),
    onSecondary = Color(p.onSecondaryContainer),
    secondaryContainer = Color(p.secondary),
    onSecondaryContainer = Color(p.onSecondary),
    tertiary = Color(p.tertiaryContainer),
    onTertiary = Color(p.onTertiaryContainer),
    tertiaryContainer = Color(p.tertiary),
    onTertiaryContainer = Color(p.onTertiary),
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurface = DarkOnSurface,
    onSurfaceVariant = DarkOnSurfaceVariant,
    error = Color(0xFFFF8A80),
    onError = Color(0xFF690005),
)

@Composable
fun SmartLedgerTheme(
    themeMode: ThemeMode = ThemeMode.GREEN,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when (themeMode) {
        ThemeMode.MONET -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> {
            val p = palettes.getValue(themeMode)
            if (darkTheme) darkScheme(p) else lightScheme(p)
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content,
    )
}
