package io.nurunuru.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tailwind CSS → Compose Modifier bridge.
 *
 * Tailwind uses a 4px base unit (spacing-1 = 4dp, spacing-4 = 16dp).
 * These extension functions let you write Compose layouts using Tailwind
 * naming conventions for consistent design language across Web and Android.
 *
 * Usage:
 *   Modifier.nuruPadding(4)        // p-4  → padding 16dp all sides
 *   Modifier.nuruPaddingX(4)       // px-4 → padding 16dp horizontal
 *   Modifier.nuruPaddingY(2)       // py-2 → padding 8dp vertical
 *   Modifier.roundedXl()           // rounded-xl → 12dp corners
 *   Modifier.roundedFull()         // rounded-full → circle
 */

// ─── Tailwind base unit: 1 = 4dp ─────────────────────────────────────────────

/** Convert a Tailwind spacing value to dp (multiplied by 4). */
fun Int.tw(): Dp = (this * 4).dp

// ─── Spacing tokens (object mirrors Tailwind scale) ──────────────────────────

object NuruSpacing {
    val none = 0.dp
    val px   = 1.dp    // 0.25 × 4dp — single pixel equivalent
    val half = 2.dp    // 0.5 × 4dp
    val xs   = 4.dp    // 1
    val sm   = 8.dp    // 2
    val sm3  = 12.dp   // 3
    val md   = 16.dp   // 4
    val md5  = 20.dp   // 5
    val lg   = 24.dp   // 6
    val lg8  = 32.dp   // 8
    val xl   = 40.dp   // 10
    val xl3  = 48.dp   // 12
    val xl4  = 64.dp   // 16
    val xl5  = 80.dp   // 20
}

// ─── Padding modifiers ────────────────────────────────────────────────────────

/** p-{n}: uniform padding = n × 4dp */
fun Modifier.nuruPadding(n: Int): Modifier = padding(n.tw())

/** px-{n}: horizontal padding = n × 4dp */
fun Modifier.nuruPaddingX(n: Int): Modifier = padding(horizontal = n.tw())

/** py-{n}: vertical padding = n × 4dp */
fun Modifier.nuruPaddingY(n: Int): Modifier = padding(vertical = n.tw())

/** pt-{n}: top padding = n × 4dp */
fun Modifier.nuruPaddingTop(n: Int): Modifier = padding(top = n.tw())

/** pb-{n}: bottom padding = n × 4dp */
fun Modifier.nuruPaddingBottom(n: Int): Modifier = padding(bottom = n.tw())

// ─── Rounded corners ─────────────────────────────────────────────────────────

/** rounded-sm: 4dp corner radius */
fun Modifier.roundedSm(): Modifier = clip(RoundedCornerShape(4.dp))

/** rounded: 6dp corner radius */
fun Modifier.rounded(): Modifier = clip(RoundedCornerShape(6.dp))

/** rounded-md: 6dp corner radius (alias) */
fun Modifier.roundedMd(): Modifier = clip(RoundedCornerShape(6.dp))

/** rounded-lg: 8dp corner radius */
fun Modifier.roundedLg(): Modifier = clip(RoundedCornerShape(8.dp))

/** rounded-xl: 12dp corner radius */
fun Modifier.roundedXl(): Modifier = clip(RoundedCornerShape(12.dp))

/** rounded-2xl: 16dp corner radius */
fun Modifier.rounded2xl(): Modifier = clip(RoundedCornerShape(16.dp))

/** rounded-3xl: 24dp corner radius */
fun Modifier.rounded3xl(): Modifier = clip(RoundedCornerShape(24.dp))

/** rounded-full: circle clip */
fun Modifier.roundedFull(): Modifier = clip(CircleShape)

// ─── Size utilities ───────────────────────────────────────────────────────────

/** w-full h-full: fill maximum size */
fun Modifier.fillAll(): Modifier = fillMaxSize()

/** max-w-full: fill max width */
fun Modifier.wFull(): Modifier = fillMaxWidth()

// ─── Border utilities ─────────────────────────────────────────────────────────

/** Border with rounded corners (border + rounded-lg) */
fun Modifier.nuruBorder(
    color: Color,
    width: Dp = 0.5.dp,
    shape: Shape = RoundedCornerShape(12.dp)
): Modifier = border(width, color, shape)

/** Divider-style bottom border (replaces HorizontalDivider for specific rows) */
fun Modifier.borderBottom(color: Color, width: Dp = 0.5.dp): Modifier =
    border(
        width = width,
        color = Color.Transparent
    ) // Use HorizontalDivider instead; kept for completeness

// ─── Surface + bg utilities ───────────────────────────────────────────────────

/**
 * Card-like surface: rounded-xl + surface background color.
 * Equivalent to Tailwind's `bg-surface rounded-xl`.
 */
@Composable
fun Modifier.nuruCard(): Modifier =
    this
        .roundedXl()
        .background(MaterialTheme.colorScheme.surface)

/**
 * Elevated card surface (slightly lighter bg).
 */
@Composable
fun Modifier.nuruCardElevated(): Modifier =
    this
        .roundedXl()
        .background(MaterialTheme.colorScheme.surfaceVariant)

// ─── Typography scale tokens ──────────────────────────────────────────────────
//
// Mirrors Tailwind text-xs / text-sm / text-base / text-lg / text-xl naming.
// Usage: Text(text, style = NuruTextStyle.bodySmall)
//
// These are simple aliases to MaterialTheme typography — access via
// MaterialTheme.typography in Compose composables.

/**
 * Named typography aliases for inline documentation.
 *
 * | Tailwind      | Material 3 equivalent        | Size  |
 * |---------------|------------------------------|-------|
 * | text-xs       | labelSmall                   | 11sp  |
 * | text-sm       | bodySmall / labelMedium      | 12sp  |
 * | text-base     | bodyMedium                   | 14sp  |
 * | text-lg       | bodyLarge                    | 16sp  |
 * | text-xl       | titleMedium                  | 16sp  |
 * | text-2xl      | titleLarge                   | 22sp  |
 * | text-3xl      | headlineMedium               | 28sp  |
 */
object NuruTextStyle {
    // Accessed via MaterialTheme.typography in @Composable context
    const val LABEL_SMALL   = "labelSmall"    // text-xs
    const val BODY_SMALL    = "bodySmall"     // text-sm
    const val BODY_MEDIUM   = "bodyMedium"    // text-base (default)
    const val BODY_LARGE    = "bodyLarge"     // text-lg
    const val TITLE_SMALL   = "titleSmall"    // text-xl
    const val TITLE_MEDIUM  = "titleMedium"   // text-xl bold
    const val TITLE_LARGE   = "titleLarge"    // text-2xl
    const val HEADLINE_SMALL = "headlineSmall"// text-3xl
}

// ─── Animation constants ──────────────────────────────────────────────────────

/**
 * Duration constants that mirror framer-motion's default timing values.
 * Used with `animateFloatAsState`, `updateTransition`, etc.
 *
 * | framer-motion default | Compose equivalent |
 * |-----------------------|--------------------|
 * | duration: 0.2         | FAST = 200ms       |
 * | duration: 0.3         | NORMAL = 300ms     |
 * | duration: 0.5         | SLOW = 500ms       |
 */
object NuruAnimDuration {
    const val FAST   = 200
    const val NORMAL = 300
    const val SLOW   = 500
    const val SPRING = 350  // Spring-based interactions (scroll snap, card dismiss)
}
