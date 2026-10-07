package com.abdurahmanharouat.syncedpass

import com.abdurahmanharouat.syncedpass.crypto.EncryptedContainer
import com.abdurahmanharouat.syncedpass.crypto.VaultCrypto
import com.abdurahmanharouat.syncedpass.crypto.VaultException
import com.abdurahmanharouat.syncedpass.model.CustomField
import com.abdurahmanharouat.syncedpass.model.LoginItem
import com.abdurahmanharouat.syncedpass.model.ReferenceDate
import com.abdurahmanharouat.syncedpass.model.SignIn
import com.abdurahmanharouat.syncedpass.model.SignInMethod
import com.abdurahmanharouat.syncedpass.model.SyncedPassJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Files in test/resources/mac-fixtures were written by the macOS app's own
 * code (VaultStore.swift, 1,000 PBKDF2 rounds) with made-up data. If these
 * tests pass, Android reads exactly what the Mac writes.
 */
class MacCompatibilityTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResourceAsStream("mac-fixtures/$name")!!.readBytes()

    private val gmailId = UUID.fromString("11111111-2222-4333-8444-555555555555")
    private val notionId = UUID.fromString("AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE")

    private fun openMacVault(): List<LoginItem> {
        val container = VaultCrypto.container(fixture("vault.syncedpass"), EncryptedContainer.Kind.Vault)
        return VaultCrypto.open(container, VaultCrypto.unlock(container, "fixture master password"))
    }

    @Test fun opensAVaultWrittenByTheMac() {
        val items = openMacVault().associateBy { it.id }
        assertEquals(setOf(gmailId, notionId), items.keys)

        val gmail = items.getValue(gmailId)
        assertEquals("Gmail", gmail.title)
        assertEquals("alex@example.com", gmail.email)
        assertEquals("p@ss wörd ✓ 🔑 \"q\" \\ /", gmail.password)
        assertEquals(listOf("https://mail.google.com"), gmail.websites)
        assertEquals(listOf(SignIn(SignInMethod.Standard)), gmail.signIns)
        assertEquals(780_000_000.123456, gmail.createdAt.secondsSinceReferenceDate, 0.0)

        val notion = items.getValue(notionId)
        assertEquals(listOf(SignIn(SignInMethod.Google, gmailId), SignIn(SignInMethod.Standard)), notion.signIns)
        assertTrue(notion.signsInWith(gmailId))
        assertEquals("JBSWY3DPEHPK3PXP", notion.totpSecret)
        assertEquals("+213 555 00 00 00", notion.phoneNumber)
        assertEquals("0042", notion.pin)
        assertEquals("Line one\nالعربية «unicode»", notion.note)
        assertEquals(
            listOf(CustomField.Kind.Text, CustomField.Kind.Hidden, CustomField.Kind.Totp, CustomField.Kind.Date),
            notion.customFields.map { it.kind },
        )
        assertEquals(800_000_000.5, notion.customFields.last().date.secondsSinceReferenceDate, 0.0)
        assertEquals(780_000_999.75, notion.modifiedAt.secondsSinceReferenceDate, 0.0)
    }

    @Test fun opensABackupWrittenByTheMac() {
        val container = VaultCrypto.container(fixture("backup.syncedpass"), EncryptedContainer.Kind.Backup)
        val items = VaultCrypto.open(container, VaultCrypto.unlock(container, "fixture backup password"))
        assertEquals(2, items.size)
    }

    @Test fun rejectsTheWrongPasswordForAMacVault() {
        val container = VaultCrypto.container(fixture("vault.syncedpass"), EncryptedContainer.Kind.Vault)
        assertThrowsVault<VaultException.WrongPassword> { VaultCrypto.unlock(container, "not the password") }
        assertThrowsVault<VaultException.WrongKind> { VaultCrypto.container(fixture("vault.syncedpass"), EncryptedContainer.Kind.Backup) }
    }

    @Test fun reWritingMacItemsKeepsEveryValueBitForBit() {
        val items = openMacVault()
        val again = SyncedPassJson.decodeFromString<List<LoginItem>>(SyncedPassJson.encodeToString(items))
        assertEquals(items, again)
    }

    @Test fun writesJsonInTheMacsFormat() {
        val item = LoginItem(
            id = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"), title = "x",
            signIns = listOf(SignIn(SignInMethod.Google)),
            createdAt = ReferenceDate(1.5), modifiedAt = ReferenceDate(2.0),
        )
        val json = SyncedPassJson.encodeToString(item)
        assertTrue(json, "\"id\":\"AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE\"" in json)       // uppercase UUID
        assertTrue(json, "\"signIns\":[{\"method\":\"Google OAuth\"}]" in json)            // nil accountID left out
        assertTrue(json, "\"createdAt\":1.5" in json)                                      // seconds since 2001
        for (key in listOf("email", "username", "password", "totpSecret", "websites", "phoneNumber", "pin", "note", "customFields", "modifiedAt")) {
            assertTrue("$key must always be written (Swift requires it)", "\"$key\":" in json)
        }
    }

    @Test fun readsItemsFromBeforeSignInsBecameAList() {
        val legacy = """{"id":"7D1E2F3A-0000-4000-8000-000000000001","title":"Notion","email":"","username":"",
            "password":"","totpSecret":"","websites":[],"signInMethod":"Google OAuth","phoneNumber":"","pin":"",
            "note":"","customFields":[],"createdAt":0,"modifiedAt":0}"""
        assertEquals(listOf(SignIn(SignInMethod.Google)), SyncedPassJson.decodeFromString<LoginItem>(legacy).signIns)
        val none = legacy.replace("Google OAuth", "")
        assertEquals(emptyList<SignIn>(), SyncedPassJson.decodeFromString<LoginItem>(none).signIns)
    }
}

inline fun <reified T : VaultException> assertThrowsVault(block: () -> Unit) {
    try {
        block()
    } catch (e: VaultException) {
        assertTrue("Expected ${T::class.simpleName}, got ${e::class.simpleName}", e is T)
        return
    }
    throw AssertionError("Expected ${T::class.simpleName}, nothing was thrown")
}

/**
 * Writes a vault with the Android code for the Mac side to open (see
 * android/README.md, "Checking compatibility"). Output:
 * app/build/android-fixtures/vault.syncedpass, password "fixture master password".
 */
class WriteFixtureForMacTest {
    @Test fun writeVaultForTheMac() = kotlinx.coroutines.runBlocking {
        val dir = java.io.File("build/android-fixtures").apply { deleteRecursively(); mkdirs() }
        val store = com.abdurahmanharouat.syncedpass.vault.VaultStore(dir, iterations = 1_000)
        store.create("fixture master password")
        val account = LoginItem(id = UUID.fromString("11111111-2222-4333-8444-555555555555"), title = "Gmail",
                                email = "alex@example.com", password = "p@ss wörd ✓ 🔑",
                                signIns = listOf(SignIn(SignInMethod.Standard)), createdAt = ReferenceDate(780_000_000.123456))
        store.save(account)
        store.save(LoginItem(title = "Notion", note = "العربية", signIns = listOf(SignIn(SignInMethod.Google, account.id)),
                             customFields = listOf(CustomField(kind = CustomField.Kind.Date, name = "Expires", date = ReferenceDate(800_000_000.5)))))
    }
}
