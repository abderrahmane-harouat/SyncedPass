package com.abdurahmanharouat.syncedpass

import com.abdurahmanharouat.syncedpass.model.LoginItem
import com.abdurahmanharouat.syncedpass.model.ReferenceDate
import com.abdurahmanharouat.syncedpass.model.SyncIdentity
import com.abdurahmanharouat.syncedpass.model.SyncPeer
import com.abdurahmanharouat.syncedpass.sync.FrameStream
import com.abdurahmanharouat.syncedpass.sync.SyncCrypto
import com.abdurahmanharouat.syncedpass.sync.SyncException
import com.abdurahmanharouat.syncedpass.sync.SyncHandshake
import com.abdurahmanharouat.syncedpass.sync.runSyncSession
import com.abdurahmanharouat.syncedpass.vault.VaultStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID

/**
 * Sync end to end between this Android code and the Mac app's real sync code,
 * over real TCP connections, in both directions: the Mac connecting to the
 * phone, and the phone connecting to the Mac (docs/SYNC.md). The Mac side is
 * Scripts/sync-harness (the Mac's VaultStore and SyncService); this test plays
 * the phone. Skipped unless SYNCEDPASS_HARNESS points to the built harness;
 * see android/README.md, "Checking compatibility".
 */
class CrossPlatformSyncTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var mac: Process
    /** Where the Mac listens, for the phone to connect to it. */
    private val macPort = 53_100 + (0 until 800).random()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var phone: VaultStore
    private val loopback = InetAddress.getLoopbackAddress()

    @Before fun startMac() {
        val harness = System.getenv("SYNCEDPASS_HARNESS")
        assumeTrue("Set SYNCEDPASS_HARNESS to run", harness != null && File(harness).canExecute())
        mac = ProcessBuilder(harness).apply { environment()["SYNCEDPASS_SYNC_PORT"] = macPort.toString() }.redirectErrorStream(true).start()
        assertEquals("ready", macLine())
    }

    @After fun stopMac() {
        scope.cancel()
        if (::mac.isInitialized) {
            runCatching { tellMac("quit") }
            mac.destroy()
        }
    }

    private val macOutput by lazy { mac.inputStream.bufferedReader() }
    private fun tellMac(command: String) = mac.outputStream.run { write("$command\n".toByteArray()); flush() }
    private fun macLine(): String = macOutput.readLine() ?: error("The Mac harness exited")
    private fun macCommand(command: String) = tellMac(command).also { assertEquals("ok", macLine()) }

    /** The Mac's logins as "title|note", sorted. */
    private fun macLogins(): List<String> {
        tellMac("dump")
        return generateSequence { macLine().takeIf { it != "end" } }.toList()
    }

    private fun phoneLogins() = phone.items.value.map { "${it.title}|${it.note}" }.sorted()

    private fun eventually(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!check()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for $what")
            Thread.sleep(150)
        }
    }

    private fun phoneEdit(title: String, note: String) {
        val login = phone.items.value.first { it.title == title }
        phone.save(login.copy(note = note, modifiedAt = ReferenceDate.now()))
    }

    private fun listen(port: Int = 0) = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(loopback, port))
    }

    private fun frames(socket: Socket) = FrameStream(socket.getInputStream(), socket.getOutputStream())

    private suspend fun newPhone(name: String = "Test Phone"): VaultStore =
        VaultStore(folder.newFolder(), iterations = 1_000).apply {
            create("phone master password")
            val (privateKey, publicKey) = SyncCrypto.newIdentity()
            updateSync { SyncIdentity(UUID.randomUUID(), name, privateKey, publicKey) }
        }

    /** Pairs [store] with the Mac through a listening socket, like the phone's pairing screen. */
    private fun pairWithMac(store: VaultStore, server: ServerSocket): SyncPeer {
        macCommand("phone 127.0.0.1:${server.localPort} pair")
        tellMac("pair")
        assertEquals("pairing", macLine())
        val socket = server.accept()  // the Mac finds the phone and connects
        val pairing = SyncHandshake.startPairing(frames(socket), store.contents.value.sync!!)
        assertEquals("Both sides derive the same code", "code ${pairing.code} ${store.contents.value.sync!!.deviceName}", macLine())
        pairing.confirm(true)
        val peer = pairing.awaitMac()
        assertEquals("paired ${store.contents.value.sync!!.deviceName}", macLine())
        socket.close()
        store.updateSync { it!!.copy(peers = listOf(peer)) }
        return peer
    }

    /** Accepts the Mac's connections and syncs over each, like the phone does while unlocked. */
    private fun serveSync(server: ServerSocket, sockets: MutableList<Socket>) = scope.launch {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            synchronized(sockets) { sockets += socket }
            launch {
                runCatching {
                    val (channel, _) = SyncHandshake.openSession(frames(socket), phone.contents.value.sync!!)
                    runSyncSession(channel, phone) {}
                }
                socket.close()
            }
        }
    }

    @Test fun macConnectsToThePhoneAndSyncsEachLoginOnItsOwnBothWays() = runBlocking {
        phone = newPhone()
        var server = listen()
        val port = server.localPort
        pairWithMac(phone, server)

        // From now on the phone is announced as not pairing: the Mac connects to sync.
        listOf("GitHub", "Notion", "Figma").forEach { macCommand("add $it") }
        phone.save(LoginItem(title = "Gmail"))
        val sockets = mutableListOf<Socket>()
        var serving = serveSync(server, sockets)
        macCommand("phone 127.0.0.1:$port")
        val all = listOf("Figma|", "GitHub|", "Gmail|", "Notion|")
        eventually("the first sync") { phoneLogins() == all && macLogins() == all }

        // Live, over the one connection: changes go both ways.
        macCommand("edit GitHub live-from-mac")
        eventually("the Mac's edit on the phone") { "GitHub|live-from-mac" in phoneLogins() }
        phoneEdit("Gmail", "live-from-phone")
        eventually("the phone's edit on the Mac") { "Gmail|live-from-phone" in macLogins() }

        // Offline: both sides change different logins, delete and add, and edit the same one.
        server.close(); serving.cancel()
        synchronized(sockets) { sockets.forEach { it.close() }; sockets.clear() }
        Thread.sleep(500)
        macCommand("edit GitHub offline-mac")
        macCommand("add Slack")
        macCommand("edit Gmail mac-earlier")
        Thread.sleep(50)
        phoneEdit("Notion", "offline-phone")
        phoneEdit("Gmail", "phone-later")
        phone.delete(setOf(phone.items.value.first { it.title == "Figma" }.id))

        // Back on the network: the Mac reconnects on its own and both sides merge.
        server = listen(port)
        serving = serveSync(server, sockets)
        val merged = listOf("GitHub|offline-mac", "Gmail|phone-later", "Notion|offline-phone", "Slack|")
        eventually("the merge after being offline") { phoneLogins() == merged && macLogins() == merged }
        assertEquals("Figma's deletion is recorded", 1, phone.contents.value.deletions.size)

        macCommand("delete Slack")
        eventually("the Mac's deletion on the phone") { phoneLogins() == merged - "Slack|" }
        server.close(); serving.cancel()
    }

    @Test fun phoneConnectsToTheMacAndSyncsBothWays() = runBlocking {
        phone = newPhone()
        listen().use { pairWithMac(phone, it) }  // the phone then stops listening: only its own connection remains
        macCommand("add GitHub")
        phone.save(LoginItem(title = "Gmail"))

        val socket = Socket(loopback, macPort)  // the Mac listens too
        val (channel, _) = SyncHandshake.openSession(frames(socket), phone.contents.value.sync!!)
        val session = scope.launch { runCatching { runSyncSession(channel, phone) {} } }
        val all = listOf("GitHub|", "Gmail|")
        eventually("the sync over the phone's connection") { phoneLogins() == all && macLogins() == all }
        macCommand("edit GitHub from-mac")
        eventually("the Mac's edit on the phone") { "GitHub|from-mac" in phoneLogins() }
        phoneEdit("Gmail", "from-phone")
        eventually("the phone's edit on the Mac") { "Gmail|from-phone" in macLogins() }
        socket.close(); session.cancel()
    }

    @Test fun refusesDevicesThatArentPaired() = runBlocking {
        phone = newPhone()
        val server = listen()
        val mac = pairWithMac(phone, server)

        // A phone announcing it's ready to pair gets no connection once the Mac's pairing window is closed.
        listen().use { other ->
            macCommand("phone 127.0.0.1:${other.localPort} pair")
            other.soTimeout = 5_000
            assertThrows<SocketTimeoutException> { other.accept() }
        }

        // A phone the Mac never paired with is turned away, whichever side connects.
        val (strangerKey, strangerPublic) = SyncCrypto.newIdentity()
        val stranger = SyncIdentity(UUID.randomUUID(), "Stranger", strangerKey, strangerPublic, peers = listOf(mac))
        listen().use { other ->
            macCommand("phone 127.0.0.1:${other.localPort}")
            other.accept().use { socket ->
                assertThrows<Exception> { SyncHandshake.openSession(frames(socket), stranger).first.receive() }
            }
        }
        Socket(loopback, macPort).use { socket ->
            assertThrows<Exception> { SyncHandshake.openSession(frames(socket), stranger).first.receive() }
        }

        // A "Mac" that can't sign with the paired Mac's key is caught by the phone.
        val (_, otherKey) = SyncCrypto.newIdentity()
        val fooled = phone.contents.value.sync!!.copy(peers = listOf(mac.copy(publicKey = otherKey)))
        Socket(loopback, macPort).use { socket ->
            assertThrows<SyncException.AuthenticationFailed> { SyncHandshake.openSession(frames(socket), fooled) }
        }

        // The real pairing still works.
        Socket(loopback, macPort).use { socket ->
            val (channel, peer) = SyncHandshake.openSession(frames(socket), phone.contents.value.sync!!)
            assertEquals(mac.deviceID, peer.deviceID)
            channel.receiveMessage()  // the Mac's first manifest
        }
        server.close()
    }

    @Test fun aFailedPairingAttemptEndsPairingVisibly() = runBlocking {
        listen().use { attacker ->
            macCommand("phone 127.0.0.1:${attacker.localPort} pair")
            tellMac("pair")
            assertEquals("pairing", macLine())
            // The attacker commits, sees the Mac's keys, and drops out because its code wouldn't match.
            attacker.accept().use { socket ->
                val frames = frames(socket)
                val commitment = java.util.Base64.getEncoder().encodeToString(SyncCrypto.randomBytes(32))
                frames.write("""{"type":"pair","protocol":1,"commitment":"$commitment"}""".toByteArray())
                frames.read(64 * 1024)
            }
            assertEquals("The Mac shows the failure instead of quietly trying again", "pairing failed", macLine())
            // And it doesn't try again: the user has to start pairing again.
            attacker.soTimeout = 5_000
            assertThrows<SocketTimeoutException> { attacker.accept() }
        }
        // Pairing can't be started by connecting to the Mac either: the Mac starts it.
        Socket(loopback, macPort).use { socket ->
            assertThrows<Exception> {
                SyncHandshake.startPairing(frames(socket), SyncIdentity(UUID.randomUUID(), "x", ByteArray(32), SyncCrypto.newIdentity().second))
            }
        }
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            throw AssertionError("Expected ${T::class.simpleName}, got $e")
        }
        throw AssertionError("Expected ${T::class.simpleName}, nothing was thrown")
    }
}
