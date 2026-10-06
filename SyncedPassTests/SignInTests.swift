import CryptoKit
import Foundation
import Testing
@testable import SyncedPass

@Suite("Sign-in methods and linked accounts")
struct SignInTests {
    // MARK: Upgrading older vaults

    /// An item exactly as vaults before sign-in lists stored it.
    private func legacyItemJSON(signInMethod: String) -> Data {
        let created = Date(timeIntervalSinceReferenceDate: 780_000_000).timeIntervalSinceReferenceDate
        return Data("""
        [{"id":"7D1E2F3A-0000-4000-8000-000000000001","title":"Notion","email":"me@gmail.com",
          "username":"","password":"","totpSecret":"","websites":["https://notion.so"],
          "signInMethod":"\(signInMethod)","phoneNumber":"","pin":"","note":"n","customFields":[],
          "createdAt":\(created),"modifiedAt":\(created)}]
        """.utf8)
    }

    @Test func oldSingleSignInMethodBecomesAOneEntryList() throws {
        let cases: [(raw: String, expected: [SignIn])] = [
            ("Google OAuth", [SignIn(method: .google)]),
            ("Standard Login", [SignIn(method: .standard)]),
            ("", []),
        ]
        for (raw, expected) in cases {
            let items = try JSONDecoder().decode([LoginItem].self, from: legacyItemJSON(signInMethod: raw))
            #expect(items.first?.signIns == expected, "\(raw)")
            #expect(items.first?.title == "Notion")
            #expect(items.first?.note == "n")
        }
    }

    @Test func oldVaultFileOpensAndIsRewrittenInTheNewFormat() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)
        // Replace the vault's payload with old-format items, encrypted with the
        // vault's own key, as an older app version would have written it.
        let container = try VaultCrypto.container(from: Data(contentsOf: store.vaultURL), expecting: .vault)
        let keys = try await VaultCrypto.unlock(container, password: masterPassword)
        let payload = try AES.GCM.seal(
            legacyItemJSON(signInMethod: "Google OAuth"), using: keys.vaultKey,
            authenticating: Data("SyncedPass v1 payload: SyncedPass Vault".utf8)).combined!
        var legacy = container
        legacy.payload = payload
        try JSONEncoder().encode(legacy).write(to: store.vaultURL)

        let reopened = VaultStore(directory: directory.url, iterations: testIterations)
        try await reopened.unlock(masterPassword: masterPassword)
        #expect(reopened.items.map(\.signIns) == [[SignIn(method: .google)]])

        // The next save writes the new format, which reads back the same.
        var item = try #require(reopened.items.first)
        item.signIns.append(SignIn(method: .standard))
        try reopened.save(item)
        let again = VaultStore(directory: directory.url, iterations: testIterations)
        try await again.unlock(masterPassword: masterPassword)
        #expect(again.items == [item])
    }

    @Test func newFormatHasNoLegacyKey() throws {
        let item = LoginItem(title: "x", signIns: [SignIn(method: .google, accountID: UUID())])
        let json = try #require(String(data: JSONEncoder().encode(item), encoding: .utf8))
        #expect(!json.contains("signInMethod"))
        #expect(try JSONDecoder().decode(LoginItem.self, from: Data(json.utf8)) == item)
    }

    // MARK: Linked accounts

    @Test func findsLoginsThatSignInWithAnAccount() async throws {
        let store = try await makeUnlockedStore(in: TemporaryDirectory())
        let gmail = LoginItem(title: "Gmail", email: "me@gmail.com")
        let other = LoginItem(title: "Work Gmail", email: "me@work.com")
        try store.add([gmail, other])
        try store.add([
            LoginItem(title: "Notion", signIns: [SignIn(method: .google, accountID: gmail.id)]),
            LoginItem(title: "EasyEDA", signIns: [SignIn(method: .google, accountID: gmail.id), SignIn(method: .standard)]),
            LoginItem(title: "Slack", signIns: [SignIn(method: .google, accountID: other.id)]),
            LoginItem(title: "Bank", password: "x"),
        ])
        #expect(store.logins(signingInWith: gmail.id).map(\.title) == ["EasyEDA", "Notion"])
        #expect(store.logins(signingInWith: other.id).map(\.title) == ["Slack"])
    }

    @Test func accountPickerListsOnlyProviderAccountsOncePerAddress() async throws {
        let store = try await makeUnlockedStore(in: TemporaryDirectory())
        let gmail = LoginItem(title: "Gmail", email: "me@gmail.com")
        try store.add([
            gmail,
            LoginItem(title: "Google", email: "ME@gmail.com"),            // same account, different case
            LoginItem(title: "Gmail perso", username: "other@gmail.com"),  // keyword in title, address in username
            LoginItem(title: "Work", email: "me@company.com", websites: ["https://accounts.google.com"]), // Workspace
            LoginItem(title: "Facebook", email: "me@gmail.com"),           // uses a Gmail address, isn't a Google account
            LoginItem(title: "Netflix", email: "me@gmail.com"),
            LoginItem(title: "iCloud", email: "me@icloud.com"),
            LoginItem(title: "Project X"),
        ])
        let google = store.accountCandidates(for: .google)
        #expect(google.map(\.accountChoiceLabel) == [
            "me@company.com (Work)", "me@gmail.com (Gmail)", "other@gmail.com (Gmail perso)",
        ])
        #expect(google.first { $0.accountAddress == "me@gmail.com" }?.id == gmail.id, "The Gmail login represents the address")
        #expect(store.accountCandidates(for: .apple).map(\.title) == ["iCloud"])
        #expect(store.accountCandidates(for: .twitter).isEmpty)
        #expect(store.accountCandidates(for: .google, excluding: gmail.id).map(\.title).contains("Google"),
                "Editing the Gmail login itself, the other login with that address stands in")
    }

    @Test func filtersByPasswordAndProvider() {
        let google = LoginItem(title: "a", signIns: [SignIn(method: .google)])
        let both = LoginItem(title: "b", password: "x", signIns: [SignIn(method: .google), SignIn(method: .standard)])
        let password = LoginItem(title: "c", password: "y")
        let items = [google, both, password]
        #expect(items.filter(LoginFilter.all.matches).count == 3)
        #expect(items.filter(LoginFilter.withPassword.matches).map(\.title) == ["b", "c"])
        #expect(items.filter(LoginFilter.provider(.google).matches).map(\.title) == ["a", "b"])
        #expect(items.filter(LoginFilter.provider(.apple).matches).isEmpty)
    }

    // MARK: Saving several at once

    @Test func addingSeveralIsOneSave() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)
        try store.save(LoginItem(title: "Existing"))
        try store.add([LoginItem(title: "A"), LoginItem(title: "B")])
        let previous = try TemporaryDirectory()
        try FileManager.default.copyItem(at: store.previousVersionURL, to: previous.url.appending(path: "vault.syncedpass"))
        let restored = VaultStore(directory: previous.url, iterations: testIterations)
        try await restored.unlock(masterPassword: masterPassword)
        #expect(restored.items.map(\.title) == ["Existing"])
    }

    // MARK: Splitting combined logins

    @Test func findsTheNamesInACombinedTitle() {
        #expect(LoginSplit.parts(of: "easyEDA, Flippa,Notion , Ling") == ["easyEDA", "Flippa", "Notion", "Ling"])
        #expect(LoginSplit.parts(of: "Daily.dev, skillShare, Sanity, poe,") == ["Daily.dev", "skillShare", "Sanity", "poe"])
        #expect(LoginSplit.parts(of: "Notion, notion") == [])
        #expect(LoginSplit.parts(of: "Notion").isEmpty)
    }

    @Test func splitCopiesEveryDetailAndLinksTheAccount() throws {
        let account = UUID()
        let combined = LoginItem(
            title: "easyEDA, Flippa, Notion", email: "me@gmail.com", password: "shared",
            websites: ["https://old.example"], signIns: [SignIn(method: .google), SignIn(method: .standard)],
            note: "from old app", customFields: [CustomField(kind: .text, name: "k", value: "v")])
        let parts = LoginSplit.makeItems(from: combined, accountID: .some(account))

        #expect(parts.map(\.title) == ["EasyEDA", "Flippa", "Notion"])
        #expect(parts.map(\.websites) == [["https://easyeda.com"], [], ["https://notion.so"]])
        #expect(Set(parts.map(\.id)).count == 3 && !parts.contains { $0.id == combined.id })
        for part in parts {
            #expect(part.email == "me@gmail.com" && part.password == "shared")
            #expect(part.signIns == [SignIn(method: .google, accountID: account), SignIn(method: .standard)])
            #expect(part.note.contains("from old app") && part.note.contains("https://old.example"))
            #expect(part.customFields.map(\.value) == ["v"])
            #expect(part.createdAt == combined.createdAt)
        }
    }

    @Test func splitIsOneSaveThatCanBeUndoneFromThePreviousVersion() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)
        let combined = LoginItem(title: "Daily.dev, Sanity", signIns: [SignIn(method: .google)])
        try store.save(LoginItem(title: "Before"))
        try store.save(combined)

        try store.replace(combined.id, with: LoginSplit.makeItems(from: combined))
        #expect(store.items.map(\.title) == ["Before", "daily.dev", "Sanity"])

        let previous = try TemporaryDirectory()
        try FileManager.default.copyItem(at: store.previousVersionURL, to: previous.url.appending(path: "vault.syncedpass"))
        let restored = VaultStore(directory: previous.url, iterations: testIterations)
        try await restored.unlock(masterPassword: masterPassword)
        #expect(restored.items.map(\.title) == ["Before", "Daily.dev, Sanity"])
    }
}
