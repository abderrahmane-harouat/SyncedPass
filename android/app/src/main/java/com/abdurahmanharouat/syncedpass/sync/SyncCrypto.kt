package com.abdurahmanharouat.syncedpass.sync

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * The primitives of the sync protocol (docs/SYNC.md, "Cryptography"), matching
 * CryptoKit on the Mac: P-256 keys sent as SubjectPublicKeyInfo DER, ECDSA
 * with SHA-256 (DER signatures), ECDH (the 32-byte x coordinate) and
 * HKDF-SHA256.
 */
object SyncCrypto {
    private val random = SecureRandom()

    /** P-256's parameters, taken from a generated key (works on every Android version). */
    private val p256: ECParameterSpec by lazy { (generate().public as ECPublicKey).params }

    private fun generate() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    /** A new long-term identity: the 32-byte private scalar and the SPKI public key. */
    fun newIdentity(): Pair<ByteArray, ByteArray> {
        val pair = generate()
        return scalar((pair.private as ECPrivateKey).s) to pair.public.encoded
    }

    fun sign(privateKey: ByteArray, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey(privateKey))
            update(data)
            sign()
        }

    /** False for a bad signature, a malformed key or a key on another curve. */
    fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey(publicKey))
            update(data)
            verify(signature)
        }
    }.getOrDefault(false)

    /** A one-time key pair for one handshake, giving forward secrecy. */
    class Ephemeral {
        private val pair = generate()
        val publicKey: ByteArray = pair.public.encoded

        /** ECDH with the other side's ephemeral key: the 32-byte shared x coordinate. */
        fun agree(peerPublicKey: ByteArray): ByteArray = KeyAgreement.getInstance("ECDH").run {
            init(pair.private)
            doPhase(publicKey(peerPublicKey), true)
            generateSecret()
        }
    }

    /** HKDF-SHA256 (RFC 5869), for up to 32 bytes of output. */
    fun hkdf(secret: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray = hkdf(secret, salt, info.toByteArray(), length)

    fun hkdf(secret: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..32)
        val prk = hmac(salt, secret)
        return hmac(prk, info + byteArrayOf(1)).copyOf(length)
    }

    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        parts.forEach { update(it) }
        digest()
    }

    fun randomBytes(count: Int) = ByteArray(count).also(random::nextBytes)

    /** Parses an SPKI public key, accepting only P-256. */
    fun publicKey(spki: ByteArray): PublicKey {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)) as ECPublicKey
        require(key.params.curve == p256.curve && key.params.order == p256.order && key.params.generator == p256.generator) { "Not a P-256 key" }
        return key
    }

    private fun privateKey(scalar: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), p256))

    /** A private scalar as exactly 32 big-endian bytes. */
    private fun scalar(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return when {
            bytes.size == 32 -> bytes
            bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)  // leading sign byte
            else -> ByteArray(32 - bytes.size) + bytes
        }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
}

/**
 * AES-256-GCM for one direction of a connection. The nonce is 4 zero bytes
 * and a 64-bit counter that both sides keep, so it's never sent; a frame
 * that's replayed, reordered or altered fails to decrypt.
 */
class FrameCipher(key: ByteArray) {
    private val key = SecretKeySpec(key, "AES")
    private var counter = 0L

    fun seal(plaintext: ByteArray): ByteArray = run(Cipher.ENCRYPT_MODE, plaintext)

    fun open(ciphertext: ByteArray): ByteArray = run(Cipher.DECRYPT_MODE, ciphertext)

    private fun run(mode: Int, data: ByteArray): ByteArray {
        val nonce = ByteArray(12)
        for (i in 0 until 8) nonce[4 + i] = (counter ushr (56 - 8 * i)).toByte()
        counter++
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, key, GCMParameterSpec(128, nonce))
            doFinal(data)
        }
    }
}
