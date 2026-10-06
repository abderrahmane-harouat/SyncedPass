import Foundation

/// Reads the plaintext JSON exported by the old Flutter password manager.
///
/// Accepts both shapes that app could produce: the export object
/// (`{"version", "exportDate", "platforms": [...]}`) and a bare array of
/// platforms. Nothing is dropped silently: values that don't fit a field
/// (an invalid URL, an unknown sign-in method) are kept in the note.
enum LegacyImport {
    private struct Platform: Decodable {
        var name: String?
        var url: String?
        var username: String?
        var password: String?
        var authenticationType: String?
        var phoneNumber: String?
        var pin: String?
    }

    private struct Export: Decodable {
        var platforms: [Platform]
    }

    static func parse(_ data: Data) throws -> [LoginItem] {
        let decoder = JSONDecoder()
        let platforms: [Platform]
        if let export = try? decoder.decode(Export.self, from: data) {
            platforms = export.platforms
        } else if let list = try? decoder.decode([Platform].self, from: data) {
            platforms = list
        } else {
            throw VaultError.notSyncedPassFile
        }
        let importedAt = Date.now
        return platforms.map { makeItem(from: $0, at: importedAt) }
    }

    private static func makeItem(from platform: Platform, at date: Date) -> LoginItem {
        var notes: [String] = []

        let rawURL = (platform.url ?? "").trimmed
        var websites: [String] = []
        if let website = LoginValidation.normalizedWebsite(rawURL) {
            websites = [website]
        } else if !rawURL.isEmpty {
            notes.append("Website (from old app): \(rawURL)")
        }

        let rawMethod = (platform.authenticationType ?? "").trimmed
        let signInMethod = SignInMethod(rawValue: rawMethod) ?? .notSet
        if signInMethod == .notSet, !rawMethod.isEmpty {
            notes.append("Sign-in method (from old app): \(rawMethod)")
        }

        var title = (platform.name ?? "").trimmed
        if title.isEmpty {
            title = websites.first.flatMap { URL(string: $0)?.host() } ?? "Imported login"
        }

        return LoginItem(
            title: title,
            username: (platform.username ?? "").trimmed,
            password: platform.password ?? "",
            websites: websites,
            signIns: signInMethod == .notSet ? [] : [SignIn(method: signInMethod)],
            phoneNumber: (platform.phoneNumber ?? "").trimmed,
            pin: platform.pin ?? "",
            note: notes.joined(separator: "\n"),
            createdAt: date,
            modifiedAt: date
        )
    }
}
