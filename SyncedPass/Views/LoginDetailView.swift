import AppKit
import SwiftUI

/// Read-only view of a login. Empty optional fields are hidden.
struct LoginDetailView: View {
    let item: LoginItem
    let onDelete: () -> Void
    /// Shows another login (a linked account, or one that signs in with this one).
    let onOpen: (LoginItem.ID) -> Void

    @Environment(VaultStore.self) private var store
    @State private var isEditing = false
    @State private var isSplitting = false

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
            let splitParts = LoginSplit.parts(of: item.title)
            if !splitParts.isEmpty {
                Section {
                    HStack {
                        Label("This login lists \(splitParts.count) services. Separate logins are easier to find and get their own icon.",
                              systemImage: "rectangle.split.3x1")
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                        Spacer()
                        Button("Split into \(splitParts.count) Logins…") { isSplitting = true }
                    }
                }
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
            if !item.signIns.isEmpty {
                Section("Sign-in Methods") {
                    SignInMethodsSummary(signIns: item.signIns, onOpen: onOpen)
                }
            }
            let usedBy = store.logins(signingInWith: item.id)
            if !usedBy.isEmpty {
                Section("Used to Sign In To (\(usedBy.count))") {
                    ForEach(usedBy) { login in
                        Button {
                            onOpen(login.id)
                        } label: {
                            HStack(spacing: 10) {
                                ServiceIcon(item: login, size: 22)
                                Text(login.title)
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .foregroundStyle(.tertiary)
                            }
                            .contentShape(.rect)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            if !item.phoneNumber.isEmpty || !item.pin.isEmpty {
                Section("Account Details") {
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
                if !LoginSplit.parts(of: item.title).isEmpty {
                    Button("Split", systemImage: "rectangle.split.3x1") { isSplitting = true }
                        .help("Split into one login per service")
                }
                Button("Edit", systemImage: "pencil") { isEditing = true }
                    .keyboardShortcut("e")
                Button("Delete", systemImage: "trash", role: .destructive, action: onDelete)
            }
        }
        .sheet(isPresented: $isEditing) {
            LoginEditorView(item: item) { try store.save($0) }
        }
        .sheet(isPresented: $isSplitting) {
            SplitLoginSheet(item: item) { parts in
                if let first = parts.first { onOpen(first.id) }
            }
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
