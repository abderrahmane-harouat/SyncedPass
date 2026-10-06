import AppKit
import SwiftUI

/// Read-only view of a login. Empty optional fields are hidden.
struct LoginDetailView: View {
    let item: LoginItem
    let onDelete: () -> Void

    @Environment(VaultStore.self) private var store
    @State private var isEditing = false

    var body: some View {
        Form {
            Section {
                HStack(spacing: 14) {
                    ServiceIcon(item: item, size: 48)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.title)
                            .font(.title2.weight(.semibold))
                            .textSelection(.enabled)
                        if let service = KnownService.matching(item), service.name != item.title {
                            Text(service.name)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .padding(.vertical, 4)
            }
            // An all-empty section still draws an empty card and throws off
            // the next section's header, so only show it when it has rows.
            if ![item.email, item.username, item.password, item.totpSecret].allSatisfy(\.isEmpty) {
                Section {
                    DetailRow("Email", value: item.email)
                    DetailRow("Username", value: item.username)
                    DetailRow("Password", value: item.password, isSecret: true)
                    DetailRow("2FA secret (TOTP)", value: item.totpSecret, isSecret: true)
                }
            }
            if !item.websites.isEmpty {
                Section("Websites") {
                    ForEach(item.websites, id: \.self) { website in
                        if let url = URL(string: website) {
                            Link(website, destination: url)
                        }
                    }
                }
            }
            if item.signInMethod != .notSet || !item.phoneNumber.isEmpty || !item.pin.isEmpty {
                Section("Account Details") {
                    if item.signInMethod != .notSet {
                        LabeledContent("Sign-in method") {
                            HStack(spacing: 6) {
                                SignInMethodIcon(method: item.signInMethod)
                                Text(item.signInMethod.displayName)
                            }
                        }
                    }
                    DetailRow("Phone number", value: item.phoneNumber)
                    DetailRow("PIN", value: item.pin, isSecret: true)
                }
            }
            if !item.note.trimmed.isEmpty {
                Section("Note") {
                    Text(item.note).textSelection(.enabled)
                }
            }
            if !item.customFields.isEmpty {
                Section("Custom Fields") {
                    ForEach(item.customFields) { field in
                        switch field.kind {
                        case .date:
                            LabeledContent(field.name, value: field.date, format: .dateTime.day().month().year())
                        default:
                            DetailRow(field.name, value: field.value, isSecret: field.kind != .text)
                        }
                    }
                }
            }
            Section {
                LabeledContent("Created", value: item.createdAt, format: .dateTime)
                LabeledContent("Modified", value: item.modifiedAt, format: .dateTime)
            }
            .foregroundStyle(.secondary)
        }
        .formStyle(.grouped)
        .navigationTitle(item.title)
        .toolbar {
            ToolbarItemGroup {
                Button("Edit", systemImage: "pencil") { isEditing = true }
                    .keyboardShortcut("e")
                Button("Delete", systemImage: "trash", role: .destructive, action: onDelete)
            }
        }
        .sheet(isPresented: $isEditing) {
            LoginEditorView(item: item) { try store.save($0) }
        }
    }
}

/// A labeled value with a copy button; secrets are masked until revealed.
private struct DetailRow: View {
    let title: String
    let value: String
    let isSecret: Bool
    @State private var isRevealed = false

    init(_ title: String, value: String, isSecret: Bool = false) {
        self.title = title
        self.value = value
        self.isSecret = isSecret
    }

    var body: some View {
        if !value.isEmpty {
            LabeledContent(title) {
                HStack {
                    Text(isSecret && !isRevealed ? String(repeating: "•", count: 10) : value)
                        .fontDesign(isSecret ? .monospaced : nil)
                        .textSelection(.enabled)
                    if isSecret {
                        Button(isRevealed ? "Hide" : "Show", systemImage: isRevealed ? "eye.slash" : "eye") {
                            isRevealed.toggle()
                        }
                        .labelStyle(.iconOnly)
                        .buttonStyle(.borderless)
                    }
                    Button("Copy \(title)", systemImage: "doc.on.doc") {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(value, forType: .string)
                    }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.borderless)
                    .help("Copy \(title)")
                }
            }
        }
    }
}
