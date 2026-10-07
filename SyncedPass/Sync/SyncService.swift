import Foundation
import Network
import Observation

/// Syncs the vault with paired phones over the local network (docs/SYNC.md).
///
/// Both sides listen and announce themselves with Bonjour, and both look for
/// the other and connect: whichever connection gets through is used, and data
/// flows both ways over it. If the macOS firewall holds back connections from
/// the phone (it asks the user the first time), the Mac's own connection to
/// the phone still works. Everything runs only while the vault is unlocked,
/// and only if a phone is paired or the pairing window is open. Every
/// connection is encrypted and mutually authenticated.
@Observable
final class SyncService {
    enum PairingState: Equatable {
        /// The pairing window is open and no phone is pairing yet.
        case waitingForPhone
        /// Both screens show `code`; waiting for this Mac's user.
        case confirming(code: String, phoneName: String)
        /// This Mac's user confirmed; waiting for the phone's.
        case waitingForPhoneConfirmation(code: String, phoneName: String)
        case paired(phoneName: String)
        case failed(String)
    }

    struct PeerStatus: Equatable {
        var isConnected = false
        var lastSynced: Date?
        var error: String?
    }

    /// A phone announcing SyncedPass on the network.
    struct FoundPhone: Hashable, Identifiable {
        let name: String
        let endpoint: NWEndpoint
        /// Its pairing screen is open.
        let readyToPair: Bool
        var id: String { name }
    }

    /// The pairing in progress, while the pairing window is open.
    private(set) var pairing: PairingState?
    private(set) var peerStatus: [UUID: PeerStatus] = [:]
    /// Why the Mac can't look for phones, e.g. local network access was denied.
    private(set) var networkError: String?
    /// True when macOS's Local Network permission is what's missing, so the
    /// window can offer to open that page of System Settings.
    private(set) var needsLocalNetworkPermission = false
    /// Phones found on the network.
    private(set) var phones: [FoundPhone] = []

    var peers: [SyncPeer] { store.contents.sync?.peers ?? [] }
    /// Phones whose pairing screen is open; with more than one, the user picks.
    var phonesReadyToPair: [FoundPhone] { phones.filter(\.readyToPair) }
    let deviceName: String

    private let store: VaultStore
    private var browser: NWBrowser?
    private var listener: NWListener?
    private var browsedPhones: [FoundPhone] = []
    private var debugPhones: [FoundPhone] = []
    private var retryLoop: Task<Void, Never>?
    /// Phones with a connection open or being opened, by name.
    private var connectedPhones: Set<String> = []
    private var lastAttempt: [String: Date] = [:]
    /// Phones that turned out not to be paired with this Mac, and when.
    private var notOurs: [String: Date] = [:]
    private var sessions: [UUID: Session] = [:]
    private var pendingPairing: PendingPairing?
    /// The one connection allowed to pair while the pairing window is open.
    /// Any failure once it's under way ends pairing visibly: otherwise someone
    /// on the network could retry, unseen, until their code happened to match.
    private var pairingConnection: FrameConnection?
    private var changeTask: Task<Void, Never>?

    init(store: VaultStore) {
        self.store = store
        deviceName = Host.current().localizedName ?? "Mac"
        store.onChange = { [weak self] in self?.vaultChanged() }
        #if DEBUG
        // Testing only: a phone at a fixed address ("host:port"), e.g. an emulator through `adb forward`.
        if let value = ProcessInfo.processInfo.environment["SYNCEDPASS_PHONE_ENDPOINT"] {
            addDebugPhone(value, readyToPair: false)
        }
        #endif
    }

    // MARK: - Starting and stopping

    private var isRunning: Bool { browser != nil }

    /// Starts or stops to match the vault's state. Call when the vault locks or unlocks.
    func refresh() {
        let shouldRun = store.status == .unlocked && (!peers.isEmpty || pairing != nil)
        if shouldRun, !isRunning {
            start()
        } else if !shouldRun, isRunning {
            stop()
        }
        if isRunning { connectToPhones() }
    }

    private func start() {
        let parameters = NWParameters.tcp
        parameters.includePeerToPeer = false
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: SyncProtocol.phoneServiceType, domain: nil), using: parameters)
        browser.stateUpdateHandler = { [weak self] state in
            MainActor.assumeIsolated { self?.browserChanged(state) }
        }
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            MainActor.assumeIsolated { self?.phonesChanged(results) }
        }
        browser.start(queue: .main)
        self.browser = browser
        startListening()
        // Retry phones that dropped or weren't reachable yet.
        retryLoop = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(3))
                self?.connectToPhones()
            }
        }
    }

    private func stop() {
        browser?.cancel()
        browser = nil
        listener?.cancel()
        listener = nil
        retryLoop?.cancel()
        retryLoop = nil
        browsedPhones = []
        updatePhones()
        for session in sessions.values { session.close() }
        sessions = [:]
        pendingPairing?.channel.frames.cancel()
        pendingPairing = nil
        pairingConnection?.cancel()
        pairingConnection = nil
        if pairing != nil, store.status != .unlocked { pairing = nil }
        for id in peerStatus.keys { peerStatus[id]?.isConnected = false }
    }

    private func browserChanged(_ state: NWBrowser.State) {
        switch state {
        case .failed(let error), .waiting(let error):
            if case .dns(let code) = error, code == -65570 {  // kDNSServiceErr_PolicyDenied
                needsLocalNetworkPermission = true
                networkError = "SyncedPass isn't allowed to use your local network, so it can't find your phone. Turn on SyncedPass in System Settings ▸ Privacy & Security ▸ Local Network."
            } else {
                networkError = "SyncedPass can't look for phones on the local network: \(error.localizedDescription)"
            }
        case .ready:
            networkError = nil
            needsLocalNetworkPermission = false
        default:
            break
        }
    }

    private func phonesChanged(_ results: Set<NWBrowser.Result>) {
        browsedPhones = results.compactMap { result in
            guard case .service(let name, _, _, _) = result.endpoint else { return nil }
            var ready = false
            if case .bonjour(let record) = result.metadata { ready = record["pairing"] == "1" }
            return FoundPhone(name: name, endpoint: result.endpoint, readyToPair: ready)
        }
        updatePhones()
        connectToPhones()
    }

    /// Listens for phones that connect to this Mac, and announces it. The
    /// first connection makes macOS ask whether SyncedPass may accept
    /// incoming connections (if the firewall is on); until the user answers,
    /// this Mac's own connections to the phone keep sync working.
    private func startListening() {
        let parameters = NWParameters.tcp
        parameters.acceptLocalOnly = true  // local network only
        parameters.prohibitedInterfaceTypes = [.cellular]
        parameters.includePeerToPeer = false
        guard let listener = try? NWListener(using: parameters, on: Self.listeningPort) else { return }
        listener.service = NWListener.Service(name: nil, type: SyncProtocol.macServiceType)
        listener.newConnectionHandler = { [weak self] connection in
            MainActor.assumeIsolated { self?.accept(connection) }
        }
        listener.start(queue: .main)
        self.listener = listener
    }

    /// A fixed port for testing (`SYNCEDPASS_SYNC_PORT`, debug builds only); otherwise any free port.
    private static var listeningPort: NWEndpoint.Port {
        #if DEBUG
        if let value = ProcessInfo.processInfo.environment["SYNCEDPASS_SYNC_PORT"], let port = NWEndpoint.Port(value) {
            return port
        }
        #endif
        return .any
    }

    private func accept(_ connection: NWConnection) {
        guard case .hostPort(let host, _) = connection.endpoint, Self.isLocal(host) else {
            connection.cancel()
            return
        }
        connection.start(queue: .main)
        let frames = FrameConnection(connection)
        Task { await serve(frames, phone: nil) }
    }

    private func updatePhones() {
        phones = (browsedPhones + debugPhones).sorted { $0.name < $1.name }
    }

    #if DEBUG
    /// Testing only: treats "host:port" as a phone found on the network.
    func addDebugPhone(_ hostPort: String, readyToPair: Bool) {
        let parts = hostPort.split(separator: ":")
        guard parts.count == 2, let port = NWEndpoint.Port(String(parts[1])) else { return }
        let phone = FoundPhone(name: "Debug phone \(hostPort)", endpoint: .hostPort(host: NWEndpoint.Host(String(parts[0])), port: port), readyToPair: readyToPair)
        debugPhones = debugPhones.filter { $0.endpoint != phone.endpoint } + [phone]
        updatePhones()
        connectToPhones()
    }
    #endif

    // MARK: - Connecting to phones

    /// Connects to the phone that's ready to pair (while pairing), and to every
    /// other phone, which is then checked to be paired during the handshake.
    private func connectToPhones() {
        guard isRunning, store.status == .unlocked else { return }
        if pairing == .waitingForPhone, pairingConnection == nil, phonesReadyToPair.count == 1 {
            connect(to: phonesReadyToPair[0], forPairing: true)
        }
        guard !peers.isEmpty, !peers.allSatisfy({ sessions[$0.deviceID] != nil }) else { return }
        for phone in phones where !phone.readyToPair && !connectedPhones.contains(phone.name) {
            if let refused = notOurs[phone.name], Date.now.timeIntervalSince(refused) < 60 { continue }
            if let last = lastAttempt[phone.name], Date.now.timeIntervalSince(last) < 5 { continue }
            connect(to: phone, forPairing: false)
        }
    }

    /// Pairs with a phone the user chose, when several are ready to pair.
    func pair(with phone: FoundPhone) {
        guard pairing == .waitingForPhone, pairingConnection == nil else { return }
        connect(to: phone, forPairing: true)
    }

    private func connect(to phone: FoundPhone, forPairing: Bool) {
        lastAttempt[phone.name] = .now
        connectedPhones.insert(phone.name)
        let parameters = NWParameters.tcp
        parameters.prohibitedInterfaceTypes = [.cellular]  // local network only
        parameters.includePeerToPeer = false
        let connection = NWConnection(to: phone.endpoint, using: parameters)
        let frames = FrameConnection(connection)
        if forPairing { pairingConnection = frames }
        var started = false
        connection.stateUpdateHandler = { [weak self] state in
            MainActor.assumeIsolated {
                guard let self else { return }
                switch state {
                case .ready where !started:
                    started = true
                    // Local network only: check where the phone's name actually led.
                    guard case .hostPort(let host, _)? = connection.currentPath?.remoteEndpoint, Self.isLocal(host) else {
                        connection.cancel()
                        return
                    }
                    Task { await self.serve(frames, phone: phone.name, initiatedByPhone: false) }
                case .failed, .waiting:
                    // Not reachable (yet); nothing was exchanged, so it's simply retried.
                    connection.cancel()
                case .cancelled:
                    if !started, self.pairingConnection === frames { self.pairingConnection = nil }
                    if !started || frames.isClosed { self.connectedPhones.remove(phone.name) }
                default:
                    break
                }
            }
        }
        connection.start(queue: .main)
    }

    /// Runs one connection with a phone, whichever side opened it: the phone
    /// always speaks first, saying whether it pairs or syncs. `phone` is the
    /// name it was found under, or nil when it connected to this Mac.
    private func serve(_ frames: FrameConnection, phone: String?, initiatedByPhone: Bool = true) async {
        defer {
            frames.cancel()
            if let phone { connectedPhones.remove(phone) }
            if pairingConnection === frames, pendingPairing == nil, pairing != .waitingForPhone { pairingConnection = nil }
        }
        do {
            // The handshake must be prompt.
            let timeout = Task {
                try await Task.sleep(for: .seconds(15))
                frames.cancel()
            }
            let (payload, frame) = try await frames.receive(maxBytes: SyncProtocol.maxHandshakeFrame)
            struct Header: Decodable {
                let type: String
                let `protocol`: Int
            }
            let header = try SyncCoding.decode(Header.self, from: payload)
            guard header.protocol == SyncProtocol.version else { throw SyncError.protocolViolation("protocol \(header.protocol)") }
            switch header.type {
            case "pair" where pairingConnection === frames:
                try await handlePairing(frames, f1Payload: payload, f1: frame, timeout: timeout)
            case "sync" where pairingConnection !== frames:
                try await handleSync(frames, f1Payload: payload, f1: frame, timeout: timeout, initiatedByPhone: initiatedByPhone)
            default:
                throw SyncError.protocolViolation("type \(header.type)")
            }
        } catch {
            if case SyncError.notPaired = error, let phone { notOurs[phone] = .now }
            // Any failure of the pairing connection ends pairing, so failed attempts are always seen.
            if pairingConnection === frames {
                failPairing("Pairing with \(phone ?? "the phone") was interrupted: \(error.localizedDescription) Try again, and make sure only your phone is pairing.")
            }
        }
    }

    // MARK: - Pairing

    /// Opens pairing: a phone may now pair with this Mac until `stopPairing()`.
    func startPairing() {
        pendingPairing?.channel.frames.cancel()
        pendingPairing = nil
        pairingConnection?.cancel()
        pairingConnection = nil
        pairing = .waitingForPhone
        refresh()
        connectToPhones()
    }

    func stopPairing() {
        if let pending = pendingPairing {
            try? pending.channel.send(SyncCoding.encode(PairConfirm(accepted: false)))
            pending.channel.frames.cancel()
        }
        pendingPairing = nil
        pairingConnection?.cancel()
        pairingConnection = nil
        pairing = nil
        refresh()
    }

    /// This Mac's user confirmed that the codes match.
    func confirmPairing() {
        guard let pending = pendingPairing, case .confirming(let code, let name) = pairing,
              let identity = store.contents.sync else { return }
        do {
            let signature = try SyncCrypto.sign(Self.confirmation(.mac, pending.transcript), privateKey: identity.privateKey)
            try pending.channel.send(SyncCoding.encode(PairConfirm(accepted: true, signature: signature)))
            pending.macAccepted = true
            pairing = .waitingForPhoneConfirmation(code: code, phoneName: name)
            finishPairingIfReady()
        } catch {
            failPairing(error.localizedDescription)
        }
    }

    func removePeer(_ id: UUID) throws {
        try store.updateSync { $0?.peers.removeAll { $0.deviceID == id } }
        notOurs = [:]
        sessions.removeValue(forKey: id)?.close()
        peerStatus[id] = nil
        refresh()
    }

    private final class PendingPairing {
        let channel: SecureChannel
        let phone: PairHello
        let transcript: Data
        var macAccepted = false
        var phoneConfirmation: PairConfirm?

        init(channel: SecureChannel, phone: PairHello, transcript: Data) {
            self.channel = channel
            self.phone = phone
            self.transcript = transcript
        }
    }

    /// Steps 2–4 of pairing (docs/SYNC.md, "Pairing"), as the Mac.
    private func handlePairing(_ frames: FrameConnection, f1Payload: Data, f1: Data, timeout: Task<Void, Error>) async throws {
        guard pairing == .waitingForPhone, pendingPairing == nil, pairingConnection === frames else { throw SyncError.cancelled }
        let request = try SyncCoding.decode(PairRequest.self, from: f1Payload)
        let identity = try ensureIdentity()
        let ephemeral = SyncCrypto.Ephemeral()
        let hello = PairHello(
            deviceID: identity.deviceID, deviceName: identity.deviceName, identityKey: identity.publicKey,
            ephemeralKey: ephemeral.publicKey, nonce: SyncCrypto.randomBytes(32), time: SyncCoding.now)
        let f2 = try await frames.send(SyncCoding.encode(hello))
        let (f3Payload, f3) = try await frames.receive(maxBytes: SyncProtocol.maxHandshakeFrame)
        // The phone committed to its keys before seeing the Mac's.
        guard SyncCrypto.sha256(f3) == request.commitment else { throw SyncError.authenticationFailed }
        let phone = try SyncCoding.decode(PairHello.self, from: f3Payload)
        guard SyncCrypto.isValidPublicKey(phone.identityKey), phone.deviceID != identity.deviceID,
              phone.identityKey != identity.publicKey else {
            throw SyncError.protocolViolation("bad identity")
        }
        try SyncCoding.checkClock(phone.time)
        guard pairing == .waitingForPhone, pendingPairing == nil, pairingConnection === frames else { throw SyncError.cancelled }

        let secret = try ephemeral.agree(with: phone.ephemeralKey)
        let salt = SyncCrypto.sha256(Data("SyncedPass pair v1".utf8), f1, f2, f3)
        let code = SyncCrypto.hkdf(secret, salt: salt, info: "SyncedPass pair v1 code", length: 4)
            .withUnsafeBytes { $0.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) } } % 1_000_000
        let channel = SecureChannel(
            frames: frames,
            sendKey: SyncCrypto.hkdf(secret, salt: salt, info: "SyncedPass pair v1 s2c", length: 32),
            receiveKey: SyncCrypto.hkdf(secret, salt: salt, info: "SyncedPass pair v1 c2s", length: 32))
        let pending = PendingPairing(channel: channel, phone: phone, transcript: salt)
        pendingPairing = pending
        pairing = .confirming(code: String(format: "%06d", code), phoneName: phone.deviceName)
        timeout.cancel()  // the users may take a while to compare codes

        // Wait for the phone's user while this Mac's user decides.
        let confirmation = try SyncCoding.decode(PairConfirm.self, from: try await channel.receive(maxBytes: SyncProtocol.maxHandshakeFrame))
        guard pendingPairing === pending else { return }
        pending.phoneConfirmation = confirmation
        finishPairingIfReady()
    }

    private func finishPairingIfReady() {
        guard let pending = pendingPairing, let confirmation = pending.phoneConfirmation else { return }
        guard confirmation.accepted else {
            failPairing("Pairing was cancelled on \(pending.phone.deviceName).")
            return
        }
        guard pending.macAccepted else { return }
        guard let signature = confirmation.signature,
              SyncCrypto.verify(signature, for: Self.confirmation(.phone, pending.transcript), publicKey: pending.phone.identityKey) else {
            failPairing(SyncError.authenticationFailed.localizedDescription)
            return
        }
        let phone = pending.phone
        do {
            try store.updateSync { sync in
                sync?.peers.removeAll { $0.deviceID == phone.deviceID }
                sync?.peers.append(SyncPeer(deviceID: phone.deviceID, name: phone.deviceName, publicKey: phone.identityKey, pairedAt: .now))
            }
        } catch {
            failPairing(error.localizedDescription)
            return
        }
        pending.channel.frames.cancel()
        pendingPairing = nil
        pairingConnection = nil
        pairing = .paired(phoneName: phone.deviceName)
        refresh()
    }

    private func failPairing(_ message: String) {
        pendingPairing?.channel.frames.cancel()
        pendingPairing = nil
        pairingConnection?.cancel()
        pairingConnection = nil
        pairing = .failed(message)
    }

    private enum Role: String {
        case mac, phone
    }

    /// What each side signs to confirm pairing; the role keeps one side's
    /// confirmation from being reflected back as the other's.
    private static func confirmation(_ role: Role, _ transcript: Data) -> Data {
        SyncCrypto.sha256(Data("SyncedPass pair v1 confirm \(role.rawValue)".utf8), transcript)
    }

    /// This Mac's sync identity, created the first time it pairs.
    private func ensureIdentity() throws -> SyncIdentity {
        if let identity = store.contents.sync {
            if identity.deviceName != deviceName {
                try store.updateSync { $0?.deviceName = deviceName }
            }
            return store.contents.sync ?? identity
        }
        let keys = SyncCrypto.newIdentity()
        let identity = SyncIdentity(deviceID: UUID(), deviceName: deviceName, privateKey: keys.privateKey, publicKey: keys.publicKey)
        try store.updateSync { $0 = identity }
        return identity
    }

    /// The Mac's side of a sync session (docs/SYNC.md, "Sync sessions").
    private func handleSync(_ frames: FrameConnection, f1Payload: Data, f1: Data, timeout: Task<Void, Error>, initiatedByPhone: Bool) async throws {
        guard let identity = store.contents.sync else { throw SyncError.notPaired }
        let request = try SyncCoding.decode(SyncRequest.self, from: f1Payload)
        guard let peer = identity.peers.first(where: { $0.deviceID == request.deviceID }) else { throw SyncError.notPaired }

        let ephemeral = SyncCrypto.Ephemeral()
        let reply = SyncReply(deviceID: identity.deviceID, ephemeralKey: ephemeral.publicKey, nonce: SyncCrypto.randomBytes(32), time: SyncCoding.now)
        let f2 = try await frames.send(SyncCoding.encode(reply))
        let serverSignature = try SyncCrypto.sign(SyncCrypto.sha256(Data("SyncedPass sync v1 server".utf8), f1, f2), privateKey: identity.privateKey)
        try await frames.send(SyncCoding.encode(SignatureFrame(signature: serverSignature)))
        let (f4Payload, _) = try await frames.receive(maxBytes: SyncProtocol.maxHandshakeFrame)
        let clientSignature = try SyncCoding.decode(SignatureFrame.self, from: f4Payload).signature
        guard SyncCrypto.verify(clientSignature, for: SyncCrypto.sha256(Data("SyncedPass sync v1 client".utf8), f1, f2), publicKey: peer.publicKey) else {
            throw SyncError.authenticationFailed
        }
        timeout.cancel()
        do {
            try SyncCoding.checkClock(request.time)
        } catch {
            peerStatus[peer.deviceID, default: PeerStatus()].error = error.localizedDescription
            throw error
        }

        let secret = try ephemeral.agree(with: request.ephemeralKey)
        let salt = SyncCrypto.sha256(f1, f2)
        let channel = SecureChannel(
            frames: frames,
            sendKey: SyncCrypto.hkdf(secret, salt: salt, info: "SyncedPass sync v1 s2c", length: 32),
            receiveKey: SyncCrypto.hkdf(secret, salt: salt, info: "SyncedPass sync v1 c2s", length: 32))
        try await run(Session(channel: channel, peer: peer, initiatedByPhone: initiatedByPhone))
    }

    private final class Session {
        let channel: SecureChannel
        let peer: SyncPeer
        /// Who opened the connection; decides which one stays when both sides connected at once.
        let initiatedByPhone: Bool
        var lastSentManifest: Manifest?
        var lastReceived = Date.now
        var keepAlive: Task<Void, Never>?

        init(channel: SecureChannel, peer: SyncPeer, initiatedByPhone: Bool) {
            self.channel = channel
            self.peer = peer
            self.initiatedByPhone = initiatedByPhone
        }

        func close() {
            keepAlive?.cancel()
            channel.frames.cancel()
        }
    }

    private func run(_ session: Session) async throws {
        let id = session.peer.deviceID
        // If both sides connected at once, both keep the connection the phone
        // opened (docs/SYNC.md); otherwise a newer connection replaces an older one.
        if let existing = sessions[id], existing.initiatedByPhone, !session.initiatedByPhone {
            session.close()
            return
        }
        sessions.removeValue(forKey: id)?.close()
        sessions[id] = session
        peerStatus[id] = PeerStatus(isConnected: true, lastSynced: peerStatus[id]?.lastSynced)
        defer {
            session.close()
            if sessions[id] === session {
                sessions[id] = nil
                peerStatus[id]?.isConnected = false
            }
        }
        try sendManifest(to: session)
        session.keepAlive = Task { [weak self] in
            var elapsed = 0
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(5))
                elapsed += 5
                guard let self, !Task.isCancelled else { return }
                if Date.now.timeIntervalSince(session.lastReceived) > 60 {
                    session.close()  // nothing heard for a minute
                    return
                }
                if elapsed % 25 == 0 { try? session.channel.sendPing() }
                if elapsed % 60 == 0 { try? self.sendManifest(to: session, force: true) }
            }
        }

        while true {
            let message = try await session.channel.receiveMessage()
            session.lastReceived = .now
            guard store.status == .unlocked, sessions[id] === session else { return }
            switch message {
            case .manifest(let theirs):
                let records = store.contents.recordsNeeded(by: theirs)
                if !records.isEmpty { try session.channel.sendRecords(records) }
                // The phone has newer logins: show it what this Mac has, so it sends them.
                if theirs.hasRecordsNeeded(by: store.contents.manifest) { try sendManifest(to: session, force: true) }
                peerStatus[id]?.lastSynced = .now
                peerStatus[id]?.error = nil
            case .records(let records):
                try store.applySync(records)  // tells every connected device if anything changed
                peerStatus[id]?.lastSynced = .now
            case .ping:
                break
            }
        }
    }

    private func sendManifest(to session: Session, force: Bool = false) throws {
        guard store.status == .unlocked else { return }
        let manifest = store.contents.manifest
        guard force || manifest != session.lastSentManifest else { return }
        try session.channel.sendManifest(manifest)
        session.lastSentManifest = manifest
    }

    /// After any saved change: tell every connected device, and drop
    /// connections to devices that were unpaired.
    private func vaultChanged() {
        changeTask?.cancel()
        changeTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(300))
            guard let self, !Task.isCancelled else { return }
            let paired = Set(peers.map(\.deviceID))
            for (id, session) in sessions {
                if paired.contains(id) {
                    try? sendManifest(to: session)
                } else {
                    sessions[id] = nil
                    session.close()
                }
            }
        }
    }

    // MARK: - Local network only

    /// Loopback, private IPv4, link-local, or unique local IPv6.
    static func isLocal(_ host: NWEndpoint.Host) -> Bool {
        switch host {
        case .ipv4(let address):
            return isLocal(ipv4: [UInt8](address.rawValue))
        case .ipv6(let address):
            let bytes = [UInt8](address.rawValue)
            if let mapped = address.asIPv4 { return isLocal(ipv4: [UInt8](mapped.rawValue)) }
            return address.isLoopback || address.isLinkLocal || (bytes.first.map { $0 & 0xFE == 0xFC } ?? false)
        default:
            return false
        }
    }

    private static func isLocal(ipv4 b: [UInt8]) -> Bool {
        guard b.count == 4 else { return false }
        return b[0] == 127 || b[0] == 10 || (b[0] == 172 && (16...31).contains(b[1]))
            || (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254)
    }
}
