import Foundation

/// Search that puts what the user most likely means first.
///
/// Searching every field (like Bitwarden and 1Password do) is useful for
/// "which logins use this email?", but matching "gmail" against every
/// `…@gmail.com` address buries the Gmail login itself. So results are split:
/// - top matches: the title or a website's domain matches, best first
/// - other matches: only the email, username or note matches
enum LoginSearch {
    enum Field: String {
        case email = "Email"
        case username = "Username"
        case note = "Note"
    }

    struct OtherMatch: Identifiable {
        let item: LoginItem
        let field: Field
        var id: LoginItem.ID { item.id }
    }

    struct Results {
        var topMatches: [LoginItem] = []
        var otherMatches: [OtherMatch] = []

        /// Every result in display order.
        var all: [LoginItem] { topMatches + otherMatches.map(\.item) }
    }

    /// With an empty query, every item is a top match, sorted by title.
    static func search(_ items: [LoginItem], for query: String) -> Results {
        let needle = fold(query.trimmed)
        guard !needle.isEmpty else {
            return Results(topMatches: items.sorted(by: titleOrder))
        }

        var ranked: [(item: LoginItem, rank: Int)] = []
        var others: [OtherMatch] = []
        for item in items {
            if let rank = topRank(of: item, for: needle) {
                ranked.append((item, rank))
            } else if let field = otherField(of: item, for: needle) {
                others.append(OtherMatch(item: item, field: field))
            }
        }
        ranked.sort { $0.rank != $1.rank ? $0.rank < $1.rank : titleOrder($0.item, $1.item) }
        others.sort { titleOrder($0.item, $1.item) }
        return Results(topMatches: ranked.map(\.item), otherMatches: others)
    }

    /// Lower is better: exact title, title prefix, word prefix, title
    /// contains, then website domain.
    private static func topRank(of item: LoginItem, for needle: String) -> Int? {
        let title = fold(item.title)
        if title == needle { return 0 }
        if title.hasPrefix(needle) { return 1 }
        let words = title.split { !$0.isLetter && !$0.isNumber }
        if words.contains(where: { $0.hasPrefix(needle) }) { return 2 }
        if title.contains(needle) { return 3 }
        // Only the domain, so "https" or "www" doesn't match every login.
        let hosts = item.websites.compactMap { URL(string: $0)?.host().map(fold) }
        if hosts.contains(where: { $0.contains(needle) }) { return 4 }
        return nil
    }

    private static func otherField(of item: LoginItem, for needle: String) -> Field? {
        if fold(item.email).contains(needle) { return .email }
        if fold(item.username).contains(needle) { return .username }
        if fold(item.note).contains(needle) { return .note }
        return nil
    }

    /// Case- and accent-insensitive, so "cafe" finds "Café".
    private static func fold(_ text: String) -> String {
        text.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: nil)
    }

    private static func titleOrder(_ a: LoginItem, _ b: LoginItem) -> Bool {
        a.title.localizedStandardCompare(b.title) == .orderedAscending
    }
}
