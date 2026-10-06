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
        .commands { NewLoginCommands() }
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

/// File ▸ New Login (⌘N) and
/// SyncedPass ▸ Change Master Password…. Shortcuts live in the menu bar so
/// they work wherever focus is; toolbar menu items can't register them.
/// Disabled while the vault is locked.
struct NewLoginActions {
    let newLogin: () -> Void
    let changeMasterPassword: () -> Void
}

extension FocusedValues {
    @Entry var newLoginActions: NewLoginActions?
}

private struct NewLoginCommands: Commands {
    @FocusedValue(\.newLoginActions) private var actions

    var body: some Commands {
        CommandGroup(after: .appSettings) {
            Button("Change Master Password…") { actions?.changeMasterPassword() }
                .disabled(actions == nil)
        }
        CommandGroup(replacing: .newItem) {
            Button("New Login") { actions?.newLogin() }
                .keyboardShortcut("n")
                .disabled(actions == nil)
        }
    }
}
