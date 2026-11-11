import Foundation

// Parsers for tasks' verification_config, accepting every spelling the Android
// client and the spec use (duration_seconds/target_seconds, lat/latitude,
// radius_m/radius_meters, …). Port of desktop-app/src/shared/verification.ts.

/// This client's key in platform-tagged JSON (blocked_apps, companion_apps).
public let localPlatform = BlockedApp.macOS

/// Default "short sit" threshold for meditation tasks, matching Android.
public let defaultShortSitSeconds = 120

public struct DurationConfig: Sendable, Equatable {
    public let targetSeconds: Int
}

public func parseDurationConfig(_ json: String?) -> DurationConfig? {
    let o = JSONValue.parse(json) ?? .object([:])
    let target = o.first("target_seconds", "duration_seconds")?.doubleValue ?? 0
    return target > 0 ? DurationConfig(targetSeconds: Int(target)) : nil
}

public struct MeditationConfig: Sendable, Equatable {
    public let durationSeconds: Int
    public let allowPause: Bool
    public let bellIntervalSeconds: Int
    /// This platform's companion apps (macOS bundle identifiers).
    public let companionApps: [String]
    /// "Middle way" threshold in seconds (0 = disabled).
    public let shortSitSeconds: Int
}

public func parseMeditationConfig(_ json: String?) -> MeditationConfig? {
    let o = JSONValue.parse(json) ?? .object([:])
    let duration = Int(o.first("duration_seconds", "target_seconds")?.doubleValue ?? 0)
    guard duration > 0 else { return nil }
    let companions = (o["companion_apps"]?.arrayValue ?? []).compactMap { entry -> String? in
        guard entry["platform"]?.stringValue == localPlatform,
              let id = entry["bundle_id"]?.stringValue, !id.isEmpty else { return nil }
        return id
    }
    let shortSit = Int(o.first("short_sit_seconds")?.doubleValue ?? Double(defaultShortSitSeconds))
    return MeditationConfig(
        durationSeconds: duration,
        allowPause: o["allow_pause"]?.boolValue == true,
        bellIntervalSeconds: max(0, Int(o.first("bell_interval_seconds")?.doubleValue ?? 0)),
        companionApps: companions,
        // A threshold at or beyond the full duration would be meaningless.
        shortSitSeconds: shortSit >= 1 && shortSit < duration ? shortSit : 0
    )
}

public struct GpsConfig: Sendable, Equatable {
    public let latitude: Double
    public let longitude: Double
    public let radiusMeters: Double
    /// enter | leave | notify
    public let mode: String
    public let minDurationMinutes: Int
}

public func parseGpsConfig(_ json: String?) -> GpsConfig? {
    let o = JSONValue.parse(json) ?? .object([:])
    guard let lat = o.first("lat", "latitude")?.doubleValue,
          let lng = o.first("lng", "longitude")?.doubleValue else { return nil }
    let radius = o.first("radius_m", "radius_meters")?.doubleValue ?? 100
    return GpsConfig(
        latitude: lat,
        longitude: lng,
        radiusMeters: radius > 0 ? radius : 100,
        mode: o.first("mode")?.stringValue ?? "enter",
        minDurationMinutes: Int(o.first("min_duration_m")?.doubleValue ?? 0)
    )
}

/// The report a report task or report step points at, from its config JSON.
public func reportId(fromConfig json: String?) -> String? {
    guard let id = JSONValue.parse(json)?["report_id"]?.stringValue, !id.isEmpty else { return nil }
    return id
}

// MARK: - Completion alternatives (shared/CONTRACTS.md)

/// A GPS way to complete a task, from tasks.completion_alternatives.
public struct GpsAlternative: Sendable, Equatable {
    /// Position in the completion_alternatives array.
    public let index: Int
    public let label: String?
    public let config: GpsConfig

    /// "Climbing gym", or "the location" without a label.
    public var place: String { label ?? "the location" }

    /// A short "or …" phrase, e.g. "be at Climbing gym for 30 min".
    public var phrase: String {
        if config.mode == "leave" { return "leave \(place)" }
        return config.minDurationMinutes > 0 ? "be at \(place) for \(config.minDurationMinutes) min" : "arrive at \(place)"
    }
}

/// The supported GPS entries of a task's completion_alternatives: mode enter
/// (default) or leave, with a valid location. Everything else is skipped
/// (`shared/fixtures/completion-alternatives.json`).
public func parseGpsAlternatives(_ json: String?) -> [GpsAlternative] {
    guard let entries = JSONValue.parse(json)?.arrayValue else { return [] }
    return entries.enumerated().compactMap { index, entry in
        guard entry.objectValue != nil, entry["verification_type"]?.stringValue == "gps" else { return nil }
        guard let config = parseGpsConfig((entry["verification_config"] ?? .object([:])).jsonString),
              config.mode == "enter" || config.mode == "leave" else { return nil }
        let label = entry["label"]?.stringValue
        return GpsAlternative(index: index, label: (label?.isEmpty ?? true) ? nil : label, config: config)
    }
}

/// A timed_sets step's config (shared/CONTRACTS.md: Timed sets,
/// `shared/fixtures/timed-sets.json`).
public struct TimedSetsConfig: Sendable, Equatable {
    /// "added": weight added to bodyweight, negative = assisted. "load": the weight lifted.
    public enum WeightKind: String, Sendable { case added, load }

    /// Hangs per side per set.
    public let reps: Int
    public let workSeconds: Int
    /// Rest between hangs.
    public let restSeconds: Int
    public let sets: Int
    /// 1, or 2 for one arm at a time (left, then right).
    public let sides: Int
    /// Rest between the left and right halves of a set.
    public let sideRestSeconds: Int
    /// Rest after each set, including before the next step.
    public let setRestSeconds: Int
    public let weight: WeightKind?
    /// "start" (once, before the routine) or "each_set" (before each set).
    public let weightPrompt: String

    public init(json: String?) {
        let c = JSONValue.parse(json) ?? .object([:])
        func int(_ key: String, _ fallback: Int, min: Int) -> Int {
            guard let d = c[key]?.doubleValue, d.isFinite, Int(d) >= min else { return fallback }
            return Int(d)
        }
        let rest = int("rest_seconds", 20, min: 0)
        reps = int("reps", 1, min: 1)
        workSeconds = int("work_seconds", 10, min: 1)
        restSeconds = rest
        sets = int("sets", 1, min: 1)
        sides = c["sides"]?.intValue == 2 ? 2 : 1
        sideRestSeconds = int("side_rest_seconds", rest, min: 0)
        setRestSeconds = int("set_rest_seconds", rest, min: 0)
        weight = c["weight"]?.stringValue.flatMap(WeightKind.init(rawValue:))
        weightPrompt = c["weight_prompt"]?.stringValue == "each_set" ? "each_set" : "start"
    }

    public var hangsPerSet: Int { reps * sides }

    /// Number of hangs (entries) in the step.
    public var hangCount: Int { sets * hangsPerSet }

    /// 0-based set a hang belongs to.
    public func setOf(_ hangIndex: Int) -> Int { hangIndex / hangsPerSet }

    /// 0-based side a hang is on (0 = left).
    public func sideOf(_ hangIndex: Int) -> Int { (hangIndex / reps) % sides }

    public func startsSet(_ hangIndex: Int) -> Bool { hangIndex % hangsPerSet == 0 }

    /// Whether the runner asks for this set's weight right before it starts.
    public func promptsBefore(_ hangIndex: Int) -> Bool {
        weight != nil && weightPrompt == "each_set" && startsSet(hangIndex)
    }

    /// Rest after hang [hangIndex]; 0 after the routine's final hang.
    public func restAfter(_ hangIndex: Int, isLastStep: Bool) -> Int {
        if isLastStep && hangIndex == hangCount - 1 { return 0 }
        if (hangIndex + 1) % hangsPerSet == 0 { return setRestSeconds }
        if (hangIndex + 1) % reps == 0 { return sideRestSeconds }
        return restSeconds
    }

    /// "+10 lb", "10 lb assisted", "Bodyweight" (added) or "25 lb" (load).
    public static func format(_ kind: WeightKind?, _ weight: Double?) -> String {
        func text(_ w: Double) -> String { w == w.rounded() ? String(Int(w)) : String(format: "%.1f", w) }
        if kind == .load { return weight.map { "\(text($0)) lb" } ?? "No weight set" }
        guard let weight, weight != 0 else { return "Bodyweight" }
        return weight > 0 ? "+\(text(weight)) lb" : "\(text(-weight)) lb assisted"
    }
}
