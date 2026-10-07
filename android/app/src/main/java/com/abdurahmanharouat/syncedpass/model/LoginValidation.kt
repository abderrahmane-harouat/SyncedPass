package com.abdurahmanharouat.syncedpass.model

import java.net.URI

/**
 * Input rules for the login editor, mirroring SyncedPass/Models/LoginValidation.swift.
 * Only the title is required; every other field is optional but must be
 * well-formed when filled in. Messages match the Mac app's.
 */
object LoginValidation {
    fun titleError(title: String): String? = if (title.isBlank()) "Title is required." else null

    fun emailError(email: String): String? {
        val e = email.trim()
        if (e.isEmpty()) return null
        return if (Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").matches(e)) null
        else "Enter a valid email address, or put this in Username instead."
    }

    fun websiteError(website: String): String? {
        if (website.isBlank()) return null
        return if (normalizedWebsite(website) == null) "Enter a valid web address, like example.com." else null
    }

    fun totpError(secret: String): String? {
        val s = secret.trim()
        if (s.isEmpty()) return null
        return if (isValidTotp(s)) null else "Enter the setup key (letters A–Z and digits 2–7) or an otpauth:// link."
    }

    /** Adds `https://` when no scheme is given. Returns null if the result still isn't a usable web address. */
    fun normalizedWebsite(website: String): String? {
        var w = website.trim()
        if (w.isEmpty() || w.any(Char::isWhitespace)) return null
        if ("://" !in w) w = "https://$w"
        val uri = runCatching { URI(w) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        val host = uri.host ?: return null
        if (scheme != "http" && scheme != "https") return null
        if ("." !in host && host != "localhost") return null
        return w
    }

    /** The host of a normalized web address. */
    fun host(normalizedWebsite: String): String? = runCatching { URI(normalizedWebsite).host }.getOrNull()

    private fun isValidTotp(secret: String): Boolean {
        if (secret.lowercase().startsWith("otpauth://")) {
            val uri = runCatching { URI(secret) }.getOrNull() ?: return false
            if (uri.host?.lowercase() !in setOf("totp", "hotp")) return false
            val key = uri.rawQuery.orEmpty().split("&")
                .map { it.split("=", limit = 2) }
                .firstOrNull { it[0].equals("secret", ignoreCase = true) }
                ?.getOrNull(1)
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                ?: return false
            return isBase32(key)
        }
        return isBase32(secret)
    }

    /**
     * Accepts the common way sites display setup keys: grouped with spaces,
     * any case, optional `=` padding. 16 characters (80 bits) is the shortest
     * key in common use.
     */
    private fun isBase32(key: String): Boolean {
        val k = key.uppercase().filter { it != ' ' && it != '-' }
        val unpadded = k.takeWhile { it != '=' }
        return unpadded.length >= 16 &&
            unpadded.all { it in 'A'..'Z' || it in '2'..'7' } &&
            k.drop(unpadded.length).all { it == '=' }
    }
}

/** Every problem that should block saving, in display order. */
val LoginItem.validationErrors: List<String>
    get() = buildList {
        LoginValidation.titleError(title)?.let(::add)
        LoginValidation.emailError(email)?.let(::add)
        LoginValidation.totpError(totpSecret)?.let(::add)
        websites.mapNotNullTo(this, LoginValidation::websiteError)
        if (customFields.any { it.name.isBlank() }) add("Every custom field needs a name.")
        customFields.filter { it.kind == CustomField.Kind.Totp }.mapNotNullTo(this) { LoginValidation.totpError(it.value) }
    }

/** A copy ready to store: whitespace trimmed, websites normalized, blank website rows dropped. */
fun LoginItem.cleanedForSaving(): LoginItem = copy(
    title = title.trim(),
    email = email.trim(),
    username = username.trim(),
    totpSecret = totpSecret.trim(),
    phoneNumber = phoneNumber.trim(),
    websites = websites.mapNotNull(LoginValidation::normalizedWebsite),
    customFields = customFields.map { it.copy(name = it.name.trim()) },
    modifiedAt = ReferenceDate.now(),
)
