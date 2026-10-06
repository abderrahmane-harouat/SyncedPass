# SyncedPass

**English** · [العربية](README.ar.md)

**Version 0.1.0** · macOS 15 or later · Swift 6 / SwiftUI

SyncedPass is a password manager for the Mac that keeps your logins **on your own devices only**.

> **Local only. No cloud.**
> SyncedPass has no account, no server and no sync service. Your vault is an encrypted file on your Mac and is never uploaded anywhere. The app doesn't even have permission to use the network: its macOS sandbox doesn't grant network access, and service logos are bundled with the app, not downloaded. Syncing with other devices (planned, see the to-do list) will happen directly between your own devices on your local network.

![SyncedPass main window showing a list of logins with service logos, and the details of a Gmail login](docs/screenshots/main.png)

<p align="center"><img src="docs/screenshots/lock.png" width="60%" alt="SyncedPass lock screen asking for the master password"></p>

<p align="center"><sub>Screenshots use made-up sample data.</sub></p>

---

## Features

- **Logins** with title, email, username, password, 2FA secret, websites, phone number, PIN, notes and custom fields (text, hidden, 2FA, date). Only the title is required.
- **Sign-in methods:** record how you sign in to each account ("Sign in with Google", "Sign in with Apple", a password, or several), and which of your accounts it uses. An account login shows every service that signs in with it.
- **Service logos** for 110 services, matched automatically from a login's website or title. A small badge on the logo shows "Sign in with…" logins.
- **Search** that ranks name and website matches first, and lists email, username and note matches separately.
- **Filters:** all logins, logins with a password, or logins that sign in with a given provider.
- **Encrypted backups:** export your vault to a `.syncedpass` file protected by its own password, and import it on any Mac. Importing merges and never creates duplicates.
- **Import from the old app's JSON export** (the earlier Flutter version).
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
  - a login missing from the vault is added (this includes logins deleted after the backup was made);
  - your master password never changes on import.
- Keep each backup's password somewhere safe; you need it to open that backup.

## Building

Requirements: Xcode 26 or later, macOS 15 or later.

```sh
open SyncedPass.xcodeproj        # then press ⌘R
```

From the command line:

```sh
xcodebuild -project SyncedPass.xcodeproj -scheme SyncedPass build
xcodebuild -project SyncedPass.xcodeproj -scheme SyncedPass test   # 83 tests
```

## Project structure

```
SyncedPass/
  Models/          data model, encryption, vault storage, search, import
  Views/           SwiftUI screens
  AppIcon.icon     layered app icon (open with Icon Composer)
  Assets.xcassets  bundled service logos
SyncedPassTests/   tests for encryption, storage, backups, search and import
Scripts/
  generate_services.py   rebuilds the service list and logos
  app-icon/              source image and script for the app icon
```

Service logos come from [Simple Icons](https://simpleicons.org) and [gilbarbara/logos](https://github.com/gilbarbara/logos) (both CC0). A few are the services' own official icons. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## To-do

### Android app (Jetpack Compose)

- [ ] Write a specification of the vault and backup file format, shared by both apps
- [ ] Create the Android project in Kotlin with Jetpack Compose and Material 3
- [ ] Port the encryption (AES-256-GCM, PBKDF2-HMAC-SHA256, 600,000 rounds)
- [ ] Cross-platform tests: a file written on the Mac opens on Android and the other way round
- [ ] Unlock, login list, search, login editor and details screens
- [ ] Service logos and sign-in methods
- [ ] Import and export encrypted backups
- [ ] Unlock with fingerprint or face (BiometricPrompt with an Android Keystore key)
- [ ] Autofill in other apps and browsers (Android Autofill Service)

### Secure sync between devices (local network only)

- [ ] Design the sync protocol and write it down before coding
- [ ] Device discovery on the local network (Bonjour on macOS, Network Service Discovery on Android)
- [ ] One-time pairing by scanning a QR code or comparing a short code
- [ ] Encrypted, mutually authenticated connection between paired devices only
- [ ] The Mac app listens while open; the phone connects automatically when on the same network
- [ ] Merge changes from both devices, using each login's modification time
- [ ] Record deletions ("tombstones") so a deleted login isn't brought back by sync
- [ ] Show sync status and the last sync time
- [ ] Unpair a lost device

### macOS improvements

- [ ] Auto-lock after a period of inactivity
- [ ] Clear copied passwords from the clipboard after a short time
- [ ] Password generator
- [ ] Unlock with Touch ID
- [ ] Undo (⌘Z) for edits and deletes
- [ ] Settings window (⌘,)
- [ ] Release build installed in /Applications

## License

No license has been chosen yet. All rights reserved.
