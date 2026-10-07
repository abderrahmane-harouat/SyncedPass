package com.abdurahmanharouat.syncedpass.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/*
 * The SyncedPass data model, mirroring SyncedPass/Models/LoginItem.swift on
 * macOS field for field. The JSON produced here must stay identical to what
 * Swift's JSONEncoder produces, because both apps read and write the same
 * vault and backup files (and will sync with each other):
 *   - UUIDs are uppercase strings          (UuidSerializer)
 *   - dates are seconds since 2001-01-01   (ReferenceDate)
 *   - enums use the Swift raw values       (@SerialName)
 *   - a nil optional is left out           (explicitNulls = false)
 */

/** A saved login. Only [title] is required; everything else may be empty. */
@Serializable(with = LoginItemSerializer::class)
data class LoginItem(
    val id: UUID = UUID.randomUUID(),
    val title: String = "",
    val email: String = "",
    val username: String = "",
    val password: String = "",
    /** A base32 secret or a full `otpauth://` URI. */
    val totpSecret: String = "",
    val websites: List<String> = emptyList(),
    /** How the account is signed into, e.g. Google plus a password. */
    val signIns: List<SignIn> = emptyList(),
    val phoneNumber: String = "",
    val pin: String = "",
    val note: String = "",
    val customFields: List<CustomField> = emptyList(),
    val createdAt: ReferenceDate = ReferenceDate.now(),
    val modifiedAt: ReferenceDate = ReferenceDate.now(),
) {
    /** The best single line to show under the title in lists. */
    val subtitle: String
        get() = listOf(email, username, websites.firstOrNull().orEmpty()).firstOrNull { it.isNotEmpty() }
            ?: signIns.firstOrNull { it.method.isProvider }?.method?.displayName
            ?: ""

    fun signsInWith(account: UUID): Boolean = signIns.any { it.accountID == account }
}

/** One way into an account, optionally pointing at the login of the account used. */
@Serializable
data class SignIn(
    val method: SignInMethod,
    @Serializable(with = UuidSerializer::class) val accountID: UUID? = null,
)

/**
 * How an account is signed into. The serial names are the raw values used by
 * the macOS app (and, before it, by the old Flutter app).
 */
@Serializable
enum class SignInMethod(val providerName: String, val displayName: String) {
    @SerialName("") NotSet("Not set", "Not set"),
    @SerialName("Standard Login") Standard("Password", "Email or username & password"),
    @SerialName("Google OAuth") Google("Google", "Sign in with Google"),
    @SerialName("Apple ID") Apple("Apple", "Sign in with Apple"),
    @SerialName("Microsoft OAuth") Microsoft("Microsoft", "Sign in with Microsoft"),
    @SerialName("GitHub OAuth") GitHub("GitHub", "Sign in with GitHub"),
    @SerialName("Facebook OAuth") Facebook("Facebook", "Sign in with Facebook"),
    @SerialName("Twitter OAuth") Twitter("X (Twitter)", "Sign in with X (Twitter)"),
    @SerialName("LinkedIn OAuth") LinkedIn("LinkedIn", "Sign in with LinkedIn"),
    @SerialName("Discord OAuth") Discord("Discord", "Sign in with Discord");

    /** Signing in through another account rather than the login's own password. */
    val isProvider: Boolean get() = this != NotSet && this != Standard

    companion object {
        /** The methods offered in the editor (everything but "not set"). */
        val selectable: List<SignInMethod> get() = entries.filter { it != NotSet }

        /** The raw value as stored in files, e.g. "Google OAuth". */
        fun fromRawValue(raw: String): SignInMethod? =
            entries.firstOrNull { it.rawValue == raw }
    }

    val rawValue: String
        get() = SignInMethod.serializer().descriptor.getElementName(ordinal)
}

/** A user-defined field, matching Proton Pass's custom field types. */
@Serializable
data class CustomField(
    @Serializable(with = UuidSerializer::class) val id: UUID = UUID.randomUUID(),
    val kind: Kind,
    val name: String = "",
    /** Used by text, hidden and TOTP fields. */
    val value: String = "",
    /** Used by date fields. */
    val date: ReferenceDate = ReferenceDate.now(),
) {
    @Serializable
    enum class Kind(val displayName: String) {
        @SerialName("text") Text("Text"),
        @SerialName("hidden") Hidden("Hidden"),
        @SerialName("totp") Totp("2FA secret (TOTP)"),
        @SerialName("date") Date("Date"),
    }
}

/**
 * Reads items saved before sign-in methods became a list: those stored a
 * single `signInMethod` string, which becomes a one-entry `signIns` list.
 * Mirrors LoginItem.init(from:) on macOS.
 */
object LoginItemSerializer : JsonTransformingSerializer<LoginItem>(GeneratedLoginItemSerializer) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonObject || "signIns" in element) return element
        val raw = element["signInMethod"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val method = SignInMethod.fromRawValue(raw) ?: SignInMethod.NotSet
        return buildJsonObject {
            element.forEach { (key, value) -> if (key != "signInMethod") put(key, value) }
            put(
                "signIns",
                if (method == SignInMethod.NotSet) JsonArray(emptyList())
                else JsonArray(listOf(buildJsonObject { put("method", JsonPrimitive(method.rawValue)) })),
            )
        }
    }
}

/** The plugin-generated serializer, kept separate so the transformer can wrap it. */
private val GeneratedLoginItemSerializer: KSerializer<LoginItem> = LoginItemSurrogate.serializer().let { surrogate ->
    object : KSerializer<LoginItem> {
        override val descriptor = surrogate.descriptor
        override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: LoginItem) =
            surrogate.serialize(encoder, LoginItemSurrogate.from(value))
        override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder) =
            surrogate.deserialize(decoder).toItem()
    }
}

/** Same shape as [LoginItem], without the custom serializer, for the generated code. */
@Serializable
@SerialName("LoginItem")
private data class LoginItemSurrogate(
    @Serializable(with = UuidSerializer::class) val id: UUID,
    val title: String,
    val email: String,
    val username: String,
    val password: String,
    val totpSecret: String,
    val websites: List<String>,
    val signIns: List<SignIn>,
    val phoneNumber: String,
    val pin: String,
    val note: String,
    val customFields: List<CustomField>,
    val createdAt: ReferenceDate,
    val modifiedAt: ReferenceDate,
) {
    fun toItem() = LoginItem(
        id, title, email, username, password, totpSecret, websites, signIns,
        phoneNumber, pin, note, customFields, createdAt, modifiedAt,
    )

    companion object {
        fun from(i: LoginItem) = LoginItemSurrogate(
            i.id, i.title, i.email, i.username, i.password, i.totpSecret, i.websites, i.signIns,
            i.phoneNumber, i.pin, i.note, i.customFields, i.createdAt, i.modifiedAt,
        )
    }
}
