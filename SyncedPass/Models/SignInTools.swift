import Foundation

extension LoginItem {
    /// "Gmail — me@gmail.com": enough to tell several accounts apart.
    var accountLabel: String {
        let detail = email.isEmpty ? username : email
        return detail.isEmpty ? title : "\(title) — \(detail)"
    }

    /// The email address that identifies this login as an account: the email
    /// field, or the username when it's an address.
    var accountAddress: String? {
        let email = email.trimmed, username = username.trimmed
        if !email.isEmpty { return email }
        return username.contains("@") ? username : nil
    }

    /// How an account reads in the account picker: the address first, since
    /// that's what tells accounts apart ("me@gmail.com (Gmail)").
    var accountChoiceLabel: String {
        accountAddress.map { "\($0) (\(title))" } ?? title
    }
}

/// Which logins the sidebar shows.
enum LoginFilter: Hashable {
    case all
    /// Logins with a saved password.
    case withPassword
    /// Logins that can be signed into with this provider.
    case provider(SignInMethod)

    func matches(_ item: LoginItem) -> Bool {
        switch self {
        case .all: true
        case .withPassword: !item.password.isEmpty
        case .provider(let method): item.signIns.contains { $0.method == method }
        }
    }
}

/// Splitting a login that lists several services in its title
/// ("easyEDA, Flippa, Notion, Ling") into one login per service.
enum LoginSplit {
    /// The service names in a combined title, or empty when there's nothing
    /// to split (fewer than two names).
    static func parts(of title: String) -> [String] {
        var seen = Set<String>()
        let names = title.split(whereSeparator: { $0 == "," || $0 == "\n" || $0 == "،" })
            .map { String($0).trimmed }
            .filter { !$0.isEmpty && seen.insert($0.lowercased()).inserted }
        return names.count >= 2 ? names : []
    }

    /// Copies of `item`, one per name, keeping every field. Names that match
    /// a catalog service get its spelling and website; any websites on the combined login
    /// are kept in each copy's note so nothing is lost. `accountID`: nil keeps
    /// the existing links; a value (including `.some(nil)`) sets the account
    /// for every provider sign-in.
    static func makeItems(from item: LoginItem, accountID: LoginItem.ID?? = nil) -> [LoginItem] {
        let names = parts(of: item.title)
        let originalWebsites = item.websites.filter { !$0.trimmed.isEmpty }
        let websiteNote = originalWebsites.isEmpty
            ? nil
            : "Websites from “\(item.title)”: " + originalWebsites.joined(separator: ", ")

        return names.map { name in
            let service = KnownService.all.first { $0.name.caseInsensitiveCompare(name) == .orderedSame }
            return LoginItem(
                title: service?.name ?? name,
                email: item.email,
                username: item.username,
                password: item.password,
                totpSecret: item.totpSecret,
                websites: service.map { [$0.website] } ?? [],
                signIns: item.signIns.map { signIn in
                    guard signIn.method.isProvider, let accountID else { return signIn }
                    return SignIn(method: signIn.method, accountID: accountID)
                },
                phoneNumber: item.phoneNumber,
                pin: item.pin,
                note: [item.note, websiteNote].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: "\n"),
                customFields: item.customFields.map { CustomField(kind: $0.kind, name: $0.name, value: $0.value, date: $0.date) },
                createdAt: item.createdAt,
                modifiedAt: .now
            )
        }
    }
}
