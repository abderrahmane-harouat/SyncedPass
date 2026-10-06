import Foundation

/// A saved login.
///
/// The core fields mirror Proton Pass's login item (title, email, username,
/// password, 2FA secret, websites, note, custom fields). The "account details"
/// fields carry over the extra data from the old Flutter app so it can be
/// migrated without loss:
///
/// | Old app (`Platform`)  | SyncedPass           |
/// |-----------------------|----------------------|
/// | `name`                | `title`              |
/// | `url`                 | `websites[0]`        |
/// | `username`            | `username`           |
/// | `password`            | `password`           |
/// | `authenticationType`  | `signIns`            |
/// | `phoneNumber`         | `phoneNumber`        |
/// | `pin`                 | `pin`                |
/// | `iconPath`            | dropped              |
struct LoginItem: Identifiable, Hashable, Codable {
    let id: UUID

    // Required
    var title: String

    // Optional — credentials
    var email: String
    var username: String
    var password: String
    /// A base32 secret or a full `otpauth://` URI.
    var totpSecret: String
    var websites: [String]

    // Optional — how the account is signed into (e.g. Google + password)
    var signIns: [SignIn]

    // Optional — account details (from the old app)
    var phoneNumber: String
    var pin: String

    // Optional — extras
    var note: String
    var customFields: [CustomField]

    let createdAt: Date
    var modifiedAt: Date

    init(
        id: UUID = UUID(),
        title: String = "",
        email: String = "",
        username: String = "",
        password: String = "",
        totpSecret: String = "",
        websites: [String] = [],
        signIns: [SignIn] = [],
        phoneNumber: String = "",
        pin: String = "",
        note: String = "",
        customFields: [CustomField] = [],
        createdAt: Date = .now,
        modifiedAt: Date = .now
    ) {
        self.id = id
        self.title = title
        self.email = email
        self.username = username
        self.password = password
        self.totpSecret = totpSecret
        self.websites = websites
        self.signIns = signIns
        self.phoneNumber = phoneNumber
        self.pin = pin
        self.note = note
        self.customFields = customFields
        self.createdAt = createdAt
        self.modifiedAt = modifiedAt
    }

    /// The best single line to show under the title in lists; for logins
    /// with nothing else, how they're signed into ("Sign in with Google").
    var subtitle: String {
        [email, username, websites.first ?? ""].first { !$0.isEmpty }
            ?? signIns.first { $0.method.isProvider }?.method.displayName
            ?? ""
    }

    /// True when `account` is linked as the account used to sign in here.
    func signsIn(with account: LoginItem.ID) -> Bool {
        signIns.contains { $0.accountID == account }
    }

    // MARK: Codable

    private enum CodingKeys: String, CodingKey {
        case id, title, email, username, password, totpSecret, websites, signIns
        case phoneNumber, pin, note, customFields, createdAt, modifiedAt
    }

    /// Vaults saved before sign-in methods became a list stored a single
    /// `signInMethod` string; it's read here so older vaults and backups open.
    private enum LegacyCodingKeys: String, CodingKey {
        case signInMethod
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(UUID.self, forKey: .id)
        title = try container.decode(String.self, forKey: .title)
        email = try container.decode(String.self, forKey: .email)
        username = try container.decode(String.self, forKey: .username)
        password = try container.decode(String.self, forKey: .password)
        totpSecret = try container.decode(String.self, forKey: .totpSecret)
        websites = try container.decode([String].self, forKey: .websites)
        phoneNumber = try container.decode(String.self, forKey: .phoneNumber)
        pin = try container.decode(String.self, forKey: .pin)
        note = try container.decode(String.self, forKey: .note)
        customFields = try container.decode([CustomField].self, forKey: .customFields)
        createdAt = try container.decode(Date.self, forKey: .createdAt)
        modifiedAt = try container.decode(Date.self, forKey: .modifiedAt)

        if let signIns = try container.decodeIfPresent([SignIn].self, forKey: .signIns) {
            self.signIns = signIns
        } else {
            let legacy = try decoder.container(keyedBy: LegacyCodingKeys.self)
            let raw = try legacy.decodeIfPresent(String.self, forKey: .signInMethod) ?? ""
            let method = SignInMethod(rawValue: raw) ?? .notSet
            signIns = method == .notSet ? [] : [SignIn(method: method)]
        }
    }
}

/// One way into an account, optionally pointing at the login of the account
/// used — e.g. "Sign in with Google" using the Gmail login for me@gmail.com.
struct SignIn: Hashable, Codable {
    var method: SignInMethod
    /// The login holding the provider account; nil when not recorded.
    var accountID: LoginItem.ID?

    init(method: SignInMethod, accountID: LoginItem.ID? = nil) {
        self.method = method
        self.accountID = accountID
    }
}

/// How the account is signed into. Raw values match the old app's
/// `authenticationType` strings exactly so migration is a direct lookup;
/// methods added later follow the same "<Name> OAuth" pattern.
enum SignInMethod: String, CaseIterable, Identifiable, Codable {
    case notSet = ""
    case standard = "Standard Login"
    case google = "Google OAuth"
    case apple = "Apple ID"
    case microsoft = "Microsoft OAuth"
    case github = "GitHub OAuth"
    case facebook = "Facebook OAuth"
    case twitter = "Twitter OAuth"
    case linkedin = "LinkedIn OAuth"
    case discord = "Discord OAuth"

    var id: String { rawValue }

    /// Methods that can be chosen; `notSet` only exists to read old data.
    static var selectable: [SignInMethod] { allCases.filter { $0 != .notSet } }

    /// Sign in through another account (Google, Apple…), as opposed to the
    /// login's own email/username and password.
    var isProvider: Bool { self != .notSet && self != .standard }

    /// Short name for lists and filters, e.g. "Google".
    var providerName: String {
        switch self {
        case .notSet: "Not set"
        case .standard: "Password"
        case .google: "Google"
        case .apple: "Apple"
        case .microsoft: "Microsoft"
        case .github: "GitHub"
        case .facebook: "Facebook"
        case .twitter: "X (Twitter)"
        case .linkedin: "LinkedIn"
        case .discord: "Discord"
        }
    }

    var displayName: String {
        switch self {
        case .notSet: "Not set"
        case .standard: "Email or username & password"
        case .google: "Sign in with Google"
        case .apple: "Sign in with Apple"
        case .microsoft: "Sign in with Microsoft"
        case .github: "Sign in with GitHub"
        case .facebook: "Sign in with Facebook"
        case .twitter: "Sign in with X (Twitter)"
        case .linkedin: "Sign in with LinkedIn"
        case .discord: "Sign in with Discord"
        }
    }
}

/// A user-defined field, matching Proton Pass's custom field types.
struct CustomField: Identifiable, Hashable, Codable {
    enum Kind: String, CaseIterable, Identifiable, Codable {
        case text, hidden, totp, date

        var id: String { rawValue }

        var displayName: String {
            switch self {
            case .text: "Text"
            case .hidden: "Hidden"
            case .totp: "2FA secret (TOTP)"
            case .date: "Date"
            }
        }

        var systemImage: String {
            switch self {
            case .text: "textformat"
            case .hidden: "eye.slash"
            case .totp: "lock.badge.clock"
            case .date: "calendar"
            }
        }
    }

    let id: UUID
    var kind: Kind
    var name: String
    /// Used by text, hidden and TOTP fields.
    var value: String
    /// Used by date fields.
    var date: Date

    init(id: UUID = UUID(), kind: Kind, name: String = "", value: String = "", date: Date = .now) {
        self.id = id
        self.kind = kind
        self.name = name
        self.value = value
        self.date = date
    }
}
