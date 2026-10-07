# Sync between the Mac and Android apps

SyncedPass keeps the Mac and the phone in sync over the local network only. There is no server and no cloud: the devices talk to each other directly, and only while both are on the same network with SyncedPass open and unlocked.

This document is the specification both apps implement. Any change must be made on both platforms.

## Overview

- **Both sides listen, and both connect.** While its vault is unlocked, each app announces itself on the local network (the Mac as `_syncedpass._tcp` with Bonjour, the phone as `_syncedpass-phone._tcp` with Network Service Discovery), both under the service name "SyncedPass" rather than the device's own name, which anyone on the network can see. Each looks for the other and connects when it isn't connected yet. Whichever connection gets through is used, and data flows both ways over it. This keeps sync working when one direction is blocked: for example, while the macOS firewall is still asking the user (or quietly holds back an app it saw before an update), the Mac's own connection to the phone works, because the firewall only checks incoming connections.
- **Who speaks first doesn't depend on who connected.** In every handshake below, the phone sends the first frame and the Mac answers, whichever side opened the connection.
- **One connection per pair of devices.** If both sides connect at the same time, both keep the connection the phone opened and close the other, so they always agree.
- **Pairing happens once.** The two devices exchange long-term identity keys, and the user confirms that both screens show the same 6-digit code. After that, they recognize each other automatically.
- **Every connection is encrypted and mutually authenticated.** Each session uses fresh keys (forward secrecy), and each side proves it holds the identity key that was paired.
- **Merging is per login.** Each login carries its last-modified time; for each login the newest version wins, independently of every other login. Deleting a login leaves a small *deletion record* so the deletion spreads instead of the login coming back.
- **Only changed logins travel.** Each side sends a short list of login IDs with their times; then each sends only the logins the other side doesn't have, or has in an older version.
- **Local network only.** Both apps refuse connections to or from addresses outside the local network (see [Local network only](#local-network-only)).

## What's stored

The vault payload (still encrypted with the vault key, as before) changes from a list of logins to an object. The container's `version` becomes `2` for vault files; backups are unchanged (version 1, a list of logins).

```json
{
  "items": [ /* LoginItem, as before */ ],
  "deletions": [ { "id": "UUID", "deletedAt": 813500000.25 } ],
  "sync": {
    "deviceID": "UUID",
    "deviceName": "Alex's MacBook Pro",
    "privateKey": "base64: 32-byte P-256 private scalar",
    "publicKey": "base64: P-256 public key, SubjectPublicKeyInfo DER",
    "peers": [
      { "deviceID": "UUID", "name": "Pixel 7", "publicKey": "base64 SPKI", "pairedAt": 813400000.0 }
    ]
  }
}
```

- `sync` is absent until the device is paired for the first time.
- When each device last synced is only kept in memory, so syncing doesn't rewrite the vault.
- The identity private key lives inside the encrypted vault, so it's only available while the vault is unlocked. Sync only runs while it is.
- Version 1 vault files (a list of logins) are still read; they're written back as version 2.
- UUIDs are uppercase and times are seconds since 2001-01-01, as everywhere else in the file format.

## Merging

Every login has `modifiedAt`, set to the current time on every edit. A deletion record has `deletedAt`. For one login ID, the record with the latest time wins:

- a newer edit replaces an older version;
- a deletion newer than the login's last edit deletes it;
- an edit newer than a deletion brings the login back;
- on a tie between a login and a deletion, the deletion wins (both sides decide the same way).

Each login is decided on its own: editing login A on the Mac and login B on the phone, even offline, keeps both edits. If the same login is edited on both sides, the later edit wins.

Restoring a login from a backup after it was deleted counts as a new edit: its `modifiedAt` becomes the time of the import, so the restore spreads instead of being undone by the deletion record.

The decision relies on the device clocks. Devices refuse to sync if their clocks differ by more than 5 minutes, and say so. A device also ignores any login or deletion dated more than 5 minutes in its own future: one device with a wrong clock (or a misbehaving one) could otherwise win over every real edit until that date.

## Transport

TCP. Every message is a frame: a 4-byte big-endian length, then the bytes. Handshake frames are plaintext JSON (at most 64 KiB). After the handshake, every frame is encrypted (at most 32 MiB):

- AES-256-GCM, a separate key for each direction;
- nonce = 4 zero bytes followed by a 64-bit big-endian counter, starting at 0 for each direction and increasing by one per frame (never sent; both sides count);
- the frame is the ciphertext followed by the 16-byte tag; no additional data.

A frame that fails to decrypt ends the connection.

### Cryptography

- Keys: NIST P-256 (secp256r1), available on both platforms without extra libraries. Public keys are sent as SubjectPublicKeyInfo DER, in base64.
- Signatures: ECDSA with SHA-256, DER-encoded.
- Key agreement: ECDH, giving the 32-byte x coordinate.
- Key derivation: HKDF-SHA256 (RFC 5869).

Below, `H` is SHA-256, `‖` is concatenation, and `f1`, `f2`, … are the handshake frames exactly as sent, *including* their 4-byte length (so the boundaries between them are unambiguous).

## Pairing

Pairing uses a short authentication string, like Bluetooth's numeric comparison: the devices agree on keys over the network, then the user checks that both screens show the same code. A commitment stops an attacker in the middle from choosing keys that produce matching codes; the chance of an undetected attack is one in a million per attempt.

Pairing connections are always opened by the Mac, to the phone whose pairing screen is open (it announces `pairing=1` in its Bonjour record, plus `name=` with its device name for as long as its pairing screen is open; with several, the user picks one on the Mac by that name). The Mac does this only while its "Pair a Phone" window is open, and only one pairing connection per opening of the window; the phone likewise accepts only one pairing connection per opening of its pairing screen. If that connection fails at any point (it drops, sends a commitment that doesn't match, or anything else goes wrong), pairing ends with an error on screen and the user has to start it again. Otherwise someone else on the network could keep retrying, unseen, until their code happened to match the phone's. The handshake up to showing the code must finish within 15 seconds, and frames are limited to 64 KiB until pairing is complete.

1. Phone → Mac, `f1`: `{"type": "pair", "protocol": 1, "commitment": base64(H(f3))}`
2. Mac → Phone, `f2`: `{"deviceID", "deviceName", "identityKey", "ephemeralKey", "nonce", "time"}`
3. Phone → Mac, `f3`: the same fields for the phone. The Mac checks `H(f3) == commitment`.
4. Both compute `s = ECDH(ephemeral keys)` and `salt = H("SyncedPass pair v1" ‖ f1 ‖ f2 ‖ f3)`, then:
   - `code = HKDF(s, salt, "SyncedPass pair v1 code", 4 bytes)` read as a big-endian unsigned integer, modulo 1,000,000, shown as 6 digits;
   - `phoneKey = HKDF(s, salt, "SyncedPass pair v1 c2s", 32)` and `macKey = HKDF(s, salt, "SyncedPass pair v1 s2c", 32)`, for the encrypted frames from here on.
5. Both show the code. When the user confirms on a device, it sends the encrypted frame `{"type": "confirm", "accepted": true, "signature": base64(Sign(identity key, H("SyncedPass pair v1 confirm phone" ‖ salt)))}` (`… confirm mac` from the Mac, so one side's confirmation can't be passed off as the other's); cancelling sends `"accepted": false` and closes. A device refuses a peer that presents its own identity key or device ID.
6. A device saves the other as a peer only after its own user confirmed *and* it received the other's confirmation with a valid signature from the identity key in `f2`/`f3`.

## Sync sessions

Either side opens the connection; the phone speaks first:

1. Phone → Mac, `f1`: `{"type": "sync", "protocol": 1, "deviceID", "ephemeralKey", "nonce", "time"}`
2. Mac → Phone, `f2`: `{"deviceID", "ephemeralKey", "nonce", "time"}`
3. Mac → Phone, `f3`: `{"signature": base64(Sign(Mac identity, H("SyncedPass sync v1 server" ‖ f1 ‖ f2)))}`
4. Phone → Mac, `f4`: `{"signature": base64(Sign(phone identity, H("SyncedPass sync v1 client" ‖ f1 ‖ f2)))}`

Each side checks that the other's device ID belongs to a paired peer and that the signature verifies with that peer's saved identity key; otherwise it closes the connection. With `s = ECDH(ephemeral keys)` and `salt = H(f1 ‖ f2)`, the phone sends with `HKDF(s, salt, "SyncedPass sync v1 c2s", 32)` and the Mac with `HKDF(s, salt, "SyncedPass sync v1 s2c", 32)`.

Then both sides send encrypted JSON messages:

- `{"type": "manifest", "items": {"ID": modifiedAt, …}, "deletions": {"ID": deletedAt, …}}`: what this side has. Sent when the session starts, after any change to the vault (a local edit, or logins received from another device), and every minute.
- `{"type": "records", "items": [LoginItem…], "deletions": [{"id", "deletedAt"}…]}`: the reply to a manifest, with only the logins and deletions that win over what the manifest lists (or that it doesn't list). Not sent when there's nothing to send.

When a device receives a manifest, it replies with the records the sender needs. If the manifest shows the sender has records *it* needs, it also replies with its own manifest, so the sender sends them right away.
- `{"type": "ping"}`: every 25 seconds; a connection with nothing received for 60 seconds is closed.

On receiving records, a device merges them as described in [Merging](#merging) and saves the vault once. If anything changed, it sends its manifest to every connected device, which spreads the change to other phones paired with the same Mac. This always ends: a device only sends records the other side doesn't have yet.

## Local network only

- Both sides only accept connections from, and only connect to, local addresses: loopback, private IPv4 (10/8, 172.16/12, 192.168/16), link-local (169.254/16, fe80::/10) or unique local IPv6 (fc00::/7). The Mac's listener also uses `acceptLocalOnly`, and cellular interfaces are excluded.
- Each side listens only while its vault is unlocked, and only if it's paired or its pairing screen is open.
- The Android app has the `INTERNET` permission, which Android requires for any socket, including local ones. It doesn't connect anywhere else.
