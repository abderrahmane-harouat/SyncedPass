import AppKit
import SwiftUI
import UniformTypeIdentifiers

extension UTType {
    /// Declared in SyncedPass-Info.plist so macOS maps `.syncedpass` files to
    /// it. A type made up at runtime instead doesn't match the type macOS gives
    /// the saved file, and the Open panel greys the backup out.
    static let syncedPassBackup = UTType(exportedAs: "com.abdurahmanharouat.syncedpass.backup")
}

extension View {
    /// Adds the export and import flows, started by setting the bindings.
    func backupFlows(exportRequested: Binding<Bool>, importRequested: Binding<Bool>) -> some View {
        modifier(BackupFlows(exportRequested: exportRequested, importRequested: importRequested))
    }
}

private struct BackupFlows: ViewModifier {
    @Binding var exportRequested: Bool
    @Binding var importRequested: Bool

    @Environment(VaultStore.self) private var store
    @State private var pendingBackup: EncryptedContainer?
    @State private var pendingLegacy: [LoginItem]?
    @State private var alert: AlertContent?

    struct AlertContent: Identifiable {
        let id = UUID()
        let title: String
        let message: String
    }

    func body(content: Content) -> some View {
        content
            .sheet(isPresented: $exportRequested) {
                BackupPasswordSheet(mode: .export) { password in
                    let data = try await store.exportBackup(password: password)
                    guard let url = chooseExportLocation() else { return false }
                    try data.write(to: url, options: .atomic)
                    try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path(percentEncoded: false))
                    alert = AlertContent(
                        title: "Backup Exported",
                        message: "\(loginCount(store.items.count)) saved to “\(url.lastPathComponent)”. Keep the backup password safe — it's needed to restore.")
                    return true
                }
            }
            .sheet(item: $pendingBackup) { container in
                BackupPasswordSheet(mode: .import) { password in
                    let summary = try await store.importBackup(container, password: password)
                    alert = AlertContent(title: "Backup Imported", message: summary.message)
                    return true
                }
            }
            .onChange(of: importRequested) { _, requested in
                guard requested else { return }
                importRequested = false
                chooseImportFile()
            }
            .confirmationDialog(
                "Import \(loginCount(pendingLegacy?.count ?? 0)) from the old app?",
                isPresented: Binding(get: { pendingLegacy != nil }, set: { if !$0 { pendingLegacy = nil } })
            ) {
                Button("Import") { importLegacy() }
            } message: {
                Text("Logins that are already in your vault are skipped.")
            }
            .alert(item: $alert) { content in
                Alert(title: Text(content.title), message: Text(content.message))
            }
    }

    private func chooseExportLocation() -> URL? {
        let panel = NSSavePanel()
        panel.title = "Export Backup"
        panel.allowedContentTypes = [.syncedPassBackup]
        panel.nameFieldStringValue = "SyncedPass Backup \(Date.now.formatted(.iso8601.year().month().day())).syncedpass"
        return panel.runModal() == .OK ? panel.url : nil
    }

    private func chooseImportFile() {
        let panel = NSOpenPanel()
        panel.title = "Import Backup"
        panel.message = "Choose a SyncedPass backup or a JSON export from the old app."
        panel.allowedContentTypes = [.syncedPassBackup, .json]
        panel.allowsMultipleSelection = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            switch try store.inspectImport(Data(contentsOf: url)) {
            case .backup(let container): pendingBackup = container
            case .legacy(let items): pendingLegacy = items
            }
        } catch {
            alert = AlertContent(title: "Can't Import This File", message: error.localizedDescription)
        }
    }

    private func importLegacy() {
        guard let items = pendingLegacy else { return }
        pendingLegacy = nil
        do {
            let summary = try store.merge(items)
            alert = AlertContent(
                title: "Import Complete",
                message: summary.message + "\n\nThe old app's export isn't encrypted. Delete that file now that your logins are in SyncedPass.")
        } catch {
            alert = AlertContent(title: "Import Failed", message: error.localizedDescription)
        }
    }
}

private func loginCount(_ count: Int) -> String {
    count == 1 ? "1 login" : "\(count) logins"
}

extension EncryptedContainer: Identifiable {
    var id: Data { payload }
}

private extension VaultStore.ImportSummary {
    var message: String {
        "\(added) added, \(updated) updated, \(unchanged) already up to date."
    }
}

/// Asks for a backup password. Export asks twice and enforces a minimum
/// length; import asks once.
private struct BackupPasswordSheet: View {
    enum Mode { case export, `import` }

    let mode: Mode
    /// Returns false when the user cancelled a follow-up step (e.g. the save panel).
    let action: (String) async throws -> Bool

    @Environment(\.dismiss) private var dismiss
    @State private var password = ""
    @State private var confirmation = ""
    @State private var isWorking = false
    @State private var error: String?
    @FocusState private var focused: Bool

    private var problem: String? {
        guard mode == .export else { return password.isEmpty ? "" : nil }
        if password.count < VaultCrypto.minimumPasswordLength {
            return "Use at least \(VaultCrypto.minimumPasswordLength) characters."
        }
        return confirmation == password ? nil : "The passwords don't match."
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(mode == .export ? "Export Encrypted Backup" : "Unlock Backup")
                .font(.title3.weight(.semibold))
            Text(mode == .export
                 ? "Choose a password for this backup. You'll need it to restore — it can be different from your master password."
                 : "Enter the password that was chosen when this backup was exported.")
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            LabeledField("Backup password") {
                RevealableField("Backup password", text: $password, focus: $focused)
                    .onSubmit(submit)
            }
            if mode == .export {
                LabeledField("Confirm backup password") {
                    RevealableField("Confirm backup password", text: $confirmation)
                        .onSubmit(submit)
                }
            }

            if let message = error ?? (mode == .export && !confirmation.isEmpty ? problem : nil) {
                FieldError(message)
            }

            HStack {
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button(isWorking ? "Working…" : (mode == .export ? "Export…" : "Import"), action: submit)
                    .keyboardShortcut(.defaultAction)
                    .disabled(problem != nil || isWorking)
            }
            .padding(.top, 4)
        }
        .textFieldStyle(.roundedBorder)
        .padding(20)
        .frame(width: 400)
        .onAppear { focused = true }
    }

    private func submit() {
        guard problem == nil, !isWorking else { return }
        isWorking = true
        error = nil
        Task {
            do {
                if try await action(password) { dismiss() }
            } catch {
                self.error = error.localizedDescription
            }
            isWorking = false
        }
    }
}
