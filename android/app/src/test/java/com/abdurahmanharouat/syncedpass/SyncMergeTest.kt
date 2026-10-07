package com.abdurahmanharouat.syncedpass

import com.abdurahmanharouat.syncedpass.crypto.EncryptedContainer
import com.abdurahmanharouat.syncedpass.crypto.VaultCrypto
import com.abdurahmanharouat.syncedpass.model.Deletion
import com.abdurahmanharouat.syncedpass.model.LoginItem
import com.abdurahmanharouat.syncedpass.model.ReferenceDate
import com.abdurahmanharouat.syncedpass.model.SyncRecords
import com.abdurahmanharouat.syncedpass.model.VaultContents
import com.abdurahmanharouat.syncedpass.model.hasRecordsNeededBy
import com.abdurahmanharouat.syncedpass.model.manifest
import com.abdurahmanharouat.syncedpass.model.merging
import com.abdurahmanharouat.syncedpass.model.recordsNeededBy
import com.abdurahmanharouat.syncedpass.vault.VaultStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Per-login merging (docs/SYNC.md, "Merging"), between two simulated devices. */
class SyncMergeTest {
    @get:Rule val folder = TemporaryFolder()

    private fun t(seconds: Double) = ReferenceDate(seconds)

    /** One sync between two devices: each sends its manifest, each answers with what the other needs. */
    private fun sync(mac: VaultContents, phone: VaultContents): Triple<VaultContents, VaultContents, Int> {
        val toPhone = mac.recordsNeededBy(phone.manifest())
        val toMac = phone.recordsNeededBy(mac.manifest())
        val newMac = mac.merging(toMac) ?: mac
        val newPhone = phone.merging(toPhone) ?: phone
        return Triple(newMac, newPhone, toPhone.items.size + toPhone.deletions.size + toMac.items.size + toMac.deletions.size)
    }

    private fun VaultContents.byId() = items.associateBy { it.id }

    private val gmail = LoginItem(title = "Gmail", email = "alex@gmail.com", modifiedAt = t(100.0))
    private val github = LoginItem(title = "GitHub", password = "old", modifiedAt = t(100.0))
    private val notion = LoginItem(title = "Notion", modifiedAt = t(100.0))
    private val start = VaultContents(listOf(gmail, github, notion))

    @Test fun differentLoginsEditedOfflineOnEachSideAreBothKept() {
        // Offline: the Mac edits GitHub, the phone edits Notion.
        val mac = start.copy(items = listOf(gmail, github.copy(password = "from mac", modifiedAt = t(200.0)), notion))
        val phone = start.copy(items = listOf(gmail, github, notion.copy(note = "from phone", modifiedAt = t(300.0))))

        val (newMac, newPhone, sent) = sync(mac, phone)

        for (device in listOf(newMac, newPhone)) {
            assertEquals("from mac", device.byId().getValue(github.id).password)
            assertEquals("from phone", device.byId().getValue(notion.id).note)
            assertEquals(gmail, device.byId().getValue(gmail.id))
        }
        assertEquals("Only the two changed logins travel", 2, sent)
        assertEquals(newMac.items.toSet(), newPhone.items.toSet())
    }

    @Test fun theSameLoginEditedOnBothSidesKeepsTheLaterEdit() {
        val mac = start.copy(items = listOf(gmail, github.copy(password = "mac, earlier", modifiedAt = t(200.0)), notion))
        val phone = start.copy(items = listOf(gmail, github.copy(password = "phone, later", modifiedAt = t(250.0)), notion))
        val (newMac, newPhone, _) = sync(mac, phone)
        assertEquals("phone, later", newMac.byId().getValue(github.id).password)
        assertEquals("phone, later", newPhone.byId().getValue(github.id).password)
    }

    @Test fun aNewLoginOnOneSideIsAddedOnTheOther() {
        val figma = LoginItem(title = "Figma", modifiedAt = t(150.0))
        val (newMac, newPhone, sent) = sync(start.copy(items = start.items + figma), start)
        assertEquals(1, sent)
        assertTrue(newPhone.byId().containsKey(figma.id))
        assertEquals(newMac.items.toSet(), newPhone.items.toSet())
    }

    @Test fun aDeletionSpreads() {
        val mac = start.copy(items = listOf(gmail, github), deletions = listOf(Deletion(notion.id, t(200.0))))
        val (newMac, newPhone, _) = sync(mac, start)
        assertTrue(notion.id !in newPhone.byId())
        assertEquals(listOf(notion.id), newPhone.deletions.map { it.id })
        assertEquals(newMac.items.toSet(), newPhone.items.toSet())
    }

    @Test fun anEditAfterTheDeletionBringsTheLoginBack() {
        val mac = start.copy(items = listOf(gmail, github), deletions = listOf(Deletion(notion.id, t(200.0))))
        val phone = start.copy(items = listOf(gmail, github, notion.copy(note = "edited later", modifiedAt = t(300.0))))
        val (newMac, newPhone, _) = sync(mac, phone)
        assertEquals("edited later", newMac.byId().getValue(notion.id).note)
        assertTrue(newMac.deletions.isEmpty())
        assertEquals(newMac.items.toSet(), newPhone.items.toSet())
    }

    @Test fun anEditBeforeTheDeletionDoesNot() {
        val mac = start.copy(items = listOf(gmail, github), deletions = listOf(Deletion(notion.id, t(300.0))))
        val phone = start.copy(items = listOf(gmail, github, notion.copy(note = "edited earlier", modifiedAt = t(200.0))))
        val (newMac, newPhone, _) = sync(mac, phone)
        assertTrue(notion.id !in newMac.byId() && notion.id !in newPhone.byId())
    }

    @Test fun onATieTheDeletionWinsOnBothSides() {
        val mac = start.copy(items = listOf(gmail, github), deletions = listOf(Deletion(notion.id, t(200.0))))
        val phone = start.copy(items = listOf(gmail, github, notion.copy(modifiedAt = t(200.0))))
        val (newMac, newPhone, _) = sync(mac, phone)
        assertTrue(notion.id !in newMac.byId() && notion.id !in newPhone.byId())
    }

    @Test fun identicalDevicesSendNothing() {
        assertTrue(start.recordsNeededBy(start.manifest()).isEmpty)
        assertNull(start.merging(start.recordsNeededBy(VaultContents().manifest())))
    }

    @Test fun deletingInTheStoreLeavesARecordAndSavingAgainRemovesIt() = runBlocking {
        val store = VaultStore(folder.newFolder(), iterations = 1_000).apply { create("correct horse battery") }
        store.save(notion)
        store.delete(setOf(notion.id))
        val record = store.contents.value.deletions.single()
        assertEquals(notion.id, record.id)
        store.save(notion.copy(modifiedAt = ReferenceDate.now()))
        assertTrue(store.contents.value.deletions.isEmpty())
    }

    @Test fun restoringADeletedLoginFromABackupCountsAsANewEdit() = runBlocking {
        val store = VaultStore(folder.newFolder(), iterations = 1_000).apply { create("correct horse battery") }
        store.save(notion)
        store.delete(setOf(notion.id))
        val deletedAt = store.contents.value.deletions.single().deletedAt
        assertEquals(1, store.merge(listOf(notion)).added)
        val restored = store.items.value.single()
        assertTrue("Newer than the deletion, so sync spreads the restore", restored.modifiedAt > deletedAt)
        assertTrue(store.contents.value.deletions.isEmpty())
    }

    @Test fun deletionsAndPairingSurviveRelaunchAndOldVaultsStillOpen() = runBlocking {
        val dir = folder.newFolder()
        VaultStore(dir, iterations = 1_000).apply {
            create("correct horse battery")
            save(notion)
            delete(setOf(notion.id))
        }
        val reopened = VaultStore(dir, iterations = 1_000).apply { unlock("correct horse battery") }
        assertEquals(listOf(notion.id), reopened.contents.value.deletions.map { it.id })
        assertEquals(2, VaultCrypto.container(File(dir, "vault.syncedpass").readBytes(), EncryptedContainer.Kind.Vault).version)
    }

    @Test fun aManifestTellsWhenItsSenderHasNewerRecords() {
        val variants = listOf(
            start,
            start.copy(items = listOf(gmail, github.copy(modifiedAt = t(200.0)), notion)),
            start.copy(items = listOf(gmail, github), deletions = listOf(Deletion(notion.id, t(100.0)))),
            start.copy(items = listOf(gmail, github), deletions = listOf(Deletion(notion.id, t(300.0)))),
            start.copy(items = listOf(gmail)),
            start.copy(items = start.items + LoginItem(title = "Figma", modifiedAt = t(150.0))),
        )
        for (a in variants) for (b in variants) {
            assertEquals(!a.recordsNeededBy(b.manifest()).isEmpty, a.manifest().hasRecordsNeededBy(b.manifest()))
        }
    }

    @Test fun hkdfMatchesRfc5869() {
        // RFC 5869, test case 1 (first 32 bytes of the output).
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = com.abdurahmanharouat.syncedpass.sync.SyncCrypto.hkdf(ikm, salt, info, 32)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf", okm.joinToString("") { "%02x".format(it) })
    }

    @Test fun recordsDatedInTheFutureAreIgnored() {
        val limit = t(1_000.0)
        val future = SyncRecords(listOf(github.copy(password = "from the future", modifiedAt = t(5_000.0))), listOf(Deletion(notion.id, t(5_000.0))))
        assertNull(start.merging(future, notAfter = limit))
        val present = SyncRecords(listOf(github.copy(password = "now", modifiedAt = t(900.0))), emptyList())
        assertEquals("now", start.merging(present, notAfter = limit)!!.byId().getValue(github.id).password)
    }
}
