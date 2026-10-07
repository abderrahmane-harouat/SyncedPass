package com.abdurahmanharouat.syncedpass.vault

import com.abdurahmanharouat.syncedpass.crypto.EncryptedContainer
import com.abdurahmanharouat.syncedpass.crypto.VaultCrypto
import com.abdurahmanharouat.syncedpass.crypto.VaultException
import com.abdurahmanharouat.syncedpass.crypto.VaultKeys
import com.abdurahmanharouat.syncedpass.model.Deletion
import com.abdurahmanharouat.syncedpass.model.LoginItem
import com.abdurahmanharouat.syncedpass.model.ReferenceDate
import com.abdurahmanharouat.syncedpass.model.SyncIdentity
import com.abdurahmanharouat.syncedpass.model.SyncRecords
import com.abdurahmanharouat.syncedpass.model.VaultContents
import com.abdurahmanharouat.syncedpass.model.merging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Owns the encrypted vault file and its decrypted contents while unlocked.
 * Same rules as VaultStore.swift on macOS:
 *   - every change is written to disk before it shows up in [items]
 *   - writes are atomic (temp file, then rename)
 *   - the version before the latest save is kept as vault.previous.syncedpass
 *   - deleting a login leaves a deletion record, so sync spreads the deletion
 *
 * Sync runs on background threads, so every change goes through [persist],
 * which is synchronized.
 */
class VaultStore(directory: File, private val iterations: Int = VaultCrypto.DEFAULT_ITERATIONS) {
    enum class Status { NeedsSetup, Locked, Unlocked }

    companion object {
        /** How far ahead another device's clock may be (docs/SYNC.md). */
        const val MAX_CLOCK_SKEW_SECONDS = 5 * 60.0
    }

    data class ImportSummary(val added: Int = 0, val updated: Int = 0, val unchanged: Int = 0) {
        val message: String get() = "$added added, $updated updated, $unchanged already up to date."
    }

    val vaultFile = File(directory, "vault.syncedpass")
    val previousVersionFile = File(directory, "vault.previous.syncedpass")

    private val _status = MutableStateFlow(if (vaultFile.exists()) Status.Locked else Status.NeedsSetup)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _contents = MutableStateFlow(VaultContents())
    /** Everything in the vault: logins, deletion records and the sync identity. Empty while locked. */
    val contents: StateFlow<VaultContents> = _contents.asStateFlow()

    private val _items = MutableStateFlow<List<LoginItem>>(emptyList())
    val items: StateFlow<List<LoginItem>> = _items.asStateFlow()

    private var keys: VaultKeys? = null
    private val lock = Any()

    suspend fun create(masterPassword: String) {
        if (_status.value != Status.NeedsSetup) return
        val newKeys = withContext(Dispatchers.Default) { VaultCrypto.makeKeys(masterPassword, iterations) }
        synchronized(lock) {
            write(VaultCrypto.seal(VaultContents(), newKeys))
            keys = newKeys
            publish(VaultContents())
            _status.value = Status.Unlocked
        }
    }

    suspend fun unlock(masterPassword: String) {
        if (_status.value != Status.Locked) return
        val (unlockedKeys, loaded) = withContext(Dispatchers.Default) {
            val container = VaultCrypto.container(vaultFile.readBytes(), EncryptedContainer.Kind.Vault)
            val k = VaultCrypto.unlock(container, masterPassword)
            k to VaultCrypto.openContents(container, k)
        }
        synchronized(lock) {
            keys = unlockedKeys
            publish(loaded)
            _status.value = Status.Unlocked
        }
    }

    /**
     * Replaces the master password after checking the current one.
     *
     * A new random vault key is generated and everything re-encrypted, rather
     * than re-wrapping the old key: if the old password leaked, someone with
     * an old copy of the file could otherwise recover the key and read future
     * versions too. The kept previous version is replaced for the same reason,
     * so nothing on disk still opens with the old password. Same as the Mac.
     */
    suspend fun changeMasterPassword(current: String, new: String) {
        if (_status.value != Status.Unlocked || keys == null) throw VaultException.Locked()
        val newKeys = withContext(Dispatchers.Default) {
            val container = VaultCrypto.container(vaultFile.readBytes(), EncryptedContainer.Kind.Vault)
            VaultCrypto.unlock(container, current).vaultKey.fill(0)
            if (new == current) throw VaultException.SamePassword()
            VaultCrypto.makeKeys(new, iterations)
        }
        synchronized(lock) {
            val old = keys
            if (_status.value != Status.Unlocked || old == null) throw VaultException.Locked()  // locked while deriving
            write(VaultCrypto.seal(_contents.value, newKeys))
            keys = newKeys
            old.vaultKey.fill(0)
            runCatching { Files.copy(vaultFile.toPath(), previousVersionFile.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                .onFailure { previousVersionFile.delete() }
        }
    }

    fun lock() {
        synchronized(lock) {
            if (_status.value != Status.Unlocked) return
            keys?.vaultKey?.fill(0)
            keys = null
            _contents.value.sync?.privateKey?.fill(0)  // the sync identity key, too
            publish(VaultContents())
            _status.value = Status.Locked
        }
    }

    fun save(item: LoginItem) = update { contents ->
        val index = contents.items.indexOfFirst { it.id == item.id }
        val items = if (index >= 0) contents.items.toMutableList().also { it[index] = item } else contents.items + item
        // Saving a login that was deleted elsewhere brings it back.
        contents.copy(items = items, deletions = contents.deletions.filterNot { it.id == item.id })
    }

    fun delete(ids: Set<UUID>) = update { contents ->
        if (contents.items.none { it.id in ids }) return@update null
        val now = ReferenceDate.now()
        contents.copy(
            items = contents.items.filterNot { it.id in ids },
            deletions = contents.deletions.filterNot { it.id in ids } + ids.filter { id -> contents.items.any { it.id == id } }.map { Deletion(it, now) },
        )
    }

    /** Logins sorted by title, filtered by [query] on title, email, username and websites. */
    fun search(query: String): List<LoginItem> {
        val sorted = _items.value.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        val q = query.trim()
        if (q.isEmpty()) return sorted
        return sorted.filter { item ->
            (listOf(item.title, item.email, item.username) + item.websites).any { it.contains(q, ignoreCase = true) }
        }
    }

    // Sync

    /** Merges logins and deletions from another device; true when anything changed. */
    fun applySync(records: SyncRecords): Boolean {
        var changed = false
        val limit = ReferenceDate(ReferenceDate.now().secondsSinceReferenceDate + MAX_CLOCK_SKEW_SECONDS)
        update { contents -> contents.merging(records, notAfter = limit)?.also { changed = true } }
        return changed
    }

    /** Changes the sync identity (pairing, unpairing, last-synced times). */
    fun updateSync(transform: (SyncIdentity?) -> SyncIdentity?) = update { contents ->
        val updated = transform(contents.sync)
        if (updated == contents.sync) null else contents.copy(sync = updated)
    }

    // Backups

    /** An encrypted backup of every login, protected by [password] (independent of the master password). */
    suspend fun exportBackup(password: String): ByteArray {
        if (_status.value != Status.Unlocked) throw VaultException.Locked()
        val snapshot = _items.value
        return withContext(Dispatchers.Default) {
            val backupKeys = VaultCrypto.makeKeys(password, iterations)
            VaultCrypto.seal(snapshot, EncryptedContainer.Kind.Backup, backupKeys).also { backupKeys.vaultKey.fill(0) }
        }
    }

    /** Checks that [data] is a SyncedPass backup this version can read. */
    fun inspectBackup(data: ByteArray): EncryptedContainer = VaultCrypto.container(data, EncryptedContainer.Kind.Backup)

    suspend fun importBackup(container: EncryptedContainer, password: String): ImportSummary {
        val incoming = withContext(Dispatchers.Default) {
            val backupKeys = VaultCrypto.unlock(container, password)
            VaultCrypto.open(container, backupKeys).also { backupKeys.vaultKey.fill(0) }
        }
        return merge(incoming)
    }

    /**
     * Adds imported logins without creating duplicates, like the Mac:
     * - same ID (a backup of this vault): the newer edit wins
     * - same title, username, email, password and websites: already there, skipped
     * - a login that was deleted: restored, as a new edit so sync spreads the restore
     * - anything else: added
     */
    fun merge(incoming: List<LoginItem>): ImportSummary {
        var summary = ImportSummary()
        update { contents ->
            val result = contents.items.toMutableList()
            val deletions = contents.deletions.associateBy { it.id }.toMutableMap()
            var added = 0; var updated = 0; var unchanged = 0
            for (item in incoming) {
                val index = result.indexOfFirst { it.id == item.id }
                when {
                    index >= 0 && item.modifiedAt > result[index].modifiedAt -> { result[index] = item; updated++ }
                    index >= 0 -> unchanged++
                    result.any { it.hasSameCredentials(item) } -> unchanged++
                    deletions.remove(item.id) != null -> { result += item.copy(modifiedAt = ReferenceDate.now()); added++ }
                    else -> { result += item; added++ }
                }
            }
            summary = ImportSummary(added, updated, unchanged)
            if (added + updated > 0) contents.copy(items = result, deletions = deletions.values.toList()) else null
        }
        return summary
    }

    private fun LoginItem.hasSameCredentials(other: LoginItem) =
        title == other.title && username == other.username && email == other.email &&
            password == other.password && websites == other.websites

    // Disk

    /** Applies [transform] to the contents and saves the result; a null result means no change. */
    private fun update(transform: (VaultContents) -> VaultContents?) {
        synchronized(lock) {
            val k = keys
            if (_status.value != Status.Unlocked || k == null) throw VaultException.Locked()
            val updated = transform(_contents.value) ?: return
            write(VaultCrypto.seal(updated, k))
            publish(updated)
        }
    }

    private fun publish(contents: VaultContents) {
        _contents.value = contents
        _items.value = contents.items
    }

    private fun write(data: ByteArray) {
        vaultFile.parentFile?.mkdirs()
        if (vaultFile.exists()) {
            // Best effort: losing the extra copy must never block saving.
            runCatching { Files.copy(vaultFile.toPath(), previousVersionFile.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        }
        val temp = File(vaultFile.parentFile, "${vaultFile.name}.tmp")
        FileOutputStream(temp).use { out ->
            out.write(data)
            out.fd.sync()
        }
        Files.move(temp.toPath(), vaultFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
