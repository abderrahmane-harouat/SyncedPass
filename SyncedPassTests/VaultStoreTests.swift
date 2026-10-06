import Foundation
import Testing
@testable import SyncedPass

@Suite("Vault store")
struct VaultStoreTests {
    @Test func firstLaunchNeedsSetupThenStaysLocked() async throws {
        let directory = try TemporaryDirectory()
        let store = VaultStore(directory: directory.url, iterations: testIterations)
        #expect(store.status == .needsSetup)

        try await store.create(masterPassword: masterPassword)
        #expect(store.status == .unlocked)
        #expect(FileManager.default.fileExists(atPath: store.vaultURL.path(percentEncoded: false)))

        let relaunched = VaultStore(directory: directory.url, iterations: testIterations)
        #expect(relaunched.status == .locked)
        #expect(relaunched.items.isEmpty)
    }

    @Test func vaultFilesAreOwnerOnly() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)
        try store.save(makeFullItem())
        for url in [store.vaultURL, store.previousVersionURL] {
            let attributes = try FileManager.default.attributesOfItem(atPath: url.path(percentEncoded: false))
            #expect((attributes[.posixPermissions] as? Int) == 0o600, "\(url.lastPathComponent)")
        }
    }

    @Test func shortMasterPasswordCreatesNothing() async throws {
        let directory = try TemporaryDirectory()
        let store = VaultStore(directory: directory.url, iterations: testIterations)
        await #expect(throws: VaultError.passwordTooShort(minimum: 10)) {
            try await store.create(masterPassword: "short")
        }
        #expect(store.status == .needsSetup)
        #expect(!FileManager.default.fileExists(atPath: store.vaultURL.path(percentEncoded: false)))
    }

    @Test func itemsSurviveRelaunchExactly() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)
        let items = [makeFullItem(), makeFullItem(title: "Bank"), LoginItem(title: "Minimal")]
        for item in items { try store.save(item) }

        let relaunched = VaultStore(directory: directory.url, iterations: testIterations)
        try await relaunched.unlock(masterPassword: masterPassword)
        #expect(relaunched.items.sortedByID == items.sortedByID)
    }

    @Test func wrongMasterPasswordKeepsVaultLocked() async throws {
        let directory = try TemporaryDirectory()
        try await makeUnlockedStore(in: directory).save(makeFullItem())

        let store = VaultStore(directory: directory.url, iterations: testIterations)
        await #expect(throws: VaultError.wrongPassword) { try await store.unlock(masterPassword: "wrong password!") }
        #expect(store.status == .locked)
        #expect(store.items.isEmpty)

        try await store.unlock(masterPassword: masterPassword)
        #expect(store.items.count == 1)
    }

    @Test func lockForgetsDecryptedItems() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)
        try store.save(makeFullItem())

        store.lock()
        #expect(store.status == .locked)
        #expect(store.items.isEmpty)
        #expect(throws: VaultError.locked) { try store.save(makeFullItem()) }

        try await store.unlock(masterPassword: masterPassword)
        #expect(store.items.count == 1)
    }

    @Test func editsAndDeletesArePersisted() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)
        var kept = makeFullItem(title: "Keep")
        let removed = makeFullItem(title: "Remove")
        try store.save(kept)
        try store.save(removed)

        kept.password = "changed password"
        try store.save(kept)
        try store.delete(id: removed.id)
        #expect(store.items == [kept])

        let relaunched = VaultStore(directory: directory.url, iterations: testIterations)
        try await relaunched.unlock(masterPassword: masterPassword)
        #expect(relaunched.items == [kept])
    }

    @Test func deletingSeveralLoginsIsOneSave() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)
        let items = (1...5).map { makeFullItem(title: "Login \($0)") }
        for item in items { try store.save(item) }

        try store.delete(ids: [items[0].id, items[2].id, items[4].id, UUID()])
        #expect(store.items.map(\.title) == ["Login 2", "Login 4"])

        let relaunched = VaultStore(directory: directory.url, iterations: testIterations)
        try await relaunched.unlock(masterPassword: masterPassword)
        #expect(relaunched.items.map(\.title) == ["Login 2", "Login 4"])

        // One write: the previous version still holds all five, so a mistaken
        // bulk delete can be recovered from it.
        let restoreDirectory = try TemporaryDirectory()
        try FileManager.default.copyItem(
            at: store.previousVersionURL, to: restoreDirectory.url.appending(path: "vault.syncedpass"))
        let restored = VaultStore(directory: restoreDirectory.url, iterations: testIterations)
        try await restored.unlock(masterPassword: masterPassword)
        #expect(restored.items.count == 5)
    }

    @Test func deletingNothingOrUnknownIDsDoesNotWrite() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)
        try store.save(makeFullItem())
        let before = try Data(contentsOf: store.vaultURL)

        try store.delete(ids: [])
        try store.delete(ids: [UUID()])
        #expect(store.items.count == 1)
        #expect(try Data(contentsOf: store.vaultURL) == before)
    }

    @Test func failedBulkDeleteRemovesNothing() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)
        let items = (1...3).map { makeFullItem(title: "Login \($0)") }
        for item in items { try store.save(item) }

        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: directory.url.path(percentEncoded: false))
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: directory.url.path(percentEncoded: false)) }
        #expect(throws: (any Error).self) { try store.delete(ids: Set(items.map(\.id))) }
        #expect(store.items.count == 3)
    }

    @Test func previousVersionIsKeptAndReadable() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)
        let first = makeFullItem(title: "First")
        try store.save(first)
        try store.save(makeFullItem(title: "Second"))

        // The copy is a complete vault one save behind; restoring it is a file rename.
        let restoreDirectory = try TemporaryDirectory()
        try FileManager.default.copyItem(
            at: store.previousVersionURL, to: restoreDirectory.url.appending(path: "vault.syncedpass"))
        let restored = VaultStore(directory: restoreDirectory.url, iterations: testIterations)
        try await restored.unlock(masterPassword: masterPassword)
        #expect(restored.items == [first])
    }

    @Test func failedWriteChangesNothing() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)
        let saved = makeFullItem(title: "Saved")
        try store.save(saved)
        let before = try Data(contentsOf: store.vaultURL)

        // A read-only folder makes the atomic write fail.
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: directory.url.path(percentEncoded: false))
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: directory.url.path(percentEncoded: false)) }

        #expect(throws: (any Error).self) { try store.save(makeFullItem(title: "Not saved")) }
        #expect(throws: (any Error).self) { try store.delete(id: saved.id) }
        #expect(store.items == [saved], "UI must keep showing what is actually on disk")
        #expect(try Data(contentsOf: store.vaultURL) == before)
    }

    @Test func damagedVaultFileIsReported() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)
        try store.save(makeFullItem())
        try Data("{ not json".utf8).write(to: store.vaultURL)

        let relaunched = VaultStore(directory: directory.url, iterations: testIterations)
        await #expect(throws: VaultError.notSyncedPassFile) { try await relaunched.unlock(masterPassword: masterPassword) }
        #expect(relaunched.status == .locked)
    }

    @Test func realVaultsUseTheFullIterationCount() async throws {
        let directory = try TemporaryDirectory()
        let store = VaultStore(directory: directory.url)
        try await store.create(masterPassword: masterPassword)
        let container = try VaultCrypto.container(from: Data(contentsOf: store.vaultURL), expecting: .vault)
        #expect(container.kdf.iterations == 600_000)
        #expect(container.kdf.salt.count == 16)
    }

    @Test func searchMatchesTitleUsernameAndWebsite() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)
        try store.save(makeFullItem(title: "GitHub"))
        try store.save(LoginItem(title: "Bank", username: "me", websites: ["https://mybank.dz"]))
        #expect(store.items(matching: "git").map(\.title) == ["GitHub"])
        #expect(store.items(matching: "mybank").map(\.title) == ["Bank"])
        #expect(store.items(matching: "").map(\.title) == ["Bank", "GitHub"])
    }
}

@Suite("Changing the master password")
struct ChangeMasterPasswordTests {
    let newPassword = "a brand new passphrase"

    @Test func newPasswordUnlocksAndOldOneNoLongerDoes() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)
        let items = [makeFullItem(), makeFullItem(title: "Bank")]
        for item in items { try store.save(item) }

        try await store.changeMasterPassword(current: masterPassword, new: newPassword)
        #expect(store.status == .unlocked)
        #expect(store.items.sortedByID == items.sortedByID)

        let relaunched = VaultStore(directory: directory.url, iterations: testIterations)
        await #expect(throws: VaultError.wrongPassword) { try await relaunched.unlock(masterPassword: masterPassword) }
        try await relaunched.unlock(masterPassword: newPassword)
        #expect(relaunched.items.sortedByID == items.sortedByID)
    }

    @Test func savesAfterTheChangeUseTheNewPassword() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)
        try await store.changeMasterPassword(current: masterPassword, new: newPassword)
        try store.save(makeFullItem(title: "Added later"))

        let relaunched = VaultStore(directory: directory.url, iterations: testIterations)
        try await relaunched.unlock(masterPassword: newPassword)
        #expect(relaunched.items.map(\.title) == ["Added later"])
    }

    @Test func vaultKeyIsReplacedNotJustRewrapped() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)
        try store.save(makeFullItem())
        let before = try VaultCrypto.container(from: Data(contentsOf: store.vaultURL), expecting: .vault)
        let oldKeys = try await VaultCrypto.unlock(before, password: masterPassword)

        try await store.changeMasterPassword(current: masterPassword, new: newPassword)
        let after = try VaultCrypto.container(from: Data(contentsOf: store.vaultURL), expecting: .vault)
        #expect(after.kdf.salt != before.kdf.salt)
        // The old vault key can't decrypt the new file.
        #expect(throws: VaultError.damaged) { try VaultCrypto.open(after, keys: oldKeys) }
    }

    @Test func noFileOnDiskStillOpensWithTheOldPassword() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)
        try store.save(makeFullItem())
        try await store.changeMasterPassword(current: masterPassword, new: newPassword)

        let previous = try VaultCrypto.container(from: Data(contentsOf: store.previousVersionURL), expecting: .vault)
        await #expect(throws: VaultError.wrongPassword) { try await VaultCrypto.unlock(previous, password: masterPassword) }
        let previousKeys = try await VaultCrypto.unlock(previous, password: newPassword)
        #expect(try VaultCrypto.open(previous, keys: previousKeys).count == 1)
    }

    @Test func wrongCurrentPasswordChangesNothing() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)
        try store.save(makeFullItem())
        let before = try Data(contentsOf: store.vaultURL)

        await #expect(throws: VaultError.wrongPassword) {
            try await store.changeMasterPassword(current: "not the password", new: newPassword)
        }
        #expect(try Data(contentsOf: store.vaultURL) == before)
        #expect(store.status == .unlocked && store.items.count == 1)
        try store.save(makeFullItem(title: "Still works"))
        let relaunched = VaultStore(directory: directory.url, iterations: testIterations)
        try await relaunched.unlock(masterPassword: masterPassword)
        #expect(relaunched.items.count == 2)
    }

    @Test func rejectsWeakSameOrLockedChanges() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)
        let before = try Data(contentsOf: store.vaultURL)

        await #expect(throws: VaultError.passwordTooShort(minimum: 10)) {
            try await store.changeMasterPassword(current: masterPassword, new: "short")
        }
        await #expect(throws: VaultError.samePassword) {
            try await store.changeMasterPassword(current: masterPassword, new: masterPassword)
        }
        #expect(try Data(contentsOf: store.vaultURL) == before)

        store.lock()
        await #expect(throws: VaultError.locked) {
            try await store.changeMasterPassword(current: masterPassword, new: newPassword)
        }
    }
}

/// Export a backup, change the master password, then import that backup.
@Suite("Backup across a master password change")
struct BackupAcrossPasswordChangeTests {
    let backupPassword = "backup password 2026"
    let newMaster = "a brand new passphrase"

    private func backupContainer(_ data: Data, store: VaultStore) throws -> EncryptedContainer {
        guard case .backup(let container) = try store.inspectImport(data) else { throw VaultError.notSyncedPassFile }
        return container
    }

    @Test func backupStillOpensWithItsOwnPasswordAndVaultKeepsTheNewOne() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)
        let items = [makeFullItem(), makeFullItem(title: "Bank")]
        try store.add(items)

        let backup = try await store.exportBackup(password: backupPassword)
        try await store.changeMasterPassword(current: masterPassword, new: newMaster)

        let summary = try await store.importBackup(backupContainer(backup, store: store), password: backupPassword)
        #expect(summary == .init(added: 0, updated: 0, unchanged: 2))
        #expect(store.items.sortedByID == items.sortedByID)

        // Importing merges logins; it doesn't bring back the old master password.
        let relaunched = VaultStore(directory: directory.url, iterations: testIterations)
        await #expect(throws: VaultError.wrongPassword) { try await relaunched.unlock(masterPassword: masterPassword) }
        try await relaunched.unlock(masterPassword: newMaster)
        #expect(relaunched.items.sortedByID == items.sortedByID)
    }

    @Test func backupProtectedWithTheOldMasterPasswordNeedsThatOldPassword() async throws {
        let store = try await makeUnlockedStore(in: TemporaryDirectory())
        try store.save(makeFullItem())
        // The user chose their (then) master password as the backup password.
        let backup = try await store.exportBackup(password: masterPassword)
        try await store.changeMasterPassword(current: masterPassword, new: newMaster)

        let container = try backupContainer(backup, store: store)
        await #expect(throws: VaultError.wrongPassword) {
            try await store.importBackup(container, password: newMaster)
        }
        let summary = try await store.importBackup(container, password: masterPassword)
        #expect(summary.unchanged == 1)
    }

    @Test func changesMadeAfterTheBackupAreMergedNotLost() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)
        var edited = makeFullItem(title: "Edited later", modifiedAt: .now.addingTimeInterval(-100))
        let deleted = makeFullItem(title: "Deleted later", modifiedAt: .now.addingTimeInterval(-100))
        try store.add([edited, deleted])
        let backup = try await store.exportBackup(password: backupPassword)

        // After the backup: edit one login, delete one, add one, change the password.
        edited.password = "changed after the backup"
        edited.modifiedAt = .now
        try store.save(edited)
        try store.delete(id: deleted.id)
        let added = makeFullItem(title: "Added later")
        try store.save(added)
        try await store.changeMasterPassword(current: masterPassword, new: newMaster)

        let summary = try await store.importBackup(backupContainer(backup, store: store), password: backupPassword)
        #expect(summary == .init(added: 1, updated: 0, unchanged: 1))
        #expect(store.items.first { $0.id == edited.id }?.password == "changed after the backup", "The newer edit wins")
        #expect(store.items.contains { $0.id == deleted.id }, "A login deleted after the backup comes back")
        #expect(store.items.contains { $0.id == added.id }, "Logins added after the backup stay")

        let relaunched = VaultStore(directory: directory.url, iterations: testIterations)
        try await relaunched.unlock(masterPassword: newMaster)
        #expect(relaunched.items.count == 3)
    }
}
