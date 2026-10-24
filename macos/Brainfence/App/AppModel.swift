import AppKit
import BrainfenceCore
import Foundation
import OSLog
import PowerSync
import Supabase

/// Composition root: owns the Supabase client, the PowerSync database, the
/// repositories, the blocking engine and its enforcers.
@MainActor
@Observable
final class AppModel {
    let session: SessionStore
    let engine: BlockingEngine
    let shield: ShieldPresenter
    let uploadLog: UploadErrorLog
    let launchAgent: LaunchAgentManager
    let navigation = AppNavigation()
    let debugLog: DebugLog
    let timers: TimerManager
    let contemplationScheduler: ContemplationScheduler
    @ObservationIgnored let db: PowerSyncDatabaseProtocol
    @ObservationIgnored let blockingRepository: BlockingRepository
    @ObservationIgnored let taskRepository: TaskRepository
    @ObservationIgnored let completionRepository: CompletionRepository
    @ObservationIgnored let routineRepository: RoutineRepository
    @ObservationIgnored let shoppingRepository: ShoppingRepository
    @ObservationIgnored let reportRepository: ReportRepository
    @ObservationIgnored let contemplationRepository: ContemplationRepository
    @ObservationIgnored let notifications: NotificationService
    @ObservationIgnored let webEnforcer: WebEnforcer
    @ObservationIgnored private let appEnforcer: AppEnforcer
    @ObservationIgnored private let connector: SupabaseConnector
    @ObservationIgnored private var housekeeping: Timer?
    /// Task notifications already sent (keys include the date).
    @ObservationIgnored private var sentTaskNotifications = Set<String>()
    @ObservationIgnored private let logger = Logger(subsystem: "dev.brainfence.macos", category: "app")

    /// Set by the AppDelegate; opens (or focuses) the main window.
    @ObservationIgnored var openMainWindow: () -> Void = {}

    private static let lastUserKey = "lastSignedInUserId"

    init() {
        guard let supabaseURL = URL(string: Secrets.supabaseURL) else {
            fatalError("Invalid SUPABASE_URL in .env: \(Secrets.supabaseURL)")
        }
        let client = SupabaseClient(
            supabaseURL: supabaseURL,
            supabaseKey: Secrets.supabasePublishableKey,
            options: SupabaseClientOptions(
                auth: .init(
                    storage: KeychainLocalStorage(service: "\(Bundle.main.bundleIdentifier ?? "dev.brainfence.macos").auth"),
                    emitLocalSessionAsInitialSession: true
                )
            )
        )

        db = PowerSyncDatabase(
            schema: AppSchema,
            dbFilename: Self.databasePath(),
            logger: DefaultLogger(minSeverity: .info)
        )

        let uploadLog = UploadErrorLog()
        self.uploadLog = uploadLog
        connector = SupabaseConnector(client: client, powerSyncURL: Secrets.powerSyncURL, uploadLog: uploadLog)
        session = SessionStore(client: client)

        taskRepository = TaskRepository(db: db)
        blockingRepository = BlockingRepository(db: db)
        completionRepository = CompletionRepository(db: db)
        routineRepository = RoutineRepository(db: db)
        shoppingRepository = ShoppingRepository(db: db)
        reportRepository = ReportRepository(db: db)
        contemplationRepository = ContemplationRepository(db: db)
        engine = BlockingEngine(taskRepository: taskRepository, blockingRepository: blockingRepository)

        shield = ShieldPresenter()
        appEnforcer = AppEnforcer(shield: shield)
        webEnforcer = WebEnforcer(appEnforcer: appEnforcer, shield: shield)
        launchAgent = LaunchAgentManager()

        let debugLog = DebugLog()
        self.debugLog = debugLog
        let notifications = NotificationService()
        self.notifications = notifications
        timers = TimerManager(completions: completionRepository, log: debugLog)
        contemplationScheduler = ContemplationScheduler(repository: contemplationRepository, notifications: notifications, log: debugLog)
    }

    func start() {
        shield.model = self
        engine.add(appEnforcer)
        engine.add(webEnforcer)
        engine.start()
        session.start { [weak self] state in
            Task { await self?.updateSync(for: state) }
        }
        launchAgent.registerIfNeeded()

        notifications.onOpen = { [weak self] target in
            guard let self else { return }
            switch target {
            case .task(let id): self.open(taskId: id)
            case .contemplations:
                self.navigation.section = .contemplations
                self.openMainWindow()
            case .tasks:
                self.navigation.section = .tasks
                self.openMainWindow()
            }
        }
        notifications.onAffirm = { [weak self] deliveryId in self?.contemplationScheduler.respond(deliveryId: deliveryId) }
        notifications.start()

        timers.userId = { [weak self] in self?.session.userIdForLocalWrites }
        timers.didComplete = { [weak self] in self?.engine.tick() }
        timers.start()

        contemplationScheduler.userId = { [weak self] in self?.session.userIdForLocalWrites }
        contemplationScheduler.start()

        let timer = Timer(timeInterval: 30, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.runHousekeeping() }
        }
        timer.tolerance = 5
        RunLoop.main.add(timer, forMode: .common)
        housekeeping = timer
        // Once the first task list has loaded.
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(3))
            self?.runHousekeeping()
        }
        debugLog.log("service", "Brainfence started")
    }

    /// Periodic work outside blocking: companion-app meditation tracking and
    /// task reminders ("ready", "blocking soon").
    private func runHousekeeping() {
        let tasks = engine.tasks
        timers.autoStartCompanionTracking(tasks)
        let enforced = Set(engine.rules.filter(\.isActive).flatMap(\.conditionTaskIds))
        let today = ZonedClock(timeZone: .current).date(of: Date()).description
        sentTaskNotifications = sentTaskNotifications.filter { $0.contains(today) }
        for n in dueTaskNotifications(tasks: tasks, enforcedTaskIds: enforced, now: Date(), sentKeys: &sentTaskNotifications) {
            notifications.post(title: n.title, body: n.body)
            debugLog.log("notification", n.title, data: n.body)
        }
    }

    /// Connects PowerSync while signed in. Signing out only disconnects:
    /// local data (and therefore blocking) stays, so signing out is not a way
    /// around a block. Data is cleared only when a *different* user signs in.
    private func updateSync(for state: SessionStore.State) async {
        do {
            switch state {
            case .signedIn(let userId, _):
                let defaults = UserDefaults.standard
                if let previous = defaults.string(forKey: Self.lastUserKey), previous != userId {
                    logger.info("different user signed in; clearing local data")
                    try await db.disconnectAndClear()
                }
                defaults.set(userId, forKey: Self.lastUserKey)
                try await db.connect(connector: connector)
            case .signedOut:
                try await db.disconnect()
            case .loading:
                break
            }
        } catch {
            logger.error("sync state change failed: \(error.localizedDescription)")
        }
    }

    // MARK: Actions

    func requireUserId() throws -> String {
        guard let userId = session.userIdForLocalWrites else { throw AppError.notSignedIn }
        return userId
    }

    func complete(_ task: BrainTask, verificationData: String = "{}") async throws {
        try await completionRepository.completeTask(taskId: task.id, userId: requireUserId(), verificationData: verificationData)
        engine.tick()
        debugLog.log("task", "Completed '\(task.title)'")
    }

    /// Opens the main window on a task's screen.
    func open(taskId: String) {
        navigation.showTask(taskId)
        openMainWindow()
    }

    /// Deletes an inactive rule; an active one is deactivated instead, which
    /// is time-locked like any other loosening edit.
    func deleteOrDeactivate(_ rule: BlockingRule) async throws -> BlockingRepository.ChangeOutcome? {
        if rule.isActive {
            return try await submit(RuleChange(isActive: false), toRuleId: rule.id)
        }
        try await blockingRepository.deleteRule(id: rule.id)
        engine.tick()
        return nil
    }

    func createRule(name: String, blockedApps: [BlockedApp], blockedDomains: [String], conditionTaskIds: [String], conditionLogic: String) async throws {
        guard let userId = session.userIdForLocalWrites else { throw AppError.notSignedIn }
        try await blockingRepository.createRule(
            userId: userId, name: name, blockedApps: blockedApps, blockedDomains: blockedDomains,
            conditionTaskIds: conditionTaskIds, conditionLogic: conditionLogic
        )
        engine.tick()
    }

    func submit(_ change: RuleChange, toRuleId ruleId: String) async throws -> BlockingRepository.ChangeOutcome {
        let outcome = try await blockingRepository.submit(change, toRuleId: ruleId)
        engine.tick()
        return outcome
    }

    func cancelPendingChange(ruleId: String) async throws {
        try await blockingRepository.cancelPendingChange(ruleId: ruleId)
        engine.tick()
    }

    // MARK: Paths

    /// An app-specific location. PowerSync's default directory
    /// (~/Library/Application Support/databases) is shared by every
    /// non-sandboxed app using the SDK.
    private static func databasePath() -> String {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent(Bundle.main.bundleIdentifier ?? "dev.brainfence.macos", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("powersync.db").path
    }
}

enum AppError: LocalizedError {
    case notSignedIn

    var errorDescription: String? {
        switch self {
        case .notSignedIn: "Sign in to Brainfence first."
        }
    }
}
