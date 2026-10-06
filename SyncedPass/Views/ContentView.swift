import SwiftUI

struct ContentView: View {
    @Environment(VaultStore.self) private var store
    @State private var selection: Set<LoginItem.ID> = []
    /// Logins waiting for the user to confirm deletion.
    @State private var pendingDeletion: Set<LoginItem.ID> = []
    @State private var searchText = ""
    @State private var isCreating = false
    @State private var isExporting = false
    @State private var isImporting = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationSplitView {
            List(store.items(matching: searchText), selection: $selection) { item in
                LoginRow(item: item)
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
                } else if store.items(matching: searchText).isEmpty {
                    ContentUnavailableView.search(text: searchText)
                }
            }
            .searchable(text: $searchText, placement: .sidebar, prompt: "Search logins")
            .onChange(of: searchText) {
                // Never keep logins selected that the search has hidden, so a
                // delete only ever touches logins the user can see.
                let visible = Set(store.items(matching: searchText).map(\.id))
                selection.formIntersection(visible)
            }
            .navigationSplitViewColumnWidth(min: 240, ideal: 280)
        } detail: {
            let selectedItems = store.items(matching: "").filter { selection.contains($0.id) }
            switch selectedItems.count {
            case 0:
                ContentUnavailableView("No Login Selected", systemImage: "key.horizontal")
            case 1:
                LoginDetailView(item: selectedItems[0]) { pendingDeletion = [selectedItems[0].id] }
            default:
                MultipleSelectionView(items: selectedItems) { pendingDeletion = Set(selectedItems.map(\.id)) }
            }
        }
        .navigationTitle("SyncedPass")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("New Login", systemImage: "plus") { isCreating = true }
                    .keyboardShortcut("n")
                    .help("New login (⌘N)")
            }
            ToolbarItem(placement: .primaryAction) {
                Menu("Backup", systemImage: "externaldrive") {
                    Button("Export Backup…", systemImage: "square.and.arrow.up") { isExporting = true }
                        .disabled(store.items.isEmpty)
                    Button("Import…", systemImage: "square.and.arrow.down") { isImporting = true }
                }
                .help("Export or import a backup")
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
            Text("This can't be undone.")
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

    var body: some View {
        HStack(spacing: 10) {
            ServiceIcon(item: item)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title)
                    .lineLimit(1)
                if !item.subtitle.isEmpty {
                    Text(item.subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
        }
        .padding(.vertical, 2)
    }
}

