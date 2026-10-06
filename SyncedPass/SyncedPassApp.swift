import SwiftUI

@main
struct SyncedPassApp: App {
    @State private var store = VaultStore(directory: SyncedPassApp.vaultDirectory)

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(store)
        }
        .defaultSize(width: 900, height: 600)
    }

    /// Debug builds can be pointed at a throwaway vault (for UI testing without
    /// touching the real one) with `SYNCEDPASS_VAULT_DIR`. Release builds
    /// always use the real location.
    private static var vaultDirectory: URL {
        #if DEBUG
        if let path = ProcessInfo.processInfo.environment["SYNCEDPASS_VAULT_DIR"] {
            return URL(filePath: path, directoryHint: .isDirectory)
        }
        #endif
        return VaultStore.defaultDirectory
    }
}
