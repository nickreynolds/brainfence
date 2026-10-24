import Foundation

/// A row from `tasks`, joined with today's completion state.
/// Mirrors Android's `dev.brainfence.domain.model.Task`.
public struct BrainTask: Sendable, Hashable, Identifiable {
    public let id: String
    public var userId: String
    public var title: String
    public var description: String?
    public var taskType: String
    public var status: String
    public var recurrenceType: String?
    public var recurrenceConfig: String      // JSONB as text
    public var verificationType: String?
    public var verificationConfig: String    // JSONB as text
    public var groupId: String?
    public var sortOrder: Int
    public var isBlockingCondition: Bool     // decorative; rules' condition_task_ids decide blocking
    public var availableFrom: String?        // "HH:MM" — when the task becomes completable
    public var dueAt: String?                // "HH:MM" — when an incomplete task starts blocking
    public var homeOnlyBlocking: Bool
    public var blockingDaysOfWeek: String    // JSON array of "mon"…"sun"; empty = every day
    public var createdAt: String
    public var updatedAt: String
    /// Derived: completed in the current occurrence (today for recurring tasks, ever for one-offs).
    public var completedToday: Bool
    public var lastCompletionAt: String?

    public init(
        id: String,
        userId: String = "",
        title: String,
        description: String? = nil,
        taskType: String = "simple",
        status: String = "active",
        recurrenceType: String? = "daily",
        recurrenceConfig: String = "{}",
        verificationType: String? = "manual",
        verificationConfig: String = "{}",
        groupId: String? = nil,
        sortOrder: Int = 0,
        isBlockingCondition: Bool = false,
        availableFrom: String? = nil,
        dueAt: String? = nil,
        homeOnlyBlocking: Bool = false,
        blockingDaysOfWeek: String = "[]",
        createdAt: String = "",
        updatedAt: String = "",
        completedToday: Bool = false,
        lastCompletionAt: String? = nil
    ) {
        self.id = id
        self.userId = userId
        self.title = title
        self.description = description
        self.taskType = taskType
        self.status = status
        self.recurrenceType = recurrenceType
        self.recurrenceConfig = recurrenceConfig
        self.verificationType = verificationType
        self.verificationConfig = verificationConfig
        self.groupId = groupId
        self.sortOrder = sortOrder
        self.isBlockingCondition = isBlockingCondition
        self.availableFrom = availableFrom
        self.dueAt = dueAt
        self.homeOnlyBlocking = homeOnlyBlocking
        self.blockingDaysOfWeek = blockingDaysOfWeek
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.completedToday = completedToday
        self.lastCompletionAt = lastCompletionAt
    }

    /// Whether this task can be completed with a single click on the Mac.
    /// Everything else needs its dedicated flow (routine steps, GPS, timers, …),
    /// which currently lives on Android.
    public var isQuickCompletable: Bool {
        let flowTypes: Set<String> = ["routine", "workout", "journal", "report", "shopping", "meditation", "timed"]
        if flowTypes.contains(taskType) { return false }
        return verificationType == nil || verificationType == "manual" || verificationType == "time_gate"
    }

    /// Short description of what completing this task requires, for tasks that
    /// can't be quick-completed. Matches Android's blockingVerificationHint.
    public var verificationHint: String? {
        switch (taskType, verificationType) {
        case ("routine", _), ("workout", _): return "Requires routine completion"
        case ("journal", _): return "Requires journal entry"
        case ("report", _): return "Requires report"
        case ("shopping", _): return "Requires shopping trip"
        case (_, "gps"): return "Requires GPS verification"
        case (_, "meditation"): return "Requires meditation session"
        case (_, "duration"): return "Requires timed session"
        default: return isQuickCompletable ? nil : "Complete on your phone"
        }
    }
}

/// One entry of `blocking_rules.blocked_apps`, e.g.
/// `{"platform":"android","package":"com.twitter.android"}` or
/// `{"platform":"macos","bundle_id":"com.hnc.Discord"}`.
///
/// The original JSON element is kept in `raw` so editing a rule on one
/// platform round-trips the other platforms' entries (and any extra fields)
/// untouched.
public struct BlockedApp: Sendable, Hashable {
    public static let android = "android"
    public static let macOS = "macos"

    public let platform: String
    /// Android package name or macOS bundle identifier.
    public let identifier: String
    public let raw: JSONValue

    public init(platform: String, identifier: String, raw: JSONValue) {
        self.platform = platform
        self.identifier = identifier
        self.raw = raw
    }

    public static func macOS(bundleID: String) -> BlockedApp {
        BlockedApp(
            platform: macOS,
            identifier: bundleID,
            raw: .object(["platform": .string(macOS), "bundle_id": .string(bundleID)])
        )
    }

    /// Identity used for set comparisons (time-lock restrictiveness checks).
    public var key: String { "\(platform):\(identifier)" }

    /// Parses `blocked_apps`. Bare strings are treated as Android packages,
    /// matching Android's parser fallback.
    public static func parseList(_ json: String?) -> [BlockedApp] {
        guard let items = JSONValue.parse(json)?.arrayValue else { return [] }
        return items.compactMap { item in
            switch item {
            case .object(let o):
                let platform = o["platform"]?.stringValue ?? ""
                let identifier: String = switch platform {
                case android: o["package"]?.stringValue ?? ""
                case macOS: o["bundle_id"]?.stringValue ?? ""
                default: o["package"]?.stringValue ?? o["bundle_id"]?.stringValue ?? ""
                }
                return BlockedApp(platform: platform, identifier: identifier, raw: item)
            case .string(let s) where !s.isEmpty:
                return BlockedApp(platform: android, identifier: s, raw: item)
            default:
                return nil
            }
        }
    }

    public static func encodeList(_ apps: [BlockedApp]) -> JSONValue {
        .array(apps.map(\.raw))
    }
}

/// A row from `blocking_rules`. Mirrors Android's `BlockingRule`, but keeps
/// every platform's blocked apps rather than only the local platform's.
public struct BlockingRule: Sendable, Hashable, Identifiable {
    public let id: String
    public var userId: String
    public var name: String
    public var blockedApps: [BlockedApp]
    public var blockedDomains: [String]
    public var conditionTaskIds: [String]
    public var conditionLogic: String        // "all" | "any"
    public var configLockHours: Int
    public var pendingChanges: String?       // JSONB text of staged changes
    public var changesApplyAt: String?       // timestamp when staged changes go live
    public var isActive: Bool

    public init(
        id: String,
        userId: String = "",
        name: String,
        blockedApps: [BlockedApp] = [],
        blockedDomains: [String] = [],
        conditionTaskIds: [String] = [],
        conditionLogic: String = "all",
        configLockHours: Int = 24,
        pendingChanges: String? = nil,
        changesApplyAt: String? = nil,
        isActive: Bool = true
    ) {
        self.id = id
        self.userId = userId
        self.name = name
        self.blockedApps = blockedApps
        self.blockedDomains = blockedDomains
        self.conditionTaskIds = conditionTaskIds
        self.conditionLogic = conditionLogic
        self.configLockHours = configLockHours
        self.pendingChanges = pendingChanges
        self.changesApplyAt = changesApplyAt
        self.isActive = isActive
    }

    public var macOSBundleIDs: [String] {
        blockedApps.filter { $0.platform == BlockedApp.macOS && !$0.identifier.isEmpty }.map(\.identifier)
    }

    public var otherPlatformApps: [BlockedApp] {
        blockedApps.filter { $0.platform != BlockedApp.macOS }
    }

    public var hasPendingChanges: Bool { pendingChanges != nil && changesApplyAt != nil }
}
