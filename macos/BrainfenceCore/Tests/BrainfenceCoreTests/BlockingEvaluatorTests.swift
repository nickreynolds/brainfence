import XCTest
@testable import BrainfenceCore

/// Port of android/…/BlockingEvaluatorTest.kt, asserting on the macOS
/// enforcement surface (bundle IDs + domains) as well as the rule decision.
final class BlockingEvaluatorTests: XCTestCase {
    private let zone = TimeZone(identifier: "America/New_York")!

    private func instant(_ hour: Int, _ minute: Int = 0) -> Date {
        ZonedClock(timeZone: zone).instant(CivilDate("2026-04-16")!, hour: hour, minute: minute) // Thursday
    }

    private func taskWithWindow(
        id: String = "task-1", completedToday: Bool = false,
        availableFrom: String? = "07:00", dueAt: String? = "10:00",
        homeOnlyBlocking: Bool = false, blockingDaysOfWeek: String = "[]"
    ) -> BrainTask {
        BrainTask(
            id: id, title: "Morning routine", availableFrom: availableFrom, dueAt: dueAt,
            homeOnlyBlocking: homeOnlyBlocking, blockingDaysOfWeek: blockingDaysOfWeek,
            completedToday: completedToday
        )
    }

    private func manualTask(id: String = "task-2", completedToday: Bool = false, blockingDaysOfWeek: String = "[]") -> BrainTask {
        BrainTask(id: id, title: "Manual task", blockingDaysOfWeek: blockingDaysOfWeek, completedToday: completedToday)
    }

    private func rule(_ conditionTaskIds: [String] = ["task-1"], logic: String = "all") -> BlockingRule {
        BlockingRule(
            id: "rule-1", name: "Block social media",
            blockedApps: [
                BlockedApp(platform: "android", identifier: "com.twitter.android",
                           raw: .object(["platform": .string("android"), "package": .string("com.twitter.android")])),
                .macOS(bundleID: "com.hnc.Discord"),
            ],
            blockedDomains: ["twitter.com"],
            conditionTaskIds: conditionTaskIds, conditionLogic: logic
        )
    }

    private func evaluate(_ rules: [BlockingRule], _ tasks: [BrainTask], at now: Date, home: HomePresence = .unconfigured) -> BlockingState {
        evaluateBlocking(rules: rules, tasks: tasks, now: now, timeZone: zone, homePresence: home)
    }

    private func assertBlocks(_ s: BlockingState, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(s.blockedBundleIDs, ["com.hnc.Discord"], file: file, line: line)
        XCTAssertEqual(s.blockedDomains, ["twitter.com"], file: file, line: line)
    }

    private func assertNotBlocking(_ s: BlockingState, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertFalse(s.isBlocking, file: file, line: line)
        XCTAssertTrue(s.blockedBundleIDs.isEmpty && s.blockedDomains.isEmpty, file: file, line: line)
    }

    // MARK: Availability window

    func testBeforeAvailableFromDoesNotBlock() { assertNotBlocking(evaluate([rule()], [taskWithWindow()], at: instant(6))) }
    func testDuringActiveWindowDoesNotBlock() { assertNotBlocking(evaluate([rule()], [taskWithWindow()], at: instant(8))) }
    func testPastDueIncompleteBlocks() { assertBlocks(evaluate([rule()], [taskWithWindow()], at: instant(11))) }
    func testPastDueCompletedDoesNotBlock() { assertNotBlocking(evaluate([rule()], [taskWithWindow(completedToday: true)], at: instant(11))) }
    func testCompletedEarlyStaysUnblocked() { assertNotBlocking(evaluate([rule()], [taskWithWindow(completedToday: true)], at: instant(15))) }

    // MARK: Mixed conditions

    func testAllLogicManualIncompleteBlocks() {
        let s = evaluate([rule(["task-1", "task-2"])], [taskWithWindow(), manualTask()], at: instant(8))
        assertBlocks(s)
        XCTAssertEqual(s.activeBlocks.first?.unmetTaskIds, ["task-2"])
    }

    func testAnyLogicTimedTaskMetBeforeDue() {
        assertNotBlocking(evaluate([rule(["task-1", "task-2"], logic: "any")], [taskWithWindow(), manualTask()], at: instant(8)))
    }

    func testAllLogicBothPastDueBlocks() {
        let s = evaluate([rule(["task-1", "task-2"])], [taskWithWindow(), manualTask()], at: instant(11))
        assertBlocks(s)
        XCTAssertEqual(s.unmetTaskIds, ["task-1", "task-2"])
    }

    // MARK: Home-only blocking

    func testHomeOnlyBlocksAtHome() { assertBlocks(evaluate([rule()], [taskWithWindow(homeOnlyBlocking: true)], at: instant(11), home: .atHome)) }
    func testHomeOnlyDoesNotBlockAway() { assertNotBlocking(evaluate([rule()], [taskWithWindow(homeOnlyBlocking: true)], at: instant(11), home: .away)) }
    func testHomeOnlyBlocksWhenUnknown() { assertBlocks(evaluate([rule()], [taskWithWindow(homeOnlyBlocking: true)], at: instant(11), home: .unknown)) }
    func testHomeOnlyBlocksWhenUnconfigured() { assertBlocks(evaluate([rule()], [taskWithWindow(homeOnlyBlocking: true)], at: instant(11), home: .unconfigured)) }
    func testNonHomeOnlyBlocksAway() { assertBlocks(evaluate([rule()], [taskWithWindow()], at: instant(11), home: .away)) }

    func testHomeOnlyAwayPlusManualStillBlocks() {
        let s = evaluate([rule(["task-1", "task-2"])], [taskWithWindow(homeOnlyBlocking: true), manualTask()], at: instant(11), home: .away)
        assertBlocks(s)
    }

    func testAllHomeOnlyAwayLifts() {
        let tasks = [taskWithWindow(id: "task-1", homeOnlyBlocking: true), taskWithWindow(id: "task-2", homeOnlyBlocking: true)]
        assertNotBlocking(evaluate([rule(["task-1", "task-2"])], tasks, at: instant(11), home: .away))
    }

    // MARK: Fail closed

    func testUnknownConditionTaskFailsClosed() {
        assertBlocks(evaluate([rule(["missing-task"])], [], at: instant(11)))
    }

    func testAnyLogicUnknownTaskButRealSiblingMet() {
        assertNotBlocking(evaluate([rule(["task-1", "ghost"], logic: "any")], [taskWithWindow(completedToday: true)], at: instant(11)))
    }

    // MARK: Blocking days of week

    func testBlockingDaysExcludesToday() {
        // Thursday is not listed → treated as met.
        assertNotBlocking(evaluate([rule(["task-2"])], [manualTask(blockingDaysOfWeek: #"["mon","tue"]"#)], at: instant(11)))
    }

    func testBlockingDaysIncludesToday() {
        assertBlocks(evaluate([rule(["task-2"])], [manualTask(blockingDaysOfWeek: #"["thu"]"#)], at: instant(11)))
    }

    // MARK: Rule shape

    func testInactiveRuleNeverBlocks() {
        var r = rule(["missing-task"])
        r.isActive = false
        assertNotBlocking(evaluate([r], [], at: instant(11)))
    }

    func testRuleWithoutConditionsAlwaysBlocks() {
        assertBlocks(evaluate([rule([])], [], at: instant(11)))
    }

    func testAndroidOnlyRuleStillBlocksDomainsOnMac() {
        var r = rule(["task-2"])
        r.blockedApps = Array(r.blockedApps.prefix(1)) // android entry only
        let s = evaluate([r], [manualTask()], at: instant(11))
        XCTAssertTrue(s.isBlocking)
        XCTAssertTrue(s.blockedBundleIDs.isEmpty)
        XCTAssertEqual(s.blockedDomains, ["twitter.com"])
    }

    func testLookupsByBundleAndHost() {
        let s = evaluate([rule(["task-2"])], [manualTask()], at: instant(11))
        XCTAssertEqual(s.blocks(forBundleID: "com.hnc.Discord").map(\.rule.id), ["rule-1"])
        XCTAssertTrue(s.blocks(forBundleID: "com.apple.Safari").isEmpty)
        XCTAssertEqual(s.blocks(forHost: "mobile.twitter.com").map(\.rule.id), ["rule-1"])
        XCTAssertEqual(s.blockedDomain(forHost: "twitter.com"), "twitter.com")
        XCTAssertNil(s.blockedDomain(forHost: "nottwitter.com"))
    }

    // MARK: Time boundaries

    func testNextTimeBoundaryIsNextDueAtOfEnforcedTask() {
        let next = nextTimeBoundary(rules: [rule()], tasks: [taskWithWindow()], now: instant(8), timeZone: zone)
        XCTAssertEqual(next, instant(10))
    }

    func testNextTimeBoundaryFallsBackToMidnight() {
        let next = nextTimeBoundary(rules: [rule()], tasks: [taskWithWindow()], now: instant(11), timeZone: zone)
        XCTAssertEqual(next, ZonedClock(timeZone: zone).startOfDay(CivilDate("2026-04-17")!))
    }
}
