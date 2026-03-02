// ============================================================
// Auto-generated from design-tokens/tokens.json — DO NOT EDIT
// Run: npm run tokens
// ============================================================

package io.nurunuru.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Color tokens — mirrors CSS custom properties in globals.tokens.css */
object NuruTokenColors {
    // ─── brand ───
    val LineGreen = Color(0xFF06C755)
    val LineGreenDark = Color(0xFF05A347)
    val LineGreenLight = Color(0x1A06C755)

    // ─── dark ───
    val BgPrimary = Color(0xFF0A0A0A)
    val BgSecondary = Color(0xFF1C1C1E)
    val BgTertiary = Color(0xFF2C2C2E)
    val TextPrimary = Color(0xFFF5F5F5)
    val TextSecondary = Color(0xFFB3B3B3)
    val TextTertiary = Color(0xFF8A8A8A)
    val BorderColor = Color(0xFF38383A)
    val BorderColorStrong = Color(0xFF48484A)

    // ─── light ───
    val BgPrimaryLight = Color(0xFFFFFFFF)
    val BgSecondaryLight = Color(0xFFF7F8FA)
    val BgTertiaryLight = Color(0xFFEBEDF0)
    val TextPrimaryLight = Color(0xFF1A1A1A)
    val TextSecondaryLight = Color(0xFF555555)
    val TextTertiaryLight = Color(0xFF767676)
    val BorderColorLight = Color(0xFFE8E8E8)

    // ─── semantic ───
    val ColorError = Color(0xFFEF9A9A)
    val ColorWarning = Color(0xFFFFCC80)
    val ColorSuccess = Color(0xFF06C755)
    val ColorZap = Color(0xFFFFB74D)
    val ColorInfo = Color(0xFF90CAF9)
    val ColorEncourage = Color(0xFF81C784)
    val ColorGentle = Color(0xFF90CAF9)
    val ColorBirdwatch = Color(0xFF2196F3)

}

/** Spacing, radius, font size, and elevation tokens */
object NuruTokenDimens {
    // ─── Spacing (8px base grid) ───
    val Space1 = 4.dp
    val Space2 = 8.dp
    val Space3 = 12.dp
    val Space4 = 16.dp
    val Space5 = 24.dp
    val Space6 = 32.dp
    val Space7 = 40.dp
    val Space8 = 48.dp
    val Space9 = 56.dp
    val Space10 = 64.dp

    // ─── Border Radius ───
    val RadiusSm = 4.dp
    val RadiusMd = 8.dp
    val RadiusLg = 12.dp
    val RadiusXl = 16.dp
    val Radius2xl = 24.dp
    val RadiusFull = 9999.dp

    // ─── Font Sizes ───
    val FontSizeXs = 12.sp
    val FontSizeSm = 14.sp
    val FontSizeBase = 16.sp
    val FontSizeLg = 18.sp
    val FontSizeXl = 20.sp
    val FontSize2xl = 24.sp

    // ─── Line Heights (multiplier) ───
    val LineHeightTight = 1.25f
    val LineHeightNormal = 1.5f
    val LineHeightRelaxed = 1.625f

    // ─── Elevation (maps to CSS box-shadow) ───
    val ElevationSm = 1.dp
    val ElevationMd = 4.dp
    val ElevationLg = 8.dp
    val ElevationGreen = 4.dp
    val ElevationGreenLg = 8.dp
}

/** Animation duration constants (milliseconds) */
object NuruTokenAnim {
    const val DurationFast = 150
    const val DurationNormal = 200
    const val DurationSlow = 300
}
