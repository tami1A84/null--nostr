#!/usr/bin/env node
/**
 * Design Token Generator
 *
 * Reads tokens.json and generates:
 *   1. app/globals.tokens.css        — CSS custom properties
 *   2. android/.../ui/theme/NuruTokens.kt — Kotlin color/dimens/anim constants
 *
 * Usage:
 *   node design-tokens/generate.mjs          # Generate and overwrite
 *   node design-tokens/generate.mjs --check  # Check for drift (exit 1 if diff)
 */

import { readFileSync, writeFileSync, existsSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..');

const tokens = JSON.parse(readFileSync(join(__dirname, 'tokens.json'), 'utf-8'));

const CSS_OUT = join(ROOT, 'app', 'globals.tokens.css');
const KT_OUT = join(ROOT, 'android', 'app', 'src', 'main', 'kotlin', 'io', 'nurunuru', 'app', 'ui', 'theme', 'NuruTokens.kt');

const isCheck = process.argv.includes('--check');

// ─── Helpers ──────────────────────────────────────────────────────────────────

function hexToRgba(hex, alpha) {
  const h = hex.replace('#', '');
  const r = parseInt(h.substring(0, 2), 16);
  const g = parseInt(h.substring(2, 4), 16);
  const b = parseInt(h.substring(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

function hexToArgbInt(hex, alpha = 1.0) {
  const h = hex.replace('#', '').toUpperCase();
  const a = Math.round(alpha * 255).toString(16).toUpperCase().padStart(2, '0');
  return `0x${a}${h}`;
}

function capitalize(s) {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

function camelToKebab(s) {
  return s.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase();
}

// ─── CSS Generation ───────────────────────────────────────────────────────────

function generateCSS() {
  const lines = [
    '/* ============================================================ */',
    '/* Auto-generated from design-tokens/tokens.json — DO NOT EDIT  */',
    '/* Run: npm run tokens                                          */',
    '/* ============================================================ */',
    '',
    ':root {',
  ];

  // Colors
  lines.push('  /* =========================== */');
  lines.push('  /* Design System: Color Tokens */');
  lines.push('  /* =========================== */');

  for (const [group, colors] of Object.entries(tokens.color)) {
    lines.push('');
    lines.push(`  /* ${group} */`);
    for (const [, def] of Object.entries(colors)) {
      const cssName = def.cssName;
      if (!cssName) continue; // Skip tokens without cssName (Android-only)

      let cssValue;
      if (def.value) {
        cssValue = def.value;
      } else if (def.hex && def.alpha !== undefined) {
        cssValue = hexToRgba(def.hex, def.alpha);
      }

      if (cssValue) {
        lines.push(`  ${cssName}: ${cssValue};`);
      }
    }
  }

  // Spacing
  lines.push('');
  lines.push('  /* =========================== */');
  lines.push('  /* Design System: Spacing Scale (8px base) */');
  lines.push('  /* =========================== */');
  for (const [key, def] of Object.entries(tokens.spacing)) {
    lines.push(`  --space-${key}: ${def.value}px;`);
  }

  // Radius
  lines.push('');
  lines.push('  /* =========================== */');
  lines.push('  /* Design System: Border Radius */');
  lines.push('  /* =========================== */');
  for (const [key, def] of Object.entries(tokens.radius)) {
    lines.push(`  --radius-${key}: ${def.value}px;`);
  }

  // Shadows
  lines.push('');
  lines.push('  /* =========================== */');
  lines.push('  /* Design System: Shadows */');
  lines.push('  /* =========================== */');
  for (const [key, def] of Object.entries(tokens.shadow)) {
    lines.push(`  --shadow-${camelToKebab(key)}: ${def.css};`);
  }

  // Typography
  lines.push('');
  lines.push('  /* =========================== */');
  lines.push('  /* Design System: Typography */');
  lines.push('  /* =========================== */');
  for (const [key, def] of Object.entries(tokens.typography.fontSize)) {
    lines.push(`  --font-size-${key}: ${def.value}px;`);
  }
  lines.push('');
  for (const [key, def] of Object.entries(tokens.typography.lineHeight)) {
    lines.push(`  --line-height-${key}: ${def.value};`);
  }

  // Transitions
  lines.push('');
  lines.push('  /* =========================== */');
  lines.push('  /* Design System: Transitions */');
  lines.push('  /* =========================== */');
  for (const [key, def] of Object.entries(tokens.transition)) {
    lines.push(`  --transition-${key}: ${def.css};`);
  }

  lines.push('}');
  lines.push('');

  return lines.join('\n');
}

// ─── Kotlin Generation ────────────────────────────────────────────────────────

function generateKotlin() {
  const lines = [
    '// ============================================================',
    '// Auto-generated from design-tokens/tokens.json — DO NOT EDIT',
    '// Run: npm run tokens',
    '// ============================================================',
    '',
    'package io.nurunuru.app.ui.theme',
    '',
    'import androidx.compose.ui.graphics.Color',
    'import androidx.compose.ui.unit.Dp',
    'import androidx.compose.ui.unit.TextUnit',
    'import androidx.compose.ui.unit.dp',
    'import androidx.compose.ui.unit.sp',
    '',
  ];

  // ── NuruTokenColors ──
  lines.push('/** Color tokens — mirrors CSS custom properties in globals.tokens.css */');
  lines.push('object NuruTokenColors {');

  for (const [group, colors] of Object.entries(tokens.color)) {
    lines.push(`    // ─── ${group} ───`);
    for (const [, def] of Object.entries(colors)) {
      const name = def.kotlinName;
      if (!name) continue; // Skip tokens without kotlinName

      if (def.value) {
        const hex = def.value.replace('#', '').toUpperCase();
        lines.push(`    val ${name} = Color(0xFF${hex})`);
      } else if (def.hex && def.alpha !== undefined) {
        lines.push(`    val ${name} = Color(${hexToArgbInt(def.hex, def.alpha)})`);
      }
    }
    lines.push('');
  }

  lines.push('}');
  lines.push('');

  // ── NuruTokenDimens ──
  lines.push('/** Spacing, radius, font size, and elevation tokens */');
  lines.push('object NuruTokenDimens {');

  // Spacing
  lines.push('    // ─── Spacing (8px base grid) ───');
  for (const [key, def] of Object.entries(tokens.spacing)) {
    lines.push(`    val Space${key} = ${def.value}.dp`);
  }
  lines.push('');

  // Radius
  lines.push('    // ─── Border Radius ───');
  for (const [key, def] of Object.entries(tokens.radius)) {
    const name = `Radius${capitalize(key)}`;
    lines.push(`    val ${name} = ${def.value}.dp`);
  }
  lines.push('');

  // Font sizes
  lines.push('    // ─── Font Sizes ───');
  for (const [key, def] of Object.entries(tokens.typography.fontSize)) {
    const name = `FontSize${capitalize(key)}`;
    lines.push(`    val ${name} = ${def.value}.sp`);
  }
  lines.push('');

  // Line heights (as multipliers)
  lines.push('    // ─── Line Heights (multiplier) ───');
  for (const [key, def] of Object.entries(tokens.typography.lineHeight)) {
    const name = `LineHeight${capitalize(key)}`;
    lines.push(`    val ${name} = ${def.value}f`);
  }
  lines.push('');

  // Elevation
  lines.push('    // ─── Elevation (maps to CSS box-shadow) ───');
  for (const [key, def] of Object.entries(tokens.shadow)) {
    const name = `Elevation${capitalize(key)}`;
    lines.push(`    val ${name} = ${def.elevation}.dp`);
  }

  lines.push('}');
  lines.push('');

  // ── NuruTokenAnim ──
  lines.push('/** Animation duration constants (milliseconds) */');
  lines.push('object NuruTokenAnim {');
  for (const [key, def] of Object.entries(tokens.transition)) {
    const name = `Duration${capitalize(key)}`;
    lines.push(`    const val ${name} = ${def.durationMs}`);
  }
  lines.push('}');
  lines.push('');

  return lines.join('\n');
}

// ─── Main ─────────────────────────────────────────────────────────────────────

const cssContent = generateCSS();
const ktContent = generateKotlin();

if (isCheck) {
  let hasDrift = false;

  if (!existsSync(CSS_OUT) || readFileSync(CSS_OUT, 'utf-8') !== cssContent) {
    console.error('DRIFT: app/globals.tokens.css is out of sync with tokens.json');
    hasDrift = true;
  }
  if (!existsSync(KT_OUT) || readFileSync(KT_OUT, 'utf-8') !== ktContent) {
    console.error('DRIFT: NuruTokens.kt is out of sync with tokens.json');
    hasDrift = true;
  }

  if (hasDrift) {
    console.error('\nRun "npm run tokens" to regenerate.');
    process.exit(1);
  } else {
    console.log('All tokens in sync.');
    process.exit(0);
  }
} else {
  writeFileSync(CSS_OUT, cssContent);
  console.log(`Generated: ${CSS_OUT}`);

  writeFileSync(KT_OUT, ktContent);
  console.log(`Generated: ${KT_OUT}`);
}
