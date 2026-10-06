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
            LabeledField("Master password") {
                RevealableField("Master password", text: $password, focus: $focused)
            }
            LabeledField("Confirm master password") {
                RevealableField("Confirm master password", text: $confirmation)
                    .onSubmit(create)
            }

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
            LabeledField("Master password") {
                RevealableField("Master password", text: $password, focus: $focused)
                    .onSubmit(unlock)
            }

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
            AppIconImage(size: 112)
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

/// Asks for the current master password and a new one (twice), then shows
/// an animated confirmation in place of the form.
struct ChangeMasterPasswordSheet: View {
    /// Called when the user chooses to export a backup from the success screen.
    let onExportBackup: () -> Void

    @Environment(VaultStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var current = ""
    @State private var new = ""
    @State private var confirmation = ""
    @State private var isWorking = false
    @State private var error: String?
    @State private var succeeded = false
    @FocusState private var focused: Bool

    private var problem: String? {
        if current.isEmpty { return "" }
        if new.count < VaultCrypto.minimumPasswordLength {
            return "Use at least \(VaultCrypto.minimumPasswordLength) characters."
        }
        if new == current { return "The new password is the same as the current one." }
        return confirmation == new ? nil : "The new passwords don't match."
    }

    var body: some View {
        Group {
            // One after the other, not a cross-fade: the form fades out
            // quickly, then the success screen fades in, so the two never
            // overlap.
            if succeeded {
                successView
                    .transition(.asymmetric(
                        insertion: .opacity.animation(.easeOut(duration: 0.2).delay(0.12)),
                        removal: .identity))
            } else {
                form
                    .transition(.asymmetric(
                        insertion: .identity,
                        removal: .opacity.animation(.easeIn(duration: 0.12))))
            }
        }
        .padding(20)
        .frame(width: 420)
    }

    private var form: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Change Master Password")
                .font(.title3.weight(.semibold))
            Text("Your vault is re-encrypted with a new key. Backups you exported before keep the password you chose when exporting them.")
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            LabeledField("Current master password") {
                RevealableField("Current master password", text: $current, focus: $focused)
            }
            LabeledField("New master password") {
                RevealableField("New master password", text: $new)
            }
            LabeledField("Confirm new master password") {
                RevealableField("Confirm new master password", text: $confirmation)
                    .onSubmit(change)
            }

            Label {
                Text("There's no way to recover a forgotten master password. Export a backup after changing it.")
            } icon: {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
            }
            .font(.callout)
            .fixedSize(horizontal: false, vertical: true)

            if let message = error ?? (confirmation.isEmpty ? nil : problem), !message.isEmpty {
                FieldError(message)
            }

            HStack {
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button(isWorking ? "Changing…" : "Change Password", action: change)
                    .keyboardShortcut(.defaultAction)
                    .disabled(problem != nil || isWorking)
            }
            .padding(.top, 4)
        }
        .defaultFocus($focused, true)
        .onAppear { DispatchQueue.main.async { focused = true } }
    }

    private var successView: some View {
        VStack(spacing: 14) {
            SuccessCheckmark()
                .padding(.top, 8)
            Text("Master Password Changed")
                .font(.title2.weight(.semibold))
            Text("Use your new password the next time you unlock. Export a fresh backup now so you have one protected by a password you'll remember.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Button("Export Backup…") {
                    dismiss()
                    onExportBackup()
                }
                Button("Done") { dismiss() }
                    .keyboardShortcut(.defaultAction)
            }
            .padding(.top, 6)
        }
        .frame(maxWidth: .infinity)
    }

    private func change() {
        guard problem == nil, !isWorking else { return }
        isWorking = true
        error = nil
        Task {
            do {
                try await store.changeMasterPassword(current: current, new: new)
                withAnimation { succeeded = true }
                // Don't keep the passwords around once they're no longer
                // needed; wait until the form has faded out so the fields
                // aren't seen emptying.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                    current = ""
                    new = ""
                    confirmation = ""
                }
            } catch {
                self.error = error.localizedDescription
                if case VaultError.wrongPassword = error {
                    current = ""
                    focused = true
                }
            }
            isWorking = false
        }
    }
}

/// A green circle that draws itself, then a checkmark drawn inside it with a
/// spring "pop", plus a light trackpad tap. With Reduce Motion on, it fades
/// in instead (Apple's guidance allows fades there; it's movement and scaling
/// that people turn the setting on to avoid).
struct SuccessCheckmark: View {
    var size: CGFloat = 96

    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    @State private var ring: CGFloat = 0
    @State private var check: CGFloat = 0
    @State private var scale: CGFloat = 0.6
    @State private var opacity: Double = 1

    private var reduceMotion: Bool {
        #if DEBUG
        // Lets UI tests see the full animation without changing system settings.
        if ProcessInfo.processInfo.environment["SYNCEDPASS_FORCE_MOTION"] != nil { return false }
        #endif
        return systemReduceMotion
    }

    var body: some View {
        ZStack {
            Circle()
                .fill(Color.green.opacity(0.14))
                .scaleEffect(scale)
            Circle()
                .trim(from: 0, to: ring)
                .stroke(Color.green, style: StrokeStyle(lineWidth: size * 0.055, lineCap: .round))
                .rotationEffect(.degrees(-90))
            CheckmarkShape()
                .trim(from: 0, to: check)
                .stroke(Color.green, style: StrokeStyle(lineWidth: size * 0.07, lineCap: .round, lineJoin: .round))
                .padding(size * 0.27)
        }
        .frame(width: size, height: size)
        .opacity(opacity)
        .accessibilityElement()
        .accessibilityLabel("Success")
        .onAppear {
            // Starts after the success screen has faded in.
            let start = 0.2
            guard !reduceMotion else {
                ring = 1; check = 1; scale = 1
                opacity = 0
                withAnimation(.easeIn(duration: 0.5).delay(start)) { opacity = 1 }
                return
            }
            withAnimation(.easeOut(duration: 0.45).delay(start)) { ring = 1 }
            withAnimation(.spring(response: 0.45, dampingFraction: 0.55).delay(start)) { scale = 1 }
            withAnimation(.easeOut(duration: 0.3).delay(start + 0.4)) { check = 1 }
            DispatchQueue.main.asyncAfter(deadline: .now() + start + 0.45) {
                NSHapticFeedbackManager.defaultPerformer.perform(.generic, performanceTime: .now)
            }
        }
    }
}

private struct CheckmarkShape: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.minX + rect.width * 0.08, y: rect.minY + rect.height * 0.52))
        path.addLine(to: CGPoint(x: rect.minX + rect.width * 0.38, y: rect.minY + rect.height * 0.80))
        path.addLine(to: CGPoint(x: rect.minX + rect.width * 0.92, y: rect.minY + rect.height * 0.22))
        return path
    }
}
