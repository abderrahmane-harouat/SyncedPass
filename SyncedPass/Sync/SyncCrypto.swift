import CryptoKit
import Foundation

/// The primitives of the sync protocol (docs/SYNC.md, "Cryptography"),
/// matching SyncCrypto.kt on Android: P-256 keys sent as SubjectPublicKeyInfo
/// DER, ECDSA with SHA-256 (DER signatures), ECDH (the 32-byte x coordinate)
/// and HKDF-SHA256.
nonisolated enum SyncCrypto {
    /// A new long-term identity: the 32-byte private scalar and the SPKI public key.
    static func newIdentity() -> (privateKey: Data, publicKey: Data) {
        let key = P256.Signing.PrivateKey()
        return (key.rawRepresentation, key.publicKey.derRepresentation)
    }

    static func sign(_ data: Data, privateKey: Data) throws -> Data {
        try P256.Signing.PrivateKey(rawRepresentation: privateKey).signature(for: data).derRepresentation
    }

    /// False for a bad signature, a malformed key or a key on another curve.
    static func verify(_ signature: Data, for data: Data, publicKey: Data) -> Bool {
        guard let key = try? P256.Signing.PublicKey(derRepresentation: publicKey),
              let signature = try? P256.Signing.ECDSASignature(derRepresentation: signature)
        else { return false }
        return key.isValidSignature(signature, for: data)
    }

    static func isValidPublicKey(_ data: Data) -> Bool {
        (try? P256.Signing.PublicKey(derRepresentation: data)) != nil
    }

    /// A one-time key pair for one handshake, giving forward secrecy.
    struct Ephemeral {
        private let key = P256.KeyAgreement.PrivateKey()
        var publicKey: Data { key.publicKey.derRepresentation }

        func agree(with peerPublicKey: Data) throws -> SharedSecret {
            try key.sharedSecretFromKeyAgreement(with: P256.KeyAgreement.PublicKey(derRepresentation: peerPublicKey))
        }
    }

    static func hkdf(_ secret: SharedSecret, salt: Data, info: String, length: Int) -> SymmetricKey {
        secret.hkdfDerivedSymmetricKey(using: SHA256.self, salt: salt, sharedInfo: Data(info.utf8), outputByteCount: length)
    }

    static func sha256(_ parts: Data...) -> Data {
        var hash = SHA256()
        for part in parts { hash.update(data: part) }
        return Data(hash.finalize())
    }

    static func randomBytes(_ count: Int) -> Data {
        SymmetricKey(size: SymmetricKeySize(bitCount: count * 8)).withUnsafeBytes { Data($0) }
    }
}

/// AES-256-GCM for one direction of a connection. The nonce is 4 zero bytes
/// and a 64-bit counter that both sides keep, so it's never sent; a frame
/// that's replayed, reordered or altered fails to decrypt.
nonisolated struct FrameCipher {
    private let key: SymmetricKey
    private var counter: UInt64 = 0

    init(key: SymmetricKey) {
        self.key = key
    }

    mutating func seal(_ plaintext: Data) throws -> Data {
        let box = try AES.GCM.seal(plaintext, using: key, nonce: nextNonce())
        return box.ciphertext + box.tag
    }

    mutating func open(_ frame: Data) throws -> Data {
        guard frame.count >= 16 else { throw SyncError.authenticationFailed }
        let box = try AES.GCM.SealedBox(nonce: nextNonce(), ciphertext: frame.dropLast(16), tag: frame.suffix(16))
        return try AES.GCM.open(box, using: key)
    }

    private mutating func nextNonce() throws -> AES.GCM.Nonce {
        var bytes = Data(count: 4)
        withUnsafeBytes(of: counter.bigEndian) { bytes.append(contentsOf: $0) }
        counter += 1
        return try AES.GCM.Nonce(data: bytes)
    }
}
