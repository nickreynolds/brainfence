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
    @ObservationIgnored let db: PowerSyncDatabaseProtocol
    @ObservationIgnored let blockingRepository: BlockingRepository
    @ObservationIgnored let webEnforcer: WebEnforcer
    @ObservationIgnored private let appEnforcer: AppEnforcer
    @ObservationIgnored private let completionRepository: CompletionRepository
    @ObservationIgnored private let connector: SupabaseConnector
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

        let taskRepository = TaskRepository(db: db)
        blockingRepository = BlockingRepository(db: db)
        completionRepository = CompletionRepository(db: db)
        engine = BlockingEngine(taskRepository: taskRepository, blockingRepository: blockingRepository)

        shield = ShieldPresenter()
        appEnforcer = AppEnforcer(shield: shield)
        webEnforcer = WebEnforcer(appEnforcer: appEnforcer, shield: shield)
        launchAgent = LaunchAgentManager()
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

    func complete(_ task: BrainTask) async throws {
        guard let userId = session.userIdForLocalWrites else { throw AppError.notSignedIn }
        try await completionRepository.completeTask(taskId: task.id, userId: userId)
        engine.tick()
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
