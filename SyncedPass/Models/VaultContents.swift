import Foundation

/// Everything stored in the vault: the logins, deletion records, and this
/// device's sync identity. Same JSON as VaultContents.kt on Android; see
/// docs/SYNC.md.
struct VaultContents: Codable, Equatable {
    var items: [LoginItem] = []
    /// Logins deleted on this device or a synced one, so the deletion spreads
    /// instead of the login coming back.
    var deletions: [Deletion] = []
    /// Absent until the first pairing.
    var sync: SyncIdentity?

    init(items: [LoginItem] = [], deletions: [Deletion] = [], sync: SyncIdentity? = nil) {
        self.items = items
        self.deletions = deletions
        self.sync = sync
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        items = try container.decodeIfPresent([LoginItem].self, forKey: .items) ?? []
        deletions = try container.decodeIfPresent([Deletion].self, forKey: .deletions) ?? []
        sync = try container.decodeIfPresent(SyncIdentity.self, forKey: .sync)
    }
}

struct Deletion: Codable, Hashable {
    let id: UUID
    let deletedAt: Date
}

/// This device's long-term identity for sync, and the devices it's paired with.
struct SyncIdentity: Codable, Equatable {
    let deviceID: UUID
    var deviceName: String
    /// The 32-byte P-256 private scalar. Only ever stored inside the encrypted vault.
    let privateKey: Data
    /// SubjectPublicKeyInfo DER.
    let publicKey: Data
    var peers: [SyncPeer] = []
}

struct SyncPeer: Codable, Hashable, Identifiable {
    let deviceID: UUID
    var name: String
    let publicKey: Data
    let pairedAt: Date

    var id: UUID { deviceID }
}

/// What a device has, by login ID and time, so two devices can compare
/// without sending the logins.
struct Manifest: Equatable {
    var items: [UUID: Date]
    var deletions: [UUID: Date]
}

/// Logins and deletion records sent to another device.
struct SyncRecords: Equatable {
    var items: [LoginItem]
    var deletions: [Deletion]

    var isEmpty: Bool { items.isEmpty && deletions.isEmpty }
}

// Per-login merging (docs/SYNC.md, "Merging"). For one login ID, the record
// with the latest time wins: a login's modifiedAt against a deletion's
// deletedAt. On a tie between a login and a deletion, the deletion wins, so
// both devices always decide the same way.
extension VaultContents {
    var manifest: Manifest {
        Manifest(
            items: Dictionary(items.map { ($0.id, $0.modifiedAt) }, uniquingKeysWith: { max($0, $1) }),
            deletions: Dictionary(deletions.map { ($0.id, $0.deletedAt) }, uniquingKeysWith: { max($0, $1) }))
    }

    /// The records that win over what `peer` has, or that it doesn't have at all.
    func recordsNeeded(by peer: Manifest) -> SyncRecords {
        let itemsToSend = items.filter { item in
            guard let theirs = [peer.items[item.id], peer.deletions[item.id]].compactMap({ $0 }).max() else { return true }
            return item.modifiedAt > theirs
        }
        let deletionsToSend = deletions.filter { deletion in
            let theirDeletion = peer.deletions[deletion.id]
            let theirItem = peer.items[deletion.id]
            return (theirDeletion.map { deletion.deletedAt > $0 } ?? true)
                && (theirItem.map { deletion.deletedAt >= $0 } ?? true)
        }
        return SyncRecords(items: itemsToSend, deletions: deletionsToSend)
    }

    /// Merges records from another device; nil when nothing changed.
    /// Records dated after `limit` are ignored: a device with a wrong clock
    /// could otherwise win over every real edit until that date.
    func merging(_ records: SyncRecords, notAfter limit: Date = .distantFuture) -> VaultContents? {
        var items = items
        var deletions = Dictionary(self.deletions.map { ($0.id, $0) }, uniquingKeysWith: { $1 })
        var order = self.deletions.map(\.id)
        var changed = false

        for incoming in records.items where incoming.modifiedAt <= limit {
            let index = items.firstIndex { $0.id == incoming.id }
            let localTime = [index.map { items[$0].modifiedAt }, deletions[incoming.id]?.deletedAt].compactMap { $0 }.max()
            if let localTime, incoming.modifiedAt <= localTime { continue }
            if let index { items[index] = incoming } else { items.append(incoming) }
            deletions[incoming.id] = nil
            changed = true
        }
        for incoming in records.deletions where incoming.deletedAt <= limit {
            if let local = deletions[incoming.id], local.deletedAt >= incoming.deletedAt { continue }
            let index = items.firstIndex { $0.id == incoming.id }
            if let index, items[index].modifiedAt > incoming.deletedAt { continue }
            if let index { items.remove(at: index) }
            if deletions[incoming.id] == nil { order.append(incoming.id) }
            deletions[incoming.id] = incoming
            changed = true
        }
        guard changed else { return nil }
        var result = self
        result.items = items
        result.deletions = order.compactMap { deletions[$0] }
        return result
    }
}

extension Manifest {
    /// True when this manifest has records that win over `mine` (the same
    /// rule as `recordsNeeded(by:)`): the other device then needs to see
    /// `mine` to send them.
    func hasRecordsNeeded(by mine: Manifest) -> Bool {
        items.contains { id, time in
            guard let ours = [mine.items[id], mine.deletions[id]].compactMap({ $0 }).max() else { return true }
            return time > ours
        } || deletions.contains { id, time in
            (mine.deletions[id].map { time > $0 } ?? true) && (mine.items[id].map { time >= $0 } ?? true)
        }
    }
}
