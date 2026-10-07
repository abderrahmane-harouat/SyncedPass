package com.abdurahmanharouat.syncedpass

import com.abdurahmanharouat.syncedpass.crypto.EncryptedContainer
import com.abdurahmanharouat.syncedpass.crypto.VaultCrypto
import com.abdurahmanharouat.syncedpass.crypto.VaultException
import com.abdurahmanharouat.syncedpass.model.LoginItem
import com.abdurahmanharouat.syncedpass.model.ReferenceDate
import com.abdurahmanharouat.syncedpass.vault.VaultStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Export, import and changing the master password, with the same rules as the Mac. */
class BackupAndPasswordTest {
    @get:Rule val folder = TemporaryFolder()
    private val master = "correct horse battery"
    private val backupPassword = "backup password 123"

    private suspend fun store(vararg items: LoginItem) = VaultStore(folder.newFolder(), iterations = 1_000).apply {
        create(master)
        items.forEach { save(it) }
    }

    @Test fun backupRoundTripsIntoAnotherVault() = runBlocking {
        val item = LoginItem(title = "GitHub", email = "alex@example.com", password = "Zebra-4471")
        val data = store(item).exportBackup(backupPassword)
        assertFalse(data.decodeToString().contains("Zebra-4471"))

        val other = store()
        val container = other.inspectBackup(data)
        assertThrowsVault<VaultException.WrongPassword> { runBlocking { other.importBackup(container, "wrong password!!") } }
        assertEquals(VaultStore.ImportSummary(added = 1), other.importBackup(container, backupPassword))
        assertEquals(listOf(item), other.items.value)
    }

    @Test fun importingTheSameBackupTwiceAddsNothing() = runBlocking {
        val s = store(LoginItem(title = "Notion"))
        val container = s.inspectBackup(s.exportBackup(backupPassword))
        assertEquals(VaultStore.ImportSummary(unchanged = 1), s.importBackup(container, backupPassword))
        assertEquals(1, s.items.value.size)
    }

    @Test fun mergeKeepsTheNewerEditAndSkipsDuplicates() = runBlocking {
        val old = LoginItem(title = "Slack", password = "old", modifiedAt = ReferenceDate(100.0))
        val s = store(old, LoginItem(title = "Figma", username = "alex"))
        val summary = s.merge(listOf(
            old.copy(password = "new", modifiedAt = ReferenceDate(200.0)),   // same ID, newer: updates
            old.copy(password = "older", modifiedAt = ReferenceDate(50.0)),  // same ID, older: ignored
            LoginItem(title = "Figma", username = "alex"),                   // same credentials, new ID: skipped
            LoginItem(title = "Dribbble"),                                   // new: added
        ))
        assertEquals(VaultStore.ImportSummary(added = 1, updated = 1, unchanged = 2), summary)
        assertEquals("new", s.items.value.first { it.id == old.id }.password)
        assertEquals(listOf("Slack", "Figma", "Dribbble"), s.items.value.map { it.title })
    }

    @Test fun opensABackupExportedOnTheMac() = runBlocking {
        val data = javaClass.getResourceAsStream("/mac-fixtures/backup.syncedpass")!!.readBytes()
        val s = store()
        val summary = s.importBackup(s.inspectBackup(data), "fixture backup password")
        assertEquals(s.items.value.size, summary.added)
    }

    @Test fun aVaultFileIsNotABackup() = runBlocking {
        val s = store()
        assertThrowsVault<VaultException.WrongKind> { s.inspectBackup(s.vaultFile.readBytes()) }
        assertThrowsVault<VaultException.NotSyncedPassFile> { s.inspectBackup("{\"some\": \"json\"}".toByteArray()) }
    }

    @Test fun changingTheMasterPasswordReEncryptsEverything() = runBlocking {
        val item = LoginItem(title = "Gmail", email = "alex@gmail.com")
        val s = store(item)
        val oldWrappedKey = VaultCrypto.container(s.vaultFile.readBytes(), EncryptedContainer.Kind.Vault).wrappedKey

        assertThrowsVault<VaultException.WrongPassword> { runBlocking { s.changeMasterPassword("not the password", "brand new password") } }
        assertThrowsVault<VaultException.SamePassword> { runBlocking { s.changeMasterPassword(master, master) } }
        s.changeMasterPassword(master, "brand new password")
        s.save(item.copy(note = "saved after the change"))  // the store keeps working with the new key

        val container = VaultCrypto.container(s.vaultFile.readBytes(), EncryptedContainer.Kind.Vault)
        assertFalse("A new vault key, not the old one re-wrapped", container.wrappedKey.contentEquals(oldWrappedKey))
        for (file in listOf(s.vaultFile, s.previousVersionFile)) {
            val dir = folder.newFolder().also { file.copyTo(File(it, "vault.syncedpass")) }
            val reopened = VaultStore(dir, iterations = 1_000)
            assertThrowsVault<VaultException.WrongPassword> { runBlocking { reopened.unlock(master) } }
            reopened.unlock("brand new password")
            assertEquals(item.title, reopened.items.value.single().title)
        }
    }
}
