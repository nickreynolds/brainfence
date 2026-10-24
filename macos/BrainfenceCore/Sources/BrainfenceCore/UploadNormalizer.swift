import Foundation

/// Converts a PowerSync CRUD entry's local column values into the JSON types
/// Postgres expects before they're sent through PostgREST.
///
/// PowerSync stores JSONB and native array columns (TEXT[] / UUID[]) locally
/// as JSON *text*, and BOOLEANs as 0/1. Uploaded verbatim, a JSONB column ends
/// up holding a JSON string instead of an object, and an array column is
/// rejected outright — and a rejected write is rolled back locally on the next
/// sync, so edits silently revert. Every such column must be listed here.
/// Keep in sync with android/…/data/db/SupabasePowerSyncConnector.kt.
public enum UploadNormalizer {
    /// table → columns whose local text must be parsed into JSON (JSONB and arrays).
    public static let jsonColumns: [String: Set<String>] = [
        "tasks": ["recurrence_config", "verification_config", "blocking_days_of_week", "tags", "blocking_rule_ids"],
        "routine_steps": ["config"],
        "task_completions": ["verification_data"],
        "step_completions": ["data"],
        "blocking_rules": ["blocked_apps", "pending_changes", "blocked_domains", "condition_task_ids"],
        "groups": ["visibility_schedule"],
        "notes": ["tags", "outgoing_links"],
    ]

    /// table → BOOLEAN columns stored locally as 0/1.
    public static let booleanColumns: [String: Set<String>] = [
        "tasks": ["is_blocking_condition", "home_only_blocking"],
        "blocking_rules": ["is_active"],
        "contemplation_decks": ["is_enabled"],
        "reports": ["include_note"],
    ]

    public static func normalize(table: String, values: [String: JSONValue]) -> [String: JSONValue] {
        let json = jsonColumns[table] ?? []
        let bools = booleanColumns[table] ?? []
        if json.isEmpty && bools.isEmpty { return values }

        var out = values
        for (column, value) in values {
            if json.contains(column), case .string(let text) = value, let parsed = JSONValue.parse(text) {
                out[column] = parsed
            } else if bools.contains(column) {
                switch value {
                case .int(let i): out[column] = .bool(i != 0)
                case .double(let d): out[column] = .bool(d != 0)
                case .string(let s): out[column] = .bool(s == "1" || s.lowercased() == "true")
                default: break
                }
            }
        }
        return out
    }
}
