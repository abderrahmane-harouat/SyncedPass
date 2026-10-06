import Foundation
import Testing
@testable import SyncedPass

/// Fixtures follow the old Flutter app's `Platform.toMap()` and
/// `DatabaseHelper.exportToJson()`; they are made-up values, not real data.
@Suite("Import from the old app")
struct LegacyImportTests {
    let export = Data("""
    {
      "version": "1.0",
      "exportDate": "2025-09-21T22:42:00.000",
      "platforms": [
        {"id": 1, "name": "GitHub", "url": "github.com", "username": "octocat", "password": "pa ss✓",
         "iconPath": "/data/icon.png", "authenticationType": "GitHub OAuth", "phoneNumber": "+213555000000", "pin": "0042"},
        {"id": 2, "name": "Bank", "url": "not a url", "username": "", "password": "x",
         "iconPath": "", "authenticationType": "Carrier Pigeon", "phoneNumber": "", "pin": ""},
        {"id": 3, "name": "  ", "url": "https://mail.example.com/login", "username": null, "password": null,
         "iconPath": null, "authenticationType": null, "phoneNumber": null, "pin": null},
        {"id": 4, "name": "", "url": "", "username": "u", "password": "p",
         "iconPath": "", "authenticationType": "", "phoneNumber": "", "pin": ""}
      ]
    }
    """.utf8)

    @Test func mapsEveryOldField() throws {
        let items = try LegacyImport.parse(export)
        try #require(items.count == 4)

        let github = items[0]
        #expect(github.title == "GitHub")
        #expect(github.websites == ["https://github.com"])
        #expect(github.username == "octocat")
        #expect(github.password == "pa ss✓")
        #expect(github.signInMethod == .github)
        #expect(github.phoneNumber == "+213555000000")
        #expect(github.pin == "0042")
        #expect(github.note.isEmpty)
    }

    @Test func keepsValuesThatDontFitInTheNote() throws {
        let bank = try LegacyImport.parse(export)[1]
        #expect(bank.websites.isEmpty)
        #expect(bank.signInMethod == .notSet)
        #expect(bank.note.contains("not a url"))
        #expect(bank.note.contains("Carrier Pigeon"))
    }

    @Test func handlesMissingNamesAndNulls() throws {
        let items = try LegacyImport.parse(export)
        #expect(items[2].title == "mail.example.com")
        #expect(items[2].password.isEmpty)
        #expect(items[3].title == "Imported login")
    }

    @Test func everyOldSignInMethodMaps() throws {
        let old = ["Google OAuth", "GitHub OAuth", "Facebook OAuth", "Twitter OAuth",
                   "LinkedIn OAuth", "Microsoft OAuth", "Apple ID", "Standard Login"]
        for value in old {
            let json = Data(#"[{"name":"X","authenticationType":"\#(value)"}]"#.utf8)
            let item = try #require(try LegacyImport.parse(json).first)
            #expect(item.signInMethod.rawValue == value)
            #expect(item.note.isEmpty)
        }
    }

    @Test func everyThirdPartySignInMethodHasALogo() {
        for method in SignInMethod.allCases where method != .notSet && method != .standard {
            let service = method.service
            #expect(service != nil, "\(method) has no service")
            #expect(service?.logoAssetName != nil, "\(method) has no bundled logo")
        }
        #expect(SignInMethod.notSet.service == nil)
        #expect(SignInMethod.standard.service == nil)
        #expect(SignInMethod.discord.service?.name == "Discord")
    }

    @Test func discordSignInSurvivesSaving() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)
        try store.save(LoginItem(title: "Server", signInMethod: .discord))
        let relaunched = VaultStore(directory: directory.url, iterations: testIterations)
        try await relaunched.unlock(masterPassword: masterPassword)
        #expect(relaunched.items.first?.signInMethod == .discord)
    }

    @Test func acceptsBareArrayFormat() throws {
        let items = try LegacyImport.parse(Data(#"[{"name":"A"},{"name":"B"}]"#.utf8))
        #expect(items.map(\.title) == ["A", "B"])
    }

    @Test func importingTheSameExportTwiceAddsNoDuplicates() async throws {
        let directory = try TemporaryDirectory()
        let store = try await makeUnlockedStore(in: directory)
        let first = try store.merge(LegacyImport.parse(export))
        let second = try store.merge(LegacyImport.parse(export))
        #expect(first == .init(added: 4, updated: 0, unchanged: 0))
        #expect(second == .init(added: 0, updated: 0, unchanged: 4))

        let relaunched = VaultStore(directory: directory.url, iterations: testIterations)
        try await relaunched.unlock(masterPassword: masterPassword)
        #expect(relaunched.items.count == 4)
    }
}
