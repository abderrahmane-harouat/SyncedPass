import Foundation
import Observation

/// Owns the encrypted vault file and the decrypted items while unlocked.
///
/// Every change is written to disk before it shows up in `items`, so if a
/// write fails the app keeps showing what is actually saved. Writes are
/// atomic, and the version before the latest save is kept next to the vault
/// as `vault.previous.syncedpass` in case the main file is ever damaged.
@Observable
final class VaultStore {
    enum Status {
        case needsSetup, locked, unlocked
    }

    struct ImportSummary: Equatable {
        var added = 0
        var updated = 0
        var unchanged = 0
    }

    /// What a file chosen for import turned out to be.
    enum ImportSource {
        case backup(EncryptedContainer)
        case legacy([LoginItem])
    }

    private(set) var status: Status
    private(set) var items: [LoginItem] = []
    @ObservationIgnored private var keys: VaultKeys?

    let vaultURL: URL
    let previousVersionURL: URL
    private let iterations: Int

    static var defaultDirectory: URL {
        URL.applicationSupportDirectory.appending(path: "SyncedPass", directoryHint: .isDirectory)
    }

    init(directory: URL = VaultStore.defaultDirectory, iterations: Int = VaultCrypto.defaultIterations) {
        vaultURL = directory.appending(path: "vault.syncedpass")
        previousVersionURL = directory.appending(path: "vault.previous.syncedpass")
        self.iterations = iterations
        status = FileManager.default.fileExists(atPath: vaultURL.path(percentEncoded: false)) ? .locked : .needsSetup
    }

    // MARK: - Locking

    func create(masterPassword: String) async throws {
        guard status == .needsSetup else { return }
        let newKeys = try await VaultCrypto.makeKeys(password: masterPassword, iterations: iterations)
        try write(VaultCrypto.seal([], kind: .vault, keys: newKeys))
        keys = newKeys
        items = []
        status = .unlocked
    }

    func unlock(masterPassword: String) async throws {
        guard status == .locked else { return }
        let data = try Data(contentsOf: vaultURL)
        let container = try VaultCrypto.container(from: data, expecting: .vault)
        let unlockedKeys = try await VaultCrypto.unlock(container, password: masterPassword)
        items = try VaultCrypto.open(container, keys: unlockedKeys)
        keys = unlockedKeys
        status = .unlocked
    }

    func lock() {
        guard status == .unlocked else { return }
        keys = nil
        items = []
        status = .locked
    }

    // MARK: - Items

    func save(_ item: LoginItem) throws {
        var updated = items
        if let index = updated.firstIndex(where: { $0.id == item.id }) {
            updated[index] = item
        } else {
            updated.append(item)
        }
        try persist(updated)
    }

    func delete(id: LoginItem.ID) throws {
        try delete(ids: [id])
    }

    /// Deletes several items in one write, so either all of them are removed
    /// or (if saving fails) none are.
    func delete(ids: Set<LoginItem.ID>) throws {
        guard items.contains(where: { ids.contains($0.id) }) else { return }
        try persist(items.filter { !ids.contains($0.id) })
    }

    func items(matching query: String) -> [LoginItem] {
        let query = query.trimmed
        let sorted = items.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
        guard !query.isEmpty else { return sorted }
        return sorted.filter { item in
            ([item.title, item.email, item.username, item.note] + item.websites)
                .contains { $0.localizedStandardContains(query) }
        }
    }

    // MARK: - Backup

    /// An encrypted backup of every item, protected by `password`
    /// (independent of the master password).
    func exportBackup(password: String) async throws -> Data {
        guard status == .unlocked else { throw VaultError.locked }
        let snapshot = items
        let backupKeys = try await VaultCrypto.makeKeys(password: password, iterations: iterations)
        return try VaultCrypto.seal(snapshot, kind: .backup, keys: backupKeys)
    }

    /// Works out whether `data` is a SyncedPass backup or an old-app export.
    func inspectImport(_ data: Data) throws -> ImportSource {
        do {
            return .backup(try VaultCrypto.container(from: data, expecting: .backup))
        } catch VaultError.notSyncedPassFile {
            return .legacy(try LegacyImport.parse(data))
        }
    }

    func importBackup(_ container: EncryptedContainer, password: String) async throws -> ImportSummary {
        let backupKeys = try await VaultCrypto.unlock(container, password: password)
        return try merge(VaultCrypto.open(container, keys: backupKeys))
    }

    /// Adds imported items without creating duplicates:
    /// - same ID (a backup of this vault): the newer edit wins
    /// - same title, username, email, password and websites: already there, skipped
    /// - anything else: added
    func merge(_ incoming: [LoginItem]) throws -> ImportSummary {
        var result = items
        var summary = ImportSummary()
        for item in incoming {
            if let index = result.firstIndex(where: { $0.id == item.id }) {
                if item.modifiedAt > result[index].modifiedAt {
                    result[index] = item
                    summary.updated += 1
                } else {
                    summary.unchanged += 1
                }
            } else if result.contains(where: { $0.hasSameCredentials(as: item) }) {
                summary.unchanged += 1
            } else {
                result.append(item)
                summary.added += 1
            }
        }
        if summary.added + summary.updated > 0 {
            try persist(result)
        }
        return summary
    }

    // MARK: - Disk

    private func persist(_ newItems: [LoginItem]) throws {
        guard status == .unlocked, let keys else { throw VaultError.locked }
        try write(VaultCrypto.seal(newItems, kind: .vault, keys: keys))
        items = newItems
    }

    private func write(_ data: Data) throws {
        let fileManager = FileManager.default
        try fileManager.createDirectory(at: vaultURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        if fileManager.fileExists(atPath: vaultURL.path(percentEncoded: false)) {
            // Best effort: losing the extra copy must never block saving.
            try? fileManager.removeItem(at: previousVersionURL)
            try? fileManager.copyItem(at: vaultURL, to: previousVersionURL)
        }
        try data.write(to: vaultURL, options: .atomic)
        try? fileManager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: vaultURL.path(percentEncoded: false))
    }
}

private extension LoginItem {
    func hasSameCredentials(as other: LoginItem) -> Bool {
        title == other.title && username == other.username && email == other.email
            && password == other.password && websites == other.websites
    }
}
