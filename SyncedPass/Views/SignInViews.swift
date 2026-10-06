import SwiftUI

/// Rows for editing a login's sign-in methods, each provider with the
/// account it uses.
struct SignInMethodsEditor: View {
    @Binding var signIns: [SignIn]
    /// The login being edited, so it isn't offered as its own account.
    let loginID: LoginItem.ID

    private var available: [SignInMethod] {
        SignInMethod.selectable.filter { method in !signIns.contains { $0.method == method } }
    }

    var body: some View {
        ForEach($signIns, id: \.method) { $signIn in
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    SignInMethodIcon(method: signIn.method)
                    Text(signIn.method.displayName)
                    Spacer()
                    Button("Remove sign-in method", systemImage: "minus.circle.fill") {
                        signIns.removeAll { $0.method == signIn.method }
                    }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.borderless)
                    .foregroundStyle(.secondary)
                }
                if signIn.method.isProvider {
                    AccountPicker(method: signIn.method, selection: $signIn.accountID, excluding: loginID)
                }
            }
            .padding(.vertical, 2)
        }
        if !available.isEmpty {
            Menu("Add Sign-in Method", systemImage: "plus") {
                ForEach(available) { method in
                    Button {
                        signIns.append(SignIn(method: method))
                    } label: {
                        Label { Text(method.displayName) } icon: { method.menuIcon }
                    }
                }
            }
            .menuStyle(.borderlessButton)
            .fixedSize()
        }
    }
}

/// Chooses which saved login holds the account used for a provider.
struct AccountPicker: View {
    let method: SignInMethod
    @Binding var selection: LoginItem.ID?
    var excluding: LoginItem.ID?

    @Environment(VaultStore.self) private var store

    var body: some View {
        let candidates = store.accountCandidates(for: method, excluding: excluding)
        Picker("\(method.providerName) account", selection: $selection) {
            Text("Not specified").tag(LoginItem.ID?.none)
            // Keep showing an existing link even if that login no longer
            // looks like an account (renamed, or deduplicated away).
            if let selection, !candidates.contains(where: { $0.id == selection }) {
                Text(store.item(selection)?.accountChoiceLabel ?? "Deleted login").tag(Optional(selection))
            }
            if !candidates.isEmpty {
                Divider()
            }
            ForEach(candidates) { item in
                Text(item.accountChoiceLabel).tag(Optional(item.id))
            }
        }
        .help(candidates.isEmpty
              ? "No \(method.providerName) accounts saved yet. Save the account itself as a login (e.g. “Gmail”) to link it here."
              : "Your saved \(method.providerName) accounts, one per address")
    }
}

/// How a login's sign-in methods read in its details, with links to the
/// accounts they use.
struct SignInMethodsSummary: View {
    let signIns: [SignIn]
    let onOpen: (LoginItem.ID) -> Void

    @Environment(VaultStore.self) private var store

    var body: some View {
        ForEach(signIns, id: \.method) { signIn in
            LabeledContent {
                if let account = store.item(signIn.accountID) {
                    Button(account.accountLabel) { onOpen(account.id) }
                        .buttonStyle(.link)
                        .help("Show \(account.title)")
                } else if signIn.accountID != nil {
                    Text("Deleted login").foregroundStyle(.secondary)
                }
            } label: {
                HStack(spacing: 6) {
                    SignInMethodIcon(method: signIn.method)
                    Text(signIn.method.displayName)
                }
            }
        }
    }
}

/// Previews splitting "easyEDA, Flippa, Notion, Ling" into one login per
/// service, optionally linking them all to an account.
struct SplitLoginSheet: View {
    let item: LoginItem
    let onDone: ([LoginItem]) -> Void

    @Environment(VaultStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var accountID: LoginItem.ID?
    @State private var error: String?

    init(item: LoginItem, onDone: @escaping ([LoginItem]) -> Void) {
        self.item = item
        self.onDone = onDone
        _accountID = State(initialValue: item.signIns.first { $0.method.isProvider }?.accountID)
    }

    private var provider: SignInMethod? {
        item.signIns.first { $0.method.isProvider }?.method
    }

    var body: some View {
        let parts = LoginSplit.makeItems(from: item, accountID: .some(accountID))
        Form {
            Section {
                Text("“\(item.title)” becomes \(parts.count) separate logins. Every other detail (sign-in methods, email, password, notes) is copied to each one.")
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let provider {
                Section {
                    AccountPicker(method: provider, selection: $accountID, excluding: item.id)
                } footer: {
                    Text("The \(provider.providerName) account these services sign in with.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Section("New Logins") {
                ForEach(parts) { part in
                    HStack(spacing: 10) {
                        ServiceIcon(item: part, size: 24)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(part.title)
                            Text(part.websites.first ?? "No website")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            if let error {
                FieldError(error)
            }
        }
        .formStyle(.grouped)
        .frame(minWidth: 440, idealWidth: 480, minHeight: 360, idealHeight: 480)
        .navigationTitle("Split Login")
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Split into \(parts.count) Logins") {
                    do {
                        try store.replace(item.id, with: parts)
                        onDone(parts)
                        dismiss()
                    } catch {
                        self.error = error.localizedDescription
                    }
                }
            }
        }
    }
}
