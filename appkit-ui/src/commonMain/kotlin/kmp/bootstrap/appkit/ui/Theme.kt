package kmp.bootstrap.appkit.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kmp.bootstrap.appkit.ThemeMode

/**
 * Bộ màu theo ĐÚNG tên token của shadcn (`--background`, `--primary`, `--muted-foreground`…) để sinh được từ CSS của web
 * bằng `tools/shadcn-theme.mjs` — web và app dùng chung một bảng màu, đổi ở một chỗ.
 *
 * `extra` chứa token riêng của app (vùng công suất, màu biểu đồ…): `colors.extra("fitness")`.
 */
@Immutable
data class AppColors(
    val dark: Boolean,
    val background: Color, val foreground: Color,
    val card: Color, val cardForeground: Color,
    val popover: Color, val popoverForeground: Color,
    val primary: Color, val primaryForeground: Color,
    val secondary: Color, val secondaryForeground: Color,
    val muted: Color, val mutedForeground: Color,
    val accent: Color, val accentForeground: Color,
    val destructive: Color, val destructiveForeground: Color,
    val border: Color, val input: Color, val ring: Color,
    val extra: Map<String, Color> = emptyMap(),
) {
    /** Màu token riêng của app; thiếu thì ném lỗi rõ ràng thay vì trả màu đoán. */
    fun extra(name: String): Color = extra[name] ?: error("thiếu token màu '$name' — thêm vào CSS rồi sinh lại theme")
}

/** Ba họ chữ: giao diện, số liệu, và câu văn do người/AI viết. Mặc định dùng họ chữ hệ thống; app đổi bằng font nhúng. */
@Immutable
data class AppTypography(
    val sans: FontFamily = FontFamily.SansSerif,
    val mono: FontFamily = FontFamily.Monospace,
    val serif: FontFamily = FontFamily.Serif,
) {
    val title = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = (-0.4).sp)
    val section = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    val body = TextStyle(fontFamily = sans, fontSize = 14.sp, lineHeight = 20.sp)
    val small = TextStyle(fontFamily = sans, fontSize = 12.sp, lineHeight = 16.sp)
    val tiny = TextStyle(fontFamily = sans, fontSize = 11.sp)
    val numeric = TextStyle(fontFamily = mono, fontSize = 14.sp)
    val numericLarge = TextStyle(fontFamily = mono, fontWeight = FontWeight.SemiBold, fontSize = 24.sp)
    val prose = TextStyle(fontFamily = serif, fontSize = 15.sp, lineHeight = 22.sp)
}

val LocalAppColors = staticCompositionLocalOf<AppColors> { error("AppTheme chưa được bọc quanh màn hình này") }
val LocalAppTypography = staticCompositionLocalOf { AppTypography() }

/** Truy cập nhanh: `A.colors.primary`, `A.type.body`. */
object A {
    val colors: AppColors @Composable get() = LocalAppColors.current
    val type: AppTypography @Composable get() = LocalAppTypography.current
}

@Composable
fun AppTheme(
    light: AppColors,
    dark: AppColors,
    mode: ThemeMode = ThemeMode.System,
    typography: AppTypography = AppTypography(),
    content: @Composable () -> Unit,
) {
    val useDark = when (mode) { ThemeMode.Dark -> true; ThemeMode.Light -> false; ThemeMode.System -> isSystemInDarkTheme() }
    val c = if (useDark) dark else light
    CompositionLocalProvider(LocalAppColors provides c, LocalAppTypography provides typography) {
        MaterialTheme(colorScheme = c.toMaterial(), content = content)
    }
}

/** Ánh xạ token shadcn sang ColorScheme của Material 3 để component Material mặc định cũng đúng màu. */
fun AppColors.toMaterial() =
    if (dark) darkColorScheme(
        primary = primary, onPrimary = primaryForeground, secondary = secondary, onSecondary = secondaryForeground,
        background = background, onBackground = foreground, surface = card, onSurface = cardForeground,
        surfaceVariant = muted, onSurfaceVariant = mutedForeground, outline = border, error = destructive, onError = destructiveForeground,
    ) else lightColorScheme(
        primary = primary, onPrimary = primaryForeground, secondary = secondary, onSecondary = secondaryForeground,
        background = background, onBackground = foreground, surface = card, onSurface = cardForeground,
        surfaceVariant = muted, onSurfaceVariant = mutedForeground, outline = border, error = destructive, onError = destructiveForeground,
    )
