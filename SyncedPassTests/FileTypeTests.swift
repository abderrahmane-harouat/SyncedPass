import Foundation
import Testing
import UniformTypeIdentifiers
@testable import SyncedPass

/// The Open panel only enables files whose system type conforms to one of
/// `allowedContentTypes`. These checks keep exported backups importable.
@Suite("Backup file type")
struct FileTypeTests {
    @Test func appDeclaresTheBackupType() throws {
        let declarations = try #require(Bundle.main.object(forInfoDictionaryKey: "UTExportedTypeDeclarations") as? [[String: Any]])
        let backup = try #require(declarations.first { $0["UTTypeIdentifier"] as? String == UTType.syncedPassBackup.identifier })
        let tags = backup["UTTypeTagSpecification"] as? [String: Any]
        #expect(tags?["public.filename-extension"] as? [String] == ["syncedpass"])
        #expect(!UTType.syncedPassBackup.isDynamic)
    }

    @Test func savedBackupFilesGetTheTypeTheOpenPanelAccepts() throws {
        // What macOS assigns to a file on disk named *.syncedpass.
        let systemType = try #require(UTType(filenameExtension: "syncedpass"))
        #expect(systemType == .syncedPassBackup)
        #expect(systemType.conforms(to: .syncedPassBackup))
    }
}
