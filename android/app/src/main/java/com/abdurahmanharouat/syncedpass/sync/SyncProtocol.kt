package com.abdurahmanharouat.syncedpass.sync

import com.abdurahmanharouat.syncedpass.model.Base64Serializer
import com.abdurahmanharouat.syncedpass.model.Deletion
import com.abdurahmanharouat.syncedpass.model.LoginItem
import com.abdurahmanharouat.syncedpass.model.Manifest
import com.abdurahmanharouat.syncedpass.model.ReferenceDate
import com.abdurahmanharouat.syncedpass.model.SyncIdentity
import com.abdurahmanharouat.syncedpass.model.SyncPeer
import com.abdurahmanharouat.syncedpass.model.SyncRecords
import com.abdurahmanharouat.syncedpass.model.SyncedPassJson
import com.abdurahmanharouat.syncedpass.model.UuidSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.math.abs

/*
 * The phone's side of the sync protocol in docs/SYNC.md: framing, pairing,
 * and opening an authenticated, encrypted session. The Mac implements the
 * other side (SyncedPass/Sync).
 */

const val SYNC_PROTOCOL = 1
private const val MAX_HANDSHAKE_FRAME = 64 * 1024
private const val MAX_FRAME = 32 * 1024 * 1024
/** Merging compares times from both devices, so their clocks must roughly agree. */
private const val MAX_CLOCK_SKEW_SECONDS = 5 * 60

sealed class SyncException(message: String) : Exception(message) {
    class NotPaired : SyncException("This Mac isn't paired with this phone.")
    class AuthenticationFailed : SyncException("The other device couldn't prove it's the one you paired with.")
    class Cancelled : SyncException("Pairing was cancelled.")
    class ClockSkew(val seconds: Long) :
        SyncException("The clocks on this phone and the Mac differ by ${seconds / 60} minutes. Set both to the correct time to sync.")
    class Protocol(detail: String) : SyncException("Unexpected reply from the other device ($detail).")
}

// Handshake frames (plaintext JSON)

@Serializable
private class PairRequest(val type: String = "pair", val protocol: Int = SYNC_PROTOCOL, @Serializable(with = Base64Serializer::class) val commitment: ByteArray)

@Serializable
class PairHello(
    @Serializable(with = UuidSerializer::class) val deviceID: UUID,
    val deviceName: String,
    @Serializable(with = Base64Serializer::class) val identityKey: ByteArray,
    @Serializable(with = Base64Serializer::class) val ephemeralKey: ByteArray,
    @Serializable(with = Base64Serializer::class) val nonce: ByteArray,
    val time: Double,
)

@Serializable
private class PairConfirm(val type: String = "confirm", val accepted: Boolean, @Serializable(with = Base64Serializer::class) val signature: ByteArray? = null)

@Serializable
private class SyncRequest(
    val type: String = "sync",
    val protocol: Int = SYNC_PROTOCOL,
    @Serializable(with = UuidSerializer::class) val deviceID: UUID,
    @Serializable(with = Base64Serializer::class) val ephemeralKey: ByteArray,
    @Serializable(with = Base64Serializer::class) val nonce: ByteArray,
    val time: Double,
)

@Serializable
private class SyncReply(
    @Serializable(with = UuidSerializer::class) val deviceID: UUID,
    @Serializable(with = Base64Serializer::class) val ephemeralKey: ByteArray,
    @Serializable(with = Base64Serializer::class) val nonce: ByteArray,
    val time: Double,
)

@Serializable
private class SignatureFrame(@Serializable(with = Base64Serializer::class) val signature: ByteArray)

// Session messages (encrypted JSON)

@Serializable
private class ManifestMessage(val type: String = "manifest", val items: Map<String, ReferenceDate>, val deletions: Map<String, ReferenceDate>)

@Serializable
private class RecordsMessage(val type: String = "records", val items: List<LoginItem>, val deletions: List<Deletion>)

/** A message received during a session. */
sealed interface SessionMessage {
    data class ManifestReceived(val manifest: Manifest) : SessionMessage
    data class RecordsReceived(val records: SyncRecords) : SessionMessage
    data object Ping : SessionMessage
}

/** Length-prefixed frames over a stream. */
class FrameStream(input: InputStream, private val output: OutputStream) {
    private val input = DataInputStream(input)

    /** Writes a frame and returns it as sent, length included (for handshake transcripts). */
    fun write(payload: ByteArray): ByteArray {
        val frame = ByteArray(4 + payload.size)
        val n = payload.size
        frame[0] = (n ushr 24).toByte(); frame[1] = (n ushr 16).toByte(); frame[2] = (n ushr 8).toByte(); frame[3] = n.toByte()
        payload.copyInto(frame, 4)
        synchronized(output) {
            output.write(frame)
            output.flush()
        }
        return frame
    }

    /** Reads one frame; returns the payload and the frame as received, length included. */
    fun read(maxBytes: Int): Pair<ByteArray, ByteArray> {
        val length = try { input.readInt() } catch (e: EOFException) { throw SyncException.Protocol("connection closed") }
        if (length < 0 || length > maxBytes) throw SyncException.Protocol("frame of $length bytes")
        val payload = ByteArray(length).also { input.readFully(it) }
        val frame = ByteArray(4 + length)
        frame[0] = (length ushr 24).toByte(); frame[1] = (length ushr 16).toByte(); frame[2] = (length ushr 8).toByte(); frame[3] = length.toByte()
        payload.copyInto(frame, 4)
        return payload to frame
    }
}

/** Encrypted, authenticated messages after a handshake; one key per direction. */
class SecureChannel(private val frames: FrameStream, sendKey: ByteArray, receiveKey: ByteArray) {
    private val sender = FrameCipher(sendKey)
    private val receiver = FrameCipher(receiveKey)

    fun send(json: String) {
        // The counter and the write must happen in the same order.
        synchronized(sender) { frames.write(sender.seal(json.toByteArray())) }
    }

    /** Reads and decrypts the next message. Only one thread may read. */
    fun receive(maxBytes: Int = MAX_FRAME): String {
        val (payload, _) = frames.read(maxBytes)
        return try {
            receiver.open(payload).decodeToString()
        } catch (e: Exception) {
            throw SyncException.AuthenticationFailed()
        }
    }

    fun sendManifest(manifest: Manifest) = send(SyncedPassJson.encodeToString(ManifestMessage.serializer(), ManifestMessage(
        items = manifest.items.mapKeys { it.key.toString().uppercase() },
        deletions = manifest.deletions.mapKeys { it.key.toString().uppercase() },
    )))

    fun sendRecords(records: SyncRecords) =
        send(SyncedPassJson.encodeToString(RecordsMessage.serializer(), RecordsMessage(items = records.items, deletions = records.deletions)))

    fun sendPing() = send("""{"type":"ping"}""")

    fun receiveMessage(): SessionMessage {
        val json = receive()
        val type = runCatching { SyncedPassJson.parseToJsonElement(json).jsonObject["type"]?.jsonPrimitive?.content }.getOrNull()
        return when (type) {
            "manifest" -> {
                val m = decode(ManifestMessage.serializer(), json)
                SessionMessage.ManifestReceived(Manifest(m.items.mapKeys { UUID.fromString(it.key) }, m.deletions.mapKeys { UUID.fromString(it.key) }))
            }
            "records" -> decode(RecordsMessage.serializer(), json).let { SessionMessage.RecordsReceived(SyncRecords(it.items, it.deletions)) }
            "ping" -> SessionMessage.Ping
            else -> throw SyncException.Protocol("message type $type")
        }
    }
}

/** A pairing that's waiting for both users to compare the code. */
class Pairing internal constructor(
    /** The 6-digit code both screens show. */
    val code: String,
    /** The Mac, as it described itself. Saved as a peer once both sides confirm. */
    val mac: PairHello,
    private val channel: SecureChannel,
    private val transcript: ByteArray,
    private val identity: SyncIdentity,
) {
    /** Tells the Mac whether this phone's user confirmed that the codes match. */
    fun confirm(accepted: Boolean) {
        val signature = if (accepted) SyncCrypto.sign(identity.privateKey, confirmation("phone", transcript)) else null
        channel.send(SyncedPassJson.encodeToString(PairConfirm.serializer(), PairConfirm(accepted = accepted, signature = signature)))
    }

    /** Waits for the Mac's user. Returns the new peer, or throws if they cancelled or the signature is wrong. */
    fun awaitMac(): SyncPeer {
        val reply = decode(PairConfirm.serializer(), channel.receive(MAX_HANDSHAKE_FRAME))
        if (reply.type != "confirm") throw SyncException.Protocol("expected confirm")
        if (!reply.accepted) throw SyncException.Cancelled()
        val signature = reply.signature ?: throw SyncException.AuthenticationFailed()
        if (!SyncCrypto.verify(mac.identityKey, confirmation("mac", transcript), signature)) throw SyncException.AuthenticationFailed()
        return SyncPeer(mac.deviceID, mac.deviceName, mac.identityKey, ReferenceDate.now())
    }
}

object SyncHandshake {
    /** Steps 1–4 of pairing (docs/SYNC.md, "Pairing"), as the phone. */
    fun startPairing(frames: FrameStream, identity: SyncIdentity): Pairing {
        val ephemeral = SyncCrypto.Ephemeral()
        val hello = PairHello(identity.deviceID, identity.deviceName, identity.publicKey, ephemeral.publicKey, SyncCrypto.randomBytes(32), now())
        val helloJson = encode(PairHello.serializer(), hello)
        // f3 is sent last, but committed to first, so the Mac's keys can't be chosen to match it.
        val f3 = frameOf(helloJson)
        val f1 = frames.write(encode(PairRequest.serializer(), PairRequest(commitment = SyncCrypto.sha256(f3))))
        val (f2Payload, f2) = frames.read(MAX_HANDSHAKE_FRAME)
        val mac = decode(PairHello.serializer(), f2Payload)
        SyncCrypto.publicKey(mac.identityKey)
        if (mac.identityKey.contentEquals(identity.publicKey) || mac.deviceID == identity.deviceID) throw SyncException.Protocol("own identity")
        frames.write(helloJson)
        checkClock(mac.time)

        val secret = ephemeral.agree(mac.ephemeralKey)
        val salt = SyncCrypto.sha256("SyncedPass pair v1".toByteArray(), f1, f2, f3)
        val codeBytes = SyncCrypto.hkdf(secret, salt, "SyncedPass pair v1 code", 4)
        val code = (codeBytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) } % 1_000_000).toString().padStart(6, '0')
        val sendKey = SyncCrypto.hkdf(secret, salt, "SyncedPass pair v1 c2s", 32)
        val receiveKey = SyncCrypto.hkdf(secret, salt, "SyncedPass pair v1 s2c", 32)
        val channel = SecureChannel(frames, sendKey, receiveKey)
        listOf(secret, sendKey, receiveKey).forEach { it.fill(0) }
        return Pairing(code, mac, channel, salt, identity)
    }

    /** Opens a session with a paired Mac (docs/SYNC.md, "Sync sessions"). */
    fun openSession(frames: FrameStream, identity: SyncIdentity): Pair<SecureChannel, SyncPeer> {
        val ephemeral = SyncCrypto.Ephemeral()
        val f1 = frames.write(encode(SyncRequest.serializer(), SyncRequest(
            deviceID = identity.deviceID, ephemeralKey = ephemeral.publicKey, nonce = SyncCrypto.randomBytes(32), time = now(),
        )))
        val (f2Payload, f2) = frames.read(MAX_HANDSHAKE_FRAME)
        val reply = decode(SyncReply.serializer(), f2Payload)
        val peer = identity.peers.firstOrNull { it.deviceID == reply.deviceID } ?: throw SyncException.NotPaired()
        val (f3Payload, _) = frames.read(MAX_HANDSHAKE_FRAME)
        val serverSignature = decode(SignatureFrame.serializer(), f3Payload).signature
        if (!SyncCrypto.verify(peer.publicKey, SyncCrypto.sha256("SyncedPass sync v1 server".toByteArray(), f1, f2), serverSignature)) {
            throw SyncException.AuthenticationFailed()
        }
        val signature = SyncCrypto.sign(identity.privateKey, SyncCrypto.sha256("SyncedPass sync v1 client".toByteArray(), f1, f2))
        frames.write(encode(SignatureFrame.serializer(), SignatureFrame(signature)))
        checkClock(reply.time)

        val secret = ephemeral.agree(reply.ephemeralKey)
        val salt = SyncCrypto.sha256(f1, f2)
        val sendKey = SyncCrypto.hkdf(secret, salt, "SyncedPass sync v1 c2s", 32)
        val receiveKey = SyncCrypto.hkdf(secret, salt, "SyncedPass sync v1 s2c", 32)
        val channel = SecureChannel(frames, sendKey, receiveKey)
        listOf(secret, sendKey, receiveKey).forEach { it.fill(0) }
        return channel to peer
    }

    private fun checkClock(theirTime: Double) {
        val skew = abs(theirTime - now())
        if (skew > MAX_CLOCK_SKEW_SECONDS) throw SyncException.ClockSkew(skew.toLong())
    }

    private fun now() = ReferenceDate.now().secondsSinceReferenceDate

    private fun frameOf(payload: ByteArray): ByteArray {
        val n = payload.size
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + payload
    }
}

/** What each side signs to confirm pairing; the role keeps one side's confirmation from being reflected back as the other's. */
private fun confirmation(role: String, transcript: ByteArray) =
    SyncCrypto.sha256("SyncedPass pair v1 confirm $role".toByteArray(), transcript)

private fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray = SyncedPassJson.encodeToString(serializer, value).toByteArray()

private fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray): T = decode(serializer, bytes.decodeToString())

private fun <T> decode(serializer: KSerializer<T>, json: String): T = try {
    SyncedPassJson.decodeFromString(serializer, json)
} catch (e: Exception) {
    throw SyncException.Protocol("malformed message")
}
