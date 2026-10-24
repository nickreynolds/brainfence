import Foundation

/// A proposed edit to a blocking rule, in the `pending_changes` JSON shape
/// Android reads and writes: `{"name": …, "blocked_apps": [...],
/// "blocked_domains": [...], "condition_task_ids": [...],
/// "condition_logic": "all", "is_active": 1, "config_lock_hours": 24}`.
///
/// Edits that only make blocking stricter apply immediately; anything that
/// could weaken it is staged in `pending_changes` until `changes_apply_at`.
public struct RuleChange: Sendable, Equatable {
    public var name: String?
    public var blockedApps: [BlockedApp]?
    public var blockedDomains: [String]?
    public var conditionTaskIds: [String]?
    public var conditionLogic: String?
    public var isActive: Bool?
    public var configLockHours: Int?

    public init(
        name: String? = nil,
        blockedApps: [BlockedApp]? = nil,
        blockedDomains: [String]? = nil,
        conditionTaskIds: [String]? = nil,
        conditionLogic: String? = nil,
        isActive: Bool? = nil,
        configLockHours: Int? = nil
    ) {
        self.name = name
        self.blockedApps = blockedApps
        self.blockedDomains = blockedDomains
        self.conditionTaskIds = conditionTaskIds
        self.conditionLogic = conditionLogic
        self.isActive = isActive
        self.configLockHours = configLockHours
    }

    public var json: JSONValue {
        var o: [String: JSONValue] = [:]
        if let name { o["name"] = .string(name) }
        if let blockedApps { o["blocked_apps"] = BlockedApp.encodeList(blockedApps) }
        if let blockedDomains { o["blocked_domains"] = .array(of: blockedDomains) }
        if let conditionTaskIds { o["condition_task_ids"] = .array(of: conditionTaskIds) }
        if let conditionLogic { o["condition_logic"] = .string(conditionLogic) }
        if let isActive { o["is_active"] = .int(isActive ? 1 : 0) }
        if let configLockHours { o["config_lock_hours"] = .int(configLockHours) }
        return .object(o)
    }

    /// Parses `pending_changes`. Tolerates the value having been double-encoded
    /// as a JSON string (Android strips surrounding quotes for the same reason)
    /// and array fields stored as JSON text rather than arrays.
    public static func parse(_ text: String?) -> RuleChange? {
        guard var value = JSONValue.parse(text) else { return nil }
        if case .string(let inner) = value, let decoded = JSONValue.parse(inner) { value = decoded }
        guard case .object(let o) = value else { return nil }

        func listText(_ v: JSONValue?) -> String? {
            switch v {
            case .array?: return v!.jsonString
            case .string(let s)?: return s
            default: return nil
            }
        }

        var change = RuleChange()
        change.name = o["name"]?.stringValue
        if o["blocked_apps"] != nil { change.blockedApps = BlockedApp.parseList(listText(o["blocked_apps"])) }
        if o["blocked_domains"] != nil { change.blockedDomains = JSONValue.stringArray(listText(o["blocked_domains"])) }
        if o["condition_task_ids"] != nil { change.conditionTaskIds = JSONValue.stringArray(listText(o["condition_task_ids"])) }
        change.conditionLogic = o["condition_logic"]?.stringValue
        change.isActive = o["is_active"]?.intValue.map { $0 != 0 }
        change.configLockHours = o["config_lock_hours"]?.intValue
        return change
    }

    /// True if applying this change can only make `rule` block more, never less.
    ///
    /// Compared with Android's `isMoreRestrictive`, this also accounts for
    /// condition logic: with "any", *adding* a condition task adds a new way
    /// to unblock (weaker), and a rule with no conditions always blocks, so
    /// giving it conditions is weaker too.
    public func isMoreRestrictive(than rule: BlockingRule) -> Bool {
        if let blockedApps {
            let new = Set(blockedApps.map(\.key))
            if !new.isSuperset(of: rule.blockedApps.map(\.key)) { return false }
        }

        if let blockedDomains {
            let new = Set(blockedDomains.compactMap(DomainMatcher.normalize))
            if !new.isSuperset(of: rule.blockedDomains.compactMap(DomainMatcher.normalize)) { return false }
        }

        let newLogic = conditionLogic ?? rule.conditionLogic
        if rule.conditionLogic == "all" && newLogic == "any" { return false }

        if let conditionTaskIds {
            let new = Set(conditionTaskIds)
            let current = Set(rule.conditionTaskIds)
            if current != new {
                if current.isEmpty { return false }          // was "always block"
                if newLogic == "any" {
                    if new.isEmpty { return true }            // becomes "always block"
                    if !new.isSubset(of: current) { return false }
                } else if !new.isSuperset(of: current) {
                    return false
                }
            }
        }

        if let isActive, rule.isActive, !isActive { return false }

        if let configLockHours, configLockHours < rule.configLockHours { return false }

        return true
    }

    /// Column assignments for promoting this change into the live row, as
    /// (column, SQLite value) pairs. Array/JSON columns are written as JSON
    /// text, booleans as 0/1 — PowerSync's local representation.
    public var columnAssignments: [(column: String, value: SQLValue)] {
        var out: [(String, SQLValue)] = []
        if let name { out.append(("name", .text(name))) }
        if let blockedApps { out.append(("blocked_apps", .text(BlockedApp.encodeList(blockedApps).jsonString))) }
        if let blockedDomains { out.append(("blocked_domains", .text(JSONValue.array(of: blockedDomains).jsonString))) }
        if let conditionTaskIds { out.append(("condition_task_ids", .text(JSONValue.array(of: conditionTaskIds).jsonString))) }
        if let conditionLogic { out.append(("condition_logic", .text(conditionLogic))) }
        if let isActive { out.append(("is_active", .integer(isActive ? 1 : 0))) }
        if let configLockHours { out.append(("config_lock_hours", .integer(configLockHours))) }
        return out
    }

    /// The change with fields equal to the current rule removed. Lists are
    /// compared as sets: reordering is not an edit.
    public func diff(against rule: BlockingRule) -> RuleChange {
        var d = RuleChange()
        if let name, name != rule.name { d.name = name }
        if let blockedApps, Set(blockedApps.map(\.key)) != Set(rule.blockedApps.map(\.key)) { d.blockedApps = blockedApps }
        if let blockedDomains, Set(blockedDomains) != Set(rule.blockedDomains) { d.blockedDomains = blockedDomains }
        if let conditionTaskIds, Set(conditionTaskIds) != Set(rule.conditionTaskIds) { d.conditionTaskIds = conditionTaskIds }
        if let conditionLogic, conditionLogic != rule.conditionLogic { d.conditionLogic = conditionLogic }
        if let isActive, isActive != rule.isActive { d.isActive = isActive }
        if let configLockHours, configLockHours != rule.configLockHours { d.configLockHours = configLockHours }
        return d
    }

    public var isEmpty: Bool { self == RuleChange() }
}

/// A value bound to a SQLite statement parameter.
public enum SQLValue: Sendable, Equatable {
    case text(String)
    case integer(Int)
}
