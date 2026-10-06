import SwiftUI

/// Create or edit a login. Only the title is required.
struct LoginEditorView: View {
    let onSave: (LoginItem) throws -> Void
    private let isNew: Bool

    @Environment(\.dismiss) private var dismiss
    @State private var draft: LoginItem
    @State private var saveError: String?

    init(item: LoginItem?, onSave: @escaping (LoginItem) throws -> Void) {
        self.onSave = onSave
        self.isNew = item == nil
        var draft = item ?? LoginItem()
        if draft.websites.isEmpty { draft.websites = [""] }
        _draft = State(initialValue: draft)
    }

    var body: some View {
        Form {
            Section {
                LabeledContent("Service") {
                    ServicePickerButton(selected: KnownService.matching(draft), onPick: apply)
                }
                // An empty title is already signalled by the asterisk and the
                // disabled Save button; only call out whitespace-only titles.
                LabeledField(error: !draft.title.isEmpty && draft.title.trimmed.isEmpty ? "Title can't be only spaces." : nil) {
                    Text("Title \(Text("*").foregroundStyle(.red))")
                } field: {
                    TextField("Title", text: $draft.title, prompt: Text("e.g. GitHub"))
                }
            } footer: {
                Text("Fields marked \(Text("*").foregroundStyle(.red)) are required. Everything else is optional.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            // No textContentType on these: it makes macOS offer its own
            // Passwords autofill inside this password manager.
            Section("Credentials") {
                LabeledField("Email", error: LoginValidation.emailError(draft.email)) {
                    TextField("Email", text: $draft.email, prompt: Text("name@example.com"))
                }
                LabeledField("Username") {
                    TextField("Username", text: $draft.username, prompt: Text(""))
                }
                LabeledField("Password") {
                    RevealableField("Password", text: $draft.password)
                }
                LabeledField("2FA secret (TOTP)", error: LoginValidation.totpError(draft.totpSecret)) {
                    RevealableField("2FA secret (TOTP)", text: $draft.totpSecret, prompt: "Setup key or otpauth:// link")
                }
            }

            Section("Websites") {
                ForEach($draft.websites.indices, id: \.self) { index in
                    LabeledField(error: LoginValidation.websiteError(draft.websites[index])) {
                        EmptyView()
                    } field: {
                        HStack {
                            TextField("Website", text: $draft.websites[index], prompt: Text("example.com"))
                            if draft.websites.count > 1 {
                                Button("Remove website", systemImage: "minus.circle.fill") {
                                    draft.websites.remove(at: index)
                                }
                                .labelStyle(.iconOnly)
                                .buttonStyle(.borderless)
                                .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                Button("Add Website", systemImage: "plus") { draft.websites.append("") }
                    .buttonStyle(.borderless)
            }

            Section {
                SignInMethodsEditor(signIns: $draft.signIns, loginID: draft.id)
            } header: {
                Text("Sign-in Methods")
            } footer: {
                Text("Add every way into this account, e.g. Sign in with Google and a password, and pick which Google account it uses.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("Account Details") {
                LabeledField("Phone number") {
                    TextField("Phone number", text: $draft.phoneNumber, prompt: Text("e.g. +213 555 12 34 56"))
                }
                LabeledField("PIN") {
                    RevealableField("PIN", text: $draft.pin)
                }
            }

            Section("Note") {
                TextEditor(text: $draft.note)
                    .frame(minHeight: 70)
                    .font(.body)
                    .scrollContentBackground(.hidden)
            }

            Section("Custom Fields") {
                ForEach($draft.customFields) { $field in
                    CustomFieldEditor(field: $field) {
                        draft.customFields.removeAll { $0.id == field.id }
                    }
                }
                Menu("Add Field", systemImage: "plus") {
                    ForEach(CustomField.Kind.allCases) { kind in
                        Button(kind.displayName, systemImage: kind.systemImage) {
                            draft.customFields.append(CustomField(kind: kind))
                        }
                    }
                }
                .menuStyle(.borderlessButton)
                .fixedSize()
            }
        }
        .formStyle(.grouped)
        .frame(minWidth: 520, idealWidth: 560, minHeight: 420, idealHeight: 560)
        .navigationTitle(isNew ? "New Login" : "Edit Login")
        .alert("Couldn't Save", isPresented: Binding(get: { saveError != nil }, set: { if !$0 { saveError = nil } })) {
        } message: {
            Text("\(saveError ?? "") Your changes are still here; try again.")
        }
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") {
                    do {
                        try onSave(draft.cleanedForSaving())
                        dismiss()
                    } catch {
                        saveError = error.localizedDescription
                    }
                }
                .disabled(!draft.validationErrors.isEmpty)
                .help(draft.validationErrors.first ?? "Save login")
            }
        }
    }

    /// Switches the draft to the picked service (or to none), replacing the
    /// previous one; a title the user typed is kept.
    private func apply(_ service: KnownService?) {
        draft = draft.changingService(to: service)
        if draft.websites.isEmpty { draft.websites = [""] }
    }
}

private struct CustomFieldEditor: View {
    @Binding var field: CustomField
    let onRemove: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Label(field.kind.displayName, systemImage: field.kind.systemImage)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Button("Remove field", systemImage: "minus.circle.fill", action: onRemove)
                    .labelStyle(.iconOnly)
                    .buttonStyle(.borderless)
                    .foregroundStyle(.secondary)
            }
            LabeledField("Name", error: field.name.trimmed.isEmpty ? "Field name is required." : nil) {
                TextField("Field name", text: $field.name, prompt: Text("e.g. Recovery email"))
            }
            LabeledField("Value", error: field.kind == .totp ? LoginValidation.totpError(field.value) : nil) {
                switch field.kind {
                case .text:
                    TextField("Value", text: $field.value, prompt: Text(""))
                case .hidden, .totp:
                    RevealableField("Value", text: $field.value, prompt: field.kind == .totp ? "Setup key or otpauth:// link" : "")
                case .date:
                    DatePicker("Date", selection: $field.date, displayedComponents: .date)
                }
            }
        }
        .padding(.vertical, 2)
    }
}
