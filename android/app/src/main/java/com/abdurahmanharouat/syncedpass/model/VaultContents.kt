package com.abdurahmanharouat.syncedpass.model

import kotlinx.serialization.Serializable
import java.util.UUID

/*
 * Everything stored in the vault: the logins, deletion records, and this
 * device's sync identity. Same JSON as SyncedPass/Models/VaultContents.swift;
 * see docs/SYNC.md.
 */

@Serializable
data class VaultContents(
    val items: List<LoginItem> = emptyList(),
    /** Logins deleted on this device or a synced one, so the deletion spreads instead of the login coming back. */
    val deletions: List<Deletion> = emptyList(),
    /** Absent until the first pairing. */
    val sync: SyncIdentity? = null,
)

@Serializable
data class Deletion(
    @Serializable(with = UuidSerializer::class) val id: UUID,
    val deletedAt: ReferenceDate,
)

/** This device's long-term identity for sync, and the devices it's paired with. */
@Serializable
data class SyncIdentity(
    @Serializable(with = UuidSerializer::class) val deviceID: UUID,
    val deviceName: String,
    /** The 32-byte P-256 private scalar. Only ever stored inside the encrypted vault. */
    @Serializable(with = Base64Serializer::class) val privateKey: ByteArray,
    /** SubjectPublicKeyInfo DER. */
    @Serializable(with = Base64Serializer::class) val publicKey: ByteArray,
    val peers: List<SyncPeer> = emptyList(),
) {
    override fun equals(other: Any?) = other is SyncIdentity && deviceID == other.deviceID && deviceName == other.deviceName &&
        privateKey.contentEquals(other.privateKey) && publicKey.contentEquals(other.publicKey) && peers == other.peers
    override fun hashCode() = deviceID.hashCode()
}

@Serializable
data class SyncPeer(
    @Serializable(with = UuidSerializer::class) val deviceID: UUID,
    val name: String,
    @Serializable(with = Base64Serializer::class) val publicKey: ByteArray,
    val pairedAt: ReferenceDate,
) {
    override fun equals(other: Any?) = other is SyncPeer && deviceID == other.deviceID && name == other.name &&
        publicKey.contentEquals(other.publicKey) && pairedAt == other.pairedAt
    override fun hashCode() = deviceID.hashCode()
}

/** What a device has, by login ID and time, so two devices can compare without sending the logins. */
data class Manifest(val items: Map<UUID, ReferenceDate>, val deletions: Map<UUID, ReferenceDate>)

/** Logins and deletion records sent to another device. */
data class SyncRecords(val items: List<LoginItem>, val deletions: List<Deletion>) {
    val isEmpty: Boolean get() = items.isEmpty() && deletions.isEmpty()
}

/*
 * Per-login merging (docs/SYNC.md, "Merging"). For one login ID, the record
 * with the latest time wins: a login's modifiedAt against a deletion's
 * deletedAt. On a tie between a login and a deletion, the deletion wins, so
 * both devices always decide the same way.
 */

fun VaultContents.manifest() = Manifest(
    items = items.groupBy { it.id }.mapValues { (_, same) -> same.maxOf { it.modifiedAt } },
    deletions = deletions.groupBy { it.id }.mapValues { (_, same) -> same.maxOf { it.deletedAt } },
)

/** The records that win over what [peer] has, or that it doesn't have at all. */
fun VaultContents.recordsNeededBy(peer: Manifest): SyncRecords {
    val itemsToSend = items.filter { item ->
        val theirs = listOfNotNull(peer.items[item.id], peer.deletions[item.id]).maxOrNull()
        theirs == null || item.modifiedAt > theirs
    }
    val deletionsToSend = deletions.filter { deletion ->
        val theirDeletion = peer.deletions[deletion.id]
        val theirItem = peer.items[deletion.id]
        (theirDeletion == null || deletion.deletedAt > theirDeletion) && (theirItem == null || deletion.deletedAt >= theirItem)
    }
    return SyncRecords(itemsToSend, deletionsToSend)
}

/**
 * True when [this] manifest has records that win over [mine] (the same rule
 * as [recordsNeededBy]): the other device then needs to see [mine] to send them.
 */
fun Manifest.hasRecordsNeededBy(mine: Manifest): Boolean =
    items.any { (id, time) ->
        val ours = listOfNotNull(mine.items[id], mine.deletions[id]).maxOrNull()
        ours == null || time > ours
    } || deletions.any { (id, time) ->
        val ourDeletion = mine.deletions[id]
        val ourItem = mine.items[id]
        (ourDeletion == null || time > ourDeletion) && (ourItem == null || time >= ourItem)
    }

/**
 * Merges records from another device; null when nothing changed. Records
 * dated after [notAfter] are ignored: a device with a wrong clock could
 * otherwise win over every real edit until that date.
 */
fun VaultContents.merging(records: SyncRecords, notAfter: ReferenceDate = ReferenceDate(Double.MAX_VALUE)): VaultContents? {
    val items = items.toMutableList()
    val deletions = deletions.associateBy { it.id }.toMutableMap()
    var changed = false

    for (incoming in records.items) {
        if (incoming.modifiedAt > notAfter) continue
        val index = items.indexOfFirst { it.id == incoming.id }
        val localTime = listOfNotNull(items.getOrNull(index)?.modifiedAt, deletions[incoming.id]?.deletedAt).maxOrNull()
        if (localTime != null && incoming.modifiedAt <= localTime) continue
        if (index >= 0) items[index] = incoming else items += incoming
        deletions.remove(incoming.id)
        changed = true
    }
    for (incoming in records.deletions) {
        if (incoming.deletedAt > notAfter) continue
        val local = deletions[incoming.id]
        if (local != null && local.deletedAt >= incoming.deletedAt) continue
        val index = items.indexOfFirst { it.id == incoming.id }
        if (index >= 0 && items[index].modifiedAt > incoming.deletedAt) continue
        if (index >= 0) items.removeAt(index)
        deletions[incoming.id] = incoming
        changed = true
    }
    return if (changed) copy(items = items, deletions = deletions.values.toList()) else null
}
