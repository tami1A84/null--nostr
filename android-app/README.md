# Nostr Android Client

A native Android Nostr client built with Kotlin and Jetpack Compose.

## Features

### Core Functionality
- **Timeline**: View global and following timeline
- **Posting**: Create text notes
- **Reactions**: Like, repost, and zap posts
- **Direct Messages**: Encrypted DMs (NIP-17/44)
- **Profile Management**: View and edit your profile
- **Search**: Find users and notes

### Authentication
- **Local Key Storage**: Secure nsec storage
- **Amber Integration**: NIP-55 signer support
- **Read-only Mode**: Browse with npub only
- **Key Generation**: Create new Nostr identity

### NIPs Implemented
- **NIP-01**: Basic protocol (events, signing)
- **NIP-02**: Contact lists / Follow lists
- **NIP-05**: DNS-based verification
- **NIP-17**: Private Direct Messages
- **NIP-19**: Bech32 encoding (npub, nsec, note, etc.)
- **NIP-25**: Reactions
- **NIP-44**: Versioned encryption
- **NIP-55**: Android signer (Amber)
- **NIP-57**: Lightning Zaps
- **NIP-65**: Relay list metadata

## Tech Stack

- **Language**: Kotlin 2.0
- **UI**: Jetpack Compose + Material 3
- **Architecture**: MVVM with Clean Architecture
- **DI**: Hilt
- **Database**: Room
- **Networking**: OkHttp WebSocket
- **Crypto**: secp256k1-kmp
- **Image Loading**: Coil

## Building

### Prerequisites
- Android Studio Hedgehog or later
- JDK 17
- Android SDK 34

### Build Steps

1. Clone the repository
2. Open in Android Studio
3. Sync Gradle
4. Run on device/emulator

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease
```

## Project Structure

```
app/src/main/java/com/example/nostr/
├── data/
│   ├── database/       # Room database, entities, DAOs
│   ├── repository/     # Data repositories
│   └── model/          # Data models
├── di/                 # Hilt dependency injection
├── domain/             # Business logic
├── network/
│   └── relay/          # WebSocket relay connections
├── nostr/
│   ├── crypto/         # Signing, encryption (secp256k1)
│   ├── event/          # Nostr event types
│   └── nip/            # NIP implementations
├── ui/
│   ├── components/     # Reusable UI components
│   ├── navigation/     # Navigation setup
│   ├── screens/        # Screen composables + ViewModels
│   └── theme/          # Material theme
└── util/               # Utility classes
```

## Configuration

### Default Relays
The app connects to these relays by default:
- wss://relay.damus.io
- wss://relay.nostr.band
- wss://nos.lol
- wss://relay.snort.social
- wss://nostr.wine
- wss://relay.nostr.wirednet.jp

### Customization
Users can add/remove relays in the Settings screen.

## Security

- Private keys are stored in encrypted DataStore
- Keys are never exposed to untrusted code
- Amber integration keeps keys off-device
- No analytics or tracking

## License

This project is released under The Unlicense - see the LICENSE file for details.
