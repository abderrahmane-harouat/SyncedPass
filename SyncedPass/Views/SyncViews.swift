import SwiftUI

/// Paired phones and pairing a new one (docs/SYNC.md).
struct SyncSheet: View {
    @Environment(SyncService.self) private var sync
    @Environment(\.dismiss) private var dismiss
    @State private var peerToRemove: SyncPeer?
    @State private var error: String?

    var body: some View {
        VStack(spacing: 0) {
            if let pairing = sync.pairing {
                PairingView(state: pairing)
            } else {
                overview
            }
        }
        .frame(width: 460)
        .frame(minHeight: 340)
        .onDisappear {
            if sync.pairing != nil { sync.stopPairing() }
        }
        .confirmationDialog(
            "Unpair “\(peerToRemove?.name ?? "")”?",
            isPresented: Binding(get: { peerToRemove != nil }, set: { if !$0 { peerToRemove = nil } }),
            titleVisibility: .visible
        ) {
            Button("Unpair", role: .destructive) {
                if let peer = peerToRemove {
                    do { try sync.removePeer(peer.deviceID) } catch { self.error = error.localizedDescription }
                }
            }
        } message: {
            Text("It stops syncing with this Mac. Logins already on it stay there. To sync again, pair it again.")
        }
        .alert("Couldn't Unpair", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
        } message: {
            Text(error ?? "")
        }
    }

    private var overview: some View {
        VStack(spacing: 0) {
            Form {
                Section {
                    Text("Your logins sync with your paired phones over this network only, never through the internet, while SyncedPass is open and unlocked on both.")
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    LabeledContent("This Mac", value: sync.deviceName)
                    if let message = sync.networkError {
                        ListenerProblem(message: message)
                    }
                }
                Section("Paired Phones") {
                    if sync.peers.isEmpty {
                        Text("No phones paired yet.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(sync.peers) { peer in
                        HStack(spacing: 10) {
                            Image(systemName: "iphone")
                                .font(.title2)
                                .foregroundStyle(.secondary)
                                .frame(width: 24)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(peer.name)
                                PeerStatusText(status: sync.peerStatus[peer.deviceID])
                            }
                            Spacer()
                            Button("Unpair") { peerToRemove = peer }
                        }
                        .padding(.vertical, 2)
                    }
                }
            }
            .formStyle(.grouped)

            HStack {
                Button("Pair a Phone…", systemImage: "plus") { sync.startPairing() }
                Spacer()
                Button("Done") { dismiss() }
                    .keyboardShortcut(.defaultAction)
            }
            .padding(20)
        }
    }
}

private struct PeerStatusText: View {
    let status: SyncService.PeerStatus?

    var body: some View {
        Group {
            if let error = status?.error {
                Text(error).foregroundStyle(.red)
            } else if status?.isConnected == true {
                if let synced = status?.lastSynced {
                    Text("Connected · synced \(synced, format: .relative(presentation: .named))")
                } else {
                    Text("Connected")
                }
            } else if let synced = status?.lastSynced {
                Text("Not connected · last synced \(synced, format: .relative(presentation: .named))")
            } else {
                Text("Not connected. Open SyncedPass on the phone, on this network.")
            }
        }
        .font(.caption)
        .foregroundStyle(.secondary)
    }
}

/// Why the Mac can't be reached, with a shortcut to the setting that fixes it.
private struct ListenerProblem: View {
    let message: String
    @Environment(SyncService.self) private var sync
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            FieldError(message)
            if sync.needsLocalNetworkPermission {
                Button("Open Local Network Settings") {
                    openURL(URL(string: "x-apple.systempreferences:com.apple.settings.PrivacySecurity.extension?Privacy_LocalNetwork")!)
                }
            }
        }
    }
}

/// The pairing steps: waiting for the phone, comparing the code, the result.
private struct PairingView: View {
    let state: SyncService.PairingState
    @Environment(SyncService.self) private var sync

    var body: some View {
        VStack(spacing: 16) {
            switch state {
            case .waitingForPhone:
                Spacer()
                ProgressView()
                Text("Waiting for your phone")
                    .font(.title2.weight(.semibold))
                Text("On your phone, open SyncedPass › Settings › Sync with Mac › Pair with a Mac. This Mac finds it on the network and connects, then both show a code. The phone must be on the same network.")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                if let message = sync.networkError {
                    ListenerProblem(message: message)
                } else if sync.phonesReadyToPair.count > 1 {
                    // More than one phone is ready to pair: let the user choose.
                    VStack(spacing: 8) {
                        Text("Choose your phone:")
                            .font(.callout.weight(.semibold))
                        ForEach(sync.phonesReadyToPair) { phone in
                            Button(phone.displayName ?? phone.name) { sync.pair(with: phone) }
                        }
                    }
                } else {
                    // Said up front, so the question macOS asks the first time doesn't come as a surprise.
                    Label {
                        Text("The first time, macOS asks whether SyncedPass can find devices on your local network and, if your firewall is on, whether it can accept incoming network connections. Click Allow: only phones you pair can sync, and only on this network.")
                    } icon: {
                        Image(systemName: "info.circle")
                    }
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 4)
                }
                Spacer()
                buttons { Button("Cancel") { sync.stopPairing() }.keyboardShortcut(.cancelAction) }

            case .confirming(let code, let phoneName), .waitingForPhoneConfirmation(let code, let phoneName):
                Spacer()
                Text("Pair with “\(phoneName)”?")
                    .font(.title2.weight(.semibold))
                Text(code.prefix(3) + " " + code.suffix(3))
                    .font(.system(size: 44, weight: .semibold, design: .monospaced))
                    .textSelection(.disabled)
                    .accessibilityLabel(code.map(String.init).joined(separator: " "))
                if case .confirming = state {
                    Text("Check that your phone shows exactly the same code. If it doesn't, cancel: someone else may be trying to connect.")
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer()
                    buttons {
                        Button("Cancel") { sync.stopPairing() }.keyboardShortcut(.cancelAction)
                        Button("The Codes Match") { sync.confirmPairing() }.keyboardShortcut(.defaultAction)
                    }
                } else {
                    HStack(spacing: 8) {
                        ProgressView().controlSize(.small)
                        Text("Waiting for you to confirm on \(phoneName)…")
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    buttons { Button("Cancel") { sync.stopPairing() }.keyboardShortcut(.cancelAction) }
                }

            case .paired(let phoneName):
                Spacer()
                SuccessCheckmark()
                Text("Paired with \(phoneName)")
                    .font(.title2.weight(.semibold))
                Text("Your logins now sync automatically whenever SyncedPass is open and unlocked on both, on the same network.")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer()
                buttons { Button("Done") { sync.stopPairing() }.keyboardShortcut(.defaultAction) }

            case .failed(let message):
                Spacer()
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 40))
                    .foregroundStyle(.orange)
                Text("Pairing Didn't Finish")
                    .font(.title2.weight(.semibold))
                Text(message)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer()
                buttons {
                    Button("Cancel") { sync.stopPairing() }.keyboardShortcut(.cancelAction)
                    Button("Try Again") { sync.startPairing() }.keyboardShortcut(.defaultAction)
                }
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func buttons(@ViewBuilder _ content: () -> some View) -> some View {
        HStack {
            Spacer()
            content()
        }
    }
}
