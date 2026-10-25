import Foundation

// The setup guide's rules: where a device goes, and the rows the guide writes.
// Shared semantics: shared/CONTRACTS.md (Onboarding); cases:
// shared/fixtures/onboarding.json, which Android and Windows run too.

public enum StarterKey: String, Sendable, CaseIterable, Hashable {
    case morningRoutine = "morning_routine"
    case morningWalk = "morning_walk"
    case meditation
    case journaling
    case groceries
    case fiveRemembrances = "five_remembrances"
}

public struct StarterStep: Sendable, Equatable {
    public let title: String
    public let stepType: String
    public let config: JSONValue
}

public struct StarterDef: Sendable {
    public enum Place: Sendable { case home, store }

    public let key: StarterKey
    public let title: String
    /// One line for the guide: what the task is and how it's completed.
    public let summary: String
    /// Has a time window the user can change.
    public let hasWindow: Bool
    public let availableFrom: String?
    public let dueAt: String?
    /// A condition of the guide's blocking rule.
    public let blocks: Bool
    /// The location the starter needs; deferred until it's known.
    public let needs: Place?
    public let sortOrder: Int

    public static let all: [StarterDef] = [
        StarterDef(key: .morningRoutine, title: "Morning Routine", summary: "A checklist you run through: make bed, take pills, then a 30-second reverse plank.", hasWindow: true, availableFrom: "06:00", dueAt: "09:00", blocks: true, needs: nil, sortOrder: 0),
        StarterDef(key: .morningWalk, title: "Morning Walk", summary: "Completes on its own when your phone leaves home.", hasWindow: true, availableFrom: "06:00", dueAt: "11:00", blocks: true, needs: .home, sortOrder: 1),
        StarterDef(key: .meditation, title: "Meditation", summary: "Ten minutes with the built-in timer or a meditation app you choose.", hasWindow: true, availableFrom: "06:00", dueAt: "19:00", blocks: true, needs: nil, sortOrder: 2),
        StarterDef(key: .journaling, title: "Journal", summary: "Write a short entry each evening. Past entries are kept in Journal.", hasWindow: true, availableFrom: "18:00", dueAt: "23:00", blocks: false, needs: nil, sortOrder: 3),
        StarterDef(key: .groceries, title: "Groceries", summary: "A running shopping list that reminds you when you reach the store.", hasWindow: false, availableFrom: nil, dueAt: nil, blocks: false, needs: .store, sortOrder: 4),
        StarterDef(key: .fiveRemembrances, title: "The Five Remembrances", summary: "Short reflections delivered a few times a day. Click \"I remember\" to acknowledge one.", hasWindow: false, availableFrom: nil, dueAt: nil, blocks: false, needs: nil, sortOrder: 5),
    ]

    public static func of(_ key: StarterKey) -> StarterDef { all.first { $0.key == key }! }
}

public enum Onboarding {
    public static let ruleName = "Distractions"
    public static let meditationSeconds = 600
    public static let meditationShortSitSeconds = 120
    public static let groceriesRadiusMeters = 100
    public static let suggestedDomains = ["youtube.com", "reddit.com", "x.com", "instagram.com", "facebook.com", "tiktok.com"]
    public static let morningRoutineSteps = [
        StarterStep(title: "Make Bed", stepType: "checkbox", config: .object([:])),
        StarterStep(title: "Take Pills", stepType: "checkbox", config: .object([:])),
        StarterStep(title: "Reverse Plank", stepType: "timed", config: .object(["duration_seconds": .int(30)])),
    ]
    static let everyDay = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]

    /// Android and Windows have a home location; the Mac doesn't.
    public static func canLocate(_ platform: String) -> Bool { platform != BlockedApp.macOS }
}

// MARK: Routing

public enum OnboardingRoute: String, Sendable {
    case wait, guide, none
    case deviceSetup = "device_setup"
    case autoDismiss = "auto_dismiss"
}

public struct DeferredStep: Sendable, Equatable {
    public let key: StarterKey
    public let availableFrom: String?
    public let dueAt: String?

    public init(key: StarterKey, availableFrom: String? = nil, dueAt: String? = nil) {
        self.key = key
        self.availableFrom = availableFrom
        self.dueAt = dueAt
    }

    public var json: JSONValue {
        var o: [String: JSONValue] = ["key": .string(key.rawValue)]
        if let availableFrom { o["available_from"] = .string(availableFrom) }
        if let dueAt { o["due_at"] = .string(dueAt) }
        return .object(o)
    }

    /// Parses `deferred_steps`, skipping entries this version doesn't know.
    public static func parseList(_ json: String?) -> [DeferredStep] {
        (JSONValue.parse(json)?.arrayValue ?? []).compactMap { item in
            guard let key = item["key"]?.stringValue.flatMap(StarterKey.init(rawValue:)) else { return nil }
            return DeferredStep(key: key, availableFrom: item["available_from"]?.stringValue, dueAt: item["due_at"]?.stringValue)
        }
    }

    public static func encodeList(_ steps: [DeferredStep]) -> String { JSONValue.array(steps.map(\.json)).jsonString }
}

/// The synced `onboarding` row (one per user, id = user_id).
public struct OnboardingRow: Sendable, Equatable {
    public let status: String
    public let deferredSteps: [DeferredStep]
    public let blockingRuleId: String?
    public let meditationTaskId: String?
    /// `<platform>_setup_at`, by platform.
    public let setupAt: [String: String]

    public init(status: String, deferredSteps: [DeferredStep], blockingRuleId: String?, meditationTaskId: String?, setupAt: [String: String]) {
        self.status = status
        self.deferredSteps = deferredSteps
        self.blockingRuleId = blockingRuleId
        self.meditationTaskId = meditationTaskId
        self.setupAt = setupAt
    }

    public static let platforms = [BlockedApp.android, BlockedApp.windows, BlockedApp.macOS]
    public static func setupColumn(_ platform: String) -> String { "\(platform)_setup_at" }
}

public func onboardingRoute(platform: String, hasSynced: Bool, onboarding: OnboardingRow?, taskCount: Int) -> OnboardingRoute {
    guard hasSynced else { return .wait }
    guard let onboarding else { return taskCount > 0 ? .autoDismiss : .guide }
    guard onboarding.status == "completed" else { return .none }
    if Onboarding.canLocate(platform) && !onboarding.deferredSteps.isEmpty { return .deviceSetup }
    if onboarding.setupAt[platform] == nil { return .deviceSetup }
    return .none
}

// MARK: Plan

public struct StarterChoice: Sendable, Equatable {
    public var availableFrom: String?
    public var dueAt: String?

    public init(availableFrom: String? = nil, dueAt: String? = nil) {
        self.availableFrom = availableFrom
        self.dueAt = dueAt
    }
}

public struct PlaceChoice: Sendable, Equatable {
    public let latitude: Double
    public let longitude: Double
    public let radiusMeters: Int

    public init(latitude: Double, longitude: Double, radiusMeters: Int = 100) {
        self.latitude = latitude
        self.longitude = longitude
        self.radiusMeters = radiusMeters
    }
}

public struct OnboardingChoices: Sendable {
    /// Present = turned on; values override the default window.
    public var starters: [StarterKey: StarterChoice]
    public var home: PlaceChoice?
    public var store: PlaceChoice?
    /// This platform's companion apps for the meditation task.
    public var companionApps: [BlockedApp]
    /// This platform's apps for the rule.
    public var blockedApps: [BlockedApp]
    public var blockedDomains: [String]

    public init(starters: [StarterKey: StarterChoice], home: PlaceChoice? = nil, store: PlaceChoice? = nil, companionApps: [BlockedApp] = [], blockedApps: [BlockedApp] = [], blockedDomains: [String] = []) {
        self.starters = starters
        self.home = home
        self.store = store
        self.companionApps = companionApps
        self.blockedApps = blockedApps
        self.blockedDomains = blockedDomains
    }

    /// Choices that re-create deferred starters, once their locations are known.
    public static func deferred(_ steps: [DeferredStep], home: PlaceChoice?, store: PlaceChoice?) -> OnboardingChoices {
        var starters: [StarterKey: StarterChoice] = [:]
        for s in steps { starters[s.key] = StarterChoice(availableFrom: s.availableFrom, dueAt: s.dueAt) }
        return OnboardingChoices(starters: starters, home: home, store: store)
    }
}

public struct PlannedTask: Sendable, Equatable {
    public let key: StarterKey
    public let title: String
    public let taskType: String
    public let verificationType: String?
    public let verificationConfig: JSONValue
    public let recurrenceType: String?
    public let recurrenceConfig: JSONValue
    public let isBlockingCondition: Bool
    public let availableFrom: String?
    public let dueAt: String?
    public let sortOrder: Int
    public let steps: [StarterStep]
}

public struct PlannedRule: Sendable, Equatable {
    public let name: String
    public let blockedApps: [BlockedApp]
    public let blockedDomains: [String]
    public let conditionKeys: [StarterKey]
    public let conditionLogic: String
}

public struct OnboardingPlan: Sendable, Equatable {
    public let tasks: [PlannedTask]
    public let deferredSteps: [DeferredStep]
    public let addRemembrances: Bool
    public let rule: PlannedRule?

    public init(_ choices: OnboardingChoices) {
        var tasks: [PlannedTask] = []
        var deferred: [DeferredStep] = []
        for def in StarterDef.all where def.key != .fiveRemembrances {
            guard let choice = choices.starters[def.key] else { continue }
            let from = def.hasWindow ? choice.availableFrom ?? def.availableFrom : nil
            let due = def.hasWindow ? choice.dueAt ?? def.dueAt : nil
            let place: PlaceChoice? = switch def.needs {
            case .home: choices.home
            case .store: choices.store
            case nil: nil
            }
            if def.needs != nil && place == nil {
                deferred.append(DeferredStep(key: def.key, availableFrom: from, dueAt: due))
                continue
            }
            tasks.append(Self.task(def, choices: choices, place: place, availableFrom: from, dueAt: due))
        }

        var domains: [String] = []
        for d in choices.blockedDomains.compactMap(DomainMatcher.normalize) where !domains.contains(d) { domains.append(d) }
        let conditions = tasks.filter(\.isBlockingCondition).map(\.key)
        let blocksSomething = !choices.blockedApps.isEmpty || !domains.isEmpty

        self.tasks = tasks
        deferredSteps = deferred
        addRemembrances = choices.starters[.fiveRemembrances] != nil
        rule = blocksSomething && !conditions.isEmpty
            ? PlannedRule(name: Onboarding.ruleName, blockedApps: choices.blockedApps, blockedDomains: domains, conditionKeys: conditions, conditionLogic: "all")
            : nil
    }

    private static func task(_ def: StarterDef, choices: OnboardingChoices, place: PlaceChoice?, availableFrom: String?, dueAt: String?) -> PlannedTask {
        var taskType = "simple"
        var verificationType: String?
        var config: [String: JSONValue] = [:]
        var recurrence: String? = "daily"
        var steps: [StarterStep] = []
        switch def.key {
        case .morningRoutine:
            taskType = "routine"
            steps = Onboarding.morningRoutineSteps
        case .morningWalk:
            verificationType = "gps"
            config = [
                "latitude": .double(place!.latitude), "longitude": .double(place!.longitude),
                "radius_meters": .int(place!.radiusMeters), "mode": .string("leave"), "min_duration_m": .int(0),
            ]
        case .meditation:
            verificationType = "meditation"
            config = [
                "duration_seconds": .int(Onboarding.meditationSeconds),
                "short_sit_seconds": .int(Onboarding.meditationShortSitSeconds),
                "companion_apps": BlockedApp.encodeList(choices.companionApps),
            ]
        case .journaling:
            taskType = "journal"
            verificationType = "journal"
        case .groceries:
            taskType = "shopping"
            verificationType = "gps"
            recurrence = nil
            config = [
                "latitude": .double(place!.latitude), "longitude": .double(place!.longitude),
                "radius_meters": .int(Onboarding.groceriesRadiusMeters), "mode": .string("notify"),
                "notify_window": .object(["days": .array(of: Onboarding.everyDay), "start": .string("08:00"), "end": .string("21:00")]),
            ]
        case .fiveRemembrances:
            break
        }
        return PlannedTask(
            key: def.key, title: def.title, taskType: taskType, verificationType: verificationType,
            verificationConfig: .object(config), recurrenceType: recurrence, recurrenceConfig: .object([:]),
            isBlockingCondition: def.blocks, availableFrom: availableFrom, dueAt: dueAt, sortOrder: def.sortOrder, steps: steps
        )
    }
}
