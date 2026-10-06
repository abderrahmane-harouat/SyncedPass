import Foundation
import Testing
@testable import SyncedPass

@Suite("Backup export and import")
struct BackupTests {
    let backupPassword = "backup password 2026"

    private func backupContainer(_ data: Data, store: VaultStore) throws -> EncryptedContainer {
        guard case .backup(let container) = try store.inspectImport(data) else {
            Issue.record("Not recognized as a backup")
            throw VaultError.notSyncedPassFile
        }
        return container
    }

    @Test func restoresEverythingIntoAFreshVault() async throws {
        let source = try await makeUnlockedStore(in: TemporaryDirectory())
        let items = [makeFullItem(), makeFullItem(title: "Bank"), LoginItem(title: "Minimal")]
        for item in items { try source.save(item) }
        let backup = try await source.exportBackup(password: backupPassword)

        // A different Mac with a different master password.
        let directory = try TemporaryDirectory()
        let target = try await makeUnlockedStore(in: directory, password: "another master pw")
        let summary = try await target.importBackup(backupContainer(backup, store: target), password: backupPassword)
        #expect(summary == .init(added: 3, updated: 0, unchanged: 0))
        #expect(target.items.sortedByID == items.sortedByID)

        let relaunched = VaultStore(directory: directory.url, iterations: testIterations)
        try await relaunched.unlock(masterPassword: "another master pw")
        #expect(relaunched.items.sortedByID == items.sortedByID, "Imported items must be saved to disk")
    }

    @Test func backupSurvivesWritingToAFile() async throws {
        let directory = try TemporaryDirectory()
        let source = try await makeUnlockedStore(in: directory)
        try source.save(makeFullItem())
        let file = directory.url.appending(path: "My Backup.syncedpass")
        try await source.exportBackup(password: backupPassword).write(to: file, options: .atomic)

        let target = try await makeUnlockedStore(in: TemporaryDirectory())
        let container = try backupContainer(Data(contentsOf: file), store: target)
        _ = try await target.importBackup(container, password: backupPassword)
        #expect(target.items == source.items)
    }

    @Test func backupIsEncryptedWithItsOwnPassword() async throws {
        let source = try await makeUnlockedStore(in: TemporaryDirectory())
        let item = makeFullItem()
        try source.save(item)
        let backup = try await source.exportBackup(password: backupPassword)
        #expect(!backup.contains(item.username))
        #expect(!backup.contains("github.com"))

        let target = try await makeUnlockedStore(in: TemporaryDirectory())
        let container = try backupContainer(backup, store: target)
        await #expect(throws: VaultError.wrongPassword) {
            try await target.importBackup(container, password: masterPassword)
        }
        #expect(target.items.isEmpty, "A failed import must not change the vault")
    }

    @Test func exportRequiresAStrongEnoughPasswordAndAnUnlockedVault() async throws {
        let store = try await makeUnlockedStore(in: TemporaryDirectory())
        await #expect(throws: VaultError.passwordTooShort(minimum: 10)) {
            try await store.exportBackup(password: "short")
        }
        store.lock()
        await #expect(throws: VaultError.locked) { try await store.exportBackup(password: backupPassword) }
    }

    @Test func importingTwiceAddsNothingTheSecondTime() async throws {
        let source = try await makeUnlockedStore(in: TemporaryDirectory())
        try source.save(makeFullItem())
        try source.save(makeFullItem(title: "Bank"))
        let backup = try await source.exportBackup(password: backupPassword)

        let target = try await makeUnlockedStore(in: TemporaryDirectory())
        let container = try backupContainer(backup, store: target)
        _ = try await target.importBackup(container, password: backupPassword)
        let second = try await target.importBackup(container, password: backupPassword)
        #expect(second == .init(added: 0, updated: 0, unchanged: 2))
        #expect(target.items.count == 2)
    }

    @Test func newerEditWinsWhenMerging() async throws {
        let store = try await makeUnlockedStore(in: TemporaryDirectory())
        let keptInVault = makeFullItem(title: "Vault copy is newer", modifiedAt: .now.addingTimeInterval(-100))
        let replacedFromBackup = makeFullItem(title: "Backup copy is newer", modifiedAt: .now.addingTimeInterval(-100))
        try store.save(keptInVault)
        try store.save(replacedFromBackup)

        var olderInBackup = keptInVault
        olderInBackup.password = "from backup (older)"
        olderInBackup.modifiedAt = .now.addingTimeInterval(-200)
        var newerInBackup = replacedFromBackup
        newerInBackup.password = "from backup (newer)"
        newerInBackup.modifiedAt = .now
        let backupSource = try await makeUnlockedStore(in: TemporaryDirectory())
        try backupSource.save(olderInBackup)
        try backupSource.save(newerInBackup)
        let backup = try await backupSource.exportBackup(password: backupPassword)

        let summary = try await store.importBackup(backupContainer(backup, store: store), password: backupPassword)
        #expect(summary == .init(added: 0, updated: 1, unchanged: 1))
        #expect(store.items.first { $0.id == keptInVault.id } == keptInVault)
        #expect(store.items.first { $0.id == replacedFromBackup.id } == newerInBackup)
    }

    @Test func inspectRecognizesEachFileType() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)

        let backup = try await store.exportBackup(password: backupPassword)
        guard case .backup = try store.inspectImport(backup) else {
            Issue.record("Backup not recognized")
            return
        }
        let legacy = Data(#"{"version":"1.0","platforms":[{"id":1,"name":"Old"}]}"#.utf8)
        guard case .legacy(let items) = try store.inspectImport(legacy) else {
            Issue.record("Old-app export not recognized")
            return
        }
        #expect(items.map(\.title) == ["Old"])

        #expect(throws: VaultError.wrongKind(.vault)) { try store.inspectImport(Data(contentsOf: store.vaultURL)) }
        #expect(throws: VaultError.notSyncedPassFile) { try store.inspectImport(Data(#"{"hello":"world"}"#.utf8)) }
        #expect(throws: VaultError.notSyncedPassFile) { try store.inspectImport(Data([0xFF, 0x00, 0x12])) }
    }
}
