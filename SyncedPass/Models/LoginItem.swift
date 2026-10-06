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
/// | `authenticationType`  | `signInMethod`       |
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

    // Optional — account details (from the old app)
    var signInMethod: SignInMethod
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
        signInMethod: SignInMethod = .notSet,
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
        self.signInMethod = signInMethod
        self.phoneNumber = phoneNumber
        self.pin = pin
        self.note = note
        self.customFields = customFields
        self.createdAt = createdAt
        self.modifiedAt = modifiedAt
    }

    /// The best single line to show under the title in lists.
    var subtitle: String {
        [email, username, websites.first ?? ""].first { !$0.isEmpty } ?? ""
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
