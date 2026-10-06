import AppKit
import SwiftUI

/// Chooses between first-run setup, the lock screen and the vault, and locks
/// automatically when the Mac sleeps or the screen locks.
struct RootView: View {
    @Environment(VaultStore.self) private var store

    var body: some View {
        Group {
            switch store.status {
            case .needsSetup: SetupView()
            case .locked: UnlockView()
            case .unlocked: ContentView()
            }
        }
        .onReceive(NSWorkspace.shared.notificationCenter.publisher(for: NSWorkspace.willSleepNotification)) { _ in
            store.lock()
        }
        .onReceive(NSWorkspace.shared.notificationCenter.publisher(for: NSWorkspace.screensDidSleepNotification)) { _ in
            store.lock()
        }
        .onReceive(DistributedNotificationCenter.default().publisher(for: .init("com.apple.screenIsLocked"))) { _ in
            store.lock()
        }
    }
}

private struct SetupView: View {
    @Environment(VaultStore.self) private var store
    @State private var password = ""
    @State private var confirmation = ""
    @State private var isWorking = false
    @State private var error: String?
    @FocusState private var focused: Bool

    private var problem: String? {
        if password.count < VaultCrypto.minimumPasswordLength {
            return "Use at least \(VaultCrypto.minimumPasswordLength) characters."
        }
        if confirmation != password { return "The passwords don't match." }
        return nil
    }

    var body: some View {
        LockScreen(
            title: "Create Your Vault",
            message: "Choose a master password. It encrypts everything you save in SyncedPass."
        ) {
            SecureField("Master password", text: $password)
                .focused($focused)
            SecureField("Confirm master password", text: $confirmation)
                .onSubmit(create)

            Label {
                Text("There's no way to recover a forgotten master password. If you lose it, your saved logins can't be decrypted. Export a backup regularly.")
            } icon: {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
            }
            .font(.callout)
            .fixedSize(horizontal: false, vertical: true)

            if let message = error ?? (confirmation.isEmpty ? nil : problem) {
                FieldError(message)
            }

            Button(action: create) {
                Text(isWorking ? "Creating…" : "Create Vault").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .keyboardShortcut(.defaultAction)
            .disabled(problem != nil || isWorking)
        }
        .defaultFocus($focused, true)
        // Set again on the next turn of the run loop: when locking with ⌘L the
        // list that had focus is removed in the same update and swallows it.
        .onAppear { DispatchQueue.main.async { focused = true } }
    }

    private func create() {
        guard problem == nil, !isWorking else { return }
        isWorking = true
        error = nil
        Task {
            do {
                try await store.create(masterPassword: password)
            } catch {
                self.error = error.localizedDescription
            }
            isWorking = false
        }
    }
}

private struct UnlockView: View {
    @Environment(VaultStore.self) private var store
    @State private var password = ""
    @State private var isWorking = false
    @State private var error: String?
    @FocusState private var focused: Bool

    var body: some View {
        LockScreen(title: "SyncedPass Is Locked", message: "Enter your master password to unlock.") {
            SecureField("Master password", text: $password)
                .focused($focused)
                .onSubmit(unlock)

            if let error {
                FieldError(error)
            }

            Button(action: unlock) {
                Text(isWorking ? "Unlocking…" : "Unlock").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .keyboardShortcut(.defaultAction)
            .disabled(password.isEmpty || isWorking)
        }
        .defaultFocus($focused, true)
        // Set again on the next turn of the run loop: when locking with ⌘L the
        // list that had focus is removed in the same update and swallows it.
        .onAppear { DispatchQueue.main.async { focused = true } }
    }

    private func unlock() {
        guard !password.isEmpty, !isWorking else { return }
        isWorking = true
        error = nil
        Task {
            do {
                try await store.unlock(masterPassword: password)
            } catch {
                self.error = error.localizedDescription
                password = ""
                focused = true
            }
            isWorking = false
        }
    }
}

private struct LockScreen<Content: View>: View {
    let title: String
    let message: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "lock.shield.fill")
                .font(.system(size: 52))
                .foregroundStyle(.tint)
            Text(title)
                .font(.title.weight(.semibold))
            Text(message)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            VStack(alignment: .leading, spacing: 12) {
                content
            }
            .textFieldStyle(.roundedBorder)
            .padding(.top, 6)
        }
        .frame(maxWidth: 360)
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
