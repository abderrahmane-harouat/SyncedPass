import Foundation
import Testing
@testable import SyncedPass

@Suite("Service catalog")
struct ServiceCatalogTests {
    private func id(_ website: String) -> String? {
        KnownService.matching(website: website)?.id
    }

    @Test func matchesByWebsite() {
        #expect(id("github.com") == "github")
        #expect(id("https://www.github.com/login") == "github")
        #expect(id("twitter.com") == "x")
        #expect(id("https://www.yeswehack.com/programs") == "yeswehack")
        #expect(id("https://eccp.poste.dz/") == "eccp")
        #expect(id("https://portswigger.net/web-security") == "portswigger")
        #expect(id("https://accounts.hsoub.com/login") == "hsoub")
        #expect(id("https://tuwaiq.edu.sa/") == "tuwaiq")
        #expect(id("dribbble.com/shots") == "dribbble")
        #expect(id("https://dashboard.ngrok.com/") == "ngrok")
        #expect(id("https://app.lemonsqueezy.com/") == "lemonsqueezy")
        #expect(id("baridiweb.poste.dz") == "baridimob")
        #expect(id("https://www.poste.dz") == nil, "Algérie Poste's main site is neither service")
    }

    @Test func mostSpecificDomainWins() {
        #expect(id("mail.google.com") == "gmail")
        #expect(id("accounts.google.com") == "google")
        #expect(id("firebase.google.com") == "firebase")
    }

    @Test func doesNotMatchLookalikes() {
        #expect(id("notgithub.com") == nil)
        #expect(id("yeswehack.com.evil.example") == nil)
        #expect(id("") == nil)
    }

    @Test func matchesByTitleWhenThereIsNoWebsite() {
        #expect(KnownService.matching(LoginItem(title: "yeswehack"))?.id == "yeswehack")
        #expect(KnownService.matching(LoginItem(title: "Baridimob"))?.id == "baridimob")
        #expect(KnownService.matching(LoginItem(title: "eccp"))?.id == "eccp")
        #expect(KnownService.matching(LoginItem(title: "tuwaiq"))?.id == "tuwaiq")
        #expect(KnownService.matching(LoginItem(title: "Lemon Squeezy"))?.id == "lemonsqueezy")
        #expect(KnownService.matching(LoginItem(title: "My bank")) == nil)
    }

    @Test func searchFindsServicesByNameOrDomain() {
        #expect(KnownService.search("yes").contains { $0.id == "yeswehack" })
        #expect(KnownService.search("yeswehack.com").contains { $0.id == "yeswehack" })
    }

    @Test func multicolorBrandsUseTheirFullColorLogo() {
        // Drawn in one color these look wrong (Gmail's M as a white shape on red).
        for id in ["google", "gmail", "googledrive", "microsoft", "slack", "figma", "firebase"] {
            #expect(KnownService.all.first { $0.id == id }?.logo == .color, "\(id)")
        }
    }

    @Test func algeriePosteServicesUseTheirOfficialIcons() {
        #expect(KnownService.all.first { $0.id == "baridimob" }?.logo == .appIcon)
        #expect(KnownService.all.first { $0.id == "eccp" }?.logo == .color)
        #expect(KnownService.all.first { $0.id == "hsoub" }?.logo == .appIcon)
    }

    // MARK: Changing a login's service

    private func service(_ id: String) -> KnownService {
        KnownService.all.first { $0.id == id }!
    }

    @Test func pickingAServiceOnAnEmptyLoginFillsTitleAndWebsite() {
        let item = LoginItem(websites: [""]).changingService(to: service("github"))
        #expect(item.title == "GitHub")
        #expect(item.websites == ["https://github.com"])
        #expect(KnownService.matching(item)?.id == "github")
    }

    @Test func pickingAnotherServiceReplacesThePreviousOne() {
        let github = LoginItem(websites: [""]).changingService(to: service("github"))
        let notion = github.changingService(to: service("notion"))
        #expect(notion.title == "Notion")
        #expect(notion.websites == ["https://notion.so"])
        #expect(KnownService.matching(notion)?.id == "notion", "The picker must show the new service")
    }

    @Test func changingServiceKeepsATypedTitleAndOtherWebsites() {
        let item = LoginItem(title: "Work code", websites: ["https://github.com", "https://intranet.example"])
            .changingService(to: service("gitlab"))
        #expect(item.title == "Work code")
        #expect(item.websites == ["https://gitlab.com", "https://intranet.example"])
        #expect(KnownService.matching(item)?.id == "gitlab")
    }

    @Test func serviceMatchedOnlyByTitleCanBeChanged() {
        let item = LoginItem(title: "Notion").changingService(to: service("slack"))
        #expect(item.title == "Slack")
        #expect(KnownService.matching(item)?.id == "slack")
    }

    @Test func removingTheServiceClearsWhatItFilledIn() {
        let picked = LoginItem(websites: [""]).changingService(to: service("github"))
        let cleared = picked.changingService(to: nil)
        #expect(cleared.title.isEmpty)
        #expect(cleared.websites.isEmpty)
        #expect(KnownService.matching(cleared) == nil)

        let custom = LoginItem(title: "Work code", websites: ["https://github.com"]).changingService(to: nil)
        #expect(custom.title == "Work code", "A typed title is never cleared")
        #expect(KnownService.matching(custom) == nil)
    }

    @Test func catalogIsConsistent() {
        #expect(Set(KnownService.all.map(\.id)).count == KnownService.all.count, "Duplicate service IDs")
        for service in KnownService.all {
            #expect(!service.domains.isEmpty, "\(service.id) has no domain")
            if let name = service.logoAssetName {
                #expect(Bundle.main.image(forResource: name) != nil, "Missing logo asset for \(service.id)")
            }
        }
    }
}
