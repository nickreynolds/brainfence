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
