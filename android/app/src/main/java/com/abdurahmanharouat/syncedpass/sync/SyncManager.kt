package com.abdurahmanharouat.syncedpass.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import com.abdurahmanharouat.syncedpass.BuildConfig
import com.abdurahmanharouat.syncedpass.model.SyncIdentity
import com.abdurahmanharouat.syncedpass.model.SyncPeer
import com.abdurahmanharouat.syncedpass.vault.VaultStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.UUID

/**
 * Keeps the vault in sync with paired Macs on the local network
 * (docs/SYNC.md). While the vault is unlocked, the phone listens and
 * announces itself with Network Service Discovery, and it also looks for Macs
 * and connects to them: whichever connection gets through is used, and each
 * stays open as a live two-way session, so a change on either side reaches
 * the other within moments. (The Mac does the same, so a Mac firewall that
 * holds back the phone's connection doesn't stop sync.)
 */
class SyncManager(context: Context, private val store: VaultStore, private val scope: CoroutineScope) {
    sealed interface PeerState {
        data object Waiting : PeerState
        data class Connected(val lastSynced: Instant?) : PeerState
        data class Failed(val message: String) : PeerState
    }

    sealed interface PairingState {
        /** Announced as ready to pair; waiting for a Mac's pairing window to connect. */
        data object Ready : PairingState
        data class Confirming(val code: String, val macName: String) : PairingState
        data class WaitingForMac(val code: String, val macName: String) : PairingState
        data class Paired(val macName: String) : PairingState
        data class Failed(val message: String) : PairingState
    }

    private val context = context.applicationContext
    private val nsd = context.getSystemService(NsdManager::class.java)

    private val _peerStates = MutableStateFlow<Map<UUID, PeerState>>(emptyMap())
    val peerStates: StateFlow<Map<UUID, PeerState>> = _peerStates.asStateFlow()

    private val _pairing = MutableStateFlow<PairingState?>(null)
    /** The pairing in progress, while the pairing screen is open. */
    val pairing: StateFlow<PairingState?> = _pairing.asStateFlow()

    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var advertisedReadyToPair: Boolean? = null
    /** Live connections, closed when sync stops. */
    private val sockets = mutableSetOf<Socket>()
    /** The open session with each Mac, and whether this phone opened it. */
    private val sessions = mutableMapOf<UUID, Pair<Socket, Boolean>>()
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolving = kotlinx.coroutines.sync.Mutex()
    /** Connection loops to Macs found on the network, by address. */
    private val macConnections = mutableMapOf<String, Job>()
    /** Macs that turned out not to be paired with this phone, and when. */
    private val notOurs = mutableMapOf<String, Long>()
    private var pairingJob: Job? = null
    private var pairingSocket: Socket? = null
    private var userDecision: CompletableDeferred<Boolean>? = null

    val deviceName: String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    // Starting and stopping

    /** Starts or stops to match the vault's state. Call when it locks or unlocks, and after pairing changes. */
    @Synchronized
    fun refresh() {
        val unlocked = store.status.value == VaultStore.Status.Unlocked
        val wanted = unlocked && (store.contents.value.sync?.peers.orEmpty().isNotEmpty() || _pairing.value != null)
        if (wanted && !running) start() else if (!wanted && running) stop()
        if (running) advertise()
    }

    private fun start() {
        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(debugPort() ?: 0))
            }
        }.getOrNull() ?: return
        server = socket
        running = true
        scope.launch(Dispatchers.IO) { acceptConnections(socket) }
        advertise()
        startDiscovery()
        debugMac()?.let { connectWhileFound(it.first, it.second, "debug") }
    }

    private fun stop() {
        running = false
        unadvertise()
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        macConnections.values.forEach { it.cancel() }
        macConnections.clear()
        runCatching { server?.close() }
        server = null
        synchronized(sockets) {
            sockets.forEach { runCatching { it.close() } }
            sockets.clear()
        }
        sessions.clear()
        _peerStates.value = emptyMap()
        if (store.status.value != VaultStore.Status.Unlocked) cancelPairing()
    }

    // Announcing this phone

    /**
     * Announces this phone as `_syncedpass-phone._tcp`, with whether it's
     * ready to pair, so Macs know whether to connect for pairing or for sync.
     */
    @Synchronized
    private fun advertise() {
        val port = server?.localPort ?: return
        val ready = _pairing.value == PairingState.Ready
        if (registration != null && advertisedReadyToPair == ready) return
        unadvertise()
        val info = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("pairing", if (ready) "1" else "0")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        if (runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess) {
            registration = listener
            advertisedReadyToPair = ready
        }
    }

    @Synchronized
    private fun unadvertise() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        advertisedReadyToPair = null
    }

    /** Debug builds only: a fixed port from files/debug-sync-port, for testing on an emulator with `adb forward`. */
    private fun debugPort(): Int? {
        if (!BuildConfig.DEBUG) return null
        return File(context.filesDir, "debug-sync-port").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
    }

    // Finding Macs and connecting to them

    private fun startDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                scope.launch(Dispatchers.IO) { resolve(info)?.let { (address, port) -> connectWhileFound(address, port, info.serviceName) } }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(macConnections) { macConnections.entries.removeAll { (key, job) -> key.startsWith(info.serviceName + "@").also { if (it) job.cancel() } } }
            }
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discovery = listener
        runCatching { nsd.discoverServices(MAC_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    /** One resolve at a time: older Android versions fail concurrent resolves. */
    @Suppress("DEPRECATION")  // resolveService still works; its replacement needs API 34
    private suspend fun resolve(info: NsdServiceInfo): Pair<InetAddress, Int>? = resolving.lock().let {
        try {
            kotlin.coroutines.suspendCoroutine { continuation ->
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = continuation.resumeWith(Result.success(null))
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host
                        continuation.resumeWith(Result.success(if (host != null && isLocal(host)) host to serviceInfo.port else null))
                    }
                })
            }
        } finally {
            resolving.unlock()
        }
    }

    /** Debug builds only: a Mac at a fixed address from files/debug-mac-endpoint ("host:port"), e.g. on an emulator. */
    private fun debugMac(): Pair<InetAddress, Int>? {
        if (!BuildConfig.DEBUG) return null
        val text = File(context.filesDir, "debug-mac-endpoint").takeIf { it.exists() }?.readText()?.trim() ?: return null
        val port = text.substringAfterLast(':').toIntOrNull() ?: return null
        return runCatching { InetAddress.getByName(text.substringBeforeLast(':')) to port }.getOrNull()
    }

    /**
     * Keeps trying to reach a Mac while it's on the network, whenever this
     * phone isn't already connected to every paired Mac (the Mac may well
     * have connected to the phone first).
     */
    private fun connectWhileFound(address: InetAddress, port: Int, name: String) {
        val key = "$name@${address.hostAddress}:$port"
        synchronized(macConnections) {
            if (macConnections[key]?.isActive == true) return
            macConnections[key] = scope.launch(Dispatchers.IO) {
                var backoff = 2_000L
                while (running) {
                    val refused = synchronized(notOurs) { notOurs[key] }
                    val peers = store.contents.value.sync?.peers.orEmpty()
                    val allConnected = peers.isNotEmpty() && synchronized(sessions) { peers.all { it.deviceID in sessions } }
                    if (peers.isNotEmpty() && !allConnected && _pairing.value == null &&
                        (refused == null || System.currentTimeMillis() - refused > 60_000)) {
                        val socket = Socket()
                        synchronized(sockets) { sockets += socket }
                        try {
                            socket.connect(InetSocketAddress(address, port), 5_000)
                            if (session(socket, initiatedByPhone = true)) backoff = 2_000L else synchronized(notOurs) { notOurs[key] = System.currentTimeMillis() }
                        } catch (e: Exception) {
                            // Not reachable right now, e.g. the Mac's firewall is still asking; the Mac's own connection may get through.
                        } finally {
                            runCatching { socket.close() }
                            synchronized(sockets) { sockets -= socket }
                        }
                    }
                    kotlinx.coroutines.delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(10_000L)
                }
            }
        }
    }

    // Connections from Macs

    private fun acceptConnections(server: ServerSocket) {
        while (running && !server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            if (!isLocal(socket.inetAddress)) {
                runCatching { socket.close() }  // local network only
                continue
            }
            synchronized(sockets) { sockets += socket }
            scope.launch(Dispatchers.IO) {
                try {
                    handle(socket)
                } finally {
                    runCatching { socket.close() }
                    synchronized(sockets) { sockets -= socket }
                }
            }
        }
    }

    private suspend fun handle(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = 15_000  // the handshake must be prompt
        // While the pairing screen is open, a Mac connects to pair. Only one
        // attempt per opening: any failure ends pairing on screen, so nobody
        // can retry unseen until a code happens to match.
        val pairingNow = synchronized(this) {
            if (_pairing.value == PairingState.Ready && pairingSocket == null) {
                pairingSocket = socket
                true
            } else false
        }
        if (pairingNow) {
            pair(socket)
            return
        }
        if (synchronized(this) { pairingSocket != null }) return  // a pairing exchange is under way
        session(socket, initiatedByPhone = false)
    }

    /**
     * Runs a sync session over [socket], whichever side opened it; the phone
     * always speaks first. Returns false if the other side isn't a Mac this
     * phone is paired with.
     */
    private suspend fun session(socket: Socket, initiatedByPhone: Boolean): Boolean {
        val identity = store.contents.value.sync ?: return true
        socket.tcpNoDelay = true
        socket.soTimeout = 15_000  // the handshake must be prompt
        val frames = FrameStream(socket.getInputStream(), socket.getOutputStream())
        val (channel, peer) = try {
            SyncHandshake.openSession(frames, identity)
        } catch (e: SyncException.NotPaired) {
            return false
        } catch (e: SyncException.ClockSkew) {
            markAll(PeerState.Failed(e.message.orEmpty()))
            return true
        } catch (e: Exception) {
            return true  // a broken handshake
        }
        socket.soTimeout = 70_000  // the Mac pings every 25 seconds
        val keep = synchronized(sessions) {
            val existing = sessions[peer.deviceID]
            // If both sides connected at once, both keep the connection the phone
            // opened (docs/SYNC.md); otherwise a newer connection replaces an older one.
            if (existing != null && existing.second && !initiatedByPhone) false
            else {
                existing?.first?.let { runCatching { it.close() } }
                sessions[peer.deviceID] = socket to initiatedByPhone
                true
            }
        }
        if (!keep) return true
        setState(peer.deviceID, PeerState.Connected(null))
        try {
            runSyncSession(channel, store) { setState(peer.deviceID, PeerState.Connected(Instant.now())) }
        } catch (e: Exception) {
            // Dropped, or the vault locked; either side reconnects.
        } finally {
            val current = synchronized(sessions) {
                (sessions[peer.deviceID]?.first === socket).also { if (it) sessions.remove(peer.deviceID) }
            }
            if (current) setState(peer.deviceID, PeerState.Waiting)
        }
        return true
    }

    private fun setState(id: UUID, state: PeerState) = _peerStates.update { it + (id to state) }

    private fun markAll(state: PeerState) =
        _peerStates.update { store.contents.value.sync?.peers.orEmpty().associate { p -> p.deviceID to state } }

    fun unpair(id: UUID) {
        store.updateSync { sync -> sync?.copy(peers = sync.peers.filterNot { it.deviceID == id }) }
        _peerStates.update { it - id }
        synchronized(sessions) { sessions.remove(id)?.let { runCatching { it.first.close() } } }
        synchronized(notOurs) { notOurs.clear() }
        refresh()
    }

    // Pairing

    /** Opens the pairing screen: announces this phone as ready to pair. */
    fun openPairing() {
        if (_pairing.value == null) _pairing.value = PairingState.Ready
        refresh()
    }

    fun closePairing() {
        cancelPairing()
        _pairing.value = null
        refresh()
    }

    /** Ready to pair again, e.g. after a failed attempt. */
    fun restartPairing() {
        cancelPairing()
        _pairing.value = PairingState.Ready
        refresh()
    }

    /** This phone's user compared the codes. */
    fun confirmPairing(accepted: Boolean) {
        userDecision?.complete(accepted)
    }

    /** Pairing over a connection from a Mac's pairing window (docs/SYNC.md, "Pairing"). */
    private suspend fun pair(socket: Socket) {
        val decision = CompletableDeferred<Boolean>()
        userDecision = decision
        val job = scope.launch(Dispatchers.IO) {
            try {
                val frames = FrameStream(socket.getInputStream(), socket.getOutputStream())
                val pairing = SyncHandshake.startPairing(frames, ensureIdentity())
                socket.soTimeout = 0  // comparing codes may take a while
                val macName = pairing.mac.deviceName
                _pairing.value = PairingState.Confirming(pairing.code, macName)

                // The Mac's user may answer before or after this phone's.
                val fromMac = async { runCatching { pairing.awaitMac() } }
                var macResult: Result<SyncPeer>? = null
                var accepted: Boolean? = null
                while (accepted == null) {
                    select {
                        decision.onAwait { accepted = it }
                        if (macResult == null) fromMac.onAwait { result ->
                            macResult = result
                            result.exceptionOrNull()?.let { throw it }
                        }
                    }
                }
                pairing.confirm(accepted == true)
                if (accepted != true) throw SyncException.Cancelled()
                if (macResult == null) _pairing.value = PairingState.WaitingForMac(pairing.code, macName)
                val peer = (macResult ?: fromMac.await()).getOrThrow()
                store.updateSync { sync -> sync?.copy(peers = sync.peers.filterNot { it.deviceID == peer.deviceID } + peer) }
                _pairing.value = PairingState.Paired(peer.name)
            } catch (e: SyncException.Cancelled) {
                _pairing.value = if (decision.isCompleted) PairingState.Ready else PairingState.Failed("Pairing was cancelled on the Mac.")
            } catch (e: SyncException) {
                _pairing.value = PairingState.Failed(e.message.orEmpty())
            } catch (e: Exception) {
                if (isActive) _pairing.value = PairingState.Failed("The connection with the Mac was interrupted. Try again, and make sure only your Mac is pairing.")
            } finally {
                runCatching { socket.close() }
                synchronized(this@SyncManager) { if (pairingSocket === socket) pairingSocket = null }
                refresh()
            }
        }
        pairingJob = job
        job.join()
    }

    private fun cancelPairing() {
        userDecision?.complete(false)
        userDecision = null
        synchronized(this) {
            runCatching { pairingSocket?.close() }
            pairingSocket = null
        }
        pairingJob?.cancel()
        pairingJob = null
    }

    /** This phone's sync identity, created the first time it pairs. */
    private fun ensureIdentity(): SyncIdentity {
        store.contents.value.sync?.let { identity ->
            if (identity.deviceName != deviceName) store.updateSync { it?.copy(deviceName = deviceName) }
            return store.contents.value.sync ?: identity
        }
        val (privateKey, publicKey) = SyncCrypto.newIdentity()
        store.updateSync { it ?: SyncIdentity(UUID.randomUUID(), deviceName, privateKey, publicKey) }
        return store.contents.value.sync!!
    }

    companion object {
        const val SERVICE_TYPE = "_syncedpass-phone._tcp"
        const val MAC_SERVICE_TYPE = "_syncedpass._tcp"

        /** Loopback, private IPv4, link-local, or unique local IPv6 (docs/SYNC.md, "Local network only"). */
        fun isLocal(address: InetAddress): Boolean = when (address) {
            is Inet4Address -> address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress
            is Inet6Address -> address.isLoopbackAddress || address.isLinkLocalAddress ||
                (address.address[0].toInt() and 0xFE) == 0xFC ||
                (address.isIPv4CompatibleAddress && isLocal(InetAddress.getByAddress(address.address.copyOfRange(12, 16))))
            else -> false
        }
    }
}
