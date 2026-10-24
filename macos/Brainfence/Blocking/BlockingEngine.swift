import AppKit
import BrainfenceCore
import Foundation
import OSLog

/// Something that turns a `BlockingState` into enforcement on this Mac.
@MainActor
protocol BlockingEnforcer: AnyObject {
    func apply(_ state: BlockingState)
}

/// Keeps tasks + rules in memory, evaluates blocking, and pushes the result to
/// the enforcers. The macOS counterpart of Android's BrainfenceService loop:
///
/// - reactive: PowerSync watches re-evaluate on every data change;
/// - correctness floor: every 60 s the data is re-read from SQLite (so a
///   dropped watch, or "today" rolling over at midnight, can never leave
///   enforcement running on a stale snapshot) and expired time-locked rule
///   changes are promoted;
/// - precise: a one-shot timer fires at the next due_at / available_from /
///   midnight boundary, and the system wake / clock-change notifications
///   trigger an immediate refresh.
@MainActor
@Observable
final class BlockingEngine {
    private(set) var tasks: [BrainTask] = []
    /// All rules, including inactive ones (for the UI).
    private(set) var rules: [BlockingRule] = []
    private(set) var state: BlockingState = .none
    private(set) var lastRefresh: Date?
    private(set) var lastError: String?
    /// Home presence is not tracked on macOS yet; `.unconfigured` keeps
    /// home-only tasks blocking (strict by default, as on Android).
    var homePresence: HomePresence = .unconfigured

    @ObservationIgnored private let taskRepository: TaskRepository
    @ObservationIgnored private let blockingRepository: BlockingRepository
    @ObservationIgnored private var enforcers: [BlockingEnforcer] = []
    @ObservationIgnored private var watchTasks: [Task<Void, Never>] = []
    @ObservationIgnored private var tickTimer: Timer?
    @ObservationIgnored private var boundaryTimer: Timer?
    @ObservationIgnored private var systemObservers: [NSObjectProtocol] = []
    @ObservationIgnored private let logger = Logger(subsystem: "dev.brainfence.macos", category: "engine")

    static let tickInterval: TimeInterval = 60

    init(taskRepository: TaskRepository, blockingRepository: BlockingRepository) {
        self.taskRepository = taskRepository
        self.blockingRepository = blockingRepository
    }

    func add(_ enforcer: BlockingEnforcer) {
        enforcers.append(enforcer)
        enforcer.apply(state)
    }

    func start() {
        stop()
        watchTasks = [
            observe(taskRepository.watchActiveTasks) { [weak self] tasks in
                self?.tasks = tasks
                self?.evaluate()
            },
            observe(blockingRepository.watchRules) { [weak self] rules in
                self?.rules = rules
                self?.evaluate()
            },
        ]

        let timer = Timer(timeInterval: Self.tickInterval, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        timer.tolerance = 5
        RunLoop.main.add(timer, forMode: .common)
        tickTimer = timer

        let workspace = NSWorkspace.shared.notificationCenter
        let center = NotificationCenter.default
        let refreshNow: @Sendable (Notification) -> Void = { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        systemObservers = [
            workspace.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main, using: refreshNow),
            workspace.addObserver(forName: NSWorkspace.sessionDidBecomeActiveNotification, object: nil, queue: .main, using: refreshNow),
            center.addObserver(forName: .NSSystemClockDidChange, object: nil, queue: .main, using: refreshNow),
            center.addObserver(forName: .NSSystemTimeZoneDidChange, object: nil, queue: .main, using: refreshNow),
            center.addObserver(forName: .NSCalendarDayChanged, object: nil, queue: .main, using: refreshNow),
        ]

        tick()
    }

    func stop() {
        watchTasks.forEach { $0.cancel() }
        watchTasks = []
        tickTimer?.invalidate()
        boundaryTimer?.invalidate()
        for observer in systemObservers {
            NSWorkspace.shared.notificationCenter.removeObserver(observer)
            NotificationCenter.default.removeObserver(observer)
        }
        systemObservers = []
    }

    /// Re-reads everything from SQLite, promotes expired rule changes and
    /// re-evaluates.
    func tick() {
        Task {
            do {
                let applied = try await blockingRepository.applyExpiredPendingChanges()
                if applied > 0 { logger.info("applied \(applied) expired pending rule change(s)") }
                async let tasks = taskRepository.activeTasks()
                async let rules = blockingRepository.rules()
                self.tasks = try await tasks
                self.rules = try await rules
                lastError = nil
            } catch {
                // Keep enforcing on the last known data rather than lifting blocks.
                logger.error("refresh failed: \(error.localizedDescription)")
                lastError = error.localizedDescription
            }
            lastRefresh = Date()
            evaluate()
        }
    }

    private func evaluate() {
        let now = Date()
        let next = evaluateBlocking(rules: rules, tasks: tasks, now: now, homePresence: homePresence)
        if next != state {
            logger.info("blocking: \(next.activeBlocks.count) rule(s), \(next.blockedBundleIDs.count) app(s), \(next.blockedDomains.count) domain(s)")
            state = next
        }
        enforcers.forEach { $0.apply(next) }
        scheduleBoundaryTimer(after: now)
    }

    private func scheduleBoundaryTimer(after now: Date) {
        let boundary = nextTimeBoundary(rules: rules, tasks: tasks, now: now)
        boundaryTimer?.invalidate()
        // +1 s so the evaluation lands strictly after the boundary minute.
        let timer = Timer(fire: boundary.addingTimeInterval(1), interval: 0, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        RunLoop.main.add(timer, forMode: .common)
        boundaryTimer = timer
    }

    /// Consumes a PowerSync watch, re-subscribing if it ever ends or fails
    /// (watches can finish on reconnects / auth transitions).
    private func observe<T: Sendable>(
        _ makeStream: @escaping @Sendable () throws -> AsyncThrowingStream<T, any Error>,
        onEach: @escaping @MainActor (T) -> Void
    ) -> Task<Void, Never> {
        Task { [logger] in
            while !Task.isCancelled {
                do {
                    for try await value in try makeStream() {
                        onEach(value)
                    }
                } catch {
                    logger.warning("watch failed, resubscribing: \(error.localizedDescription)")
                }
                try? await Task.sleep(for: .seconds(2))
            }
        }
    }

    // MARK: Queries for the UI

    /// Rules that currently enforce a task (are active and list it as a condition).
    /// This, not `is_blocking_condition`, is what decides whether a task blocks.
    func enforcingRules(for taskId: String) -> [BlockingRule] {
        rules.filter { $0.isActive && $0.conditionTaskIds.contains(taskId) }
    }

    func task(id: String) -> BrainTask? { tasks.first { $0.id == id } }
}
