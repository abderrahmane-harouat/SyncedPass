import SwiftUI

struct ContentView: View {
    @Environment(VaultStore.self) private var store
    @State private var selection: Set<LoginItem.ID> = []
    /// Logins waiting for the user to confirm deletion.
    @State private var pendingDeletion: Set<LoginItem.ID> = []
    @State private var searchText = ""
    @State private var filter: LoginFilter = .all
    @State private var isCreating = false
    @State private var isExporting = false
    @State private var isImporting = false
    @State private var isChangingPassword = false
    @State private var exportAfterPasswordChange = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationSplitView {
            // The filter sits above the list rather than in an overlay, so
            // rows scroll beneath the bar's edge instead of showing through it.
            VStack(spacing: 0) {
                FilterPicker(filter: $filter, items: store.items)
                loginList(visibleResults)
            }
            .searchable(text: $searchText, placement: .sidebar, prompt: "Search logins")
            .onChange(of: searchText) { dropHiddenSelection() }
            .onChange(of: filter) { dropHiddenSelection() }
            .navigationSplitViewColumnWidth(min: 240, ideal: 280)
        } detail: {
            let selectedItems = store.items(matching: "").filter { selection.contains($0.id) }
            switch selectedItems.count {
            case 0:
                ContentUnavailableView("No Login Selected", systemImage: "key.horizontal")
            case 1:
                LoginDetailView(item: selectedItems[0], onDelete: { pendingDeletion = [selectedItems[0].id] }, onOpen: open)
            default:
                MultipleSelectionView(items: selectedItems) { pendingDeletion = Set(selectedItems.map(\.id)) }
            }
        }
        .navigationTitle("SyncedPass")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("New Login", systemImage: "plus") { isCreating = true }
                    .help("New login (⌘N)")
            }
            ToolbarItem(placement: .primaryAction) {
                Menu("More", systemImage: "ellipsis.circle") {
                    Button("Export Backup…", systemImage: "square.and.arrow.up") { isExporting = true }
                        .disabled(store.items.isEmpty)
                    Button("Import…", systemImage: "square.and.arrow.down") { isImporting = true }
                    Divider()
                    Button("Change Master Password…", systemImage: "key.viewfinder") { isChangingPassword = true }
                }
                .help("Backups and master password")
            }
            ToolbarItem(placement: .primaryAction) {
                Button("Lock", systemImage: "lock") { store.lock() }
                    .keyboardShortcut("l")
                    .help("Lock (⌘L)")
            }
        }
        .sheet(isPresented: $isCreating) {
            LoginEditorView(item: nil) { item in
                try store.save(item)
                selection = [item.id]
            }
        }
        .focusedSceneValue(\.newLoginActions, NewLoginActions(
            newLogin: { isCreating = true },
            changeMasterPassword: { isChangingPassword = true }))
        .sheet(isPresented: $isChangingPassword, onDismiss: {
            // Open the export sheet only once this one has closed.
            if exportAfterPasswordChange {
                exportAfterPasswordChange = false
                isExporting = true
            }
        }) {
            ChangeMasterPasswordSheet { exportAfterPasswordChange = true }
        }
        .backupFlows(exportRequested: $isExporting, importRequested: $isImporting)
        .confirmationDialog(
            deletionTitle,
            isPresented: Binding(get: { !pendingDeletion.isEmpty }, set: { if !$0 { pendingDeletion = [] } }),
            titleVisibility: .visible
        ) {
            Button(pendingDeletion.count == 1 ? "Delete" : "Delete \(pendingDeletion.count) Logins", role: .destructive) {
                deletePending()
            }
        } message: {
            Text(deletionMessage)
        }
        .alert("Couldn't Save", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private var deletionTitle: String {
        if pendingDeletion.count == 1, let item = store.items.first(where: { pendingDeletion.contains($0.id) }) {
            return "Delete “\(item.title)”?"
        }
        return "Delete \(pendingDeletion.count) logins?"
    }

    private func loginList(_ results: LoginSearch.Results) -> some View {
        List(selection: $selection) {
            if searchText.trimmed.isEmpty {
                ForEach(results.topMatches) { LoginRow(item: $0) }
            } else {
                if !results.topMatches.isEmpty {
                    Section("Top Matches") {
                        ForEach(results.topMatches) { LoginRow(item: $0) }
                    }
                }
                if !results.otherMatches.isEmpty {
                    Section("Other Matches") {
                        ForEach(results.otherMatches) { LoginRow(item: $0.item, matchedField: $0.field) }
                    }
                }
            }
        }
        .contextMenu(forSelectionType: LoginItem.ID.self) { ids in
            if !ids.isEmpty {
                Button(ids.count == 1 ? "Delete" : "Delete \(ids.count) Logins", systemImage: "trash", role: .destructive) {
                    pendingDeletion = ids
                }
            }
        }
        .onDeleteCommand { pendingDeletion = selection }
        .overlay {
            if store.items.isEmpty {
                ContentUnavailableView {
                    Label("No Passwords Yet", systemImage: "key")
                } description: {
                    Text("Click + to save your first login.")
                }
            } else if results.all.isEmpty {
                ContentUnavailableView.search(text: searchText)
            }
        }
    }

    /// Warns when a deleted login is the account others sign in with.
    private var deletionMessage: String {
        let dependents = store.items.filter { item in
            !pendingDeletion.contains(item.id) && pendingDeletion.contains { item.signsIn(with: $0) }
        }
        guard !dependents.isEmpty else { return "This can't be undone." }
        let names = dependents.prefix(5).map(\.title).joined(separator: ", ")
        let more = dependents.count > 5 ? " and \(dependents.count - 5) more" : ""
        return "\(dependents.count) other login\(dependents.count == 1 ? "" : "s") (\(names)\(more)) sign in with this account. They keep their sign-in method but lose the link. This can't be undone."
    }

    private var visibleResults: LoginSearch.Results {
        LoginSearch.search(store.items.filter(filter.matches), for: searchText)
    }

    /// Never keep logins selected that the search or filter has hidden, so a
    /// delete only ever touches logins the user can see.
    private func dropHiddenSelection() {
        selection.formIntersection(Set(visibleResults.all.map(\.id)))
    }

    /// Selects a login, clearing the search and filter if they hide it.
    private func open(_ id: LoginItem.ID) {
        if !visibleResults.all.contains(where: { $0.id == id }) {
            searchText = ""
            filter = .all
        }
        selection = [id]
    }

    private func deletePending() {
        let ids = pendingDeletion
        pendingDeletion = []
        do {
            try store.delete(ids: ids)
            selection.subtract(ids)
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

/// "Show: All Logins ▾" above the list, with a count for each choice.
private struct FilterPicker: View {
    @Binding var filter: LoginFilter
    let items: [LoginItem]

    private var providers: [SignInMethod] {
        SignInMethod.selectable.filter { method in
            method.isProvider && items.contains { LoginFilter.provider(method).matches($0) }
        }
    }

    private func count(_ filter: LoginFilter) -> Int {
        items.filter(filter.matches).count
    }

    /// Short form for the button, so it always fits the sidebar.
    private func shortTitle(_ filter: LoginFilter) -> String {
        switch filter {
        case .all: "All Logins (\(items.count))"
        case .withPassword: "Password (\(count(.withPassword)))"
        case .provider(let method): "\(method.providerName) (\(count(filter)))"
        }
    }

    private func title(_ filter: LoginFilter) -> String {
        switch filter {
        case .all: "All Logins (\(items.count))"
        case .withPassword: "With Password (\(count(.withPassword)))"
        case .provider(let method): "Sign in with \(method.providerName) (\(count(filter)))"
        }
    }

    // A Menu rather than a pop-up Picker: a pop-up button sizes itself to its
    // widest option ("Sign in with Microsoft (12)") and overflows a narrow
    // sidebar, while a menu's button fits its own label and truncates it.
    var body: some View {
        Menu {
            Picker("Show", selection: $filter) {
                Text(title(.all)).tag(LoginFilter.all)
                Label(title(.withPassword), systemImage: "key").tag(LoginFilter.withPassword)
                if !providers.isEmpty {
                    Divider()
                    ForEach(providers) { method in
                        Label { Text(title(.provider(method))) } icon: { method.menuIcon }
                            .tag(LoginFilter.provider(method))
                    }
                }
            }
            .pickerStyle(.inline)
            .labelsHidden()
        } label: {
            Label {
                Text(shortTitle(filter))
                    .lineLimit(1)
                    .truncationMode(.tail)
            } icon: {
                if case .provider(let method) = filter, let icon = method.menuIcon {
                    icon
                } else {
                    Image(systemName: filter == .all
                          ? "line.3.horizontal.decrease.circle"
                          : "line.3.horizontal.decrease.circle.fill")
                }
            }
        }
        .help("Show: \(title(filter))")
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .onChange(of: providers) {
            // The last login of a filtered provider was deleted or changed.
            if case .provider(let method) = filter, !providers.contains(method) { filter = .all }
        }
    }
}

/// Shown in the detail pane when more than one login is selected.
private struct MultipleSelectionView: View {
    let items: [LoginItem]
    let onDelete: () -> Void

    private let previewLimit = 8

    var body: some View {
        VStack(spacing: 16) {
            HStack(spacing: -10) {
                ForEach(items.prefix(5)) { item in
                    ServiceIcon(item: item, size: 44)
                        .shadow(color: .black.opacity(0.15), radius: 2, y: 1)
                }
            }
            Text("\(items.count) Logins Selected")
                .font(.title2.weight(.semibold))
            VStack(spacing: 4) {
                ForEach(items.prefix(previewLimit)) { item in
                    Text(item.title).lineLimit(1)
                }
                if items.count > previewLimit {
                    Text("and \(items.count - previewLimit) more")
                }
            }
            .foregroundStyle(.secondary)
            Button(role: .destructive, action: onDelete) {
                Label("Delete \(items.count) Logins", systemImage: "trash")
            }
            .controlSize(.large)
            Text("⌘-click or ⇧-click to change the selection, ⌘A to select all.")
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct LoginRow: View {
    let item: LoginItem
    /// Set for "other matches", to show why the login matched.
    var matchedField: LoginSearch.Field?

    private var subtitle: String {
        switch matchedField {
        case .email: "Email: \(item.email)"
        case .username: "Username: \(item.username)"
        case .note: "Matched in note"
        case nil: item.subtitle
        }
    }

    var body: some View {
        HStack(spacing: 10) {
            ServiceIcon(item: item)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title)
                    .lineLimit(1)
                if !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
        }
        .padding(.vertical, 2)
    }
}

