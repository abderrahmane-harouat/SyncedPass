package com.abdurahmanharouat.syncedpass.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abdurahmanharouat.syncedpass.crypto.EncryptedContainer
import com.abdurahmanharouat.syncedpass.crypto.VaultException
import com.abdurahmanharouat.syncedpass.model.LoginItem
import com.abdurahmanharouat.syncedpass.sync.SyncManager
import com.abdurahmanharouat.syncedpass.vault.VaultStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID

/** The state of a slow action started from a settings screen. */
sealed interface Operation {
    data object Idle : Operation
    data object Working : Operation
    data class Failed(val message: String, val wrongPassword: Boolean = false) : Operation
    data class Succeeded(val message: String) : Operation
}

/** Holds the vault for the UI and runs the slow key derivation off the main thread. */
class VaultViewModel(app: Application) : AndroidViewModel(app) {
    val store = VaultStore(app.filesDir)

    /** Syncs with paired Macs while the vault is unlocked (docs/SYNC.md). */
    val sync = SyncManager(app, store, viewModelScope)

    init {
        viewModelScope.launch { store.status.collect { sync.refresh() } }
    }

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun clearError() { _error.value = null }

    fun create(password: String) = runSlow { store.create(password) }

    fun unlock(password: String) = runSlow { store.unlock(password) }

    /**
     * The login being edited, or null. Kept here rather than in saved instance
     * state, so unsaved passwords survive rotation but are never written to
     * disk by the system. Cleared when the vault locks.
     */
    var draft by mutableStateOf<LoginItem?>(null)

    /** Starts editing [item], or a new login when null. */
    fun startEditing(item: LoginItem?) {
        val start = item ?: LoginItem()
        // Like the Mac editor: always show at least one website row.
        draft = if (start.websites.isEmpty()) start.copy(websites = listOf("")) else start
    }

    override fun onCleared() = lock()

    fun lock() {
        draft = null
        // pendingExport is kept: it's already encrypted, so a save that was
        // under way when the vault locked can still finish.
        pendingImport = null
        listOf(_exportState, _importState, _passwordState).forEach { it.value = Operation.Idle }
        store.lock()
    }

    // Backups and the master password

    private val _exportState = MutableStateFlow<Operation>(Operation.Idle)
    val exportState: StateFlow<Operation> = _exportState.asStateFlow()
    private val _importState = MutableStateFlow<Operation>(Operation.Idle)
    val importState: StateFlow<Operation> = _importState.asStateFlow()
    private val _passwordState = MutableStateFlow<Operation>(Operation.Idle)
    val passwordState: StateFlow<Operation> = _passwordState.asStateFlow()

    /**
     * True while a system file picker opened by the app is in front. Going to
     * the picker stops the activity, which would otherwise lock the vault in
     * the middle of an import.
     */
    var awaitingPicker = false

    /** A sealed backup waiting for the user to choose where to save it, and how many logins it holds. Already encrypted. */
    private var pendingExport: Pair<ByteArray, Int>? = null

    /** A backup file that was opened and is waiting for its password. Still encrypted. */
    var pendingImport by mutableStateOf<EncryptedContainer?>(null)
        private set

    /** Back to Idle when leaving the screen that shows [state]. */
    fun resetOperation(state: StateFlow<Operation>) {
        when (state) {
            exportState -> _exportState
            importState -> _importState
            passwordState -> _passwordState
            else -> null
        }?.value = Operation.Idle
    }

    /** Encrypts the backup first, then [onReady] asks where to save it. */
    fun prepareExport(password: String, onReady: () -> Unit) = perform(_exportState) {
        pendingExport = store.exportBackup(password) to store.items.value.size
        onReady()
        null  // Still working until the file is written.
    }

    /** Writes the prepared backup to [uri]; null when the user cancelled the save dialog. */
    fun writeExport(uri: Uri?) {
        val (data, count) = pendingExport ?: (null to 0)
        pendingExport = null
        if (uri == null || data == null) {
            _exportState.value = Operation.Idle
            return
        }
        // Continues the export started by prepareExport, which is still Working.
        perform(_exportState, continuing = true) {
            val resolver = getApplication<Application>().contentResolver
            withContext(Dispatchers.IO) {
                resolver.openOutputStream(uri, "wt")?.use { it.write(data) } ?: error("The file couldn't be opened for writing.")
            }
            // The vault may have locked while the save dialog was open; the
            // backup is still saved, but there's no screen left to tell.
            if (store.status.value != VaultStore.Status.Unlocked) {
                _exportState.value = Operation.Idle
                return@perform null
            }
            "${loginCount(count)} saved to “${displayName(uri) ?: "the backup file"}”. Keep the backup password safe — it's needed to restore."
        }
    }

    /** Reads the file the user picked and checks it's a backup; [onReady] then asks for its password. */
    fun openImport(uri: Uri?, onReady: () -> Unit) {
        // Nothing to do if cancelled, or if the vault locked while the picker was open.
        if (uri == null || store.status.value != VaultStore.Status.Unlocked) return
        perform(_importState) {
            val resolver = getApplication<Application>().contentResolver
            val data = withContext(Dispatchers.IO) {
                resolver.openInputStream(uri)?.use { readAtMost(it, MAX_BACKUP_BYTES) } ?: error("The file couldn't be opened.")
            } ?: throw IllegalStateException("This file is too large to be a SyncedPass backup.")
            pendingImport = store.inspectBackup(data)
            onReady()
            _importState.value = Operation.Idle
            null
        }
    }

    fun importBackup(password: String) {
        val container = pendingImport ?: return
        perform(_importState) {
            val summary = store.importBackup(container, password)
            pendingImport = null
            summary.message
        }
    }

    fun changeMasterPassword(current: String, new: String) = perform(_passwordState) {
        store.changeMasterPassword(current, new)
        "Changed"
    }

    /**
     * Runs [block]; a returned message means success, null leaves the state to
     * the block. Ignored while the same kind of action is already running,
     * unless [continuing] it.
     */
    private fun perform(state: MutableStateFlow<Operation>, continuing: Boolean = false, block: suspend () -> String?) {
        if (state.value == Operation.Working && !continuing) return
        state.value = Operation.Working
        viewModelScope.launch {
            state.value = try {
                block()?.let { Operation.Succeeded(it) } ?: state.value
            } catch (e: VaultException) {
                Operation.Failed(e.message.orEmpty(), wrongPassword = e is VaultException.WrongPassword)
            } catch (e: Exception) {
                Operation.Failed(e.message ?: "Something went wrong.")
            }
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    private fun readAtMost(input: InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > limit) return null
            out.write(buffer, 0, n)
        }
    }

    private fun loginCount(count: Int) = if (count == 1) "1 login" else "$count logins"

    private companion object {
        /** Far more than any real vault; stops a wrong file from using up memory. */
        const val MAX_BACKUP_BYTES = 64 * 1024 * 1024
    }

    /** Saves, returning an error message instead of throwing. */
    fun save(item: LoginItem): String? = runCatching { store.save(item) }.exceptionOrNull()?.message

    fun delete(id: UUID): String? = runCatching { store.delete(setOf(id)) }.exceptionOrNull()?.message

    private fun runSlow(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: VaultException) {
                _error.value = e.message
            } catch (e: Exception) {
                _error.value = "Something went wrong: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }
}
