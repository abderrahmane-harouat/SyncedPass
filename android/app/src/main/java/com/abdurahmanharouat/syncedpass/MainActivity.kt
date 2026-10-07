package com.abdurahmanharouat.syncedpass

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.contentcapture.ContentCaptureManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abdurahmanharouat.syncedpass.ui.ChangeMasterPasswordScreen
import com.abdurahmanharouat.syncedpass.ui.ExportBackupScreen
import com.abdurahmanharouat.syncedpass.ui.HomeScreen
import com.abdurahmanharouat.syncedpass.ui.ImportBackupScreen
import com.abdurahmanharouat.syncedpass.ui.Operation
import com.abdurahmanharouat.syncedpass.ui.PairMacScreen
import com.abdurahmanharouat.syncedpass.ui.PrivateTextInput
import com.abdurahmanharouat.syncedpass.ui.SettingsScreen
import com.abdurahmanharouat.syncedpass.ui.SyncScreen
import com.abdurahmanharouat.syncedpass.ui.LoginDetailScreen
import com.abdurahmanharouat.syncedpass.ui.LoginEditorScreen
import com.abdurahmanharouat.syncedpass.ui.SetupScreen
import com.abdurahmanharouat.syncedpass.ui.UnlockScreen
import com.abdurahmanharouat.syncedpass.ui.VaultViewModel
import com.abdurahmanharouat.syncedpass.ui.theme.SyncedPassTheme
import com.abdurahmanharouat.syncedpass.vault.VaultStore
import java.io.File

class MainActivity : ComponentActivity() {
    private val vault: VaultViewModel by viewModels()
    private val handler = Handler(Looper.getMainLooper())

    /** Locks if a file picker opened by the app is left in the background, e.g. for another app. */
    private val lockAfterPicker = Runnable {
        vault.awaitingPicker = false
        vault.lock()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Keeps the screen out of screenshots, screen recordings, the recent-apps
        // preview, and assistants that read the screen (Circle to Search, Gemini,
        // Bixby), which would send it to Google or Samsung.
        if (!allowScreenshotsForDebugging()) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // Content capture lets Android System Intelligence see the text on screen.
        if (Build.VERSION.SDK_INT >= 29) getSystemService(ContentCaptureManager::class.java)?.isContentCaptureEnabled = false
        setContent { SyncedPassTheme { PrivateTextInput { App(vault) } } }
    }

    /** Debug builds only, for README screenshots: files/debug-allow-screenshots. */
    private fun allowScreenshotsForDebugging() =
        BuildConfig.DEBUG && File(filesDir, "debug-allow-screenshots").exists()

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(lockAfterPicker)
    }

    override fun onStop() {
        super.onStop()
        // Lock when the app leaves the screen (not when it's just rotating).
        if (isChangingConfigurations) return
        // Opening a system file picker for a backup stops the activity too.
        // The vault stays unlocked while the picker is used, for a limited time.
        if (vault.awaitingPicker) handler.postDelayed(lockAfterPicker, PICKER_GRACE_MILLIS) else vault.lock()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(lockAfterPicker)
    }
}

@Composable
private fun App(vault: VaultViewModel) {
    val status by vault.store.status.collectAsStateWithLifecycle()
    val items by vault.store.items.collectAsStateWithLifecycle()
    val busy by vault.busy.collectAsStateWithLifecycle()
    val error by vault.error.collectAsStateWithLifecycle()
    // null: home. NEW_LOGIN: editor for a new login. Otherwise the login being edited.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    // The logins being viewed, most recent last; Back returns to the previous one, then home.
    var viewing by rememberSaveable { mutableStateOf(listOf<String>()) }
    // Kept here rather than in the home screen, which leaves the screen while a
    // login is open: coming back keeps the search and the scroll position.
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    // null: home (or the editor). Otherwise a settings screen, see Route.
    var route by rememberSaveable { mutableStateOf<Route?>(null) }
    val exportState by vault.exportState.collectAsStateWithLifecycle()
    val importState by vault.importState.collectAsStateWithLifecycle()
    val passwordState by vault.passwordState.collectAsStateWithLifecycle()
    val contents by vault.store.contents.collectAsStateWithLifecycle()
    val peers = contents.sync?.peers.orEmpty()

    // Backup files have no registered type, so any file can be picked; the
    // contents are checked after reading.
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        vault.awaitingPicker = false
        vault.writeExport(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        vault.awaitingPicker = false
        vault.openImport(uri) { route = Route.Import }
    }
    fun leave(to: Route?, state: kotlinx.coroutines.flow.StateFlow<Operation>) {
        vault.resetOperation(state)
        route = to
    }

    when (status) {
        VaultStore.Status.NeedsSetup -> SetupScreen(busy, error, vault::create)
        VaultStore.Status.Locked -> {
            editing = null
            viewing = emptyList()
            query = ""
            route = null
            UnlockScreen(busy, error, vault::unlock)
        }
        VaultStore.Status.Unlocked -> {
            val edited = editing
            when (route) {
                Route.Settings -> SettingsScreen(
                    importState = importState,
                    syncSummary = if (peers.isEmpty()) "Not set up" else "Paired with ${peers.joinToString { it.name }}",
                    onBack = { route = null },
                    onSync = { route = Route.Sync },
                    onExport = { route = Route.Export },
                    onImport = {
                        vault.awaitingPicker = true
                        importLauncher.launch(arrayOf("*/*"))
                    },
                    onDismissImportError = { vault.resetOperation(vault.importState) },
                    onChangePassword = { route = Route.ChangePassword },
                )
                Route.Export -> ExportBackupScreen(
                    state = exportState,
                    onExport = { password ->
                        vault.prepareExport(password) {
                            vault.awaitingPicker = true
                            exportLauncher.launch("SyncedPass Backup ${java.time.LocalDate.now()}.syncedpass")
                        }
                    },
                    onClose = { leave(Route.Settings, vault.exportState) },
                )
                Route.Import -> if (vault.pendingImport == null && importState !is Operation.Succeeded) {
                    route = Route.Settings
                } else {
                    ImportBackupScreen(importState, vault::importBackup, onClose = { leave(Route.Settings, vault.importState) })
                }
                Route.ChangePassword -> ChangeMasterPasswordScreen(
                    state = passwordState,
                    onChange = vault::changeMasterPassword,
                    onExportBackup = { leave(Route.Export, vault.passwordState) },
                    onClose = { leave(Route.Settings, vault.passwordState) },
                )
                Route.Sync -> {
                    val states by vault.sync.peerStates.collectAsStateWithLifecycle()
                    SyncScreen(
                        deviceName = vault.sync.deviceName,
                        peers = peers,
                        states = states,
                        onBack = { route = Route.Settings },
                        onPair = {
                            vault.sync.openPairing()
                            route = Route.PairMac
                        },
                        onUnpair = vault.sync::unpair,
                    )
                }
                Route.PairMac -> {
                    val pairing by vault.sync.pairing.collectAsStateWithLifecycle()
                    LaunchedEffect(Unit) { vault.sync.openPairing() }
                    PairMacScreen(
                        state = pairing,
                        deviceName = vault.sync.deviceName,
                        onConfirm = vault.sync::confirmPairing,
                        onRetry = vault.sync::restartPairing,
                        onClose = {
                            vault.sync.closePairing()
                            route = Route.Sync
                        },
                    )
                }
                null -> {}
            }
            if (route != null) return
            val viewed = viewing.lastOrNull()?.let { id -> items.firstOrNull { it.id.toString() == id } }
            if (edited == null && viewing.isNotEmpty() && viewed == null) {
                viewing = viewing.dropLast(1)  // deleted, here or on another device
            }
            if (edited == null && viewed != null) {
                LoginDetailScreen(
                    item = viewed,
                    allItems = items,
                    onEdit = {
                        vault.startEditing(viewed)
                        editing = viewed.id.toString()
                    },
                    onOpen = { id -> viewing = viewing + id.toString() },
                    onBack = { viewing = viewing.dropLast(1) },
                )
            } else if (edited == null) {
                HomeScreen(
                    items = items,
                    search = vault.store::search,
                    query = query,
                    onQuery = { query = it },
                    listState = listState,
                    onLock = vault::lock,
                    onSettings = { route = Route.Settings },
                    onNew = { vault.startEditing(null); editing = NEW_LOGIN },
                    onOpen = { viewing = listOf(it.id.toString()) },
                )
            } else {
                val draft = vault.draft
                if (draft == null) {
                    // The draft lives in memory only; without it there's nothing to edit.
                    editing = null
                } else {
                    LoginEditorScreen(
                        isNew = edited == NEW_LOGIN,
                        draft = draft,
                        onChange = { vault.draft = it },
                        allItems = items,
                        onSave = vault::save,
                        onDelete = { vault.delete(draft.id) },
                        onClose = { vault.draft = null; editing = null },
                    )
                }
            }
        }
    }
}

private const val NEW_LOGIN = "new"

/** How long the vault stays unlocked while a file picker opened by the app is in front. */
private const val PICKER_GRACE_MILLIS = 2 * 60 * 1000L

private enum class Route { Settings, Export, Import, ChangePassword, Sync, PairMac }

