package com.abdurahmanharouat.syncedpass.sync

import com.abdurahmanharouat.syncedpass.model.Manifest
import com.abdurahmanharouat.syncedpass.model.hasRecordsNeededBy
import com.abdurahmanharouat.syncedpass.model.manifest
import com.abdurahmanharouat.syncedpass.model.recordsNeededBy
import com.abdurahmanharouat.syncedpass.vault.VaultStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/**
 * One live session with a Mac (docs/SYNC.md, "Sync sessions"): exchanges
 * manifests and only the logins that changed, until the connection drops or
 * the vault locks. [onSynced] is called whenever the two sides compared notes.
 */
@OptIn(FlowPreview::class)
suspend fun runSyncSession(channel: SecureChannel, store: VaultStore, onSynced: () -> Unit) = coroutineScope {
    var lastSent: Manifest? = null
    val sendLock = Any()
    fun sendManifest(force: Boolean = false) = synchronized(sendLock) {
        val manifest = store.contents.value.manifest()
        if (force || manifest != lastSent) {
            channel.sendManifest(manifest)
            lastSent = manifest
        }
    }
    sendManifest()

    // Tell the Mac about every change, local or from another device.
    val watcher = launch(Dispatchers.IO) {
        store.contents.map { it.manifest() }.distinctUntilChanged().debounce(300).collect {
            if (store.status.value == VaultStore.Status.Unlocked) runCatching { sendManifest() }
        }
    }
    val keepAlive = launch(Dispatchers.IO) {
        var elapsed = 0
        while (isActive) {
            delay(5_000)
            elapsed += 5
            if (elapsed % 25 == 0) runCatching { channel.sendPing() }
            if (elapsed % 60 == 0) runCatching { sendManifest(force = true) }
        }
    }
    try {
        while (isActive && store.status.value == VaultStore.Status.Unlocked) {
            when (val message = runInterruptible(Dispatchers.IO) { channel.receiveMessage() }) {
                is SessionMessage.ManifestReceived -> {
                    val contents = store.contents.value
                    val records = contents.recordsNeededBy(message.manifest)
                    if (!records.isEmpty) channel.sendRecords(records)
                    // The Mac has newer logins: show it what this phone has, so it sends them.
                    if (message.manifest.hasRecordsNeededBy(contents.manifest())) sendManifest(force = true)
                    onSynced()
                }
                is SessionMessage.RecordsReceived -> {
                    store.applySync(message.records)
                    onSynced()
                }
                SessionMessage.Ping -> {}
            }
        }
    } finally {
        watcher.cancel()
        keepAlive.cancel()
    }
}
