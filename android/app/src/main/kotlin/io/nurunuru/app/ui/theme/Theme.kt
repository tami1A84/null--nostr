package io.nurunuru.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = LineGreen,
    onPrimary = Color.Black,
    primaryContainer = LineGreenLight,
    onPrimaryContainer = LineGreen,
    secondary = TextSecondary,
    onSecondary = BgPrimary,
    background = BgPrimary,
    onBackground = TextPrimary,
    surface = BgSecondary,
    onSurface = TextPrimary,
    surfaceVariant = BgTertiary,
    onSurfaceVariant = TextSecondary,
    outline = BorderColor,
    outlineVariant = BorderColorStrong,
    error = ColorError,
    onError = BgPrimary
)

private val LightColorScheme = lightColorScheme(
    primary = LineGreen,
    onPrimary = Color.White,
    primaryContainer = LineGreenLight,
    onPrimaryContainer = LineGreenDark,
    secondary = TextSecondaryLight,
    onSecondary = Color.White,
    background = BgPrimaryLight,
    onBackground = TextPrimaryLight,
    surface = BgSecondaryLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = BgTertiaryLight,
    onSurfaceVariant = TextSecondaryLight,
    outline = BorderColorLight,
    error = Color(0xFFE57373),
    onError = Color.White
)

// Custom colors accessible via LocalNuruColors.current
data class NuruColors(
    val lineGreen: Color,
    val zapColor: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val bgPrimary: Color,
    val bgSecondary: Color,
    val bgTertiary: Color,
    val border: Color
)

val LocalNuruColors = staticCompositionLocalOf {
    NuruColors(
        lineGreen = LineGreen,
        zapColor = ColorZap,
        textPrimary = TextPrimary,
        textSecondary = TextSecondary,
        textTertiary = TextTertiary,
        bgPrimary = BgPrimary,
        bgSecondary = BgSecondary,
        bgTertiary = BgTertiary,
        border = BorderColor
    )
}

@Composable
fun NuruNuruTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val nuruColors = if (darkTheme) {
        NuruColors(
            lineGreen = LineGreen,
            zapColor = ColorZap,
            textPrimary = TextPrimary,
            textSecondary = TextSecondary,
            textTertiary = TextTertiary,
            bgPrimary = BgPrimary,
            bgSecondary = BgSecondary,
            bgTertiary = BgTertiary,
            border = BorderColor
        )
    } else {
        NuruColors(
            lineGreen = LineGreen,
            zapColor = ColorZap,
            textPrimary = TextPrimaryLight,
            textSecondary = TextSecondaryLight,
            textTertiary = TextTertiaryLight,
            bgPrimary = BgPrimaryLight,
            bgSecondary = BgSecondaryLight,
            bgTertiary = BgTertiaryLight,
            border = BorderColorLight
        )
    }

    CompositionLocalProvider(LocalNuruColors provides nuruColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
