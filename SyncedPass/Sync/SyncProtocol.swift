import CryptoKit
import Foundation
import Network

// The Mac's side of the sync protocol in docs/SYNC.md: framing, messages and
// the encrypted channel. The phone implements the other side
// (android/…/sync/SyncProtocol.kt).

enum SyncProtocol {
    static let version = 1
    /// What each side announces with Bonjour; each looks for the other's and connects.
    static let macServiceType = "_syncedpass._tcp"
    static let phoneServiceType = "_syncedpass-phone._tcp"
    static let maxHandshakeFrame = 64 * 1024
    static let maxFrame = 32 * 1024 * 1024
    /// Merging compares times from both devices, so their clocks must roughly agree.
    static let maxClockSkew: TimeInterval = 5 * 60
}

enum SyncError: LocalizedError {
    case closed
    case protocolViolation(String)
    case authenticationFailed
    case notPaired
    case clockSkew(TimeInterval)
    case cancelled

    var errorDescription: String? {
        switch self {
        case .closed: "The connection was closed."
        case .protocolViolation(let detail): "Unexpected message from the other device (\(detail))."
        case .authenticationFailed: "The other device couldn't prove it's the one you paired with."
        case .notPaired: "This device isn't paired."
        case .clockSkew(let seconds):
            "The clocks on this Mac and the phone differ by \(Int(seconds / 60)) minutes. Set both to the correct time to sync."
        case .cancelled: "Pairing was cancelled."
        }
    }
}

// MARK: - Messages

struct PairRequest: Codable {
    var type = "pair"
    var `protocol` = SyncProtocol.version
    let commitment: Data
}

struct PairHello: Codable {
    let deviceID: UUID
    let deviceName: String
    let identityKey: Data
    let ephemeralKey: Data
    let nonce: Data
    let time: Double
}

struct PairConfirm: Codable {
    var type = "confirm"
    let accepted: Bool
    var signature: Data?
}

struct SyncRequest: Codable {
    var type = "sync"
    var `protocol` = SyncProtocol.version
    let deviceID: UUID
    let ephemeralKey: Data
    let nonce: Data
    let time: Double
}

struct SyncReply: Codable {
    let deviceID: UUID
    let ephemeralKey: Data
    let nonce: Data
    let time: Double
}

struct SignatureFrame: Codable {
    let signature: Data
}

private struct TypedMessage: Decodable {
    let type: String
}

private struct ManifestMessage: Codable {
    var type = "manifest"
    let items: [String: Date]
    let deletions: [String: Date]
}

private struct RecordsMessage: Codable {
    var type = "records"
    let items: [LoginItem]
    let deletions: [Deletion]
}

enum SessionMessage {
    case manifest(Manifest)
    case records(SyncRecords)
    case ping
}

enum SyncCoding {
    static let encoder = JSONEncoder()
    static let decoder = JSONDecoder()

    static func encode<T: Encodable>(_ value: T) throws -> Data {
        try encoder.encode(value)
    }

    static func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        do {
            return try decoder.decode(type, from: data)
        } catch {
            throw SyncError.protocolViolation("malformed message")
        }
    }

    static var now: Double { Date.now.timeIntervalSinceReferenceDate }

    static func checkClock(_ theirTime: Double) throws {
        let skew = abs(theirTime - now)
        if skew > SyncProtocol.maxClockSkew { throw SyncError.clockSkew(skew) }
    }
}

// MARK: - Framing

/// Length-prefixed frames over a TCP connection.
final class FrameConnection {
    let connection: NWConnection

    init(_ connection: NWConnection) {
        self.connection = connection
    }

    /// Sends a frame and returns it as sent, length included (for handshake transcripts).
    @discardableResult
    func send(_ payload: Data) async throws -> Data {
        let frame = Self.frame(payload)
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: frame, completion: .contentProcessed { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            })
        }
        return frame
    }

    /// Queues a frame without waiting; frames go out in the order they're queued.
    func enqueue(_ payload: Data) {
        connection.send(content: Self.frame(payload), completion: .idempotent)
    }

    /// Reads one frame: the payload, and the frame as received with its length.
    func receive(maxBytes: Int) async throws -> (payload: Data, frame: Data) {
        let header = try await receive(exactly: 4)
        let length = header.reduce(0) { ($0 << 8) | Int($1) }
        guard length <= maxBytes else { throw SyncError.protocolViolation("frame of \(length) bytes") }
        let payload = length == 0 ? Data() : try await receive(exactly: length)
        return (payload, header + payload)
    }

    func cancel() {
        connection.cancel()
    }

    var isClosed: Bool {
        switch connection.state {
        case .cancelled, .failed: true
        default: false
        }
    }

    static func frame(_ payload: Data) -> Data {
        var length = UInt32(payload.count).bigEndian
        return Data(bytes: &length, count: 4) + payload
    }

    private func receive(exactly count: Int) async throws -> Data {
        var received = Data()
        while received.count < count {
            let chunk: Data = try await withCheckedThrowingContinuation { continuation in
                connection.receive(minimumIncompleteLength: 1, maximumLength: count - received.count) { data, _, isComplete, error in
                    if let error {
                        continuation.resume(throwing: error)
                    } else if let data, !data.isEmpty {
                        continuation.resume(returning: data)
                    } else if isComplete {
                        continuation.resume(throwing: SyncError.closed)
                    } else {
                        continuation.resume(returning: Data())
                    }
                }
            }
            received += chunk
        }
        return received
    }
}

/// Encrypted, authenticated messages after a handshake; one key per direction.
final class SecureChannel {
    let frames: FrameConnection
    private var sender: FrameCipher
    private var receiver: FrameCipher

    init(frames: FrameConnection, sendKey: SymmetricKey, receiveKey: SymmetricKey) {
        self.frames = frames
        sender = FrameCipher(key: sendKey)
        receiver = FrameCipher(key: receiveKey)
    }

    /// Encrypts and queues a message. Sealing and queueing happen together,
    /// so frames always go out in counter order.
    func send(_ json: Data) throws {
        frames.enqueue(try sender.seal(json))
    }

    func receive(maxBytes: Int = SyncProtocol.maxFrame) async throws -> Data {
        let (payload, _) = try await frames.receive(maxBytes: maxBytes)
        do {
            return try receiver.open(payload)
        } catch {
            throw SyncError.authenticationFailed
        }
    }

    func sendManifest(_ manifest: Manifest) throws {
        try send(SyncCoding.encode(ManifestMessage(
            items: Dictionary(uniqueKeysWithValues: manifest.items.map { ($0.key.uuidString, $0.value) }),
            deletions: Dictionary(uniqueKeysWithValues: manifest.deletions.map { ($0.key.uuidString, $0.value) }))))
    }

    func sendRecords(_ records: SyncRecords) throws {
        try send(SyncCoding.encode(RecordsMessage(items: records.items, deletions: records.deletions)))
    }

    func sendPing() throws {
        try send(Data(#"{"type":"ping"}"#.utf8))
    }

    func receiveMessage() async throws -> SessionMessage {
        let json = try await receive()
        switch try SyncCoding.decode(TypedMessage.self, from: json).type {
        case "manifest":
            let message = try SyncCoding.decode(ManifestMessage.self, from: json)
            return .manifest(Manifest(items: try Self.byID(message.items), deletions: try Self.byID(message.deletions)))
        case "records":
            let message = try SyncCoding.decode(RecordsMessage.self, from: json)
            return .records(SyncRecords(items: message.items, deletions: message.deletions))
        case "ping":
            return .ping
        case let other:
            throw SyncError.protocolViolation("message type \(other)")
        }
    }

    private static func byID(_ values: [String: Date]) throws -> [UUID: Date] {
        var result: [UUID: Date] = [:]
        for (key, value) in values {
            guard let id = UUID(uuidString: key) else { throw SyncError.protocolViolation("bad ID") }
            result[id] = value
        }
        return result
    }
}
