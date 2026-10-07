package com.abdurahmanharouat.syncedpass.crypto

import com.abdurahmanharouat.syncedpass.model.Base64Serializer
import com.abdurahmanharouat.syncedpass.model.LoginItem
import com.abdurahmanharouat.syncedpass.model.SyncedPassJson
import com.abdurahmanharouat.syncedpass.model.VaultContents
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * The file format shared with the macOS app (SyncedPass/Models/VaultCrypto.swift).
 *
 *   master password --PBKDF2-HMAC-SHA256--> password key
 *   password key    --AES-256-GCM-->         wraps a random 256-bit vault key
 *   vault key       --AES-256-GCM-->         encrypts the JSON list of logins
 *
 * AES-GCM boxes use CryptoKit's "combined" layout: 12-byte nonce, ciphertext,
 * 16-byte tag. Any change here must be made on both platforms.
 */

@Serializable
data class EncryptedContainer(
    val format: Kind,
    val version: Int,
    val kdf: Kdf,
    /** The vault key, sealed with the password-derived key. */
    @Serializable(with = Base64Serializer::class) val wrappedKey: ByteArray,
    /** The JSON-encoded logins, sealed with the vault key. */
    @Serializable(with = Base64Serializer::class) val payload: ByteArray,
) {
    @Serializable
    enum class Kind(val rawValue: String) {
        @SerialName("SyncedPass Vault") Vault("SyncedPass Vault"),
        @SerialName("SyncedPass Backup") Backup("SyncedPass Backup"),
    }

    @Serializable
    data class Kdf(
        val algorithm: String,
        val iterations: Int,
        @Serializable(with = Base64Serializer::class) val salt: ByteArray,
    )

    companion object {
        /** The newest format this version reads. Vaults are written as 2, backups as 1 (docs/SYNC.md). */
        const val CURRENT_VERSION = 2
        const val VAULT_VERSION = 2
        const val BACKUP_VERSION = 1
    }
}

sealed class VaultException(message: String) : Exception(message) {
    class WrongPassword : VaultException("The password is incorrect.")
    class Locked : VaultException("The vault is locked.")
    class Damaged : VaultException("The file is damaged or has been modified, so it can't be decrypted.")
    class NotSyncedPassFile : VaultException("This file isn't a SyncedPass file.")
    class WrongKind(val kind: EncryptedContainer.Kind) : VaultException(
        if (kind == EncryptedContainer.Kind.Vault) "This is a vault file, not a backup." else "This is a backup file, not a vault.",
    )
    class UnsupportedVersion(val version: Int) :
        VaultException("This file was made by a newer version of SyncedPass (format $version). Update the app to open it.")
    class UnsupportedKdf(val name: String) : VaultException("This file uses an unsupported key derivation method ($name).")
    class PasswordTooShort(val minimum: Int) : VaultException("Use at least $minimum characters.")
    class SamePassword : VaultException("The new password is the same as the current one.")
}

/** An unlocked vault key together with the header needed to write it back. */
class VaultKeys(val vaultKey: ByteArray, val kdf: EncryptedContainer.Kdf, val wrappedKey: ByteArray)

object VaultCrypto {
    /** OWASP's recommendation for PBKDF2-HMAC-SHA256; the Mac uses the same. */
    const val DEFAULT_ITERATIONS = 600_000
    const val MINIMUM_PASSWORD_LENGTH = 10
    private const val KDF_ALGORITHM = "PBKDF2-HMAC-SHA256"
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    // Binding each box to its purpose stops a wrapped key or payload from
    // being swapped into a different kind of file. Same strings as on macOS.
    private val wrapAad = "SyncedPass v1 wrapped key".toByteArray()
    private fun payloadAad(kind: EncryptedContainer.Kind) = "SyncedPass v1 payload: ${kind.rawValue}".toByteArray()

    private val random = SecureRandom()
    private val itemsSerializer = ListSerializer(LoginItem.serializer())

    /** Makes a new random vault key protected by [password]. Slow: call off the main thread. */
    fun makeKeys(password: String, iterations: Int = DEFAULT_ITERATIONS): VaultKeys {
        if (password.length < MINIMUM_PASSWORD_LENGTH) throw VaultException.PasswordTooShort(MINIMUM_PASSWORD_LENGTH)
        val kdf = EncryptedContainer.Kdf(KDF_ALGORITHM, iterations, randomBytes(16))
        val passwordKey = deriveKey(password, kdf.salt, iterations)
        val vaultKey = randomBytes(32)
        return VaultKeys(vaultKey, kdf, seal(vaultKey, passwordKey, wrapAad))
    }

    /** Recovers the vault key from [container] using [password]. Slow: call off the main thread. */
    fun unlock(container: EncryptedContainer, password: String): VaultKeys {
        validateHeader(container)
        val passwordKey = deriveKey(password, container.kdf.salt, container.kdf.iterations)
        val vaultKey = try {
            open(container.wrappedKey, passwordKey, wrapAad)
        } catch (e: AEADBadTagException) {
            // A wrong password and a tampered wrapped key are indistinguishable.
            throw VaultException.WrongPassword()
        } catch (e: GeneralSecurityException) {
            throw VaultException.Damaged()
        } catch (e: IllegalArgumentException) {
            throw VaultException.Damaged()
        }
        if (vaultKey.size != 32) throw VaultException.Damaged()
        return VaultKeys(vaultKey, container.kdf, container.wrappedKey)
    }

    /** A vault (with everything in [VaultContents]) or a backup (logins only). */
    fun seal(items: List<LoginItem>, kind: EncryptedContainer.Kind, keys: VaultKeys): ByteArray = when (kind) {
        EncryptedContainer.Kind.Vault -> seal(VaultContents(items), keys)
        EncryptedContainer.Kind.Backup ->
            seal(SyncedPassJson.encodeToString(itemsSerializer, items), kind, EncryptedContainer.BACKUP_VERSION, keys)
    }

    /** A vault file, format version 2 (docs/SYNC.md). */
    fun seal(contents: VaultContents, keys: VaultKeys): ByteArray = seal(
        SyncedPassJson.encodeToString(VaultContents.serializer(), contents),
        EncryptedContainer.Kind.Vault, EncryptedContainer.VAULT_VERSION, keys,
    )

    private fun seal(json: String, kind: EncryptedContainer.Kind, version: Int, keys: VaultKeys): ByteArray {
        val payload = seal(json.toByteArray(), keys.vaultKey, payloadAad(kind))
        val container = EncryptedContainer(kind, version, keys.kdf, keys.wrappedKey, payload)
        return SyncedPassJson.encodeToString(EncryptedContainer.serializer(), container).toByteArray()
    }

    /** The logins in a vault or backup. */
    fun open(container: EncryptedContainer, keys: VaultKeys): List<LoginItem> = openContents(container, keys).items

    /** Everything in a vault: version 1 files are a list of logins, version 2 a [VaultContents] object. */
    fun openContents(container: EncryptedContainer, keys: VaultKeys): VaultContents {
        val plaintext = try {
            open(container.payload, keys.vaultKey, payloadAad(container.format))
        } catch (e: GeneralSecurityException) {
            throw VaultException.Damaged()
        } catch (e: IllegalArgumentException) {
            throw VaultException.Damaged()
        }
        return try {
            val json = plaintext.decodeToString()
            if (container.format == EncryptedContainer.Kind.Vault && container.version >= 2) {
                SyncedPassJson.decodeFromString(VaultContents.serializer(), json)
            } else {
                VaultContents(SyncedPassJson.decodeFromString(itemsSerializer, json))
            }
        } catch (e: Exception) {
            throw VaultException.Damaged()
        }
    }

    /** Parses a container, checking it's a SyncedPass file this version can read. */
    fun container(data: ByteArray, expecting: EncryptedContainer.Kind): EncryptedContainer {
        val container = try {
            SyncedPassJson.decodeFromString(EncryptedContainer.serializer(), data.decodeToString())
        } catch (e: Exception) {
            throw VaultException.NotSyncedPassFile()
        }
        if (container.format != expecting) throw VaultException.WrongKind(container.format)
        validateHeader(container)
        return container
    }

    private fun validateHeader(container: EncryptedContainer) {
        if (container.version > EncryptedContainer.CURRENT_VERSION) throw VaultException.UnsupportedVersion(container.version)
        if (container.kdf.algorithm != KDF_ALGORITHM) throw VaultException.UnsupportedKdf(container.kdf.algorithm)
        if (container.kdf.iterations < 1 || container.kdf.salt.size < 16) throw VaultException.Damaged()
    }

    // MARK: Primitives

    /**
     * PBKDF2-HMAC-SHA256 over the UTF-8 bytes of the NFC-normalized password,
     * like the Mac (which normalizes with precomposedStringWithCanonicalMapping).
     * Uses the fast implementation in [Pbkdf2] (two SHA-256 blocks per round);
     * [deriveKeyReference] is the plain version on HMAC, and tests check on the
     * JVM and on the device that both always agree.
     */
    fun deriveKey(password: String, salt: ByteArray, iterations: Int, length: Int = 32): ByteArray {
        val passwordBytes = Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        try {
            return Pbkdf2.deriveKey(passwordBytes, salt, iterations, length)
        } finally {
            passwordBytes.fill(0)
        }
    }

    /** PBKDF2-HMAC-SHA256 written out on HMAC, with the UTF-8 encoding explicit. */
    fun deriveKeyReference(password: String, salt: ByteArray, iterations: Int, length: Int = 32): ByteArray {
        val passwordBytes = Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(passwordBytes, "HmacSHA256")) }
        passwordBytes.fill(0)
        val out = ByteArray(length)
        var block = 1
        var offset = 0
        while (offset < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            t.copyInto(out, offset, 0, minOf(t.size, length - offset))
            offset += t.size
            block++
        }
        return out
    }

    private fun seal(plaintext: ByteArray, key: ByteArray, aad: ByteArray): ByteArray {
        val nonce = randomBytes(NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)    // ciphertext followed by the tag
    }

    private fun open(box: ByteArray, key: ByteArray, aad: ByteArray): ByteArray {
        require(box.size >= NONCE_BYTES + TAG_BITS / 8) { "box too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, box, 0, NONCE_BYTES))
        cipher.updateAAD(aad)
        return cipher.doFinal(box, NONCE_BYTES, box.size - NONCE_BYTES)
    }

    private fun randomBytes(count: Int) = ByteArray(count).also(random::nextBytes)
}
