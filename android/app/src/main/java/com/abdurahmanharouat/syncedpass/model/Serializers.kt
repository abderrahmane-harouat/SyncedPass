package com.abdurahmanharouat.syncedpass.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** JSON settings that match Swift's JSONEncoder/JSONDecoder defaults. */
val SyncedPassJson = Json {
    ignoreUnknownKeys = true   // newer versions may add fields
    explicitNulls = false      // Swift leaves a nil optional out
    encodeDefaults = true      // Swift always writes every non-optional field
}

/**
 * A point in time as Swift's JSONEncoder writes a `Date` by default: seconds
 * since 2001-01-01 00:00:00 UTC, as a Double. Kept as the raw Double (not
 * converted to Instant and back) so a value read from the Mac is written back
 * bit-for-bit identical; merging compares these values.
 */
@Serializable(with = ReferenceDateSerializer::class)
@JvmInline
value class ReferenceDate(val secondsSinceReferenceDate: Double) : Comparable<ReferenceDate> {
    val instant: Instant
        get() {
            val unix = secondsSinceReferenceDate + UNIX_OFFSET
            val whole = kotlin.math.floor(unix).toLong()
            return Instant.ofEpochSecond(whole, ((unix - whole) * 1_000_000_000).toLong())
        }

    override fun compareTo(other: ReferenceDate) =
        secondsSinceReferenceDate.compareTo(other.secondsSinceReferenceDate)

    companion object {
        /** Seconds between 1970-01-01 and 2001-01-01. */
        const val UNIX_OFFSET = 978_307_200.0

        fun now() = of(Instant.now())

        fun of(instant: Instant) =
            ReferenceDate(instant.epochSecond - UNIX_OFFSET + instant.nano / 1_000_000_000.0)
    }
}

object ReferenceDateSerializer : KSerializer<ReferenceDate> {
    override val descriptor = PrimitiveSerialDescriptor("ReferenceDate", PrimitiveKind.DOUBLE)
    override fun serialize(encoder: Encoder, value: ReferenceDate) = encoder.encodeDouble(value.secondsSinceReferenceDate)
    override fun deserialize(decoder: Decoder) = ReferenceDate(decoder.decodeDouble())
}

/** UUIDs in Swift's format: uppercase, e.g. "7D1E2F3A-0000-4000-8000-000000000001". */
object UuidSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString().uppercase())
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

/** Binary data as Swift's JSONEncoder writes `Data`: standard base64 with padding. */
object Base64Serializer : KSerializer<ByteArray> {
    override val descriptor = PrimitiveSerialDescriptor("Base64", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ByteArray) = encoder.encodeString(Base64.getEncoder().encodeToString(value))
    override fun deserialize(decoder: Decoder): ByteArray = Base64.getDecoder().decode(decoder.decodeString())
}
