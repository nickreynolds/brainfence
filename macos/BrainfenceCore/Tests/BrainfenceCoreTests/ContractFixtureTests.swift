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
                        "due_at": optional(t.dueAt), "sort_order": .int(t.sortOrder), "report": optional(t.report),
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

    func testAuthCallbackLinks() throws {
        let cases = try XCTUnwrap(fixture("auth-callback.json")["cases"]?.arrayValue)
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let text = c["url"]?.stringValue ?? ""
            let parsed = URL(string: text).flatMap(AuthCallback.parse)
            let actual: JSONValue = switch parsed {
            case nil: .null
            case .code(let code)?: .object(["kind": .string("code"), "code": .string(code)])
            case .tokens(let a, let r)?: .object(["kind": .string("tokens"), "access_token": .string(a), "refresh_token": .string(r)])
            case .error(let m)?: .object(["kind": .string("error"), "message": .string(m)])
            case .none?: .object(["kind": .string("none")])
            }
            XCTAssertEqual(actual, c["result"], text)
        }
    }

    func testFeedbackMessages() throws {
        let f = try fixture("feedback.json")
        XCTAssertEqual(f["max_length"]?.intValue, Feedback.maxLength)
        let cases = try XCTUnwrap(f["cases"]?.arrayValue)
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"]?.stringValue ?? ""
            let message: String
            if let r = c["repeat"] {
                message = String(repeating: r["text"]?.stringValue ?? "", count: r["times"]?.intValue ?? 0) + (r["suffix"]?.stringValue ?? "")
            } else {
                message = try XCTUnwrap(c["message"]?.stringValue, name)
            }
            let actual = Feedback.normalize(message)
            if let length = c["expect_length"]?.intValue {
                XCTAssertEqual(actual, message.trimmingCharacters(in: .whitespacesAndNewlines), name)
                XCTAssertEqual(actual.map(Feedback.length), length, name)
            } else {
                XCTAssertEqual(actual, c["expect"]?.stringValue, name)
            }
        }
    }

    func testTaskStatus() throws {
        let fixture = try fixture("task-status.json")
        let zone = try XCTUnwrap(TimeZone(identifier: try XCTUnwrap(fixture["time_zone"]?.stringValue)))
        let base = try XCTUnwrap(fixture["base_task"]?.objectValue)
        let cases = try XCTUnwrap(fixture["cases"]?.arrayValue)
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let row = base.merging(c["task"]?.objectValue ?? [:]) { _, override in override }
            let name = c["name"]?.stringValue ?? ""
            let task = BrainTask(
                id: "t", title: "T",
                recurrenceType: row["recurrence_type"]?.stringValue,
                recurrenceConfig: row["recurrence_config"]?.jsonString ?? "{}",
                availableFrom: row["available_from"]?.stringValue,
                dueAt: row["due_at"]?.stringValue,
                homeOnlyBlocking: row["home_only_blocking"]?.intValue == 1,
                blockingDaysOfWeek: row["blocking_days_of_week"]?.jsonString ?? "[]",
                carryOver: row["carry_over"]?.intValue == 1,
                createdAt: row["created_at"]?.stringValue ?? "",
                lastCompletionAt: c["last_completion_at"]?.stringValue
            )
            let now = try XCTUnwrap(Timestamp.parse(c["now"]?.stringValue), name)
            let status = task.status(now: now, timeZone: zone)
            let home: HomePresence = switch c["home"]?.stringValue {
            case "AT_HOME": .atHome
            case "AWAY": .away
            case "UNKNOWN": .unknown
            default: .unconfigured
            }
            let expect = try XCTUnwrap(c["expect"])
            XCTAssertEqual(status.scheduled, expect["scheduled"]?.boolValue, name)
            XCTAssertEqual(status.completed, expect["completed"]?.boolValue, name)
            XCTAssertEqual(status.carriedOver, expect["carried_over"]?.boolValue, name)
            let phase: String? = switch status.phase {
            case .beforeStart: "BEFORE_START"
            case .active: "ACTIVE"
            case .pastDue: "PAST_DUE"
            case nil: nil
            }
            XCTAssertEqual(phase, expect["phase"]?.stringValue, name)
            XCTAssertEqual(isConditionUnmet(task, now: now, timeZone: zone, homePresence: home), expect["unmet"]?.boolValue, name)
        }
    }

    func testCompletionAlternatives() throws {
        for c in try XCTUnwrap(fixture("completion-alternatives.json")["cases"]?.arrayValue) {
            let name = c["name"]?.stringValue ?? ""
            let actual = parseGpsAlternatives(c["input"]?.jsonString)
            let expected = c["gps"]?.arrayValue ?? []
            XCTAssertEqual(actual.count, expected.count, name)
            for (a, e) in zip(actual, expected) {
                XCTAssertEqual(a.index, e["index"]?.intValue, name)
                XCTAssertEqual(a.label, e["label"]?.stringValue, name)
                XCTAssertEqual(a.config.latitude, e["lat"]?.doubleValue, name)
                XCTAssertEqual(a.config.longitude, e["lng"]?.doubleValue, name)
                XCTAssertEqual(a.config.radiusMeters, e["radius_m"]?.doubleValue, name)
                XCTAssertEqual(a.config.mode, e["mode"]?.stringValue, name)
                XCTAssertEqual(a.config.minDurationMinutes, e["min_duration_m"]?.intValue, name)
            }
        }
    }

    func testTimedSets() throws {
        for c in try XCTUnwrap(fixture("timed-sets.json")["cases"]?.arrayValue) {
            let name = c["name"]?.stringValue ?? ""
            let config = TimedSetsConfig(json: c["config"]?.jsonString)
            XCTAssertEqual(config.hangCount, c["hangs"]?.intValue, name)
            XCTAssertEqual(config.weight?.rawValue, c["weight"]?.stringValue, name)
            XCTAssertEqual(config.weightPrompt, c["weight_prompt"]?.stringValue, name)
            XCTAssertEqual((0..<config.hangCount).map(config.setOf), (c["set_of"]?.arrayValue ?? []).compactMap(\.intValue), name)
            XCTAssertEqual((0..<config.hangCount).map(config.sideOf), (c["side_of"]?.arrayValue ?? []).compactMap(\.intValue), name)
            let lastStep = c["last_step"]?.boolValue == true
            XCTAssertEqual((0..<config.hangCount).map { config.restAfter($0, isLastStep: lastStep) },
                           (c["rest_after"]?.arrayValue ?? []).compactMap(\.intValue), name)
        }
    }
}
