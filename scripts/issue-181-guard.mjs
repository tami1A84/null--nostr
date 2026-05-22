#!/usr/bin/env node
/**
 * Issue #181 guard — prevents reintroduction of unkeyed (plaintext-DB) MLS
 * FFI constructors in app-layer code.
 *
 * Background:
 *   PR for issue #181 made the MLS SQLite DB SQLCipher-encrypted on disk. The
 *   plaintext-DB code paths still exist as low-level UniFFI constructors
 *   (`NuruNuruClient(secretKeyHex:)` in Swift, `NuruNuruClient.invoke(...)` in
 *   Kotlin, and `newReadOnly(pubkeyHex:)` on both) because the FFI surface is
 *   generated. Application code must only use the `*WithMlsDbKey` variants.
 *
 * What this script does:
 *   - Walks the iOS Swift sources (`ios/NuruNuru/`) and Android Kotlin
 *     sources (`android/app/src/main/kotlin/`).
 *   - Fails the build if it finds calls to the forbidden unkeyed
 *     constructors anywhere outside the generated UniFFI bindings or
 *     explicitly allowlisted wrapper files.
 *   - Reports the file + line + matched text for every offence so reviewers
 *     can fix them quickly.
 *
 * Run locally: `npm run lint:issue-181`
 * In CI: invoked from the same script alongside the rest of the lint suite.
 */

import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(scriptDir, '..')

// ---------------------------------------------------------------------------
// Directories scanned and files allowlisted
// ---------------------------------------------------------------------------

const SCAN_ROOTS = [
  { dir: 'ios/NuruNuru', exts: ['.swift'] },
  { dir: 'android/app/src/main/kotlin', exts: ['.kt'] },
  // napi-rs Rust crate is intentionally NOT scanned: it operates at the
  // FFI layer itself and must invoke the lower-level constructors.
]

const ALLOWLIST = new Set([
  // The single wrapper file allowed to mention NuruNuruClient unkeyed APIs
  // is the live FFI client, which only uses them through the WithMlsDbKey
  // constructors. The guard still verifies the file does not call the
  // forbidden symbols — see FORBIDDEN_PATTERNS below.
])

// Skip generated bindings — they are not app code and must contain the
// unkeyed constructors as declarations.
const SKIP_DIR_FRAGMENTS = [
  'bindgen/swift-out',
  'bindgen/kotlin-out',
  'NuruNuruFFI.xcframework',
  'build/',
  '/build/',
  '.build/',
]

// ---------------------------------------------------------------------------
// Forbidden patterns
// ---------------------------------------------------------------------------
//
// Each pattern has:
//   id:      short code shown in the failure report
//   regex:   ECMAScript regex matched per line
//   message: human-readable explanation
//   exts:    extensions to apply this pattern on (subset of SCAN_ROOTS exts)
//
// Patterns are intentionally tight so they cannot match the
// `*WithMlsDbKey` variants (those contain the word "MlsDbKey").
// ---------------------------------------------------------------------------

const FORBIDDEN_PATTERNS = [
  {
    id: 'swift-ctor-unkeyed',
    // try NuruNuruClient(secretKeyHex: …)  — but NOT *WithMlsDbKey()
    regex: /\bNuruNuruClient\s*\(\s*secretKeyHex\s*:/,
    message:
      'Use `NuruNuruClient.newWithMlsDbKey(secretKeyHex:mlsDbKey:)` ' +
      '(via MlsDbKeyStore.deriveInternalKey) instead of the unkeyed ' +
      'constructor — Issue #181.',
    exts: ['.swift'],
  },
  {
    id: 'swift-readonly-unkeyed',
    // NuruNuruClient.newReadOnly(pubkeyHex: …) called from app code.
    // The WithMlsDbKey variant has "WithMlsDbKey" in the name, so this
    // regex anchors on a word boundary right after `newReadOnly`.
    regex: /\bNuruNuruClient\s*\.\s*newReadOnly\s*\(/,
    message:
      'Use `NuruNuruClient.newReadOnlyWithMlsDbKey(pubkeyHex:mlsDbKey:)` ' +
      '(via MlsDbKeyStore.getOrCreateExternalKey) instead of `newReadOnly` ' +
      '— Issue #181.',
    exts: ['.swift'],
  },
  {
    id: 'kotlin-ctor-unkeyed',
    // Kotlin UniFFI emits NuruNuruClient as a class; the unkeyed invoke is
    // exposed as `NuruNuruClient(secretKeyHex)` (positional) or
    // `NuruNuruClient.invoke(secretKeyHex)` in older bindings. Anchor on
    // the constructor-style call shape and exclude the `newWithMlsDbKey`
    // companion call.
    regex: /\bNuruNuruClient\s*\(\s*[a-zA-Z_][a-zA-Z0-9_]*\s*\)/,
    message:
      'Use `NuruNuruClient.newWithMlsDbKey(keyHex, dbKey)` (via ' +
      'MlsDbKeyStore.deriveInternalKey) instead of the unkeyed constructor — ' +
      'Issue #181.',
    exts: ['.kt'],
  },
  {
    id: 'kotlin-readonly-unkeyed',
    // NuruNuruClient.newReadOnly(pubkeyHex)  (Kotlin call site)
    regex: /\bNuruNuruClient\s*\.\s*newReadOnly\s*\(/,
    message:
      'Use `NuruNuruClient.newReadOnlyWithMlsDbKey(pubkeyHex, dbKey)` ' +
      '(via MlsDbKeyStore.getOrCreateExternalKey) instead of `newReadOnly` ' +
      '— Issue #181.',
    exts: ['.kt'],
  },
]

// ---------------------------------------------------------------------------
// Filesystem walk
// ---------------------------------------------------------------------------

function walk(dir, exts) {
  const out = []
  for (const name of readdirSync(dir)) {
    const p = path.join(dir, name)
    const relFromRoot = path.relative(root, p)
    if (SKIP_DIR_FRAGMENTS.some((frag) => relFromRoot.includes(frag))) continue
    let st
    try {
      st = statSync(p)
    } catch {
      continue
    }
    if (st.isDirectory()) {
      out.push(...walk(p, exts))
    } else if (exts.some((e) => name.endsWith(e))) {
      out.push(p)
    }
  }
  return out
}

// ---------------------------------------------------------------------------
// Lint
// ---------------------------------------------------------------------------

const failures = []
let filesScanned = 0
let matchesChecked = 0

for (const { dir, exts } of SCAN_ROOTS) {
  const absDir = path.join(root, dir)
  if (!existsSync(absDir)) {
    console.warn(`WARN  skip missing scan root: ${dir}`)
    continue
  }
  const files = walk(absDir, exts)
  for (const f of files) {
    filesScanned++
    const relPath = path.relative(root, f).replace(/\\/g, '/')
    if (ALLOWLIST.has(relPath)) continue
    const text = readFileSync(f, 'utf8')
    const lines = text.split(/\r?\n/)
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i]
      // Skip comments — single-line // and /// for Swift/Kotlin.
      // Block comments are not perfectly handled, but the conservative
      // single-line check covers ~all real-world usage.
      const trimmed = line.trim()
      if (trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed.startsWith('/*')) continue
      for (const pat of FORBIDDEN_PATTERNS) {
        if (!pat.exts.some((e) => f.endsWith(e))) continue
        matchesChecked++
        if (pat.regex.test(line)) {
          failures.push({
            file: relPath,
            line: i + 1,
            id: pat.id,
            snippet: line.trim(),
            message: pat.message,
          })
        }
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Report
// ---------------------------------------------------------------------------

if (failures.length === 0) {
  console.log(
    `issue-181-guard OK — scanned ${filesScanned} files, ` +
      `${matchesChecked} pattern checks, 0 violations.`,
  )
  process.exit(0)
}

console.error(`\nissue-181-guard FAIL — ${failures.length} violation(s):\n`)
for (const f of failures) {
  console.error(`  ${f.file}:${f.line}  [${f.id}]`)
  console.error(`    ${f.snippet}`)
  console.error(`    → ${f.message}\n`)
}
console.error(
  'These constructors open the MLS DB in plaintext mode and reintroduce ' +
    'the vulnerability fixed by issue #181. See docs/wiki/features/' +
    'mls-db-encryption.md for the approved keyed variants.\n',
)
process.exit(1)
