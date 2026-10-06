import Foundation
import Testing
@testable import SyncedPass

@Suite("Search")
struct LoginSearchTests {
    private func titles(_ items: [LoginItem]) -> [String] { items.map(\.title) }

    @Test func nameMatchesComeBeforeEmailMatches() {
        let items = [
            LoginItem(title: "Facebook", email: "me@gmail.com"),
            LoginItem(title: "Gmail", email: "me@gmail.com", websites: ["https://mail.google.com"]),
            LoginItem(title: "Netflix", email: "me@gmail.com"),
            LoginItem(title: "Bank", email: "me@outlook.com"),
        ]
        let results = LoginSearch.search(items, for: "gmail")
        #expect(titles(results.topMatches) == ["Gmail"])
        #expect(results.otherMatches.map(\.item.title) == ["Facebook", "Netflix"])
        #expect(results.otherMatches.allSatisfy { $0.field == .email })
    }

    @Test func topMatchesAreRankedByHowWellTheNameMatches() {
        let items = ["Snowbank", "My Bank", "Bankify", "Bank", "Work"].map { LoginItem(title: $0) }
            + [LoginItem(title: "Savings", websites: ["https://online.bank.example"])]
        // exact, prefix, word prefix, contains, then website domain
        #expect(titles(LoginSearch.search(items, for: "bank").topMatches)
                == ["Bank", "Bankify", "My Bank", "Snowbank", "Savings"])
    }

    @Test func websitesMatchOnTheirDomainOnly() {
        let items = [
            LoginItem(title: "Work mail", websites: ["https://mail.google.com/u/0"]),
            LoginItem(title: "Shop", websites: ["https://shop.example"]),
        ]
        #expect(titles(LoginSearch.search(items, for: "google").topMatches) == ["Work mail"])
        #expect(LoginSearch.search(items, for: "https").all.isEmpty, "Scheme must not match every login")
        #expect(LoginSearch.search(items, for: "u/0").all.isEmpty, "Paths aren't searched")
    }

    @Test func otherMatchesSayWhichFieldMatched() {
        let items = [
            LoginItem(title: "A", username: "octocat"),
            LoginItem(title: "B", note: "recovery codes for octocat"),
        ]
        let others = LoginSearch.search(items, for: "octocat").otherMatches
        #expect(others.map(\.field) == [.username, .note])
    }

    @Test func eachLoginAppearsOnce() {
        let items = [LoginItem(title: "Gmail", email: "me@gmail.com", note: "gmail")]
        let results = LoginSearch.search(items, for: "gmail")
        #expect(results.topMatches.count == 1)
        #expect(results.otherMatches.isEmpty)
    }

    @Test func ignoresCaseAccentsAndSurroundingSpaces() {
        let items = [LoginItem(title: "Café Rewards")]
        #expect(titles(LoginSearch.search(items, for: "  CAFE ").topMatches) == ["Café Rewards"])
    }

    @Test func emptyQueryListsEverythingAlphabetically() {
        let items = ["b", "C", "a"].map { LoginItem(title: $0, email: "x@gmail.com") }
        let results = LoginSearch.search(items, for: "")
        #expect(titles(results.topMatches) == ["a", "b", "C"])
        #expect(results.otherMatches.isEmpty)
    }
}
