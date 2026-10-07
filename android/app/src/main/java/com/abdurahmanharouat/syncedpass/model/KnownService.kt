package com.abdurahmanharouat.syncedpass.model

import androidx.annotation.DrawableRes

/**
 * A popular service with a bundled logo, mirroring SyncedPass/Models/KnownService.swift.
 * The catalog is exported from the Mac app by Scripts/export_android_services.swift.
 *
 * Matching is done entirely offline against the bundled list; the app never
 * fetches icons from the network.
 */
data class KnownService(
    val id: String,
    val name: String,
    val domains: List<String>,
    /** Brand color as 0xRRGGBB. */
    val color: Int,
    val logo: Logo,
    @DrawableRes val logoRes: Int?,
) {
    enum class Logo {
        /** Single-color mark, drawn in white or black on the brand color. */
        Monochrome,
        /** Full-color mark, drawn as-is on a white tile. */
        Color,
        /** A complete app icon with its own background, filling the whole tile. */
        AppIcon,
        /** No bundled logo; shows the first letter on the brand color. */
        None,
    }

    val website: String get() = "https://${domains[0]}"

    companion object {
        val all: List<KnownService> get() = knownServiceCatalog

        fun byId(id: String): KnownService? = all.firstOrNull { it.id == id }

        /**
         * The service for a login: by website first, then by exact title (so
         * migrated items with only a name, like "GitHub", still match).
         */
        fun matching(item: LoginItem): KnownService? {
            for (website in item.websites) matching(website)?.let { return it }
            val title = item.title.trim()
            return all.firstOrNull { it.name.equals(title, ignoreCase = true) }
        }

        /**
         * Matches a host or any subdomain of it. When several domains match,
         * the most specific wins, so mail.google.com is Gmail rather than Google.
         */
        fun matching(website: String): KnownService? {
            var host = LoginValidation.normalizedWebsite(website)?.let(LoginValidation::host)?.lowercase() ?: return null
            host = host.removePrefix("www.")
            var best: KnownService? = null
            var bestLength = 0
            for (service in all) for (domain in service.domains) {
                if ((host == domain || host.endsWith(".$domain")) && domain.length > bestLength) {
                    best = service; bestLength = domain.length
                }
            }
            return best
        }

        fun search(query: String): List<KnownService> {
            val q = query.trim()
            val sorted = all.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            if (q.isEmpty()) return sorted
            return sorted.filter { s -> s.name.contains(q, ignoreCase = true) || s.domains.any { it.contains(q, ignoreCase = true) } }
        }
    }
}

/** The service whose logo represents this method; null for "not set" and plain password. */
val SignInMethod.service: KnownService?
    get() = when (this) {
        SignInMethod.NotSet, SignInMethod.Standard -> null
        SignInMethod.Google -> "google"
        SignInMethod.Apple -> "apple"
        SignInMethod.Microsoft -> "microsoft"
        SignInMethod.GitHub -> "github"
        SignInMethod.Facebook -> "facebook"
        SignInMethod.Twitter -> "x"
        SignInMethod.LinkedIn -> "linkedin"
        SignInMethod.Discord -> "discord"
    }?.let(KnownService::byId)

/**
 * Services whose logins *are* accounts for this provider: a Gmail login is a
 * Google account; a Facebook login that merely uses a Gmail address is not.
 */
private val SignInMethod.accountServiceIds: Set<String>
    get() = when (this) {
        SignInMethod.NotSet, SignInMethod.Standard -> emptySet()
        SignInMethod.Google -> setOf("google", "gmail", "googledrive", "youtube")
        SignInMethod.Apple -> setOf("apple", "icloud")
        SignInMethod.Microsoft -> setOf("microsoft", "outlook", "xbox")
        SignInMethod.GitHub -> setOf("github")
        SignInMethod.Facebook -> setOf("facebook")
        SignInMethod.Twitter -> setOf("x")
        SignInMethod.LinkedIn -> setOf("linkedin")
        SignInMethod.Discord -> setOf("discord")
    }

/** Words that mark a login as one of this provider's accounts, e.g. "Gmail perso". */
private val SignInMethod.accountTitleWords: Set<String>
    get() = when (this) {
        SignInMethod.NotSet, SignInMethod.Standard -> emptySet()
        SignInMethod.Google -> setOf("google", "gmail", "googlemail")
        SignInMethod.Apple -> setOf("apple", "icloud")
        SignInMethod.Microsoft -> setOf("microsoft", "outlook", "hotmail")
        SignInMethod.GitHub -> setOf("github")
        SignInMethod.Facebook -> setOf("facebook")
        SignInMethod.Twitter -> setOf("twitter")
        SignInMethod.LinkedIn -> setOf("linkedin")
        SignInMethod.Discord -> setOf("discord")
    }

/** True when [item] is one of this provider's accounts (not just a login using the same address). */
fun SignInMethod.isAccount(item: LoginItem): Boolean {
    KnownService.matching(item)?.let { if (it.id in accountServiceIds) return true }
    val words = item.title.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    return words.any { it in accountTitleWords }
}

/**
 * A copy switched to [service] (or to none), replacing whatever service it had
 * before rather than adding to it:
 * - the previous service's websites are removed and the new one's added
 *   (first, so it's the one the login is recognized by); other websites stay
 * - the title follows the service when it's empty or still the previous
 *   service's name; a title the user typed themselves is kept
 */
fun LoginItem.changingService(service: KnownService?): LoginItem {
    val previous = KnownService.matching(this)
    val websites = websites.toMutableList()
    if (previous != null) websites.removeAll { KnownService.matching(it) == previous }
    if (service != null && websites.none { KnownService.matching(it) == service }) {
        websites.indexOfFirst { it.isBlank() }.takeIf { it >= 0 }?.let(websites::removeAt)
        websites.add(0, service.website)
    }
    val title = title.trim()
    val titleIsPreviousService = previous?.let { title.equals(it.name, ignoreCase = true) } ?: false
    // Clearing the service also clears a title that was just its name,
    // otherwise the login would still match it by title.
    val newTitle = if (title.isEmpty() || titleIsPreviousService) service?.name.orEmpty() else this.title
    return copy(title = newTitle, websites = websites)
}

/** "Gmail — me@gmail.com": enough to tell several accounts apart. */
val LoginItem.accountLabel: String
    get() {
        val detail = email.ifEmpty { username }
        return if (detail.isEmpty()) title else "$title — $detail"
    }

/** The address identifying this login as an account: the email, or the username when it's an address. */
val LoginItem.accountAddress: String?
    get() {
        val email = email.trim()
        val username = username.trim()
        return email.ifEmpty { null } ?: username.takeIf { "@" in it }
    }

/** How an account reads in the account picker: "me@gmail.com (Gmail)". */
val LoginItem.accountChoiceLabel: String
    get() = accountAddress?.let { "$it ($title)" } ?: title

/**
 * The saved logins that can be picked as the account for [method], one per
 * address, mirroring VaultStore.accountCandidates on macOS.
 */
fun List<LoginItem>.accountCandidates(method: SignInMethod, excluding: java.util.UUID? = null): List<LoginItem> {
    val seen = mutableSetOf<String>()
    val byName = Comparator<String>(String.CASE_INSENSITIVE_ORDER::compare)
    return filter { it.id != excluding && method.isAccount(it) }
        // Exact service matches (a login titled "Gmail") win over keyword
        // matches ("Google Ads") when two share an address.
        .sortedWith(compareBy<LoginItem> { KnownService.matching(it) == null }.thenBy(byName) { it.title })
        .filter { item -> item.accountAddress?.lowercase()?.let(seen::add) ?: true }
        .sortedWith(compareBy(byName) { it.accountChoiceLabel })
}

