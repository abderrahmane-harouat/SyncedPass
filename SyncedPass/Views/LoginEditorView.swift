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
                TextField(text: $draft.title, prompt: Text("Required")) {
                    Text("Title \(Text("*").foregroundStyle(.red))")
                }
                // An empty title is already signalled by the asterisk and the
                // disabled Save button; only call out whitespace-only titles.
                FieldError(!draft.title.isEmpty && draft.title.trimmed.isEmpty ? "Title can't be only spaces." : nil)
            } footer: {
                Text("Fields marked \(Text("*").foregroundStyle(.red)) are required. Everything else is optional.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("Credentials") {
                TextField("Email", text: $draft.email, prompt: Text("Optional"))
                    .textContentType(.emailAddress)
                FieldError(LoginValidation.emailError(draft.email))

                TextField("Username", text: $draft.username, prompt: Text("Optional"))
                    .textContentType(.username)

                RevealableField("Password", text: $draft.password)
                    .textContentType(.password)

                RevealableField("2FA secret (TOTP)", text: $draft.totpSecret)
                FieldError(LoginValidation.totpError(draft.totpSecret))
            }

            Section("Websites") {
                ForEach($draft.websites.indices, id: \.self) { index in
                    HStack {
                        TextField("Website", text: $draft.websites[index], prompt: Text("example.com (optional)"))
                            .labelsHidden()
                        if draft.websites.count > 1 {
                            Button("Remove website", systemImage: "minus.circle.fill") {
                                draft.websites.remove(at: index)
                            }
                            .labelStyle(.iconOnly)
                            .buttonStyle(.borderless)
                            .foregroundStyle(.secondary)
                        }
                    }
                    FieldError(LoginValidation.websiteError(draft.websites[index]))
                }
                Button("Add Website", systemImage: "plus") { draft.websites.append("") }
                    .buttonStyle(.borderless)
            }

            Section("Account Details") {
                Picker("Sign-in method", selection: $draft.signInMethod) {
                    ForEach(SignInMethod.allCases) { method in
                        Label {
                            Text(method.displayName)
                        } icon: {
                            method.menuIcon
                        }
                        .tag(method)
                    }
                }
                TextField("Phone number", text: $draft.phoneNumber, prompt: Text("Optional"))
                    .textContentType(.telephoneNumber)
                RevealableField("PIN", text: $draft.pin)
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

    /// Fills in what's still empty from the picked service; never
    /// overwrites a title the user typed.
    private func apply(_ service: KnownService) {
        if draft.title.trimmed.isEmpty { draft.title = service.name }
        let alreadyListed = draft.websites.contains { KnownService.matching(website: $0) == service }
        if !alreadyListed {
            if let empty = draft.websites.firstIndex(where: { $0.trimmed.isEmpty }) {
                draft.websites[empty] = service.website
            } else {
                draft.websites.append(service.website)
            }
        }
    }
}

private struct CustomFieldEditor: View {
    @Binding var field: CustomField
    let onRemove: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Image(systemName: field.kind.systemImage)
                    .foregroundStyle(.secondary)
                    .help(field.kind.displayName)
                TextField("Field name", text: $field.name, prompt: Text("Field name (required)"))
                    .labelsHidden()
                    .fontWeight(.medium)
                Button("Remove field", systemImage: "minus.circle.fill", action: onRemove)
                    .labelStyle(.iconOnly)
                    .buttonStyle(.borderless)
                    .foregroundStyle(.secondary)
            }
            switch field.kind {
            case .text:
                TextField("Value", text: $field.value, prompt: Text("Value"))
                    .labelsHidden()
            case .hidden, .totp:
                RevealableField("Value", text: $field.value, prompt: field.kind == .totp ? "Setup key or otpauth:// link" : "Value")
                    .labelsHidden()
            case .date:
                DatePicker("Date", selection: $field.date, displayedComponents: .date)
                    .labelsHidden()
            }
            if field.name.trimmed.isEmpty {
                FieldError("Field name is required.")
            }
            if field.kind == .totp {
                FieldError(LoginValidation.totpError(field.value))
            }
        }
        .padding(.vertical, 2)
    }
}
