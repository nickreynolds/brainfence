import Foundation

/// Converts a PowerSync CRUD entry's local column values into the JSON types
/// Postgres expects before they're sent through PostgREST.
///
/// PowerSync stores JSONB and native array columns (TEXT[] / UUID[]) locally
/// as JSON *text*, and BOOLEANs as 0/1. Uploaded verbatim, a JSONB column ends
/// up holding a JSON string instead of an object, and an array column is
/// rejected outright — and a rejected write is rolled back locally on the next
/// sync, so edits silently revert. Every such column must be marked with an
/// `encoding` in shared/schema.json, which generates `SchemaColumns`.
public enum UploadNormalizer {
    /// table → columns whose local text must be parsed into JSON (JSONB and arrays).
    public static let jsonColumns = SchemaColumns.json

    /// table → BOOLEAN columns stored locally as 0/1.
    public static let booleanColumns = SchemaColumns.boolean

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
