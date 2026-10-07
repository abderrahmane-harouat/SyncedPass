# SyncedPass

**English** · [العربية](README.ar.md)

**Version 0.2.0** · macOS 15 or later (Swift 6 / SwiftUI) · Android 8.0 or later (Kotlin / Jetpack Compose)

SyncedPass is a password manager for the Mac and Android that keeps your logins **on your own devices only**, and syncs them directly between your Mac and your phone over your local network.

> **Local only. No cloud.**
> SyncedPass has no account, no server and no cloud service. Your vault is an encrypted file on your Mac and is never uploaded anywhere. The only network access the app has is for syncing with your own paired phone, directly over your local network: it only connects to, and accepts connections from, the local network, and only syncs with devices you paired. Service logos are bundled with the app, not downloaded.

![SyncedPass main window showing a list of logins with service logos, and the details of a Gmail login](docs/screenshots/main.png)

<p align="center"><img src="docs/screenshots/lock.png" width="60%" alt="SyncedPass lock screen asking for the master password"></p>

<p align="center"><sub>Screenshots use made-up sample data.</sub></p>

---

## Features

- **Logins** with title, email, username, password, 2FA secret, websites, phone number, PIN, notes and custom fields (text, hidden, 2FA, date). Only the title is required.
- **Sign-in methods:** record how you sign in to each account ("Sign in with Google", "Sign in with Apple", a password, or several), and which of your accounts it uses. An account login shows every service that signs in with it.
- **Service logos** for 111 services, matched automatically from a login's website or title. A small badge on the logo shows "Sign in with…" logins.
- **Search** that ranks name and website matches first, and lists email, username and note matches separately.
- **Filters:** all logins, logins with a password, or logins that sign in with a given provider.
- **Encrypted backups:** export your vault to a `.syncedpass` file protected by its own password, and import it on any Mac. Importing merges and never creates duplicates.
- **Import from the old app's JSON export** (the earlier Flutter version).
- **Android app** with the same logins, fields, logos, backups and master password change as the Mac (see [android/README.md](android/README.md)).
- **Sync with your Android phone** over your local network only: pair once by comparing a 6-digit code, then changes sync automatically, login by login, end-to-end encrypted. See [Sync](#sync).
- **Change the master password** at any time.
- **Locks automatically** when the Mac sleeps or the screen locks, and on demand with ⌘L.
- Multi-select delete, split a login that lists several services into separate logins, and show/hide on every secret field.

## How it works

### Where your data lives

Everything is stored in one encrypted file inside the app's private sandbox folder:

```
~/Library/Containers/com.abdurahmanharouat.SyncedPass/Data/Library/Application Support/SyncedPass/
├── vault.syncedpass            the vault (always the latest version)
└── vault.previous.syncedpass   the version before your last change
```

- Every change is written to disk **before** it appears on screen. If saving fails, what you see stays what is saved.
- Writes are atomic: a new file is written in full, then swapped in, so a crash can't leave a half-written vault.
- Both files are readable only by your macOS user account.
- While the vault is unlocked, decrypted logins exist in memory only. Locking or quitting the app clears them.

### Encryption

```
master password ──PBKDF2-HMAC-SHA256 (600,000 rounds, random 16-byte salt)──▶ password key
password key ──AES-256-GCM──▶ wraps a random 256-bit vault key
vault key ──AES-256-GCM──▶ encrypts all logins
```

- The **vault key** is random and encrypts your logins. Your **master password** only unlocks the vault key.
- **AES-GCM is authenticated:** any change to the file, even one byte, makes decryption fail instead of returning altered data.
- Each part of the file is bound to its purpose, so a vault's contents can't be passed off as a backup or the other way round.
- **Changing the master password** generates a brand-new vault key and re-encrypts everything, so no copy on disk still opens with the old password.
- There is **no password recovery**. If you forget the master password, the vault can't be decrypted. Export a backup regularly.

### Backups

- **Export Backup** creates a `.syncedpass` file encrypted the same way, with a password you choose for that backup. It is independent of your master password.
- **Import** merges a backup into the current vault:
  - a login already in the vault is kept, unless the backup's copy was edited more recently;
  - a login missing from the vault is added; a login you deleted after the backup was made is restored (and the restore syncs to your phone);
  - your master password never changes on import.
- Keep each backup's password somewhere safe; you need it to open that backup.

### Sync

SyncedPass syncs with the Android app over your local network, with no server in between. The full design is in [docs/SYNC.md](docs/SYNC.md).

- **Pair once.** On the phone, open **Settings ▸ Sync with Mac ▸ Pair with a Mac**; on the Mac, choose **SyncedPass ▸ Sync with Phone… ▸ Pair a Phone**. The Mac finds the phone and connects, and both show a 6-digit code; if they match, confirm on both. The devices exchange long-term keys and recognize each other from then on.
- **Then it's automatic.** While SyncedPass is open and unlocked on both, on the same network, each announces itself and each connects to the other; whichever connection gets through is used, and changes flow both ways over it. A change on either side reaches the other within moments.
- **macOS permissions.** The first time, macOS asks whether SyncedPass can find devices on your local network, and, if your firewall is on, whether it can accept incoming connections. Click Allow. Sync keeps working even before you answer, or if macOS holds back incoming connections after an update, because the Mac also connects to the phone itself.
- **Login by login.** Each login keeps its last-modified time, and for each login the latest edit wins, independently of the others. Edit one login on the Mac and another on the phone, even offline, and both edits are kept when they meet. Deleting a login leaves a small deletion record, so the deletion spreads instead of the login coming back.
- **Only what changed travels.** The devices first compare lists of login IDs and times, then send only the logins the other side is missing or has in an older version.
- **Encrypted and authenticated.** Every connection uses fresh keys (ECDH on P-256, AES-256-GCM) and each side proves it holds the key that was paired (ECDSA). Comparing the code during pairing stops anyone on the network from slipping in between.
- **Local network only.** Each app only listens while unlocked, and only if it's paired or its pairing window is open. Both only connect to, and accept connections from, local network addresses. The clocks of both devices must agree within 5 minutes, since merging compares edit times.
- **Unpair** a lost device from the same window; it can no longer connect.

## Building

Requirements: Xcode 26 or later, macOS 15 or later.

```sh
open SyncedPass.xcodeproj        # then press ⌘R
```

From the command line:

```sh
xcodebuild -project SyncedPass.xcodeproj -scheme SyncedPass build
xcodebuild -project SyncedPass.xcodeproj -scheme SyncedPass test   # 90 tests
```

The Android app (JDK 21 and the Android SDK; details in [android/README.md](android/README.md)):

```sh
cd android
./gradlew installDebug         # build and install on a connected phone
./gradlew testDebugUnitTest    # 50 tests
```

## Project structure

```
SyncedPass/
  Models/          data model, encryption, vault storage, search, import, merging
  Sync/            local network sync: protocol, encryption, pairing (docs/SYNC.md)
  Views/           SwiftUI screens
  AppIcon.icon     layered app icon (open with Icon Composer)
  Assets.xcassets  bundled service logos
SyncedPassTests/   tests for encryption, storage, backups, search, import and merging
android/           the Android app (see android/README.md)
docs/SYNC.md       the sync protocol, implemented by both apps
Scripts/
  generate_services.py   rebuilds the service list and logos
  export_android_services.swift   copies the service list and logos to the Android app
  sync-harness/          a stand-in Mac for testing sync end to end from the Android tests
  app-icon/              source image and script for the app icon
```

Service logos come from [Simple Icons](https://simpleicons.org) and [gilbarbara/logos](https://github.com/gilbarbara/logos) (both CC0). A few are the services' own official icons. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## To-do

### Android app (Jetpack Compose)

- [ ] Write a specification of the vault and backup file format, shared by both apps
- [x] Create the Android project in Kotlin with Jetpack Compose (no Material; see [android/README.md](android/README.md))
- [x] Port the encryption (AES-256-GCM, PBKDF2-HMAC-SHA256, 600,000 rounds)
- [x] Cross-platform tests: a file written on the Mac opens on Android and the other way round
- [x] Unlock, login list, search and login editor screens (every field of the Mac editor)
- [x] Login details screen (only what the login has; Edit opens the editor)
- [x] Service logos and sign-in methods
- [x] Import and export encrypted backups, and change the master password
- [ ] Unlock with fingerprint or face (BiometricPrompt with an Android Keystore key)
- [ ] Autofill in other apps and browsers (Android Autofill Service)

### Secure sync between devices (local network only)

- [x] Design the sync protocol and write it down before coding ([docs/SYNC.md](docs/SYNC.md))
- [x] Device discovery on the local network (Bonjour on macOS, Network Service Discovery on Android)
- [x] One-time pairing by comparing a 6-digit code
- [x] Encrypted, mutually authenticated connection between paired devices only
- [x] Both apps connect to each other automatically when open and on the same network (either side can connect)
- [x] Merge changes from both devices, login by login, using each login's modification time
- [x] Record deletions so a deleted login isn't brought back by sync
- [x] Show sync status and the last sync time
- [x] Unpair a lost device
- [ ] Sync while the phone app is in the background (today both apps must be open and unlocked)
- [ ] Test on a real phone and Wi-Fi network (so far: emulator, and automated tests over a real connection)

### macOS improvements

- [ ] Auto-lock after a period of inactivity
- [ ] Clear copied passwords from the clipboard after a short time
- [ ] Password generator
- [ ] Unlock with Touch ID
- [ ] Undo (⌘Z) for edits and deletes
- [ ] Settings window (⌘,)
- [ ] Release build installed in /Applications

## License

SyncedPass is free software, licensed under the [GNU General Public License v3.0 or later](LICENSE).

You may use, study, change and share it. If you distribute a modified version, you must publish its source code under the same license, so the code of any version people rely on stays open to inspection.

**Not covered by this license:** the logos of third-party services, which remain trademarks of their owners and are included only to identify those services. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
