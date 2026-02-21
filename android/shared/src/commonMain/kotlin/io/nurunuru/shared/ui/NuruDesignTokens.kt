package io.nurunuru.shared.ui

/**
 * Design tokens shared across all platforms.
 *
 * Colors are stored as ARGB Long values (0xAARRGGBB format).
 * Each platform converts these to its native color type:
 *   - Android: androidx.compose.ui.graphics.Color(value)
 *   - iOS: SwiftUI Color(red:green:blue:opacity:)
 *
 * Mirrors the Web globals.css and tailwind.config.js color definitions.
 */
object NuruColors {

    // ─── Brand / Accent ───────────────────────────────────────────────────────
    const val LINE_GREEN       = 0xFF06C755L  // #06C755 – primary brand color
    const val LINE_GREEN_DARK  = 0xFF05A347L  // #05A347 – pressed/hover state
    const val LINE_GREEN_LIGHT = 0x1A06C755L  // 10% opacity – container background

    // ─── Dark mode backgrounds ────────────────────────────────────────────────
    const val BG_PRIMARY   = 0xFF0A0A0AL  // #0A0A0A – main background
    const val BG_SECONDARY = 0xFF1C1C1EL  // #1C1C1E – card / surface
    const val BG_TERTIARY  = 0xFF2C2C2EL  // #2C2C2E – subtle elevation / skeleton

    // ─── Dark mode text ───────────────────────────────────────────────────────
    const val TEXT_PRIMARY   = 0xFFF5F5F5L  // #F5F5F5
    const val TEXT_SECONDARY = 0xFFB3B3B3L  // #B3B3B3
    const val TEXT_TERTIARY  = 0xFF8A8A8AL  // #8A8A8A – timestamps, meta

    // ─── Borders ─────────────────────────────────────────────────────────────
    const val BORDER        = 0xFF38383AL   // #38383A
    const val BORDER_STRONG = 0xFF48484AL   // #48484A

    // ─── Semantic ────────────────────────────────────────────────────────────
    const val COLOR_ERROR   = 0xFFEF9A9AL   // #EF9A9A
    const val COLOR_WARNING = 0xFFFFCC80L   // #FFCC80
    const val COLOR_ZAP     = 0xFFFFB74DL   // #FFB74D – ⚡ Lightning / Zap
    const val COLOR_INFO    = 0xFF90CAF9L   // #90CAF9
    const val COLOR_SUCCESS = 0xFF06C755L   // same as LINE_GREEN
    const val COLOR_LIKE    = 0xFFFF6B6BL   // #FF6B6B – heart/like red

    // ─── Light mode ───────────────────────────────────────────────────────────
    const val BG_PRIMARY_LIGHT   = 0xFFFFFFFFL   // #FFFFFF
    const val BG_SECONDARY_LIGHT = 0xFFF7F8FAL   // #F7F8FA
    const val BG_TERTIARY_LIGHT  = 0xFFEBEDF0L   // #EBEDFF0
    const val TEXT_PRIMARY_LIGHT   = 0xFF1A1A1AL  // #1A1A1A
    const val TEXT_SECONDARY_LIGHT = 0xFF555555L  // #555555
    const val TEXT_TERTIARY_LIGHT  = 0xFF767676L  // #767676
    const val BORDER_LIGHT         = 0xFFE8E8E8L  // #E8E8E8
}

/**
 * Engagement weight constants, identical to Web's recommendation.js.
 * Used by [io.nurunuru.shared.recommendation.RecommendationEngine].
 */
object NuruEngagementWeights {
    const val ZAP      = 100.0
    const val QUOTE    = 35.0
    const val REPLY    = 30.0
    const val REPOST   = 25.0
    const val BOOKMARK = 15.0
    const val LIKE     = 5.0
}
