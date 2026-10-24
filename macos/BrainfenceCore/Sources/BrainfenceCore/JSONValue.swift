import Foundation

/// A JSON value. PowerSync stores JSONB and Postgres array columns locally as
/// JSON *strings*; this type is how we parse, inspect and re-encode them.
public enum JSONValue: Sendable, Hashable, Codable {
    case string(String)
    case int(Int)
    case double(Double)
    case bool(Bool)
    case null
    case array([JSONValue])
    case object([String: JSONValue])

    public init(from decoder: any Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let i = try? c.decode(Int.self) { self = .int(i) }
        else if let d = try? c.decode(Double.self) { self = .double(d) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSONValue].self) { self = .array(a) }
        else if let o = try? c.decode([String: JSONValue].self) { self = .object(o) }
        else {
            throw DecodingError.typeMismatch(
                JSONValue.self,
                .init(codingPath: decoder.codingPath, debugDescription: "Expected a JSON value")
            )
        }
    }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .string(let v): try c.encode(v)
        case .int(let v): try c.encode(v)
        case .double(let v): try c.encode(v)
        case .bool(let v): try c.encode(v)
        case .null: try c.encodeNil()
        case .array(let v): try c.encode(v)
        case .object(let v): try c.encode(v)
        }
    }

    /// Parses a JSON document. Returns nil for blank or malformed input.
    public static func parse(_ text: String?) -> JSONValue? {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let data = text.data(using: .utf8)
        else { return nil }
        return try? JSONDecoder().decode(JSONValue.self, from: data)
    }

    /// Compact JSON text, with sorted keys so output is deterministic.
    public var jsonString: String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        guard let data = try? encoder.encode(self), let s = String(data: data, encoding: .utf8) else {
            return "null"
        }
        return s
    }

    public subscript(key: String) -> JSONValue? {
        if case .object(let o) = self { return o[key] }
        return nil
    }

    public var stringValue: String? {
        if case .string(let s) = self { return s }
        return nil
    }

    /// Integer view, accepting integral doubles and numeric strings (Android's
    /// org.json is similarly lenient when reading pending_changes).
    public var intValue: Int? {
        switch self {
        case .int(let i): return i
        case .double(let d) where d.rounded() == d: return Int(d)
        case .bool(let b): return b ? 1 : 0
        case .string(let s): return Int(s)
        default: return nil
        }
    }

    /// Numeric view, accepting numeric strings (like JavaScript's Number()).
    public var doubleValue: Double? {
        switch self {
        case .int(let i): return Double(i)
        case .double(let d): return d
        case .string(let s): return Double(s.trimmingCharacters(in: .whitespaces))
        default: return nil
        }
    }

    public var boolValue: Bool? {
        if case .bool(let b) = self { return b }
        return nil
    }

    public var arrayValue: [JSONValue]? {
        if case .array(let a) = self { return a }
        return nil
    }

    public var objectValue: [String: JSONValue]? {
        if case .object(let o) = self { return o }
        return nil
    }

    public var isNull: Bool {
        if case .null = self { return true }
        return false
    }
}

extension JSONValue {
    /// Parses a JSON array of strings (condition_task_ids, blocked_domains, …).
    /// Non-string elements are skipped; blank / "null" / malformed input → [].
    public static func stringArray(_ text: String?) -> [String] {
        guard let items = parse(text)?.arrayValue else { return [] }
        return items.compactMap(\.stringValue)
    }

    public static func array(of strings: [String]) -> JSONValue {
        .array(strings.map(JSONValue.string))
    }

    /// The first of [keys] that is present and not null, like a chain of `??`
    /// over a JSON object's fields.
    public func first(_ keys: String...) -> JSONValue? {
        for key in keys {
            if let value = self[key], !value.isNull { return value }
        }
        return nil
    }
}
