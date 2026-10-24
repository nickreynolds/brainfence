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
}
