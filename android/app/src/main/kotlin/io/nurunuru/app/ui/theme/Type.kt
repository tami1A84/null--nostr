package io.nurunuru.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.nurunuru.app.R

val LineSeedJP = FontFamily(
    Font(R.font.line_seed_jp_rg, FontWeight.Normal),
    Font(R.font.line_seed_jp_bd, FontWeight.Bold),
    Font(R.font.line_seed_jp_bd, FontWeight.SemiBold),
    Font(R.font.line_seed_jp_rg, FontWeight.Light),
    Font(R.font.line_seed_jp_rg, FontWeight.Medium),
)

val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = LineSeedJP,
        fontWeight = FontWeight.Normal,
        fontSize = NuruTokenDimens.FontSizeBase,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = LineSeedJP,
        fontWeight = FontWeight.Normal,
        fontSize = NuruTokenDimens.FontSizeSm,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    bodySmall = TextStyle(
        fontFamily = LineSeedJP,
        fontWeight = FontWeight.Normal,
        fontSize = NuruTokenDimens.FontSizeXs,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = LineSeedJP,
        fontWeight = FontWeight.SemiBold,
        fontSize = NuruTokenDimens.FontSizeXl,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = LineSeedJP,
        fontWeight = FontWeight.SemiBold,
        fontSize = NuruTokenDimens.FontSizeBase,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = LineSeedJP,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.sp
    )
)
