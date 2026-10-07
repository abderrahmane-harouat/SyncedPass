import Foundation
import Observation

/// Owns the encrypted vault file and the decrypted items while unlocked.
///
/// Deleting a login leaves a deletion record, so sync spreads the deletion
/// instead of the login coming back from another device (docs/SYNC.md).
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
    /// Everything in the vault: logins, deletion records and the sync
    /// identity. Empty while locked.
    private(set) var contents = VaultContents()
    /// Called after every saved change, so sync can tell other devices.
    @ObservationIgnored var onChange: (() -> Void)?
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
        try write(VaultCrypto.seal(VaultContents(), keys: newKeys))
        keys = newKeys
        publish(VaultContents())
        status = .unlocked
    }

    func unlock(masterPassword: String) async throws {
        guard status == .locked else { return }
        let data = try Data(contentsOf: vaultURL)
        let container = try VaultCrypto.container(from: data, expecting: .vault)
        let unlockedKeys = try await VaultCrypto.unlock(container, password: masterPassword)
        publish(try VaultCrypto.openContents(container, keys: unlockedKeys))
        keys = unlockedKeys
        status = .unlocked
    }

    /// Replaces the master password after checking the current one.
    ///
    /// A new random vault key is generated and everything re-encrypted, rather
    /// than re-wrapping the old key: if the old password leaked, someone with
    /// an old copy of the file could otherwise recover the key and read future
    /// versions too. The kept previous version is replaced for the same reason,
    /// so nothing on disk still opens with the old password.
    func changeMasterPassword(current: String, new: String) async throws {
        guard status == .unlocked, keys != nil else { throw VaultError.locked }
        let container = try VaultCrypto.container(from: Data(contentsOf: vaultURL), expecting: .vault)
        _ = try await VaultCrypto.unlock(container, password: current)
        guard new != current else { throw VaultError.samePassword }

        let newKeys = try await VaultCrypto.makeKeys(password: new, iterations: iterations)
        guard status == .unlocked else { throw VaultError.locked }  // locked while deriving
        try write(VaultCrypto.seal(contents, keys: newKeys))
        keys = newKeys

        let fileManager = FileManager.default
        try? fileManager.removeItem(at: previousVersionURL)
        try? fileManager.copyItem(at: vaultURL, to: previousVersionURL)
    }

    func lock() {
        guard status == .unlocked else { return }
        keys = nil
        publish(VaultContents())
        status = .locked
    }

    // MARK: - Items

    func save(_ item: LoginItem) throws {
        try update { contents in
            if let index = contents.items.firstIndex(where: { $0.id == item.id }) {
                contents.items[index] = item
            } else {
                contents.items.append(item)
            }
            // Saving a login that was deleted on another device brings it back.
            contents.deletions.removeAll { $0.id == item.id }
        }
    }

    /// Adds several new items in one write (all or nothing).
    func add(_ newItems: [LoginItem]) throws {
        guard !newItems.isEmpty else { return }
        try update { $0.items += newItems }
    }

    /// Replaces one item with several (used to split a combined login) in a
    /// single write, so a failure leaves the original untouched.
    func replace(_ id: LoginItem.ID, with replacements: [LoginItem]) throws {
        guard let index = items.firstIndex(where: { $0.id == id }) else { return }
        try update { contents in
            contents.items.replaceSubrange(index...index, with: replacements)
            if !replacements.contains(where: { $0.id == id }) {
                contents.deletions.append(Deletion(id: id, deletedAt: .now))
            }
        }
    }

    func item(_ id: LoginItem.ID?) -> LoginItem? {
        guard let id else { return nil }
        return items.first { $0.id == id }
    }

    /// Logins that record `account` as the account they sign in with.
    func logins(signingInWith account: LoginItem.ID) -> [LoginItem] {
        items.filter { $0.signsIn(with: account) }
            .sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }

    /// The provider's accounts to choose from: only logins that are such an
    /// account (Gmail/Google logins for "Sign in with Google"), one per email
    /// address, sorted by address. Logins that only *use* a Gmail address
    /// (Facebook, Netflix…) aren't accounts and aren't listed.
    func accountCandidates(for method: SignInMethod, excluding excluded: LoginItem.ID? = nil) -> [LoginItem] {
        var seen = Set<String>()
        return items
            .filter { $0.id != excluded && method.isAccount($0) }
            // Exact service matches (a login titled "Gmail") win over keyword
            // matches ("Google Ads") when two share an address.
            .sorted { a, b in
                let aExact = KnownService.matching(a) != nil, bExact = KnownService.matching(b) != nil
                return aExact != bExact ? aExact : a.title.localizedStandardCompare(b.title) == .orderedAscending
            }
            .filter { item in
                guard let address = item.accountAddress?.lowercased() else { return true }
                return seen.insert(address).inserted
            }
            .sorted { $0.accountChoiceLabel.localizedStandardCompare($1.accountChoiceLabel) == .orderedAscending }
    }

    func delete(id: LoginItem.ID) throws {
        try delete(ids: [id])
    }

    /// Deletes several items in one write, so either all of them are removed
    /// or (if saving fails) none are.
    func delete(ids: Set<LoginItem.ID>) throws {
        guard items.contains(where: { ids.contains($0.id) }) else { return }
        let now = Date.now
        try update { contents in
            let deleted = contents.items.filter { ids.contains($0.id) }.map(\.id)
            contents.items.removeAll { ids.contains($0.id) }
            contents.deletions.removeAll { ids.contains($0.id) }
            contents.deletions += deleted.map { Deletion(id: $0, deletedAt: now) }
        }
    }

    func search(_ query: String) -> LoginSearch.Results {
        LoginSearch.search(items, for: query)
    }

    /// Every match for `query` in display order (all items when it's empty).
    func items(matching query: String) -> [LoginItem] {
        search(query).all
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
    /// - a login that was deleted: restored, as a new edit so sync spreads the restore
    /// - anything else: added
    func merge(_ incoming: [LoginItem]) throws -> ImportSummary {
        var result = items
        var deletions = contents.deletions
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
            } else if let deleted = deletions.firstIndex(where: { $0.id == item.id }) {
                deletions.remove(at: deleted)
                var restored = item
                restored.modifiedAt = .now
                result.append(restored)
                summary.added += 1
            } else {
                result.append(item)
                summary.added += 1
            }
        }
        if summary.added + summary.updated > 0 {
            try update { contents in
                contents.items = result
                contents.deletions = deletions
            }
        }
        return summary
    }

    // MARK: - Sync

    /// Merges logins and deletions from another device; true when anything changed.
    @discardableResult
    func applySync(_ records: SyncRecords) throws -> Bool {
        guard let merged = contents.merging(records, notAfter: .now + SyncProtocol.maxClockSkew) else { return false }
        try update { $0 = merged }
        return true
    }

    /// Changes the sync identity (pairing, unpairing).
    func updateSync(_ transform: (inout SyncIdentity?) -> Void) throws {
        var sync = contents.sync
        transform(&sync)
        guard sync != contents.sync else { return }
        try update { $0.sync = sync }
    }

    // MARK: - Disk

    /// Applies `change` to the contents and saves the result.
    private func update(_ change: (inout VaultContents) -> Void) throws {
        guard status == .unlocked, let keys else { throw VaultError.locked }
        var updated = contents
        change(&updated)
        try write(VaultCrypto.seal(updated, keys: keys))
        publish(updated)
        onChange?()
    }

    private func publish(_ newContents: VaultContents) {
        contents = newContents
        items = newContents.items
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
