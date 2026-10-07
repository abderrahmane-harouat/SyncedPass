package com.abdurahmanharouat.syncedpass.crypto

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Fast PBKDF2-HMAC-SHA256 (RFC 8018).
 *
 * Each round is HMAC(password, previous). HMAC hashes the padded password key
 * once for the inner and once for the outer hash; those two states never
 * change, so they're computed once here and every round costs exactly two
 * SHA-256 block compressions on fixed-size input, with no allocation and no
 * calls into a crypto provider. Results are checked against the published
 * test vectors and the straightforward implementation in the tests.
 */
internal object Pbkdf2 {
    fun deriveKey(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        require(iterations >= 1 && length >= 1)
        // HMAC key: hash it first if longer than one block, then pad to 64 bytes.
        val key = (if (password.size > 64) MessageDigest.getInstance("SHA-256").digest(password) else password).copyOf(64)
        val innerState = IV.copyOf().also { compress(it, words(ByteArray(64) { (key[it].toInt() xor 0x36).toByte() })) }
        val outerState = IV.copyOf().also { compress(it, words(ByteArray(64) { (key[it].toInt() xor 0x5c).toByte() })) }
        key.fill(0)

        // The first round's message (salt || block index) has any length, so it
        // goes through a regular HMAC; every later round uses the fast path.
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        val out = ByteArray(length)
        val w = IntArray(64)
        val state = IntArray(8)
        val u = IntArray(8)
        val t = IntArray(8)
        var block = 1
        var offset = 0
        while (offset < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            val first = words(mac.doFinal().copyOf(64))
            for (i in 0 until 8) { u[i] = first[i]; t[i] = first[i] }
            repeat(iterations - 1) {
                // Inner hash of the 32-byte previous result: one padded block.
                for (i in 0 until 8) w[i] = u[i]
                w[8] = 0x80000000.toInt(); for (i in 9 until 15) w[i] = 0; w[15] = (64 + 32) * 8
                innerState.copyInto(state); compress(state, w)
                // Outer hash of the 32-byte inner result: one padded block.
                for (i in 0 until 8) w[i] = state[i]
                w[8] = 0x80000000.toInt(); for (i in 9 until 15) w[i] = 0; w[15] = (64 + 32) * 8
                outerState.copyInto(state); compress(state, w)
                for (i in 0 until 8) { u[i] = state[i]; t[i] = t[i] xor state[i] }
            }
            for (i in 0 until 8) for (b in 0 until 4) {
                val pos = offset + i * 4 + b
                if (pos < length) out[pos] = (t[i] ushr (24 - 8 * b)).toByte()
            }
            offset += 32
            block++
        }
        return out
    }

    /** 64 bytes as 16 big-endian words, in a 64-entry schedule array. */
    private fun words(block: ByteArray): IntArray {
        val w = IntArray(64)
        for (i in 0 until 16) {
            w[i] = (block[i * 4].toInt() and 0xff shl 24) or (block[i * 4 + 1].toInt() and 0xff shl 16) or
                (block[i * 4 + 2].toInt() and 0xff shl 8) or (block[i * 4 + 3].toInt() and 0xff)
        }
        return w
    }

    /** SHA-256 compression of the block in w[0..15] into [state]. Overwrites w[16..63]. */
    private fun compress(state: IntArray, w: IntArray) {
        for (i in 16 until 64) {
            val x = w[i - 15]; val y = w[i - 2]
            val s0 = x.rotateRight(7) xor x.rotateRight(18) xor (x ushr 3)
            val s1 = y.rotateRight(17) xor y.rotateRight(19) xor (y ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (i in 0 until 64) {
            val t1 = h + (e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)) + ((e and f) xor (e.inv() and g)) + K[i] + w[i]
            val t2 = (a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)) + ((a and b) xor (a and c) xor (b and c))
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        state[0] += a; state[1] += b; state[2] += c; state[3] += d
        state[4] += e; state[5] += f; state[6] += g; state[7] += h
    }

    private val IV = intArrayOf(
        0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
        0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
    )

    private val K = longArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ).map { it.toInt() }.toIntArray()
}
