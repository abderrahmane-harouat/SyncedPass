package com.abdurahmanharouat.syncedpass

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.abdurahmanharouat.syncedpass.crypto.EncryptedContainer
import com.abdurahmanharouat.syncedpass.crypto.VaultCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a phone or emulator: checks Android's own crypto provider derives
 * the same keys as the reference implementation (and so as the Mac), and
 * that it opens the vault written by the Mac.
 */
@RunWith(AndroidJUnit4::class)
class CryptoOnDeviceTest {
    @Test fun fastPbkdf2MatchesReferenceAndPublishedVectors() {
        val salt = ByteArray(16) { it.toByte() }
        for (pw in listOf("password", "p@ss wörd ✓ 🔑", "كلمة المرور", "cafe\u0301", "fixture master password")) {
            assertArrayEquals(pw, VaultCrypto.deriveKeyReference(pw, salt, 1_000), VaultCrypto.deriveKey(pw, salt, 1_000))
        }
        val hex = VaultCrypto.deriveKey("password", "salt".toByteArray(), 4096).joinToString("") { "%02x".format(it) }
        assertEquals("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a", hex)
    }

    @Test fun opensTheMacVaultOnDevice() {
        val data = InstrumentationRegistry.getInstrumentation().context.assets.open("mac-fixtures/vault.syncedpass").readBytes()
        val container = VaultCrypto.container(data, EncryptedContainer.Kind.Vault)
        val items = VaultCrypto.open(container, VaultCrypto.unlock(container, "fixture master password"))
        assertEquals(setOf("Gmail", "Notion"), items.map { it.title }.toSet())
    }

    @Test fun fullStrengthKeyDerivationIsFast() {
        val start = System.nanoTime()
        VaultCrypto.deriveKey("correct horse battery", ByteArray(16), VaultCrypto.DEFAULT_ITERATIONS)
        val ms = (System.nanoTime() - start) / 1_000_000
        println("PBKDF2 600,000 rounds: $ms ms")
        assert(ms < 3_000) { "600,000 rounds took $ms ms" }
    }
}
