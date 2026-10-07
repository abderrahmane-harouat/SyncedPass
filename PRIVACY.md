# Privacy

**English** · [العربية](PRIVACY.ar.md)

SyncedPass is built privacy first. Your logins belong on your devices and nowhere else, so the app is designed to keep them there. It doesn't trust any company with them: not us, not Apple, not Google, not Samsung.

## The short version

- **No data collection.** No analytics, no telemetry, no crash reporting, no ads, no tracking. The app never sends anything to us or to anyone else.
- **No account, no server, no cloud.** Your vault is an encrypted file on each of your devices. It is never uploaded.
- **No internet.** The only network traffic is sync between your own paired devices, directly over your local network.
- **Locked down against the platform too.** Features of macOS, Android and keyboards that could pass your logins to Apple, Google, Samsung or anyone else are switched off inside the app (see below).

## Where your data lives

| | Mac | Android |
|---|---|---|
| Vault | `~/Library/Containers/com.abdurahmanharouat.SyncedPass/Data/Library/Application Support/SyncedPass/` | The app's private storage, readable by no other app |
| Encryption | AES-256-GCM, key unlocked by your master password (PBKDF2-HMAC-SHA256, 600,000 rounds) | Same file format and encryption |
| Cloud backup | Not synced by iCloud | Excluded from Google backup and from device-to-device transfer |
| While unlocked | Decrypted logins in memory only | Same |
| Locks | When the Mac sleeps or the screen locks, or with ⌘L | As soon as the app leaves the screen (after 2 minutes if you are choosing a backup file) |

Backups you export are encrypted with a password you choose. They go only where you save them.

## What the app switches off

### On the Mac

| Feature | Why it's off |
|---|---|
| **Universal Clipboard** | Copied logins would be handed to your other Apple devices. Copies stay on this Mac. |
| **Clipboard history** | Copies are marked secret, so clipboard managers that follow the [nspasteboard.org](http://nspasteboard.org) convention don't save or show them. |
| **Lingering copies** | The clipboard is cleared 90 seconds after a copy, and when you quit, unless you copied something else since. |
| **Apple Intelligence Writing Tools** | They can send text to Apple's servers or to ChatGPT. Not offered in any field. |
| **Auto-correction** | Off in every field. |
| **Window restoration** | macOS doesn't save the window's state to disk. |
| **Your Mac's name on the network** | The Mac announces itself as "SyncedPass", not by its name (which often holds yours). |
| **DNS lookups** | The app reads the Mac's name locally, without asking a DNS server. |

The app is sandboxed: it can only open files you pick, and it has no access to your contacts, photos, location or anything else.

### On Android

| Feature | Why it's off |
|---|---|
| **Screen reading** | The screen is protected (`FLAG_SECURE`): no screenshots, screen recording or recent-apps preview, and assistants that read the screen (Circle to Search, Gemini, Bixby) can't see it. |
| **Content capture** | Android System Intelligence can't capture the app's text. |
| **Autofill** | Fields are hidden from autofill services (Google, Samsung Pass), so they can't offer to save what you type. "Autofill" is removed from the text menu. |
| **Keyboard learning** | Every field asks the keyboard (Gboard, Samsung Keyboard…) for incognito mode: it doesn't learn your words or sync them to its maker's cloud. Auto-correction is off. |
| **Clipboard preview and history** | Every copy is marked sensitive: Android's preview shows dots, and keyboards don't keep it in their clipboard history. |
| **Lingering copies** | The clipboard is cleared 90 seconds after a copy. |
| **Cloud backup and device transfer** | Your vault is excluded from both. |
| **Your phone's name on the network** | The phone announces itself as "SyncedPass". It shows its name only while you are pairing it. |

The app asks for one permission, network access, which Android requires for any connection, including on the local network. It uses Google's core Android and Jetpack Compose libraries, and nothing else: no Google Play Services, no Firebase, no analytics or advertising libraries.

## What travels over your network

Sync is end-to-end encrypted and authenticated. The full design is in [docs/SYNC.md](docs/SYNC.md).

- **Logins** only ever travel encrypted, between devices you paired by comparing a 6-digit code.
- **Only on the local network.** Both apps only connect to, and accept connections from, local network addresses. Neither connects to the internet.
- **Only when needed.** Nothing is announced or listened for unless the vault is unlocked and a device is paired or the pairing window is open.
- **What someone on the same network can see:**
  - that a "SyncedPass" service exists;
  - while you are pairing, your phone's name, and the names both devices exchange;
  - each device's random sync ID, which isn't tied to your hardware, at the start of each connection.

## What's up to you

Some things are outside any app's control. For the most privacy:

- **Mac:** in **System Settings ▸ Privacy & Security ▸ Analytics & Improvements**, turn off **Share Mac Analytics**. When it's on, macOS sends crash reports for every app to Apple.
- **Android:** turn off Google's **Usage & diagnostics**, and on Samsung phones the diagnostic data sharing in **Settings ▸ Privacy**.
- **Backups:** save them somewhere you control. A backup saved in a folder synced by iCloud Drive, Google Drive or similar is uploaded there (still encrypted).
- **Accessibility apps** you install can read the screen of any app. Only install ones you trust.
- **Look Up, Translate and Dictation** on the Mac send text to Apple only when you choose them.

## Questions

Found something that could leak data? Please [open an issue](https://github.com/abderrahmane-harouat/SyncedPass/issues). For anything sensitive, don't post the details publicly. Open an issue asking for a private way to share them first.
