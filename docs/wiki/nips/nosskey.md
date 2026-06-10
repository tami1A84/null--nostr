# Nosskey — Passkey-Derived Nostr Keys (draft NIP / nosskey-sdk)

## Summary

Nosskey (a portmanteau of **Nos**tr + pass**key**) is a draft NIP and open-source
SDK that derives the Nostr secp256k1 secret key directly from the WebAuthn
**PRF extension** output of a platform Passkey. The secret is **never persisted
to disk** — only a small `{ credentialId, pubkey, salt, username? }` metadata
record is kept. Each signing / NIP-04 / NIP-44 operation may re-prompt the user
for a Face ID / Touch ID / 指紋認証 assertion to recompute the secret, unless the
platform integration keeps a short-lived in-memory PRF/secret-key cache.

Reference:
- SDK: <https://github.com/ocknamo/nosskey-sdk>
- Draft NIP: `docs/nip-draft.md` in the nosskey-sdk repository.

null--nostr ships first-class Nosskey support on all three platforms (Web,
iOS, Android) in the new-user onboarding flow.

## How it works (PRF Direct Method)

1. Register a WebAuthn Passkey with the PRF extension enabled.
2. To sign anything, request an assertion with a fixed PRF salt
   `"nostr-pwk"` (UTF-8 bytes `0x6e 0x6f 0x73 0x74 0x72 0x2d 0x70 0x77 0x6b`,
   hex `6e6f7374722d70776b`).
3. The 32-byte PRF output IS the secp256k1 Schnorr private key.
4. Wipe the secret from memory after use.

The same passkey + same salt deterministically yields the same Nostr key. On
platforms that support discoverable credentials, a fresh app install can ask the
Passkey picker for any credential under the RP and rebuild the local metadata
from the assertion result.

## Standard salt

The canonical PRF salt across implementations is the UTF-8 string `"nostr-pwk"`.

- hex: `6e6f7374722d70776b`
- bytes: `[0x6e, 0x6f, 0x73, 0x74, 0x72, 0x2d, 0x70, 0x77, 0x6b]`

The legacy value `6e6f7374722d6b6579` (`"nostr-key"`) appears in older nosskey
SDK releases and in this repo's pre-2026-05-23 Web sign-up. `nosskey-sdk@^0.1.2`
auto-normalises legacy salt values to the standard one on load; new keys
across all three platforms are now created with the standard salt.

## Current behavior

### Web (`components/SignUpModal.js`, `components/LoginScreen.js`)

- Uses `nosskey-sdk@^0.1.2` from npm. Lazy-loaded in `LoginScreen.js` and
  `app/page.js`.
- `NosskeyManager` is created with `storageKey: 'nurunuru_nosskey'` and a
  1-hour key cache.
- 5-step sign-up wizard (`SignUpModal.js`):
  1. `welcome` — calls `createPasskey({ rp: { name: 'ぬるぬる' }, … })` to
     create the resident Passkey, then calls `exportNostrKey(keyInfo, cid)`
     with a minimal SDK-0.1.x-compatible `NostrKeyInfo` shape to derive the
     initial secret key and public key. This preserves the pre-0.1.2 Web
     behavior of one Passkey registration prompt plus one PRF assertion.
  2. `relay`   — region picker → recommended relays.
  3. `profile` — kind 0 metadata + kind 10002 NIP-65 relay list publish.
  4. `tutorial` — `#nostrはじめました` first post.
  5. `success` — show npub / complete or redirect to app.
- The old `backup` step and `exportNostrKey(null, credentialId)` call were
  removed when moving to `nosskey-sdk@0.1.x`, where `exportNostrKey` requires
  a non-null `NostrKeyInfo`.
- Subsequent logins call `NosskeyManager.createNostrKey()` with no
  `credentialId` so the browser shows the passkey picker. Normal Web login does
  **not** call `exportNostrKey()`; exporting is reserved for app redirect,
  explicit key export, DM fallback, or signing paths that require the secret.
  This avoids a second passkey prompt during login.
- When the user explicitly exports the key, Web stores it in the module-private
  key store and persists an encrypted-at-rest copy using AES-GCM with a
  non-extractable per-origin WebCrypto key in IndexedDB. On reload,
  `app/page.js` restores this copy only when auto-sign is enabled, without
  invoking WebAuthn.

### iOS (`ios/NuruNuru/Data/NosskeyManager.swift`, `NosskeySigner.swift`)

- Native `AuthenticationServices` framework. No third-party SDK.
- Available only on iOS 18.0+ (`PRF` extension shipped with that release).
  On iOS 17 the UI shows the classic "アカウントを作成する" button + caption
  "パスキー対応はiOS 18以降で利用できます".
- `NosskeyManager` (`@MainActor`) wraps:
  - `ASAuthorizationPlatformPublicKeyCredentialRegistrationRequest.prf`
    with `InputValues(saltInput1: "nostr-pwk")` for registration. If the
    registration result does not include PRF bytes, iOS immediately performs one
    assertion to derive the Nostr secret.
  - `ASAuthorizationPlatformPublicKeyCredentialAssertionRequest.prf`
    with `.inputValues(saltInput1: …)` for every secret derivation.
  - Discoverable assertions with no `allowedCredentials` as the reinstall
    recovery path. If UserDefaults metadata is gone, iOS shows the RP's Passkey
    picker, returns the credential ID + PRF output, and the app saves a fresh
    `NosskeyKeyInfo` before entering the session.
- `NosskeySigner` implements the common `EventSigner` protocol so it slots
  into existing `NostrRepository` paths without further refactoring. It keeps
  a 5-minute in-memory PRF cache to avoid prompting the user on every event.
- Sign-up wizard skips the `backup` step for the passkey path (5 steps
  instead of 6) — the passkey itself is the backup via iCloud Keychain.
- `rpId = "www.nullnull.app"`. For real device registration, a
  `webcredentials:www.nullnull.app` Associated Domains entitlement and an
  `apple-app-site-association` file at
  `https://www.nullnull.app/.well-known/apple-app-site-association`
  must be deployed:
  ```json
  { "webcredentials": { "apps": ["66G7S3P755.io.nurunuru.app"] } }
  ```
  iOS Simulator accepts the request regardless.

### Android (`android/.../data/NosskeyManager.kt`, `signers/NosskeySigner.kt`)

- `androidx.credentials.CredentialManager` 1.2.2 (already in deps).
- Requires Android API 28+ (Credential Manager + PRF).
- The PRF extension is requested via the request JSON
  `"extensions": { "prf": { "eval": { "first": "<saltBase64Url>" } } }` for
  assertion, and `"extensions": { "prf": {} }` for registration.
- `NosskeySigner` implements the existing `AppSigner` interface used by
  `NostrRepository` and `NostrClient`.
- `LoginScreen.kt` shows "パスキーでログイン" only when stored keyInfo exists.
  `SignUpModal.kt` shows "パスキーで登録" as primary + "従来の方法で作成（nsec）"
  as secondary when the device supports it. Sign-up wizard switches to a
  5-step flow (skips backup) for the passkey path.
- `rpId = "www.nullnull.app"`. Production rollout requires
  `https://nullnull.app/.well-known/assetlinks.json` binding the application
  ID `io.nurunuru.app` to the RP.

## Storage shape

```jsonc
// Persisted record (UserDefaults / SharedPreferences / localStorage)
{
  "credentialId": "<hex (iOS, Android) or base64url (Android raw)>",
  "pubkey":       "<32-byte secp256k1 x-only public key, lowercase hex>",
  "salt":         "6e6f7374722d70776b",
  "username":     "user"            // optional
}
```

The secret key is **never** stored. It's derived on demand via biometric
prompt and zeroized after use.

## Platform notes

| Concern | Web | iOS | Android |
|---|---|---|---|
| Library | `nosskey-sdk@^0.1.2` | Native `AuthenticationServices` (iOS 18+) | `androidx.credentials` 1.2.2 (API 28+) |
| Storage key | `nurunuru_nosskey` (localStorage) | `nurunuru_nosskey_keyinfo` (UserDefaults) | `nurunuru_nosskey` SharedPreferences |
| Login method flag | `nurunuru_login_method = 'nosskey'` | `loginMethod = "nosskey"` | `loginMethod = "nosskey"` |
| Signer | `NosskeyManager.signEvent` | `NosskeySigner` (`EventSigner` impl) | `NosskeySigner` (`AppSigner` impl) |
| Cache TTL | 60 min | 5 min | 5 min |
| RP ID | `location.host` (auto) | `"www.nullnull.app"` | `"www.nullnull.app"` |
| Sign-up step count | 5 (skip backup) | 5 (skip backup) | 5 (skip backup) |
| Fallback | nostr-login extension / Web NIP-46 where enabled | nsec import; NIP-46 signer removed by ADR-0023 | nsec / NIP-55 (Amber) |

## Source references

- Web
  - `components/SignUpModal.js` (uses `NosskeyManager.createPasskey` /
    `exportNostrKey(keyInfo, cid)`; salt `6e6f7374722d70776b`)
  - `components/LoginScreen.js` (lazy-loads `NosskeyManager`, restores stored
    key info, and uses discoverable `createNostrKey()` for passkey login)
  - `app/page.js` (rehydration of `NosskeyManager` on reload without passive
    encrypted auto-sign key restore)
  - `src/adapters/signing/NosskeySigner.ts` (tracks nosskey-sdk 0.1.x flat
    NIP-04/NIP-44 method names; broader DM routing is future work)
- iOS
  - `ios/NuruNuru/Data/NosskeyManager.swift` (PRF orchestration)
  - `ios/NuruNuru/Data/NosskeySigner.swift` (event signing with cached secret)
  - `ios/NuruNuru/Data/EventSigner.swift` (shared protocol for
    InternalSigner / NosskeySigner)
  - `ios/NuruNuru/ViewModels/AuthViewModel.swift`
    (`generateNewAccountWithPasskey`, `loginWithPasskey`,
    `currentSessionSigner`)
  - `ios/NuruNuru/Views/Screens/LoginView.swift` (`SignUpWelcomeStep`,
    `SignUpSheet.generateAccountWithPasskey`)
  - `ios/NuruNuru/Data/AppPreferences.swift` (`loginMethod`)
  - `ios/NuruNuru/Data/NostrRepository.swift` (injectable `signer:` arg)
- Android
  - `android/app/src/main/kotlin/io/nurunuru/app/data/NosskeyManager.kt`
  - `android/app/src/main/kotlin/io/nurunuru/app/data/signers/NosskeySigner.kt`
  - `android/app/src/main/kotlin/io/nurunuru/app/viewmodel/AuthViewModel.kt`
    (`generateNewAccountWithPasskey`, `loginWithPasskey`, `buildSigner`)
  - `android/app/src/main/kotlin/io/nurunuru/app/ui/screens/LoginScreen.kt`
    (uncommented + wired passkey login button)
  - `android/app/src/main/kotlin/io/nurunuru/app/ui/components/SignUpModal.kt`
    (`onNextWithPasskey`, 5-step progress when passkey)
  - `android/app/src/main/kotlin/io/nurunuru/app/data/prefs/AppPreferences.kt`
    (`loginMethod`)

## Related pages

- [[../features/onboarding|features/onboarding]] — 5-step passkey sign-up wizard
  with tutorial post step.
- [[nip-46|NIP-46: Nostr Connect]] — Web NIP-46 path; iOS signer removed by ADR-0023.
- [[../decisions/adr-0010-passkey-prf-direct-method|ADR-0010]] — why we chose
  the PRF Direct Method over an encryption/decryption Passkey scheme.

## Open questions

- Apple/Google Passkey backup quality varies by region. Cross-device sync
  reliability needs more telemetry before we promote nosskey as the default
  path over nsec.
- Production AASA (iOS) and assetlinks.json (Android) files for
  `nullnull.app` are **not** yet deployed. Until they ship, registration on
  physical devices fails; Simulator/emulator paths work.
- iOS deployment-target bump from 17.0 → 18.0 is not done. iOS 17 users see
  the classic nsec flow with a "iOS 18以降で利用できます" notice.
- iOS reinstall recovery should be verified on physical devices with iCloud
  Keychain enabled and production AASA deployed; Simulator behavior is not a
  substitute for the synced-Passkey path.
- Should `NosskeyKeyInfo.username` be exposed in profile copy ("@user")
  somewhere in Settings? Currently we hardcode `"user"` to avoid asking the
  user up-front.
- Web sign-up currently still needs one Passkey creation prompt plus one PRF
  assertion to obtain the Nostr secret with `nosskey-sdk@0.1.2`. Native
  iOS/Android can derive the secret during registration; true one-prompt Web
  sign-up parity depends on WebAuthn PRF registration-result support and/or SDK
  API changes.
