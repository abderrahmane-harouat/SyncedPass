import Foundation
import Testing
@testable import SyncedPass

/// Per-login merging (docs/SYNC.md, "Merging"); the same cases as SyncMergeTest.kt on Android.
@Suite("Sync merging")
struct SyncMergeTests {
    private let gmail = LoginItem(title: "Gmail", modifiedAt: Date(timeIntervalSinceReferenceDate: 100))
    private let github = LoginItem(title: "GitHub", password: "old", modifiedAt: Date(timeIntervalSinceReferenceDate: 100))
    private let notion = LoginItem(title: "Notion", modifiedAt: Date(timeIntervalSinceReferenceDate: 100))

    private func t(_ seconds: TimeInterval) -> Date { Date(timeIntervalSinceReferenceDate: seconds) }

    /// One sync: each side sends its manifest, each answers with what the other needs.
    private func sync(_ mac: VaultContents, _ phone: VaultContents) -> (VaultContents, VaultContents, sent: Int) {
        let toPhone = mac.recordsNeeded(by: phone.manifest)
        let toMac = phone.recordsNeeded(by: mac.manifest)
        return (mac.merging(toMac) ?? mac, phone.merging(toPhone) ?? phone,
                toPhone.items.count + toPhone.deletions.count + toMac.items.count + toMac.deletions.count)
    }

    @Test func differentLoginsEditedOfflineOnEachSideAreBothKept() {
        var macGitHub = github
        macGitHub.password = "from mac"
        macGitHub.modifiedAt = t(200)
        var phoneNotion = notion
        phoneNotion.note = "from phone"
        phoneNotion.modifiedAt = t(300)
        let (mac, phone, sent) = sync(VaultContents(items: [gmail, macGitHub, notion]), VaultContents(items: [gmail, github, phoneNotion]))
        for device in [mac, phone] {
            #expect(device.items.first { $0.id == github.id }?.password == "from mac")
            #expect(device.items.first { $0.id == notion.id }?.note == "from phone")
        }
        #expect(sent == 2, "Only the two changed logins travel")
    }

    @Test func theLaterEditOfTheSameLoginWins() {
        var earlier = github
        earlier.password = "earlier"
        earlier.modifiedAt = t(200)
        var later = github
        later.password = "later"
        later.modifiedAt = t(250)
        let (mac, phone, _) = sync(VaultContents(items: [earlier]), VaultContents(items: [later]))
        #expect(mac.items.first?.password == "later")
        #expect(phone.items.first?.password == "later")
    }

    @Test func deletionsSpreadAndTiesGoToTheDeletion() {
        let deleted = VaultContents(items: [gmail], deletions: [Deletion(id: notion.id, deletedAt: t(200))])
        let (_, phone, _) = sync(deleted, VaultContents(items: [gmail, notion]))
        #expect(!phone.items.contains { $0.id == notion.id })

        var tie = notion
        tie.modifiedAt = t(200)
        let (mac2, phone2, _) = sync(deleted, VaultContents(items: [gmail, tie]))
        #expect(!mac2.items.contains { $0.id == notion.id } && !phone2.items.contains { $0.id == notion.id })

        var later = notion
        later.modifiedAt = t(300)
        let (mac3, _, _) = sync(deleted, VaultContents(items: [gmail, later]))
        #expect(mac3.items.contains { $0.id == notion.id }, "An edit after the deletion brings the login back")
    }

    @Test func manifestTellsWhenItsSenderHasNewerRecords() {
        var newer = github
        newer.modifiedAt = t(200)
        let variants = [
            VaultContents(items: [gmail, github]),
            VaultContents(items: [gmail, newer]),
            VaultContents(items: [gmail], deletions: [Deletion(id: github.id, deletedAt: t(150))]),
            VaultContents(items: [gmail]),
        ]
        for a in variants {
            for b in variants {
                #expect(a.manifest.hasRecordsNeeded(by: b.manifest) == !a.recordsNeeded(by: b.manifest).isEmpty)
            }
        }
    }

    @Test func storeRecordsDeletionsAndRestoresFromBackupsAsNewEdits() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)
        try store.save(notion)
        try store.delete(id: notion.id)
        let deletedAt = try #require(store.contents.deletions.first { $0.id == notion.id }?.deletedAt)

        let summary = try store.merge([notion])
        #expect(summary.added == 1)
        #expect(try #require(store.items.first).modifiedAt > deletedAt, "Newer than the deletion, so sync spreads the restore")
        #expect(store.contents.deletions.isEmpty)

        // Survives relaunch, in format version 2.
        let reopened = VaultStore(directory: directory.url, iterations: testIterations)
        try await reopened.unlock(masterPassword: masterPassword)
        #expect(reopened.items.map(\.id) == [notion.id])
        let container = try VaultCrypto.container(from: Data(contentsOf: reopened.vaultURL), expecting: .vault)
        #expect(container.version == 2)
    }

    @Test func signaturesAndKeysRoundTrip() throws {
        let identity = SyncCrypto.newIdentity()
        let data = Data("transcript".utf8)
        let signature = try SyncCrypto.sign(data, privateKey: identity.privateKey)
        #expect(SyncCrypto.verify(signature, for: data, publicKey: identity.publicKey))
        #expect(!SyncCrypto.verify(signature, for: Data("other".utf8), publicKey: identity.publicKey))
        #expect(!SyncCrypto.verify(signature, for: data, publicKey: SyncCrypto.newIdentity().publicKey))
        #expect(identity.privateKey.count == 32)
    }

    @Test func recordsDatedInTheFutureAreIgnored() {
        var future = github
        future.password = "from the future"
        future.modifiedAt = t(5_000)
        let contents = VaultContents(items: [gmail, github, notion])
        let records = SyncRecords(items: [future], deletions: [Deletion(id: notion.id, deletedAt: t(5_000))])
        #expect(contents.merging(records, notAfter: t(1_000)) == nil)
    }
}
