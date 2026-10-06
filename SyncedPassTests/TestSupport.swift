import Foundation
@testable import SyncedPass

/// Low iteration count so store tests run fast; the real default is checked separately.
let testIterations = 1_000
let masterPassword = "correct horse battery"

/// A fresh, empty directory for one test.
///
/// Directories live under a per-run folder, and each run first deletes the
/// folders of earlier runs. Deleting in `deinit` alone wasn't enough: a
/// directory passed inline (`makeUnlockedStore(in: TemporaryDirectory())`) is
/// released straight away, and the store's next save recreates it.
nonisolated final class TemporaryDirectory {
    private static let root = FileManager.default.temporaryDirectory
        .appending(path: "SyncedPassTests", directoryHint: .isDirectory)

    private static let runFolder: URL = {
        let fileManager = FileManager.default
        for old in (try? fileManager.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? [] {
            makeDeletable(old)
            try? fileManager.removeItem(at: old)
        }
        // Folders left directly in tmp by the earlier version of this helper.
        let tmp = fileManager.temporaryDirectory
        for old in (try? fileManager.contentsOfDirectory(at: tmp, includingPropertiesForKeys: nil)) ?? []
        where old.lastPathComponent.hasPrefix("SyncedPassTests-") {
            makeDeletable(old)
            try? fileManager.removeItem(at: old)
        }
        return root.appending(path: UUID().uuidString, directoryHint: .isDirectory)
    }()

    let url: URL

    init() throws {
        url = Self.runFolder.appending(path: UUID().uuidString, directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    }

    deinit {
        Self.makeDeletable(url)
        try? FileManager.default.removeItem(at: url)
    }

    /// Restores write permission (some tests make a folder read-only).
    private static func makeDeletable(_ url: URL) {
        try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: url.path(percentEncoded: false))
    }
}

func makeUnlockedStore(in directory: TemporaryDirectory, password: String = masterPassword) async throws -> VaultStore {
    let store = VaultStore(directory: directory.url, iterations: testIterations)
    try await store.create(masterPassword: password)
    return store
}

/// An item with every field filled, including awkward Unicode, so round trips
/// prove nothing is lost or altered.
func makeFullItem(title: String = "GitHub", modifiedAt: Date = .now) -> LoginItem {
    LoginItem(
        title: title,
        email: "octo@example.com",
        username: "octocat",
        password: "p@ss wörd ✓ 🔑 \"quoted\" \\ back",
        totpSecret: "JBSWY3DPEHPK3PXP",
        websites: ["https://github.com", "https://gist.github.com/path?q=1"],
        signIns: [SignIn(method: .github), SignIn(method: .standard)],
        phoneNumber: "+213 555 00 00 00",
        pin: "0042",
        note: "Line one\nLine two — «unicode» العربية",
        customFields: [
            CustomField(kind: .text, name: "Recovery email", value: "recover@example.com"),
            CustomField(kind: .hidden, name: "Security answer", value: "blue"),
            CustomField(kind: .totp, name: "Backup 2FA", value: "otpauth://totp/X:me?secret=JBSWY3DPEHPK3PXP"),
            CustomField(kind: .date, name: "Expires", date: .now),
        ],
        createdAt: .now.addingTimeInterval(-1000),
        modifiedAt: modifiedAt
    )
}

extension Array where Element == LoginItem {
    var sortedByID: [LoginItem] { sorted { $0.id.uuidString < $1.id.uuidString } }
}

extension Data {
    func contains(_ string: String) -> Bool {
        range(of: Data(string.utf8)) != nil
    }
}
