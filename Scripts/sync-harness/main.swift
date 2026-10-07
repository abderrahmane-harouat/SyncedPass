// A stand-in Mac for testing sync end to end: the real VaultStore and
// SyncService from the Mac app, driven by commands on standard input. The
// Android test CrossPlatformSyncTest talks to it over a real TCP connection.
//
// Build (from the repository root):
//   xcrun swiftc -O -parse-as-library -D DEBUG -default-isolation MainActor -o build/sync-harness \
//     Scripts/sync-harness/main.swift \
//     SyncedPass/Models/*.swift SyncedPass/Sync/*.swift
// Commands, one per line:
//   phone <host:port> [pair] a phone listening at that address (the Mac connects to phones), ready to pair or not
//   pair                   open pairing ("pairing"), then confirm the code automatically
//   add <title>            save a new login
//   edit <title> <note>    change a login's note
//   delete <title>         delete a login
//   dump                   print every login as "title|note", then "end"
//   quit
// Only fake test data is used; the vault lives in a temporary folder.

import Foundation

@main
struct SyncHarness {
    static func main() async throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: "sync-harness-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = VaultStore(directory: directory, iterations: 1_000)
        try await store.create(masterPassword: "harness master password")
        let sync = SyncService(store: store)
        sync.refresh()
        say("ready")

        for try await line in FileHandle.standardInput.bytes.lines {
            let parts = line.split(separator: " ", maxSplits: 2).map(String.init)
            guard let command = parts.first else { continue }
            func item(_ title: String) -> LoginItem? { store.items.first { $0.title == title } }
            switch command {
            case "pair":
                sync.startPairing()
                say("pairing")
                let state = await waitFor(sync) { if case .confirming = $0 { true } else if case .failed = $0 { true } else { false } }
                guard case .confirming(let code, let name) = state else {
                    say("pairing failed")  // like the app, the failure stays on screen until the user acts
                    continue
                }
                say("code \(code) \(name)")
                sync.confirmPairing()
                let result = await waitFor(sync) { if case .paired = $0 { true } else if case .failed = $0 { true } else { false } }
                if case .paired(let phone) = result { say("paired \(phone)") } else { say("pairing failed \(String(describing: result))") }
                sync.stopPairing()
            case "phone":
                // phone <host:port> [pair]: a phone listening there, ready to pair or not.
                sync.addDebugPhone(parts[1], readyToPair: parts.count > 2 && parts[2] == "pair")
                say("ok")
            case "add":
                try store.save(LoginItem(title: parts[1]))
                say("ok")
            case "edit":
                guard var login = item(parts[1]) else { say("missing"); continue }
                login.note = parts.count > 2 ? parts[2] : ""
                login.modifiedAt = .now
                try store.save(login)
                say("ok")
            case "delete":
                guard let login = item(parts[1]) else { say("missing"); continue }
                try store.delete(id: login.id)
                say("ok")
            case "dump":
                for login in store.items.sorted(by: { $0.title < $1.title }) { say("\(login.title)|\(login.note)") }
                say("end")
            case "quit":
                return
            default:
                say("unknown \(command)")
            }
        }
    }

    static func say(_ text: String) {
        print(text)
        fflush(stdout)
    }

    /// Waits up to 30 seconds for the pairing state to match.
    static func waitFor(_ sync: SyncService, _ match: (SyncService.PairingState) -> Bool) async -> SyncService.PairingState? {
        for _ in 0..<600 {
            if let state = sync.pairing, match(state) { return state }
            try? await Task.sleep(for: .milliseconds(50))
        }
        return sync.pairing
    }
}
