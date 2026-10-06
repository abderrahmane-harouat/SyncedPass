import Foundation

/// Input rules for the login editor. Only the title is required; every other
/// field is optional but must be well-formed when filled in.
enum LoginValidation {
    static func titleError(_ title: String) -> String? {
        title.trimmed.isEmpty ? "Title is required." : nil
    }

    static func emailError(_ email: String) -> String? {
        let email = email.trimmed
        guard !email.isEmpty else { return nil }
        return email.wholeMatch(of: #/[^\s@]+@[^\s@]+\.[^\s@]+/#) == nil
            ? "Enter a valid email address, or put this in Username instead."
            : nil
    }

    static func websiteError(_ website: String) -> String? {
        guard !website.trimmed.isEmpty else { return nil }
        return normalizedWebsite(website) == nil ? "Enter a valid web address, like example.com." : nil
    }

    static func totpError(_ secret: String) -> String? {
        let secret = secret.trimmed
        guard !secret.isEmpty else { return nil }
        return isValidTOTP(secret)
            ? nil
            : "Enter the setup key (letters A–Z and digits 2–7) or an otpauth:// link."
    }

    /// Adds `https://` when no scheme is given. Returns nil if the result
    /// still isn't a usable web address.
    static func normalizedWebsite(_ website: String) -> String? {
        var website = website.trimmed
        guard !website.isEmpty, !website.contains(where: \.isWhitespace) else { return nil }
        if !website.contains("://") {
            website = "https://" + website
        }
        guard let url = URL(string: website),
              let scheme = url.scheme?.lowercased(), ["http", "https"].contains(scheme),
              let host = url.host(), host.contains(".") || host == "localhost"
        else { return nil }
        return website
    }

    private static func isValidTOTP(_ secret: String) -> Bool {
        if secret.lowercased().hasPrefix("otpauth://") {
            guard let components = URLComponents(string: secret),
                  ["totp", "hotp"].contains(components.host?.lowercased() ?? ""),
                  let key = components.queryItems?.first(where: { $0.name.lowercased() == "secret" })?.value
            else { return false }
            return isBase32(key)
        }
        return isBase32(secret)
    }

    /// Accepts the common way sites display setup keys: grouped with spaces,
    /// any case, optional `=` padding. 16 characters (80 bits) is the shortest
    /// key in common use.
    private static func isBase32(_ key: String) -> Bool {
        let key = key.uppercased().filter { $0 != " " && $0 != "-" }
        let unpadded = key.prefix { $0 != "=" }
        let base32 = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")
        return unpadded.count >= 16
            && unpadded.allSatisfy(base32.contains)
            && key.dropFirst(unpadded.count).allSatisfy { $0 == "=" }
    }
}

extension LoginItem {
    /// Every problem that should block saving, in display order.
    var validationErrors: [String] {
        var errors: [String] = []
        if let error = LoginValidation.titleError(title) { errors.append(error) }
        if let error = LoginValidation.emailError(email) { errors.append(error) }
        if let error = LoginValidation.totpError(totpSecret) { errors.append(error) }
        errors += websites.compactMap(LoginValidation.websiteError)
        if customFields.contains(where: { $0.name.trimmed.isEmpty }) {
            errors.append("Every custom field needs a name.")
        }
        errors += customFields.filter { $0.kind == .totp }.compactMap { LoginValidation.totpError($0.value) }
        return errors
    }

    /// A copy ready to store: whitespace trimmed, websites normalized,
    /// blank website rows dropped.
    func cleanedForSaving() -> LoginItem {
        var item = self
        item.title = title.trimmed
        item.email = email.trimmed
        item.username = username.trimmed
        item.totpSecret = totpSecret.trimmed
        item.phoneNumber = phoneNumber.trimmed
        item.websites = websites.compactMap(LoginValidation.normalizedWebsite)
        item.customFields = customFields.map { field in
            var field = field
            field.name = field.name.trimmed
            return field
        }
        item.modifiedAt = .now
        return item
    }
}

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}
