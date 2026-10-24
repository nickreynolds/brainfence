import BrainfenceCore
import Foundation
import PowerSync

/// Reads and edits `blocking_rules`, including the anti-bypass time-lock:
/// changes that could weaken a rule are staged in `pending_changes` and only
/// promoted once `changes_apply_at` has passed. Behaviour matches
/// android/…/data/blocking/BlockingRepository.kt, with a stricter
/// restrictiveness check (see `RuleChange.isMoreRestrictive`).
struct BlockingRepository: Sendable {
    let db: PowerSyncDatabaseProtocol

    enum ChangeOutcome: Equatable {
        case noChange
        case appliedNow
        case scheduled(applyAt: Date)
    }

    private static let columns = """
        id, user_id, name, blocked_apps, blocked_domains, condition_task_ids,
        condition_logic, config_lock_hours, pending_changes, changes_apply_at, is_active
        """

    /// All rules (active and inactive), by name. Evaluation filters on `isActive`.
    func watchRules() throws -> AsyncThrowingStream<[BlockingRule], any Error> {
        try db.watch(sql: "SELECT \(Self.columns) FROM blocking_rules ORDER BY name", parameters: [], mapper: Self.map)
    }

    func rules() async throws -> [BlockingRule] {
        try await db.getAll(sql: "SELECT \(Self.columns) FROM blocking_rules ORDER BY name", parameters: [], mapper: Self.map)
    }

    func rule(id: String) async throws -> BlockingRule? {
        try await db.getOptional(sql: "SELECT \(Self.columns) FROM blocking_rules WHERE id = ?", parameters: [id], mapper: Self.map)
    }

    /// New rules only ever add blocking, so they apply immediately.
    @discardableResult
    func createRule(
        userId: String,
        name: String,
        blockedApps: [BlockedApp],
        blockedDomains: [String],
        conditionTaskIds: [String],
        conditionLogic: String
    ) async throws -> String {
        let id = UUID().uuidString.lowercased()
        let now = Timestamp.format(Date())
        try await db.execute(
            sql: """
                INSERT INTO blocking_rules
                    (id, user_id, name, blocked_apps, blocked_domains, condition_task_ids,
                     condition_logic, config_lock_hours, is_active, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            parameters: [
                id, userId, name,
                BlockedApp.encodeList(blockedApps).jsonString,
                JSONValue.array(of: blockedDomains).jsonString,
                JSONValue.array(of: conditionTaskIds).jsonString,
                conditionLogic, 24, 1, now, now,
            ]
        )
        return id
    }

    /// Applies `change` now if it only makes the rule stricter; otherwise
    /// stages it behind the rule's time-lock (replacing any staged change).
    func submit(_ change: RuleChange, toRuleId ruleId: String, now: Date = Date()) async throws -> ChangeOutcome {
        guard let current = try await rule(id: ruleId) else { return .noChange }
        let diff = change.diff(against: current)
        if diff.isEmpty { return .noChange }

        if diff.isMoreRestrictive(than: current) {
            try await apply(diff, toRuleId: ruleId)
            return .appliedNow
        }

        let applyAt = now.addingTimeInterval(TimeInterval(max(current.configLockHours, 0) * 3600))
        try await db.execute(
            sql: "UPDATE blocking_rules SET pending_changes = ?, changes_apply_at = ?, updated_at = ? WHERE id = ?",
            parameters: [diff.json.jsonString, Timestamp.format(applyAt), Timestamp.format(now), ruleId]
        )
        return .scheduled(applyAt: applyAt)
    }

    /// Deletes an inactive rule. Active rules must be deactivated first (a
    /// time-locked change), or deleting would be a way around the lock.
    func deleteRule(id: String) async throws {
        try await db.execute(sql: "DELETE FROM blocking_rules WHERE id = ? AND is_active = 0", parameters: [id])
    }

    func cancelPendingChange(ruleId: String) async throws {
        try await db.execute(
            sql: "UPDATE blocking_rules SET pending_changes = NULL, changes_apply_at = NULL, updated_at = ? WHERE id = ?",
            parameters: [Timestamp.format(Date()), ruleId]
        )
    }

    /// Promotes staged changes whose time-lock has expired. Dates are compared
    /// parsed, not as strings: rows written locally and rows replicated from
    /// Postgres use different timestamp spellings.
    @discardableResult
    func applyExpiredPendingChanges(now: Date = Date()) async throws -> Int {
        let pending = try await db.getAll(
            sql: """
                SELECT id, pending_changes, changes_apply_at FROM blocking_rules
                WHERE pending_changes IS NOT NULL AND changes_apply_at IS NOT NULL
                """,
            parameters: []
        ) { c in
            (try c.getString(index: 0), c.getStringOptional(index: 1), c.getStringOptional(index: 2))
        }

        var applied = 0
        for (id, json, applyAtText) in pending {
            guard let applyAt = Timestamp.parse(applyAtText), applyAt <= now else { continue }
            if let change = RuleChange.parse(json) {
                try await apply(change, toRuleId: id)
            } else {
                try await cancelPendingChange(ruleId: id)
            }
            applied += 1
        }
        return applied
    }

    /// Writes `change` into the live columns and clears any staged change.
    private func apply(_ change: RuleChange, toRuleId ruleId: String) async throws {
        var sets: [String] = []
        var params: [Sendable?] = []
        for (column, value) in change.columnAssignments {
            sets.append("\(column) = ?")
            switch value {
            case .text(let s): params.append(s)
            case .integer(let i): params.append(i)
            }
        }
        sets += ["pending_changes = NULL", "changes_apply_at = NULL", "updated_at = ?"]
        params += [Timestamp.format(Date()), ruleId]
        try await db.execute(sql: "UPDATE blocking_rules SET \(sets.joined(separator: ", ")) WHERE id = ?", parameters: params)
    }

    @Sendable
    private static func map(_ c: SqlCursor) throws -> BlockingRule {
        BlockingRule(
            id: try c.getString(name: "id"),
            userId: try c.getStringOptional(name: "user_id") ?? "",
            name: try c.getStringOptional(name: "name") ?? "",
            blockedApps: BlockedApp.parseList(try c.getStringOptional(name: "blocked_apps")),
            blockedDomains: JSONValue.stringArray(try c.getStringOptional(name: "blocked_domains")),
            conditionTaskIds: JSONValue.stringArray(try c.getStringOptional(name: "condition_task_ids")),
            conditionLogic: try c.getStringOptional(name: "condition_logic") ?? "all",
            configLockHours: Int(try c.getInt64Optional(name: "config_lock_hours") ?? 24),
            pendingChanges: try c.getStringOptional(name: "pending_changes"),
            changesApplyAt: try c.getStringOptional(name: "changes_apply_at"),
            isActive: (try c.getInt64Optional(name: "is_active") ?? 0) != 0
        )
    }
}
