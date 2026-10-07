package com.abdurahmanharouat.syncedpass

import com.abdurahmanharouat.syncedpass.crypto.EncryptedContainer
import com.abdurahmanharouat.syncedpass.crypto.VaultCrypto
import com.abdurahmanharouat.syncedpass.crypto.VaultException
import com.abdurahmanharouat.syncedpass.model.LoginItem
import com.abdurahmanharouat.syncedpass.model.SignIn
import com.abdurahmanharouat.syncedpass.model.SignInMethod
import com.abdurahmanharouat.syncedpass.vault.VaultStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VaultTest {
    @get:Rule val folder = TemporaryFolder()
    private val password = "correct horse battery"

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    // Published PBKDF2-HMAC-SHA256 test vectors (P = "password", S = "salt").
    @Test fun pbkdf2MatchesPublishedVectors() {
        mapOf(
            1 to "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            2 to "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            4096 to "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
        ).forEach { (rounds, expected) ->
            assertEquals(expected, hex(VaultCrypto.deriveKey("password", "salt".toByteArray(), rounds)))
        }
    }

    @Test fun nativeAndReferencePbkdf2Agree() {
        val salt = ByteArray(16) { it.toByte() }
        for (pw in listOf("password", "p@ss wörd ✓ 🔑", "كلمة المرور", "cafe\u0301")) {
            assertArrayEquals(pw, VaultCrypto.deriveKeyReference(pw, salt, 1_000), VaultCrypto.deriveKey(pw, salt, 1_000))
        }
    }

    @Test fun passwordUnicodeIsNormalizedLikeOnTheMac() {
        val salt = ByteArray(16) { 7 }
        assertArrayEquals(VaultCrypto.deriveKey("café", salt, 10), VaultCrypto.deriveKey("café", salt, 10))
    }

    @Test fun roundTripAndNoPlaintextOnDisk() {
        val keys = VaultCrypto.makeKeys(password, iterations = 1_000)
        val item = LoginItem(title = "GitHub", email = "octo@example.com", password = "Zebra-4471",
                             signIns = listOf(SignIn(SignInMethod.GitHub)))
        val data = VaultCrypto.seal(listOf(item), EncryptedContainer.Kind.Vault, keys)
        for (secret in listOf("GitHub", "octo@example.com", "Zebra-4471", password)) {
            assertFalse(secret, data.decodeToString().contains(secret))
        }
        val container = VaultCrypto.container(data, EncryptedContainer.Kind.Vault)
        assertEquals(listOf(item), VaultCrypto.open(container, VaultCrypto.unlock(container, password)))
    }

    @Test fun tamperingIsDetected() {
        val keys = VaultCrypto.makeKeys(password, iterations = 1_000)
        val container = VaultCrypto.container(VaultCrypto.seal(listOf(LoginItem(title = "x")), EncryptedContainer.Kind.Vault, keys), EncryptedContainer.Kind.Vault)
        val payload = container.payload.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertThrowsVault<VaultException.Damaged> { VaultCrypto.open(container.copy(payload = payload), keys) }
        assertThrowsVault<VaultException.Damaged> {
            VaultCrypto.open(container.copy(format = EncryptedContainer.Kind.Backup), VaultCrypto.unlock(container, password))
        }
    }

    @Test fun shortPasswordsAreRejected() {
        assertThrowsVault<VaultException.PasswordTooShort> { VaultCrypto.makeKeys("short", iterations = 1_000) }
    }

    @Test fun storeSurvivesRelaunchAndLocks() = runBlocking {
        val dir = folder.newFolder()
        val store = VaultStore(dir, iterations = 1_000)
        assertEquals(VaultStore.Status.NeedsSetup, store.status.value)
        store.create(password)
        val item = LoginItem(title = "Gmail", email = "alex@example.com")
        store.save(item)

        val relaunched = VaultStore(dir, iterations = 1_000)
        assertEquals(VaultStore.Status.Locked, relaunched.status.value)
        assertThrowsVault<VaultException.WrongPassword> { runBlocking { relaunched.unlock("wrong password!!") } }
        relaunched.unlock(password)
        assertEquals(listOf(item), relaunched.items.value)

        relaunched.lock()
        assertEquals(emptyList<LoginItem>(), relaunched.items.value)
        assertThrowsVault<VaultException.Locked> { relaunched.save(item) }
    }

    @Test fun previousVersionIsKept() = runBlocking {
        val dir = folder.newFolder()
        val store = VaultStore(dir, iterations = 1_000)
        store.create(password)
        val first = LoginItem(title = "First")
        store.save(first)
        store.save(LoginItem(title = "Second"))

        val restored = folder.newFolder()
        store.previousVersionFile.copyTo(java.io.File(restored, "vault.syncedpass"))
        val old = VaultStore(restored, iterations = 1_000).also { it.unlock(password) }
        assertEquals(listOf(first), old.items.value)
    }
}
