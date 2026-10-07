package com.abdurahmanharouat.syncedpass

import com.abdurahmanharouat.syncedpass.model.CustomField
import com.abdurahmanharouat.syncedpass.model.KnownService
import com.abdurahmanharouat.syncedpass.model.LoginItem
import com.abdurahmanharouat.syncedpass.model.LoginValidation
import com.abdurahmanharouat.syncedpass.model.SignInMethod
import com.abdurahmanharouat.syncedpass.model.accountAddress
import com.abdurahmanharouat.syncedpass.model.accountCandidates
import com.abdurahmanharouat.syncedpass.model.accountChoiceLabel
import com.abdurahmanharouat.syncedpass.model.changingService
import com.abdurahmanharouat.syncedpass.model.cleanedForSaving
import com.abdurahmanharouat.syncedpass.model.validationErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same cases as the Mac's ServiceCatalogTests and SignInTests, so both apps behave alike. */
class ServicesAndValidationTest {
    private fun id(website: String) = KnownService.matching(website)?.id
    private fun service(id: String) = KnownService.byId(id)!!

    @Test fun matchesByWebsite() {
        assertEquals("github", id("github.com"))
        assertEquals("github", id("https://www.github.com/login"))
        assertEquals("x", id("twitter.com"))
        assertEquals("yeswehack", id("https://www.yeswehack.com/programs"))
        assertEquals("eccp", id("https://eccp.poste.dz/"))
        assertEquals("hsoub", id("https://accounts.hsoub.com/login"))
        assertEquals("dribbble", id("dribbble.com/shots"))
        assertEquals("baridimob", id("baridiweb.poste.dz"))
        assertEquals("projectdiscovery", id("https://cloud.projectdiscovery.io/scans"))
        assertNull("Algérie Poste's main site is neither service", id("https://www.poste.dz"))
    }

    @Test fun mostSpecificDomainWins() {
        assertEquals("gmail", id("mail.google.com"))
        assertEquals("google", id("accounts.google.com"))
        assertEquals("firebase", id("firebase.google.com"))
    }

    @Test fun doesNotMatchLookalikes() {
        assertNull(id("notgithub.com"))
        assertNull(id("yeswehack.com.evil.example"))
        assertNull(id(""))
    }

    @Test fun matchesByTitleAndSearches() {
        assertEquals("lemonsqueezy", KnownService.matching(LoginItem(title = "Lemon Squeezy"))?.id)
        assertNull(KnownService.matching(LoginItem(title = "My bank")))
        assertTrue(KnownService.search("yes").any { it.id == "yeswehack" })
        assertTrue(KnownService.search("yeswehack.com").any { it.id == "yeswehack" })
    }

    @Test fun catalogMatchesTheMac() {
        assertEquals(111, KnownService.all.size)
        assertEquals(KnownService.all.size, KnownService.all.map { it.id }.toSet().size)
        for (s in KnownService.all) {
            assertTrue(s.id, s.domains.isNotEmpty())
            assertEquals(s.id, s.logo == KnownService.Logo.None, s.logoRes == null)
        }
        assertEquals(KnownService.Logo.AppIcon, service("baridimob").logo)
        assertEquals(KnownService.Logo.Color, service("gmail").logo)
    }

    @Test fun pickingAndReplacingAService() {
        val github = LoginItem(websites = listOf("")).changingService(service("github"))
        assertEquals("GitHub", github.title)
        assertEquals(listOf("https://github.com"), github.websites)
        val notion = github.changingService(service("notion"))
        assertEquals("Notion", notion.title)
        assertEquals(listOf("https://notion.so"), notion.websites)
        assertEquals("notion", KnownService.matching(notion)?.id)
    }

    @Test fun changingServiceKeepsATypedTitleAndOtherWebsites() {
        val item = LoginItem(title = "Work code", websites = listOf("https://github.com", "https://intranet.example"))
            .changingService(service("gitlab"))
        assertEquals("Work code", item.title)
        assertEquals(listOf("https://gitlab.com", "https://intranet.example"), item.websites)
        assertEquals("slack", KnownService.matching(LoginItem(title = "Notion").changingService(service("slack")))?.id)
    }

    @Test fun removingTheServiceClearsWhatItFilledIn() {
        val cleared = LoginItem(websites = listOf("")).changingService(service("github")).changingService(null)
        assertEquals("", cleared.title)
        assertEquals(emptyList<String>(), cleared.websites)
        val custom = LoginItem(title = "Work code", websites = listOf("https://github.com")).changingService(null)
        assertEquals("Work code", custom.title)
        assertNull(KnownService.matching(custom))
    }

    @Test fun accountPickerListsOnlyProviderAccountsOncePerAddress() {
        val gmail = LoginItem(title = "Gmail", email = "me@gmail.com")
        val items = listOf(
            gmail,
            LoginItem(title = "Google", email = "ME@gmail.com"),
            LoginItem(title = "Gmail perso", username = "other@gmail.com"),
            LoginItem(title = "Work", email = "me@company.com", websites = listOf("https://accounts.google.com")),
            LoginItem(title = "Facebook", email = "me@gmail.com"),
            LoginItem(title = "Dropbox", email = "me@gmail.com"),
            LoginItem(title = "iCloud", email = "me@icloud.com"),
            LoginItem(title = "Project X"),
        )
        val google = items.accountCandidates(SignInMethod.Google)
        assertEquals(listOf("me@company.com (Work)", "me@gmail.com (Gmail)", "other@gmail.com (Gmail perso)"), google.map { it.accountChoiceLabel })
        assertEquals(gmail.id, google.first { it.accountAddress == "me@gmail.com" }.id)
        assertEquals(listOf("iCloud"), items.accountCandidates(SignInMethod.Apple).map { it.title })
        assertTrue(items.accountCandidates(SignInMethod.Twitter).isEmpty())
        assertTrue(items.accountCandidates(SignInMethod.Google, excluding = gmail.id).any { it.title == "Google" })
    }

    @Test fun validation() {
        assertNull(LoginValidation.emailError(""))
        assertNull(LoginValidation.emailError(" alex@example.com "))
        assertNotNull(LoginValidation.emailError("alex"))
        assertNull(LoginValidation.websiteError("example.com"))
        assertNotNull(LoginValidation.websiteError("not a site"))
        assertNotNull(LoginValidation.websiteError("ftp://example.com"))
        assertEquals("https://example.com", LoginValidation.normalizedWebsite(" example.com "))
        assertNull(LoginValidation.totpError("JBSW Y3DP EHPK 3PXP"))
        assertNull(LoginValidation.totpError("otpauth://totp/Example:alex@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example"))
        assertNotNull(LoginValidation.totpError("123456"))
        assertNotNull(LoginValidation.totpError("otpauth://totp/x?issuer=Example"))
    }

    @Test fun savingCleansAndBlocksInvalidItems() {
        val item = LoginItem(title = " GitHub ", email = " a@b.co ", websites = listOf("github.com", " "),
                             customFields = listOf(CustomField(kind = CustomField.Kind.Text, name = " PIN hint ")))
        assertEquals(emptyList<String>(), item.validationErrors)
        val clean = item.cleanedForSaving()
        assertEquals("GitHub", clean.title)
        assertEquals("a@b.co", clean.email)
        assertEquals(listOf("https://github.com"), clean.websites)
        assertEquals("PIN hint", clean.customFields.single().name)
        assertEquals(
            listOf("Title is required.", "Every custom field needs a name."),
            LoginItem(customFields = listOf(CustomField(kind = CustomField.Kind.Hidden))).validationErrors,
        )
    }
}
