import AppKit
import BrainfenceCore
import Foundation

/// Duration and meditation timers. They live here, not in a view, so they
/// keep running with the window closed; they're persisted so a relaunch
/// restores them (paused). The macOS counterpart of Android's
/// DurationTimerManager and MeditationTimerManager.
@MainActor
@Observable
final class TimerManager {
    private(set) var durations: [String: DurationSession] = [:]
    private(set) var meditations: [String: MeditationSession] = [:]
    /// Updated every tick so views showing elapsed time re-render.
    private(set) var now = Date()

    @ObservationIgnored private let completions: CompletionRepository
    @ObservationIgnored private let log: DebugLog
    @ObservationIgnored var userId: @MainActor () -> String? = { nil }
    /// Called after a timer records a completion (re-evaluates blocking).
    @ObservationIgnored var didComplete: @MainActor () -> Void = {}
    @ObservationIgnored private var ticker: Timer?
    @ObservationIgnored private var tickCount = 0
    @ObservationIgnored private var frontmostObserver: NSObjectProtocol?
    @ObservationIgnored private var frontmostBundleID: String?

    private static let durationsKey = "durationTimers"
    private static let meditationsKey = "meditationTimers"

    init(completions: CompletionRepository, log: DebugLog) {
        self.completions = completions
        self.log = log
    }

    func start() {
        restore()
        frontmostBundleID = NSWorkspace.shared.frontmostApplication?.bundleIdentifier
        frontmostObserver = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didActivateApplicationNotification, object: nil, queue: .main
        ) { [weak self] note in
            let id = (note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication)?.bundleIdentifier
            MainActor.assumeIsolated { self?.foregroundChanged(to: id) }
        }
    }

    private var today: String { ZonedClock(timeZone: .current).date(of: Date()).description }

    // MARK: Duration

    func startDuration(task: BrainTask, targetSeconds: Int) {
        guard durations[task.id] == nil else { return }
        durations[task.id] = DurationSession(
            taskId: task.id, taskTitle: task.title, targetSeconds: targetSeconds, runningSince: Date(), day: today
        )
        log.log("duration", "Started timer for '\(task.title)' (target=\(targetSeconds)s)")
        changed()
    }

    func pauseDuration(_ taskId: String) {
        durations[taskId]?.pause(at: Date())
        changed()
    }

    func resumeDuration(_ taskId: String) {
        durations[taskId]?.resume(at: Date())
        changed()
    }

    func cancelDuration(_ taskId: String) {
        durations[taskId] = nil
        changed()
    }

    // MARK: Meditation

    /// Starts an in-app session. Takes over from companion tracking, carrying
    /// today's accumulated time over.
    func startMeditationInApp(task: BrainTask, config: MeditationConfig, shortSit: Bool) {
        let existing = meditations[task.id]
        if let existing, existing.method != .companionApp { return }
        if shortSit && config.shortSitSeconds <= 0 { return }
        let now = Date()
        let elapsed = existing?.elapsed(at: now) ?? 0
        var session = MeditationSession(
            timer: DurationSession(
                taskId: task.id, taskTitle: task.title, targetSeconds: config.durationSeconds,
                accumulatedSeconds: elapsed, runningSince: now, day: today
            ),
            method: .inApp
        )
        session.shortSitSeconds = config.shortSitSeconds
        session.shortSitIntent = shortSit
        session.shortSitBanked = existing?.shortSitBanked ?? false
        session.bellIntervalSeconds = config.bellIntervalSeconds
        // Don't ring for time carried over.
        session.bellsRung = config.bellIntervalSeconds > 0 ? elapsed / config.bellIntervalSeconds : 0
        meditations[task.id] = session
        log.log("meditation", "Started in-app meditation for '\(task.title)' (target=\(config.durationSeconds)s, shortSit=\(config.shortSitSeconds)s, intent=\(shortSit ? "short" : "full"))")
        changed()
    }

    /// Tracks time spent in the task's companion apps toward its duration.
    func startCompanionTracking(task: BrainTask, config: MeditationConfig) {
        guard meditations[task.id] == nil, !config.companionApps.isEmpty else { return }
        var session = MeditationSession(
            timer: DurationSession(taskId: task.id, taskTitle: task.title, targetSeconds: config.durationSeconds, day: today),
            method: .companionApp
        )
        session.companionApps = config.companionApps
        _ = session.foregroundChanged(to: frontmostBundleID, at: Date())
        meditations[task.id] = session
        log.log("companion", "Started companion tracking for '\(task.title)'", data: config.companionApps.joined(separator: ", "))
        changed()
    }

    func pauseMeditation(_ taskId: String) {
        guard var session = meditations[taskId], session.timer.isRunning else { return }
        let now = Date()
        session.pause(at: now)
        meditations[taskId] = session
        if session.shortSitBanked {
            let data = session.verificationData(actualSeconds: session.elapsed(at: now))
            Task { try? await completions.updateTodaysVerificationData(taskId: taskId, data: data) }
        }
        changed()
    }

    func resumeMeditation(_ taskId: String) {
        meditations[taskId]?.timer.resume(at: Date())
        changed()
    }

    /// Ends an in-app session at or past its short-sit threshold, recording it as done.
    func finishMeditationEarly(_ taskId: String) {
        guard let session = meditations[taskId], session.canFinishEarly(at: Date()) else { return }
        completeMeditation(taskId, actualSeconds: session.elapsed(at: Date()))
    }

    func cancelMeditation(_ taskId: String) {
        meditations[taskId] = nil
        changed()
    }

    /// Starts companion tracking for meditation tasks with this Mac's
    /// companion apps, restarting it when the configured apps changed.
    func autoStartCompanionTracking(_ tasks: [BrainTask]) {
        for task in tasks where task.verificationType == "meditation" && !task.completedToday {
            guard let config = parseMeditationConfig(task.verificationConfig), !config.companionApps.isEmpty else { continue }
            if let existing = meditations[task.id] {
                // The user chose the in-app timer: don't replace their session.
                if existing.method == .inApp { continue }
                if Set(existing.companionApps) == Set(config.companionApps) { continue }
                log.log("companion", "Restarting tracking for '\(task.title)' — companion apps changed")
                meditations[task.id] = nil
            }
            startCompanionTracking(task: task, config: config)
        }
    }

    private func foregroundChanged(to bundleID: String?) {
        frontmostBundleID = bundleID
        var changedAny = false
        for (id, var session) in meditations where session.method == .companionApp {
            let wasRunning = session.timer.isRunning
            if session.foregroundChanged(to: bundleID, at: Date()) {
                meditations[id] = session
                changedAny = true
                log.log("companion", wasRunning ? "Companion app left the foreground — paused" : "Companion app opened: \(bundleID ?? "?")")
            }
        }
        if changedAny { changed() }
    }

    // MARK: Ticking

    private func changed() {
        persist()
        let active = durations.values.contains(where: \.isRunning) || meditations.values.contains { $0.timer.isRunning }
        if active && ticker == nil {
            let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
                MainActor.assumeIsolated { self?.tick() }
            }
            timer.tolerance = 0.1
            RunLoop.main.add(timer, forMode: .common)
            ticker = timer
        } else if !active {
            ticker?.invalidate()
            ticker = nil
        }
        now = Date()
    }

    private func tick() {
        let now = Date()
        self.now = now
        tickCount += 1
        for (id, session) in durations where session.isRunning && session.isComplete(at: now) {
            completeDuration(id, actualSeconds: session.elapsed(at: now))
        }
        for (id, var session) in meditations {
            let events = session.tick(at: now)
            if events.isEmpty { continue }
            meditations[id] = session
            for event in events {
                switch event {
                case .complete(let actual):
                    if session.method == .inApp { Self.chime() }
                    completeMeditation(id, actualSeconds: actual)
                case .bankShortSit(let actual, let chime):
                    // Record today's completion the moment the threshold is
                    // reached, so it counts even if the session is abandoned.
                    let data = session.verificationData(actualSeconds: actual)
                    record(id, data: data)
                    log.log("meditation", "Short sit banked for '\(session.timer.taskTitle)' (\(actual)s)", data: data)
                    if chime { Self.chime() }
                case .bell:
                    Self.chime()
                }
            }
        }
        if tickCount % 10 == 0 { persist() }
    }

    private func completeDuration(_ taskId: String, actualSeconds: Int) {
        guard let session = durations.removeValue(forKey: taskId) else { return }
        changed()
        let data = session.verificationData(actualSeconds: actualSeconds)
        log.log("duration", "Timer complete for '\(session.taskTitle)' (\(actualSeconds)s)", data: data)
        record(taskId, data: data)
    }

    private func completeMeditation(_ taskId: String, actualSeconds: Int) {
        guard let session = meditations.removeValue(forKey: taskId) else { return }
        changed()
        let data = session.verificationData(actualSeconds: actualSeconds)
        log.log("meditation", "Meditation complete for '\(session.timer.taskTitle)' (\(actualSeconds)s, method=\(session.method.rawValue))", data: data)
        // A banked short sit already wrote today's completion (the insert is a
        // no-op), so overwrite its proof with the final tally.
        record(taskId, data: data, overwriteToday: session.shortSitBanked)
    }

    private func record(_ taskId: String, data: String, overwriteToday: Bool = false) {
        guard let userId = userId() else {
            log.log("timers", "Couldn't record a completion: not signed in")
            return
        }
        Task {
            do {
                try await completions.completeTask(taskId: taskId, userId: userId, verificationData: data)
                if overwriteToday { try await completions.updateTodaysVerificationData(taskId: taskId, data: data) }
                didComplete()
            } catch {
                log.log("timers", "Couldn't record a completion: \(error.localizedDescription)")
            }
        }
    }

    static func chime() {
        (NSSound(named: "meditation_bell") ?? NSSound(named: "Glass"))?.play()
    }

    // MARK: Persistence

    private func persist() {
        let now = Date()
        let durationSnapshot = durations.mapValues { s -> DurationSession in
            var s = s
            s.pause(at: now)
            return s
        }
        let meditationSnapshot = meditations.mapValues { s -> MeditationSession in
            var s = s
            s.timer.pause(at: now)
            return s
        }
        let defaults = UserDefaults.standard
        defaults.set(try? JSONEncoder().encode(durationSnapshot), forKey: Self.durationsKey)
        defaults.set(try? JSONEncoder().encode(meditationSnapshot), forKey: Self.meditationsKey)
    }

    /// Restores persisted timers, paused. Timers from a previous day are
    /// dropped: targets are per day.
    private func restore() {
        let defaults = UserDefaults.standard
        let today = self.today
        if let data = defaults.data(forKey: Self.durationsKey),
           let saved = try? JSONDecoder().decode([String: DurationSession].self, from: data) {
            for (id, s) in saved where s.day == today && s.targetSeconds > 0 {
                durations[id] = s
                if s.isComplete(at: Date()) { completeDuration(id, actualSeconds: s.elapsed(at: Date())) }
            }
        }
        if let data = defaults.data(forKey: Self.meditationsKey),
           let saved = try? JSONDecoder().decode([String: MeditationSession].self, from: data) {
            for (id, s) in saved where s.timer.day == today && s.timer.targetSeconds > 0 {
                meditations[id] = s
            }
        }
        changed()
    }
}
