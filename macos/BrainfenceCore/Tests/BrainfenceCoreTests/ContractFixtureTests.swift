import Foundation
import XCTest
@testable import BrainfenceCore

/// Runs the cross-platform cases in shared/fixtures, which the Android and
/// Windows test suites load too. See shared/CONTRACTS.md.
final class ContractFixtureTests: XCTestCase {
    private func fixture(_ name: String) throws -> JSONValue {
        // …/macos/BrainfenceCore/Tests/BrainfenceCoreTests/<this file> → repo root
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        url.appendPathComponent("shared/fixtures/\(name)")
        return try XCTUnwrap(JSONValue.parse(try String(contentsOf: url, encoding: .utf8)), name)
    }

    func testRuleRestrictiveness() throws {
        let fixture = try fixture("rule-restrictiveness.json")
        let base = try XCTUnwrap(fixture["base_rule"]?.objectValue)
        let cases = try XCTUnwrap(fixture["cases"]?.arrayValue)
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let row = base.merging(c["rule"]?.objectValue ?? [:]) { _, override in override }
            let rule = BlockingRule(
                id: "r",
                name: "R",
                blockedApps: BlockedApp.parseList(row["blocked_apps"]?.jsonString),
                blockedDomains: JSONValue.stringArray(row["blocked_domains"]?.jsonString),
                conditionTaskIds: JSONValue.stringArray(row["condition_task_ids"]?.jsonString),
                conditionLogic: row["condition_logic"]?.stringValue ?? "all",
                configLockHours: row["config_lock_hours"]?.intValue ?? 24,
                isActive: row["is_active"]?.intValue != 0
            )
            let change = try XCTUnwrap(RuleChange.parse(c["changes"]?.jsonString))
            XCTAssertEqual(
                change.isMoreRestrictive(than: rule),
                c["more_restrictive"]?.intValue == 1,
                c["name"]?.stringValue ?? ""
            )
        }
    }

    func testBlockedAppKeys() throws {
        for c in try XCTUnwrap(fixture("blocked-app-keys.json")["cases"]?.arrayValue) {
            let entry = try XCTUnwrap(c["entry"])
            let app = BlockedApp.parseList(JSONValue.array([entry]).jsonString).first
            XCTAssertEqual(app?.key, c["key"]?.stringValue, entry.jsonString)
        }
    }

    func testDomains() throws {
        let fixture = try fixture("domains.json")
        for c in try XCTUnwrap(fixture["normalize"]?.arrayValue) {
            let input = c["input"]?.stringValue ?? ""
            XCTAssertEqual(DomainMatcher.normalize(input), c["normalized"]?.stringValue, input)
        }
        for c in try XCTUnwrap(fixture["matches"]?.arrayValue) {
            let host = c["host"]?.stringValue ?? "", domain = c["domain"]?.stringValue ?? ""
            XCTAssertEqual(DomainMatcher.host(host, matches: domain), c["matches"]?.intValue == 1, "\(host) / \(domain)")
        }
    }

    func testTimestamps() throws {
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        for c in try XCTUnwrap(fixture("timestamps.json")["cases"]?.arrayValue) {
            let input = c["input"]?.stringValue ?? ""
            let parsed = Timestamp.parse(input)
            guard let expected = c["utc"]?.stringValue.flatMap(iso.date(from:)) else {
                XCTAssertNil(parsed, input)
                continue
            }
            let actual = try XCTUnwrap(parsed, input)
            XCTAssertEqual(actual.timeIntervalSince1970, expected.timeIntervalSince1970, accuracy: 0.0005, input)
        }
    }

    func testOnboardingRoute() throws {
        let cases = try XCTUnwrap(fixture("onboarding.json")["route"]?.arrayValue)
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let row: OnboardingRow? = c["onboarding"].flatMap { o in
                guard let status = o["status"]?.stringValue else { return nil }
                var setupAt: [String: String] = [:]
                for p in OnboardingRow.platforms {
                    if let at = o[OnboardingRow.setupColumn(p)]?.stringValue { setupAt[p] = at }
                }
                return OnboardingRow(
                    status: status, deferredSteps: DeferredStep.parseList(o["deferred_steps"]?.jsonString),
                    blockingRuleId: nil, meditationTaskId: nil, setupAt: setupAt
                )
            }
            let route = onboardingRoute(
                platform: c["platform"]?.stringValue ?? "",
                hasSynced: c["has_synced"]?.boolValue ?? false,
                onboarding: row,
                taskCount: c["task_count"]?.intValue ?? 0
            )
            XCTAssertEqual(route.rawValue, c["route"]?.stringValue, c["name"]?.stringValue ?? "")
        }
    }

    func testOnboardingPlan() throws {
        let cases = try XCTUnwrap(fixture("onboarding.json")["plan"]?.arrayValue)
        XCTAssertFalse(cases.isEmpty)
        func place(_ v: JSONValue?) -> PlaceChoice? {
            guard let v, let lat = v["latitude"]?.doubleValue, let lng = v["longitude"]?.doubleValue else { return nil }
            return PlaceChoice(latitude: lat, longitude: lng, radiusMeters: v["radius_meters"]?.intValue ?? 100)
        }
        for c in cases {
            let ch = try XCTUnwrap(c["choices"])
            var starters: [StarterKey: StarterChoice] = [:]
            for (k, v) in ch["starters"]?.objectValue ?? [:] {
                starters[try XCTUnwrap(StarterKey(rawValue: k))] = StarterChoice(availableFrom: v["available_from"]?.stringValue, dueAt: v["due_at"]?.stringValue)
            }
            let plan = OnboardingPlan(OnboardingChoices(
                starters: starters,
                home: place(ch["home"]),
                store: place(ch["store"]),
                companionApps: BlockedApp.parseList(ch["companion_apps"]?.jsonString),
                blockedApps: BlockedApp.parseList(ch["blocked_apps"]?.jsonString),
                blockedDomains: JSONValue.stringArray(ch["blocked_domains"]?.jsonString)
            ))
            let optional = { (s: String?) in s.map(JSONValue.string) ?? .null }
            let actual = JSONValue.object([
                "tasks": .array(plan.tasks.map { t in
                    .object([
                        "key": .string(t.key.rawValue), "title": .string(t.title), "task_type": .string(t.taskType),
                        "verification_type": optional(t.verificationType), "verification_config": t.verificationConfig,
                        "recurrence_type": optional(t.recurrenceType), "recurrence_config": t.recurrenceConfig,
                        "is_blocking_condition": .bool(t.isBlockingCondition), "available_from": optional(t.availableFrom),
                        "due_at": optional(t.dueAt), "sort_order": .int(t.sortOrder),
                        "steps": .array(t.steps.map { .object(["title": .string($0.title), "step_type": .string($0.stepType), "config": $0.config]) }),
                    ])
                }),
                "deferred_steps": .array(plan.deferredSteps.map(\.json)),
                "add_remembrances": .bool(plan.addRemembrances),
                "rule": plan.rule.map { r in
                    .object([
                        "name": .string(r.name), "blocked_apps": BlockedApp.encodeList(r.blockedApps),
                        "blocked_domains": .array(of: r.blockedDomains),
                        "condition_keys": .array(of: r.conditionKeys.map(\.rawValue)), "condition_logic": .string(r.conditionLogic),
                    ])
                } ?? .null,
            ])
            XCTAssertEqual(actual, c["expect"], c["name"]?.stringValue ?? "")
        }
    }
}
