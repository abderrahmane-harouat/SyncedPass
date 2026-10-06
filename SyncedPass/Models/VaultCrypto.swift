import CommonCrypto
import CryptoKit
import Foundation

/// The on-disk format shared by the vault file and backup files.
///
/// A random 256-bit vault key encrypts the items with AES-256-GCM. That key is
/// itself encrypted ("wrapped") with a key derived from the password using
/// PBKDF2-HMAC-SHA256, so changing the password only re-wraps the vault key.
/// Everything except the KDF parameters is encrypted and authenticated; any
/// tampering makes decryption fail rather than return altered data.
struct EncryptedContainer: Codable, Equatable {
    enum Kind: String, Codable {
        case vault = "SyncedPass Vault"
        case backup = "SyncedPass Backup"
    }

    struct KDF: Codable, Equatable {
        var algorithm: String
        var iterations: Int
        var salt: Data
    }

    static let currentVersion = 1

    var format: Kind
    var version: Int
    var kdf: KDF
    /// The vault key, sealed with the password-derived key (AES-GCM combined form).
    var wrappedKey: Data
    /// The JSON-encoded items, sealed with the vault key (AES-GCM combined form).
    var payload: Data
}

enum VaultError: LocalizedError, Equatable {
    case wrongPassword
    case locked
    case damaged
    case notSyncedPassFile
    case wrongKind(EncryptedContainer.Kind)
    case unsupportedVersion(Int)
    case unsupportedKDF(String)
    case passwordTooShort(minimum: Int)

    var errorDescription: String? {
        switch self {
        case .wrongPassword:
            "The password is incorrect."
        case .locked:
            "The vault is locked."
        case .damaged:
            "The file is damaged or has been modified, so it can't be decrypted."
        case .notSyncedPassFile:
            "This file isn't a SyncedPass backup or an export from the old app."
        case .wrongKind(let kind):
            kind == .vault
                ? "This is a vault file, not a backup. Choose a file made with Export Backup."
                : "This is a backup file, not a vault."
        case .unsupportedVersion(let version):
            "This file was made by a newer version of SyncedPass (format \(version)). Update the app to open it."
        case .unsupportedKDF(let name):
            "This file uses an unsupported key derivation method (\(name))."
        case .passwordTooShort(let minimum):
            "Use at least \(minimum) characters."
        }
    }
}

/// An unlocked vault key together with the header needed to write it back.
struct VaultKeys {
    let vaultKey: SymmetricKey
    let kdf: EncryptedContainer.KDF
    let wrappedKey: Data
}

enum VaultCrypto {
    /// OWASP's 2023 recommendation for PBKDF2-HMAC-SHA256.
    static let defaultIterations = 600_000
    static let minimumPasswordLength = 10
    private static let kdfAlgorithm = "PBKDF2-HMAC-SHA256"

    /// Makes a new random vault key protected by `password`.
    static func makeKeys(password: String, iterations: Int = defaultIterations) async throws -> VaultKeys {
        guard password.count >= minimumPasswordLength else {
            throw VaultError.passwordTooShort(minimum: minimumPasswordLength)
        }
        let kdf = EncryptedContainer.KDF(algorithm: kdfAlgorithm, iterations: iterations, salt: randomBytes(16))
        let passwordKey = try await KeyDerivation.derive(password: password, salt: kdf.salt, iterations: iterations)
        let vaultKey = SymmetricKey(size: .bits256)
        let wrapped = try vaultKey.withUnsafeBytes { bytes in
            try AES.GCM.seal(Data(bytes), using: passwordKey, authenticating: wrapAAD).combined!
        }
        return VaultKeys(vaultKey: vaultKey, kdf: kdf, wrappedKey: wrapped)
    }

    /// Recovers the vault key from a container using `password`.
    static func unlock(_ container: EncryptedContainer, password: String) async throws -> VaultKeys {
        try validateHeader(container)
        let passwordKey = try await KeyDerivation.derive(
            password: password, salt: container.kdf.salt, iterations: container.kdf.iterations)
        let keyData: Data
        do {
            let box = try AES.GCM.SealedBox(combined: container.wrappedKey)
            keyData = try AES.GCM.open(box, using: passwordKey, authenticating: wrapAAD)
        } catch CryptoKitError.authenticationFailure {
            // A wrong password and a tampered wrapped key are indistinguishable.
            throw VaultError.wrongPassword
        } catch {
            throw VaultError.damaged
        }
        guard keyData.count == 32 else { throw VaultError.damaged }
        return VaultKeys(vaultKey: SymmetricKey(data: keyData), kdf: container.kdf, wrappedKey: container.wrappedKey)
    }

    static func seal(_ items: [LoginItem], kind: EncryptedContainer.Kind, keys: VaultKeys) throws -> Data {
        let plaintext = try itemEncoder.encode(items)
        let payload = try AES.GCM.seal(plaintext, using: keys.vaultKey, authenticating: payloadAAD(kind)).combined!
        let container = EncryptedContainer(
            format: kind, version: EncryptedContainer.currentVersion,
            kdf: keys.kdf, wrappedKey: keys.wrappedKey, payload: payload)
        return try containerEncoder.encode(container)
    }

    static func open(_ container: EncryptedContainer, keys: VaultKeys) throws -> [LoginItem] {
        let plaintext: Data
        do {
            let box = try AES.GCM.SealedBox(combined: container.payload)
            plaintext = try AES.GCM.open(box, using: keys.vaultKey, authenticating: payloadAAD(container.format))
        } catch {
            throw VaultError.damaged
        }
        do {
            return try itemDecoder.decode([LoginItem].self, from: plaintext)
        } catch {
            throw VaultError.damaged
        }
    }

    /// Parses a container, checking it's a SyncedPass file this version can read.
    static func container(from data: Data, expecting kind: EncryptedContainer.Kind) throws -> EncryptedContainer {
        guard let container = try? JSONDecoder().decode(EncryptedContainer.self, from: data) else {
            throw VaultError.notSyncedPassFile
        }
        guard container.format == kind else { throw VaultError.wrongKind(container.format) }
        try validateHeader(container)
        return container
    }

    private static func validateHeader(_ container: EncryptedContainer) throws {
        guard container.version <= EncryptedContainer.currentVersion else {
            throw VaultError.unsupportedVersion(container.version)
        }
        guard container.kdf.algorithm == kdfAlgorithm else {
            throw VaultError.unsupportedKDF(container.kdf.algorithm)
        }
        guard container.kdf.iterations >= 1, container.kdf.salt.count >= 16 else { throw VaultError.damaged }
    }

    // Binding the purpose into the authenticated data stops a wrapped key or
    // payload from being swapped into a different kind of file.
    private static let wrapAAD = Data("SyncedPass v1 wrapped key".utf8)
    private static func payloadAAD(_ kind: EncryptedContainer.Kind) -> Data {
        Data("SyncedPass v1 payload: \(kind.rawValue)".utf8)
    }

    // Dates use the default encoding (seconds since 2001-01-01 as a Double),
    // which round-trips exactly. Converting to 1970-based seconds can shift the
    // last bit, making an unchanged item look "newer" when merging backups.
    private static let itemEncoder = JSONEncoder()
    private static let itemDecoder = JSONDecoder()

    private static let containerEncoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return encoder
    }()

    private static func randomBytes(_ count: Int) -> Data {
        SymmetricKey(size: SymmetricKeySize(bitCount: count * 8)).withUnsafeBytes { Data($0) }
    }
}

/// PBKDF2 runs off the main actor: at 600,000 rounds it takes a noticeable
/// fraction of a second and would otherwise freeze the UI.
nonisolated enum KeyDerivation {
    static func derive(password: String, salt: Data, iterations: Int) async throws -> SymmetricKey {
        try await Task.detached(priority: .userInitiated) {
            try deriveSync(password: password, salt: salt, iterations: iterations)
        }.value
    }

    /// Normalizes the password first so the same characters typed on
    /// different keyboards or devices always give the same key.
    static func deriveSync(password: String, salt: Data, iterations: Int, length: Int = 32) throws -> SymmetricKey {
        let passwordBytes = Array(password.precomposedStringWithCanonicalMapping.utf8)
        var derived = [UInt8](repeating: 0, count: length)
        let status = passwordBytes.withUnsafeBufferPointer { passwordPointer in
            salt.withUnsafeBytes { saltPointer in
                CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2),
                    passwordPointer.baseAddress.map { UnsafeRawPointer($0).assumingMemoryBound(to: CChar.self) },
                    passwordBytes.count,
                    saltPointer.bindMemory(to: UInt8.self).baseAddress,
                    salt.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                    UInt32(clamping: iterations),
                    &derived,
                    length)
            }
        }
        guard status == kCCSuccess else { throw VaultError.damaged }
        defer { derived.withUnsafeMutableBufferPointer { $0.update(repeating: 0) } }
        return SymmetricKey(data: derived)
    }
}
