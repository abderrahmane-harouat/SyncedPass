import CryptoKit
import Foundation
import Testing
@testable import SyncedPass

@Suite("Vault encryption")
struct VaultCryptoTests {
    private func hex(_ key: SymmetricKey) -> String {
        key.withUnsafeBytes { $0.map { String(format: "%02x", $0) }.joined() }
    }

    // Published PBKDF2-HMAC-SHA256 test vectors (RFC 7914 §11 style, P="password", S="salt").
    @Test("PBKDF2 matches published test vectors", arguments: [
        (1, "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b"),
        (2, "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43"),
        (4096, "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a"),
    ])
    func pbkdf2Vectors(iterations: Int, expected: String) throws {
        let key = try KeyDerivation.deriveSync(password: "password", salt: Data("salt".utf8), iterations: iterations)
        #expect(hex(key) == expected)
    }

    @Test func passwordUnicodeIsNormalized() throws {
        let salt = Data(repeating: 7, count: 16)
        let composed = try KeyDerivation.deriveSync(password: "caf\u{E9}", salt: salt, iterations: 10)
        let decomposed = try KeyDerivation.deriveSync(password: "cafe\u{301}", salt: salt, iterations: 10)
        #expect(hex(composed) == hex(decomposed))
    }

    @Test func roundTripKeepsEveryField() async throws {
        let items = [makeFullItem(), makeFullItem(title: "Second")]
        let keys = try await VaultCrypto.makeKeys(password: masterPassword, iterations: testIterations)
        let data = try VaultCrypto.seal(items, kind: .vault, keys: keys)

        let container = try VaultCrypto.container(from: data, expecting: .vault)
        let unlocked = try await VaultCrypto.unlock(container, password: masterPassword)
        #expect(try VaultCrypto.open(container, keys: unlocked) == items)
    }

    @Test func fileContainsNoPlaintext() async throws {
        let item = makeFullItem()
        let keys = try await VaultCrypto.makeKeys(password: masterPassword, iterations: testIterations)
        let data = try VaultCrypto.seal([item], kind: .vault, keys: keys)
        for secret in [item.title, item.username, item.email, "p@ss", item.pin, item.totpSecret, "github.com", masterPassword] {
            #expect(!data.contains(secret), "Found \(secret) in the encrypted file")
        }
    }

    @Test func wrongPasswordIsRejected() async throws {
        let keys = try await VaultCrypto.makeKeys(password: masterPassword, iterations: testIterations)
        let container = try VaultCrypto.container(
            from: VaultCrypto.seal([makeFullItem()], kind: .vault, keys: keys), expecting: .vault)
        await #expect(throws: VaultError.wrongPassword) {
            try await VaultCrypto.unlock(container, password: "correct horse batterY")
        }
    }

    @Test func shortPasswordIsRejected() async {
        await #expect(throws: VaultError.passwordTooShort(minimum: 10)) {
            try await VaultCrypto.makeKeys(password: "123456789", iterations: testIterations)
        }
    }

    @Test func everyEncryptionUsesFreshRandomness() async throws {
        let keys = try await VaultCrypto.makeKeys(password: masterPassword, iterations: testIterations)
        let other = try await VaultCrypto.makeKeys(password: masterPassword, iterations: testIterations)
        let items = [makeFullItem()]
        let first = try VaultCrypto.container(from: VaultCrypto.seal(items, kind: .vault, keys: keys), expecting: .vault)
        let second = try VaultCrypto.container(from: VaultCrypto.seal(items, kind: .vault, keys: keys), expecting: .vault)
        #expect(first.payload != second.payload, "Nonce reused")
        #expect(keys.kdf.salt != other.kdf.salt)
        #expect(keys.wrappedKey != other.wrappedKey)
    }

    // MARK: Tampering

    private func sealedContainer() async throws -> (EncryptedContainer, VaultKeys) {
        let keys = try await VaultCrypto.makeKeys(password: masterPassword, iterations: testIterations)
        let data = try VaultCrypto.seal([makeFullItem()], kind: .vault, keys: keys)
        return (try VaultCrypto.container(from: data, expecting: .vault), keys)
    }

    private func flipByte(_ data: Data, at index: Int) -> Data {
        var data = data
        data[data.startIndex + index] ^= 0x01
        return data
    }

    @Test func tamperedPayloadIsDetected() async throws {
        var (container, keys) = try await sealedContainer()
        container.payload = flipByte(container.payload, at: container.payload.count / 2)
        #expect(throws: VaultError.damaged) { try VaultCrypto.open(container, keys: keys) }
    }

    @Test func truncatedPayloadIsDetected() async throws {
        var (container, keys) = try await sealedContainer()
        container.payload = container.payload.prefix(20)
        #expect(throws: VaultError.damaged) { try VaultCrypto.open(container, keys: keys) }
    }

    @Test func tamperedWrappedKeyOrSaltIsRejected() async throws {
        var (container, _) = try await sealedContainer()
        let original = container
        container.wrappedKey = flipByte(container.wrappedKey, at: 20)
        await #expect(throws: VaultError.wrongPassword) { try await VaultCrypto.unlock(container, password: masterPassword) }

        container = original
        container.kdf.salt = flipByte(container.kdf.salt, at: 0)
        await #expect(throws: VaultError.wrongPassword) { try await VaultCrypto.unlock(container, password: masterPassword) }

        container = original
        container.kdf.iterations += 1
        await #expect(throws: VaultError.wrongPassword) { try await VaultCrypto.unlock(container, password: masterPassword) }
    }

    @Test func vaultPayloadCannotBePassedOffAsBackup() async throws {
        var (container, _) = try await sealedContainer()
        container.format = .backup
        let keys = try await VaultCrypto.unlock(container, password: masterPassword)
        #expect(throws: VaultError.damaged) { try VaultCrypto.open(container, keys: keys) }
    }

    // MARK: Header checks

    @Test func rejectsFilesItCannotRead() async throws {
        let (container, keys) = try await sealedContainer()
        let vaultData = try VaultCrypto.seal([], kind: .vault, keys: keys)

        #expect(throws: VaultError.notSyncedPassFile) { try VaultCrypto.container(from: Data("hello".utf8), expecting: .vault) }
        #expect(throws: VaultError.notSyncedPassFile) { try VaultCrypto.container(from: Data(#"{"a":1}"#.utf8), expecting: .vault) }
        #expect(throws: VaultError.wrongKind(.vault)) { try VaultCrypto.container(from: vaultData, expecting: .backup) }

        var newer = container
        newer.version = EncryptedContainer.currentVersion + 1
        #expect(throws: VaultError.unsupportedVersion(EncryptedContainer.currentVersion + 1)) {
            try VaultCrypto.container(from: JSONEncoder().encode(newer), expecting: .vault)
        }
        var otherKDF = container
        otherKDF.kdf.algorithm = "argon2id"
        #expect(throws: VaultError.unsupportedKDF("argon2id")) {
            try VaultCrypto.container(from: JSONEncoder().encode(otherKDF), expecting: .vault)
        }
    }
}
