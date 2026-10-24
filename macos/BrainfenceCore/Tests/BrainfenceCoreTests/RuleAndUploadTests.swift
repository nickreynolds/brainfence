import XCTest
@testable import BrainfenceCore

final class BlockedAppParsingTests: XCTestCase {
    func testParsesBothPlatformsAndBareStrings() {
        let apps = BlockedApp.parseList(#"""
        [{"platform":"android","package":"com.twitter.android"},
         {"platform":"macos","bundle_id":"com.hnc.Discord","token":"abc"},
         "com.legacy.app"]
        """#)
        XCTAssertEqual(apps.map(\.key), ["android:com.twitter.android", "macos:com.hnc.Discord", "android:com.legacy.app"])
        XCTAssertEqual(apps[1].raw["token"], .string("abc"), "extra fields must survive a round trip")
    }

    func testWindowsAndUnknownPlatformsHaveDistinctKeys() {
        let apps = BlockedApp.parseList(#"""
        [{"platform":"windows","exe":"Discord.exe"},
         {"platform":"windows","exe":"steam.exe"},
         {"platform":"linux","bin":"a"},
         {"platform":"linux","bin":"b"}]
        """#)
        XCTAssertEqual(apps[0].key, "windows:discord.exe", "matches the Windows client's appRefKey")
        XCTAssertEqual(apps[1].key, "windows:steam.exe")
        XCTAssertEqual(Set(apps.map(\.key)).count, 4)
    }

    func testBlankOrInvalidIsEmpty() {
        XCTAssertTrue(BlockedApp.parseList(nil).isEmpty)
        XCTAssertTrue(BlockedApp.parseList("null").isEmpty)
        XCTAssertTrue(BlockedApp.parseList("{}").isEmpty)
    }

    func testRoundTripPreservesOtherPlatforms() {
        let original = #"[{"package":"com.twitter.android","platform":"android"}]"#
        var apps = BlockedApp.parseList(original)
        apps.append(.macOS(bundleID: "com.hnc.Discord"))
        XCTAssertEqual(
            BlockedApp.encodeList(apps).jsonString,
            #"[{"package":"com.twitter.android","platform":"android"},{"bundle_id":"com.hnc.Discord","platform":"macos"}]"#
        )
    }
}

final class RuleChangeTests: XCTestCase {
    private let android = BlockedApp.parseList(#"[{"platform":"android","package":"com.twitter.android"}]"#)[0]

    private func rule(ids: [String] = ["a"], logic: String = "all", domains: [String] = ["reddit.com"]) -> BlockingRule {
        BlockingRule(id: "r", name: "R", blockedApps: [android, .macOS(bundleID: "com.x")],
                     blockedDomains: domains, conditionTaskIds: ids, conditionLogic: logic)
    }

    func testAddingAppsAndDomainsIsStricter() {
        let c = RuleChange(blockedApps: [android, .macOS(bundleID: "com.x"), .macOS(bundleID: "com.y")],
                           blockedDomains: ["reddit.com", "x.com"])
        XCTAssertTrue(c.isMoreRestrictive(than: rule()))
    }

    func testRemovingAnyPlatformsAppIsWeaker() {
        XCTAssertFalse(RuleChange(blockedApps: [.macOS(bundleID: "com.x")]).isMoreRestrictive(than: rule()))
        XCTAssertFalse(RuleChange(blockedApps: [android]).isMoreRestrictive(than: rule()))
    }

    func testRemovingOneOfSeveralWindowsAppsIsWeaker() {
        let windows = BlockedApp.parseList(#"[{"platform":"windows","exe":"discord.exe"},{"platform":"windows","exe":"steam.exe"}]"#)
        let current = BlockingRule(id: "r", name: "R", blockedApps: windows, blockedDomains: [],
                                   conditionTaskIds: ["a"], conditionLogic: "all")
        XCTAssertFalse(RuleChange(blockedApps: [windows[0]]).isMoreRestrictive(than: current))
    }

    func testDomainNormalizationIsNotAWeakening() {
        XCTAssertTrue(RuleChange(blockedDomains: ["https://www.Reddit.com/"]).isMoreRestrictive(than: rule()))
        XCTAssertFalse(RuleChange(blockedDomains: []).isMoreRestrictive(than: rule()))
    }

    func testAllLogicAddingConditionIsStricter() {
        XCTAssertTrue(RuleChange(conditionTaskIds: ["a", "b"]).isMoreRestrictive(than: rule()))
        XCTAssertFalse(RuleChange(conditionTaskIds: ["b"]).isMoreRestrictive(than: rule()))
    }

    func testAnyLogicAddingConditionIsWeaker() {
        let r = rule(ids: ["a", "b"], logic: "any")
        XCTAssertFalse(RuleChange(conditionTaskIds: ["a", "b", "c"]).isMoreRestrictive(than: r))
        XCTAssertTrue(RuleChange(conditionTaskIds: ["a"]).isMoreRestrictive(than: r))
    }

    func testGivingAnAlwaysBlockingRuleConditionsIsWeaker() {
        XCTAssertFalse(RuleChange(conditionTaskIds: ["a"]).isMoreRestrictive(than: rule(ids: [])))
    }

    func testLogicChanges() {
        XCTAssertFalse(RuleChange(conditionLogic: "any").isMoreRestrictive(than: rule()))
        XCTAssertTrue(RuleChange(conditionLogic: "all").isMoreRestrictive(than: rule(logic: "any")))
    }

    func testDeactivatingIsWeakerActivatingIsStricter() {
        XCTAssertFalse(RuleChange(isActive: false).isMoreRestrictive(than: rule()))
        var off = rule()
        off.isActive = false
        XCTAssertTrue(RuleChange(isActive: true).isMoreRestrictive(than: off))
    }

    func testShorteningLockIsWeaker() {
        XCTAssertFalse(RuleChange(configLockHours: 1).isMoreRestrictive(than: rule()))
        XCTAssertTrue(RuleChange(configLockHours: 48).isMoreRestrictive(than: rule()))
    }

    func testRenameIsNeutral() {
        XCTAssertTrue(RuleChange(name: "New name").isMoreRestrictive(than: rule()))
    }

    func testParsesAndroidPendingChanges() {
        let json = #"{"name":"R","blocked_apps":[{"platform":"android","package":"p"}],"blocked_domains":["a.com"],"condition_task_ids":["t"],"condition_logic":"any","is_active":0}"#
        let c = RuleChange.parse(json)
        XCTAssertEqual(c?.name, "R")
        XCTAssertEqual(c?.blockedApps?.map(\.key), ["android:p"])
        XCTAssertEqual(c?.blockedDomains, ["a.com"])
        XCTAssertEqual(c?.conditionTaskIds, ["t"])
        XCTAssertEqual(c?.conditionLogic, "any")
        XCTAssertEqual(c?.isActive, false)
    }

    func testParsesDoubleEncodedPendingChanges() {
        let inner = #"{"is_active":1}"#
        let doubled = JSONValue.string(inner).jsonString
        XCTAssertEqual(RuleChange.parse(doubled)?.isActive, true)
    }

    func testJSONRoundTrip() {
        let c = RuleChange(name: "R", blockedApps: [.macOS(bundleID: "com.x")], blockedDomains: ["a.com"],
                           conditionTaskIds: ["t"], conditionLogic: "all", isActive: true, configLockHours: 24)
        XCTAssertEqual(RuleChange.parse(c.json.jsonString), c)
    }

    func testColumnAssignmentsUseLocalRepresentation() {
        let c = RuleChange(blockedDomains: ["a.com"], isActive: false)
        let cols = Dictionary(uniqueKeysWithValues: c.columnAssignments.map { ($0.column, $0.value) })
        XCTAssertEqual(cols["blocked_domains"], .text(#"["a.com"]"#))
        XCTAssertEqual(cols["is_active"], .integer(0))
    }

    func testDiffDropsUnchangedFields() {
        let c = RuleChange(name: "R", blockedDomains: ["reddit.com"], conditionLogic: "any", isActive: true)
        XCTAssertEqual(c.diff(against: rule()), RuleChange(conditionLogic: "any"))
        XCTAssertTrue(RuleChange(name: "R").diff(against: rule()).isEmpty)
    }

    func testReorderingIsNotAnEdit() {
        let r = rule()
        let c = RuleChange(blockedApps: r.blockedApps.reversed(), blockedDomains: r.blockedDomains.reversed(),
                           conditionTaskIds: r.conditionTaskIds.reversed())
        XCTAssertTrue(c.diff(against: r).isEmpty)
    }
}

final class UploadNormalizerTests: XCTestCase {
    func testParsesJSONAndArrayColumns() {
        let out = UploadNormalizer.normalize(table: "blocking_rules", values: [
            "name": .string("Morning"),
            "blocked_apps": .string(#"[{"platform":"macos","bundle_id":"com.x"}]"#),
            "condition_task_ids": .string(#"["t1","t2"]"#),
            "blocked_domains": .string("[]"),
            "pending_changes": .null,
            "is_active": .int(1),
        ])
        XCTAssertEqual(out["name"], .string("Morning"))
        XCTAssertEqual(out["blocked_apps"], .array([.object(["platform": .string("macos"), "bundle_id": .string("com.x")])]))
        XCTAssertEqual(out["condition_task_ids"], .array([.string("t1"), .string("t2")]))
        XCTAssertEqual(out["blocked_domains"], .array([]))
        XCTAssertEqual(out["pending_changes"], .null)
        XCTAssertEqual(out["is_active"], .bool(true))
    }

    func testTaskJSONBAndBooleans() {
        let out = UploadNormalizer.normalize(table: "tasks", values: [
            "verification_config": .string(#"{"duration_seconds":300}"#),
            "blocking_days_of_week": .string(#"["mon"]"#),
            "home_only_blocking": .int(0),
            "title": .string("[not json column]"),
        ])
        XCTAssertEqual(out["verification_config"], .object(["duration_seconds": .int(300)]))
        XCTAssertEqual(out["blocking_days_of_week"], .array([.string("mon")]))
        XCTAssertEqual(out["home_only_blocking"], .bool(false))
        XCTAssertEqual(out["title"], .string("[not json column]"))
    }

    func testUnlistedTablePassesThrough() {
        let values: [String: JSONValue] = ["completed_at": .string("2026-04-16T14:00:00.000Z")]
        XCTAssertEqual(UploadNormalizer.normalize(table: "shopping_items", values: values), values)
    }

    func testCompletionVerificationData() {
        let out = UploadNormalizer.normalize(table: "task_completions", values: ["verification_data": .string("{}")])
        XCTAssertEqual(out["verification_data"], .object([:]))
    }
}

final class DomainMatcherTests: XCTestCase {
    func testNormalize() {
        XCTAssertEqual(DomainMatcher.normalize("Twitter.com"), "twitter.com")
        XCTAssertEqual(DomainMatcher.normalize(" https://www.reddit.com/r/all?x=1 "), "reddit.com")
        XCTAssertEqual(DomainMatcher.normalize("*.youtube.com"), "youtube.com")
        XCTAssertEqual(DomainMatcher.normalize("news.ycombinator.com:443"), "news.ycombinator.com")
        XCTAssertNil(DomainMatcher.normalize("localhost"))
        XCTAssertNil(DomainMatcher.normalize("   "))
    }

    func testHostOfURL() {
        XCTAssertEqual(DomainMatcher.host(ofURL: "https://Mobile.Twitter.com/home"), "mobile.twitter.com")
        XCTAssertNil(DomainMatcher.host(ofURL: "about:blank"))
        XCTAssertNil(DomainMatcher.host(ofURL: "file:///tmp/x.html"))
    }

    func testMatchesSubdomainsOnly() {
        XCTAssertTrue(DomainMatcher.host("twitter.com", matches: "twitter.com"))
        XCTAssertTrue(DomainMatcher.host("mobile.twitter.com", matches: "twitter.com"))
        XCTAssertFalse(DomainMatcher.host("nottwitter.com", matches: "twitter.com"))
        XCTAssertFalse(DomainMatcher.host("twitter.com.evil.io", matches: "twitter.com"))
    }
}

final class TaskCapabilityTests: XCTestCase {
    func testQuickCompletable() {
        XCTAssertTrue(BrainTask(id: "1", title: "t").isQuickCompletable)
        XCTAssertTrue(BrainTask(id: "1", title: "t", verificationType: nil).isQuickCompletable)
        XCTAssertFalse(BrainTask(id: "1", title: "t", taskType: "routine").isQuickCompletable)
        XCTAssertFalse(BrainTask(id: "1", title: "t", verificationType: "gps").isQuickCompletable)
        XCTAssertEqual(BrainTask(id: "1", title: "t", verificationType: "duration").verificationHint, "Requires timed session")
    }
}
