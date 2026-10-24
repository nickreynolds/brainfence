import Foundation

// Duration and meditation timers as pure state, so the app layer only has to
// tick them and act on the events. Ports of Android's DurationTimerManager and
// MeditationTimerManager (via desktop-app's main/timers.ts). Elapsed time is
// derived from wall-clock instants, so a delayed tick never loses time.

/// A timer counting toward a target, with pause/resume.
public struct DurationSession: Sendable, Equatable, Codable {
    public let taskId: String
    public var taskTitle: String
    public var targetSeconds: Int
    /// Seconds accumulated before the current run.
    public var accumulatedSeconds: Int
    /// When the current run started; nil while paused.
    public var runningSince: Date?
    /// Local day ("YYYY-MM-DD") the session belongs to; targets are per day.
    public var day: String

    public init(taskId: String, taskTitle: String, targetSeconds: Int, accumulatedSeconds: Int = 0, runningSince: Date? = nil, day: String) {
        self.taskId = taskId
        self.taskTitle = taskTitle
        self.targetSeconds = targetSeconds
        self.accumulatedSeconds = accumulatedSeconds
        self.runningSince = runningSince
        self.day = day
    }

    public var isRunning: Bool { runningSince != nil }

    public func elapsed(at now: Date) -> Int {
        guard let since = runningSince else { return accumulatedSeconds }
        return accumulatedSeconds + max(0, Int(now.timeIntervalSince(since)))
    }

    public mutating func pause(at now: Date) {
        guard isRunning else { return }
        accumulatedSeconds = elapsed(at: now)
        runningSince = nil
    }

    public mutating func resume(at now: Date) {
        guard !isRunning else { return }
        runningSince = now
    }

    public func isComplete(at now: Date) -> Bool { elapsed(at: now) >= targetSeconds }

    /// task_completions.verification_data for a finished session.
    public func verificationData(actualSeconds: Int) -> String {
        JSONValue.object(["actual_seconds": .int(actualSeconds)]).jsonString
    }
}

public struct MeditationSession: Sendable, Equatable, Codable {
    public enum Method: String, Sendable, Codable {
        case inApp = "in_app_timer"
        case companionApp = "companion_app"
    }

    public enum Event: Sendable, Equatable {
        /// The target was reached: record the completion (chime for in-app sessions).
        case complete(actualSeconds: Int)
        /// The short-sit threshold was reached: record today's completion now,
        /// so it counts even if the session is abandoned.
        case bankShortSit(actualSeconds: Int, chime: Bool)
        case bell
    }

    public var timer: DurationSession
    public var method: Method
    public var pauses = 0
    public var shortSitSeconds = 0
    /// Started as a short sit: count down to the threshold first.
    public var shortSitIntent = false
    public var shortSitBanked = false
    public var bellIntervalSeconds = 0
    /// Bells rung so far this session, so a late tick rings each one once.
    public var bellsRung = 0
    /// This platform's companion apps (bundle IDs) for companion tracking.
    public var companionApps: [String] = []
    /// The companion app currently counting, if any.
    public var companionApp: String?

    public init(timer: DurationSession, method: Method) {
        self.timer = timer
        self.method = method
    }

    public var taskId: String { timer.taskId }

    public func elapsed(at now: Date) -> Int { timer.elapsed(at: now) }

    public mutating func pause(at now: Date) {
        guard timer.isRunning else { return }
        timer.pause(at: now)
        if method == .inApp { pauses += 1 }
    }

    /// Advances the session to [now] and reports what happened.
    public mutating func tick(at now: Date) -> [Event] {
        guard timer.isRunning else { return [] }
        let elapsed = timer.elapsed(at: now)
        if elapsed >= timer.targetSeconds { return [.complete(actualSeconds: elapsed)] }
        guard method == .inApp else { return [] }
        if shortSitSeconds > 0 && !shortSitBanked && elapsed >= shortSitSeconds {
            shortSitBanked = true
            return [.bankShortSit(actualSeconds: elapsed, chime: shortSitIntent)]
        }
        if bellIntervalSeconds > 0 && elapsed / bellIntervalSeconds > bellsRung {
            bellsRung = elapsed / bellIntervalSeconds
            return [.bell]
        }
        return []
    }

    /// Whether the session can end early and still count: at or past the
    /// short-sit threshold.
    public func canFinishEarly(at now: Date) -> Bool {
        shortSitSeconds > 0 && elapsed(at: now) >= shortSitSeconds
    }

    /// task_completions.verification_data, matching the other clients.
    public func verificationData(actualSeconds: Int) -> String {
        var out: [String: JSONValue] = ["method": .string(method.rawValue)]
        if method == .inApp {
            out["pauses"] = .int(pauses)
        } else {
            out["app"] = .string(companionApp ?? "unknown")
        }
        out["actual_seconds"] = .int(actualSeconds)
        if shortSitSeconds > 0 {
            out["target_seconds"] = .int(timer.targetSeconds)
            out["short_sit"] = .bool(actualSeconds < timer.targetSeconds)
        }
        return JSONValue.object(out).jsonString
    }

    /// Companion tracking: counts while one of [companionApps] is frontmost.
    /// Returns whether the running state changed.
    public mutating func foregroundChanged(to bundleID: String?, at now: Date) -> Bool {
        guard method == .companionApp else { return false }
        let inCompanion = bundleID.map { companionApps.contains($0) } ?? false
        if inCompanion && !timer.isRunning {
            timer.resume(at: now)
            companionApp = bundleID
            return true
        }
        if !inCompanion && timer.isRunning {
            timer.pause(at: now)
            return true
        }
        return false
    }
}
