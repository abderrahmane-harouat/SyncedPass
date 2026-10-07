# SyncedPass for Android

The Android app, written in Kotlin with Jetpack Compose. It reads and writes the same vault file as the macOS app, and syncs with it. It works locally only: there's no server or cloud, and Android's cloud backup and device transfer are turned off for its data. The app's only network use is syncing with your paired Mac over the local network ([docs/SYNC.md](../docs/SYNC.md)); Android requires the `INTERNET` permission for any socket, but the app only connects to, and accepts connections from, local addresses.

**Version 0.2.0** · Android 8.0 (API 26) or later

<p align="center">
  <img src="../docs/screenshots/android-home.png" width="32%" alt="The list of logins with service logos and a search bar">
  &nbsp;
  <img src="../docs/screenshots/android-detail.png" width="32%" alt="The details of a GitHub login, with masked password and copy buttons">
</p>

<p align="center"><sub>Screenshots use made-up sample data.</sub></p>

## What's here

- **Same data model as the Mac.** `model/LoginItem.kt` mirrors `SyncedPass/Models/LoginItem.swift` field for field, and writes the same JSON: uppercase UUIDs, dates as seconds since 2001-01-01, missing values left out. Older items with a single `signInMethod` are upgraded to `signIns` the same way.
- **Same encryption.** `crypto/VaultCrypto.kt` uses the same format as `VaultCrypto.swift`: AES-256-GCM, PBKDF2-HMAC-SHA256 with 600,000 rounds, and a random vault key wrapped by the password key. Key derivation takes about 1.3 s on a phone, thanks to an optimized PBKDF2 in `crypto/Pbkdf2.kt`. The tests check it against published test vectors and a plain implementation.
- **Safe saving.** Saves are atomic, and the previous version is kept as `vault.previous.syncedpass`. The vault locks when the app leaves the screen.
- **Screens.** Create vault, unlock, a login list with search, a details page for each login (only what it has, secrets masked until revealed, copy buttons, linked accounts), and a login editor with every field of the Mac editor: service, title, email, username, password, 2FA secret, websites, sign-in methods with the linked account, phone number, PIN, a multi-line note, and custom fields (text, hidden, 2FA, date). Light and dark mode.
- **Same services and logos.** `Scripts/export_android_services.swift` exports the Mac's service catalog and logos (rendered to PNG by macOS), so both apps recognize the same 111 services. Run it again after changing the Mac catalog.
- **Backups and the master password.** Settings has export, import and change master password, working like the Mac: a backup has its own password, importing merges without duplicates (the newer edit wins), and changing the master password re-encrypts the vault with a new key. Backups move freely between the two apps. Files are picked with the system file picker; the vault doesn't auto-lock while a picker the app opened is in front.
- **Sync with the Mac.** Settings ▸ Sync with Mac pairs the phone once (both screens show a 6-digit code to compare). After that, while the app is open and unlocked, it announces itself and listens, and also finds the Mac with Network Service Discovery and connects to it; whichever connection gets through carries changes both ways, login by login: the latest edit of each login wins, and deletions spread. The protocol, encryption and merge rules are in [docs/SYNC.md](../docs/SYNC.md); the code is in `sync/` and `model/VaultContents.kt`.
- **Private.** The screen is protected from screenshots, screen recording and assistants that read it; content capture is off; fields are hidden from autofill and ask the keyboard for incognito mode; copies are marked sensitive and cleared after 90 seconds; the phone announces itself on the network as "SyncedPass". See [PRIVACY.md](../PRIVACY.md). For README screenshots, debug builds allow screenshots while `files/debug-allow-screenshots` exists.
- **No Material.** The UI is built only on Compose Foundation, with its own components in `ui/components` and its own theme in `ui/theme`. Icons are [Phosphor](https://phosphoricons.com) (MIT), imported as vector drawables.

## Building

You need JDK 21 and the Android SDK (platform 37):

```sh
cd android
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # install on a connected phone or emulator
```

## Testing

```sh
./gradlew testDebugUnitTest              # JVM tests: crypto, file format, vault store
./gradlew connectedDebugAndroidTest      # on a device: PBKDF2 speed, opening a Mac vault
```

## Checking compatibility

Compatibility is tested in both directions with files containing only fake data.

**Mac → Android.** `app/src/test/resources/mac-fixtures/` holds a vault and a backup made by the Mac code (passwords `fixture master password` and `fixture backup password`, 1,000 rounds so the tests run fast). `MacCompatibilityTest` opens both, checks every field, and checks that writing them back gives the same JSON.

**Sync with the Mac's code.** `CrossPlatformSyncTest` pairs with and syncs against the Mac app's real sync code (`Scripts/sync-harness`, built from the Mac sources) over real TCP connections, opened by either side: matching pairing codes, live changes both ways, offline edits on both sides, conflicting edits, deletions, refusing unpaired devices, and a failed pairing attempt ending pairing. It's skipped unless the harness is built:

```sh
# from the repository root
xcrun swiftc -O -parse-as-library -D DEBUG -default-isolation MainActor -o build/sync-harness \
  Scripts/sync-harness/main.swift SyncedPass/Models/*.swift SyncedPass/Sync/*.swift
cd android && SYNCEDPASS_HARNESS=$PWD/../build/sync-harness ./gradlew testDebugUnitTest
```

**Testing sync on an emulator.** An emulator can't see Bonjour on the real network. In debug builds only, the app listens on the port in `files/debug-sync-port`, and also connects to the Mac at `files/debug-mac-endpoint` (`host:port`). Run a debug build of the Mac app with a test vault (`SYNCEDPASS_VAULT_DIR`), `SYNCEDPASS_SYNC_PORT=53555` and `SYNCEDPASS_PHONE_ENDPOINT=127.0.0.1:53556`, then:

```sh
adb forward tcp:53556 tcp:53556   # Mac → phone
adb reverse tcp:53555 tcp:53555   # phone → Mac
adb shell "run-as com.abdurahmanharouat.syncedpass sh -c 'echo 53556 > files/debug-sync-port; echo 127.0.0.1:53555 > files/debug-mac-endpoint'"
```

On a real phone none of this is needed: both apps find each other on the Wi-Fi.

**Android → Mac.** `WriteFixtureForMacTest` writes `app/build/android-fixtures/vault.syncedpass` (password `fixture master password`) with emoji, Arabic text, a linked Google account and a date field. To open it in a **debug** build of the Mac app without touching your real vault:

```sh
./gradlew testDebugUnitTest --tests '*WriteFixtureForMacTest*'
SYNCEDPASS_VAULT_DIR="$PWD/app/build/android-fixtures" \
  /path/to/DerivedData/.../Debug/SyncedPass.app/Contents/MacOS/SyncedPass
```
