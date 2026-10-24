import Foundation

// The task editor's state, validation and the column values it writes.
// Port of android/…/ui/tasks/TaskEditorViewModel.kt via desktop-app's
// taskEditor.ts, including its one deliberate difference: when the task type
// is unchanged, verification_config keys this editor doesn't manage (another
// platform's companion apps, an automatic routine's execution_mode) are
// carried over, and only this platform's companion-app entries are replaced.

public struct EditableStep: Sendable, Equatable, Identifiable {
    public var id: String
    public var title: String
    public var stepType: String
    public var defaultSets: Int
    public var durationSeconds: Int
    public var supersetGroup: String?
    public var reportId: String?
    public var description: String

    public init(
        id: String = UUID().uuidString.lowercased(), title: String = "", stepType: String = "checkbox",
        defaultSets: Int = 3, durationSeconds: Int = 60, supersetGroup: String? = nil,
        reportId: String? = nil, description: String = ""
    ) {
        self.id = id
        self.title = title
        self.stepType = stepType
        self.defaultSets = defaultSets
        self.durationSeconds = durationSeconds
        self.supersetGroup = supersetGroup
        self.reportId = reportId
        self.description = description
    }

    /// routine_steps.config for this step.
    public var configJSON: JSONValue {
        switch stepType {
        case "weight_reps", "just_reps": .object(["default_sets": .int(defaultSets)])
        case "timed": .object(["duration_seconds": .int(durationSeconds)])
        case "timed_sets": .object(["reps": .int(defaultSets), "work_seconds": .int(durationSeconds), "rest_seconds": .int(20)])
        case "report": reportId.map { JSONValue.object(["report_id": .string($0)]) } ?? .object([:])
        default: .object([:])
        }
    }
}

/// The `tasks` columns the editor reads.
public struct TaskRow: Sendable, Equatable {
    public var title: String
    public var description: String?
    public var taskType: String
    public var recurrenceType: String?
    public var recurrenceConfig: String?
    public var verificationType: String?
    public var verificationConfig: String?
    public var isBlockingCondition: Bool
    public var homeOnlyBlocking: Bool
    public var blockingDaysOfWeek: String?
    public var availableFrom: String?
    public var dueAt: String?

    public init(
        title: String, description: String? = nil, taskType: String = "simple", recurrenceType: String? = nil,
        recurrenceConfig: String? = nil, verificationType: String? = nil, verificationConfig: String? = nil,
        isBlockingCondition: Bool = false, homeOnlyBlocking: Bool = false, blockingDaysOfWeek: String? = nil,
        availableFrom: String? = nil, dueAt: String? = nil
    ) {
        self.title = title
        self.description = description
        self.taskType = taskType
        self.recurrenceType = recurrenceType
        self.recurrenceConfig = recurrenceConfig
        self.verificationType = verificationType
        self.verificationConfig = verificationConfig
        self.isBlockingCondition = isBlockingCondition
        self.homeOnlyBlocking = homeOnlyBlocking
        self.blockingDaysOfWeek = blockingDaysOfWeek
        self.availableFrom = availableFrom
        self.dueAt = dueAt
    }
}

/// The column values a save writes, plus the routine steps to sync.
public struct TaskWrite: Sendable, Equatable {
    public struct Step: Sendable, Equatable {
        public let id: String
        public let title: String
        public let stepType: String
        public let config: String
        public let supersetGroup: String?
        public let description: String?
    }

    public let title: String
    public let description: String?
    public let taskType: String
    public let recurrenceType: String?
    public let recurrenceConfig: String
    public let verificationType: String?
    public let verificationConfig: String
    public let isBlockingCondition: Bool
    public let availableFrom: String?
    public let dueAt: String?
    public let homeOnlyBlocking: Bool
    public let blockingDaysOfWeek: String
    /// Nil when the task isn't a routine (its steps are left alone).
    public let routineSteps: [Step]?
}

public let taskTypes: [(value: String, label: String, detail: String)] = [
    ("simple", "Simple", "Click to complete"),
    ("timed", "Timed", "Requires a timed session"),
    ("routine", "Routine", "Multi-step checklist"),
    ("workout", "Workout", "Exercise tracking with sets"),
    ("journal", "Journal", "Write a short entry"),
    ("shopping", "Shopping List", "Running list with location reminder"),
    ("report", "Report", "Answer a few quick questions"),
]

public func isRoutineType(_ type: String) -> Bool { type == "routine" || type == "workout" }

private let dayOrder = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]

/// Days in week order, without duplicates.
public func sortDays(_ days: [String]) -> [String] {
    dayOrder.filter(Set(days).contains)
}

public struct TaskEditorState: Sendable, Equatable {
    public var title = ""
    public var description = ""
    /// simple | timed | routine | workout | journal | shopping | report
    public var taskType = "simple"
    /// manual | duration | gps | meditation | journal | report
    public var verificationType = "manual"
    public var durationSeconds = 300
    public var latitude = ""
    public var longitude = ""
    public var radiusMeters = 100
    public var gpsMode = "enter"
    public var gpsMinDurationMinutes = 0
    public var notifyDays = ["mon", "tue", "wed", "thu", "fri"]
    public var notifyStart = "15:00"
    public var notifyEnd = "21:00"
    public var meditationSeconds = 300
    public var shortSitSeconds = defaultShortSitSeconds
    /// This platform's companion-app entries ({"platform":"macos","bundle_id":…}).
    public var companionApps: [JSONValue] = []
    public var reportId: String?
    public var routineSteps: [EditableStep] = []
    /// nil | daily | weekly (multi-day weekly is stored as daily + days),
    /// or monthly / interval, which have no editor UI and are kept as they are.
    public var recurrenceType: String?
    public var weeklyDays: [String] = []
    public var isBlockingCondition = false
    public var homeOnlyBlocking = false
    public var blockingDaysOfWeek: [String] = []
    public var availableFrom = ""
    public var dueAt = ""
    public var editingTaskId: String?
    /// The loaded task's type, verification and configs, for carrying unmanaged keys over.
    public var originalTaskType: String?
    public var originalVerificationType: String?
    public var originalVerificationConfig: [String: JSONValue] = [:]
    public var originalRecurrenceConfig: [String: JSONValue] = [:]

    public init() {}

    public init(editing taskId: String, row: TaskRow, steps: [RoutineStep]) {
        let v = JSONValue.parse(row.verificationConfig)?.objectValue ?? [:]
        let r = JSONValue.parse(row.recurrenceConfig)?.objectValue ?? [:]
        let vj = JSONValue.object(v)
        let taskType = row.taskType
        let isGps = row.verificationType == "gps"
        let isMeditation = row.verificationType == "meditation"
        let effectiveVerification = row.verificationType ?? (taskType == "timed" ? "duration" : "manual")

        title = row.title
        description = row.description ?? ""
        self.taskType = taskType
        verificationType = effectiveVerification

        switch row.recurrenceType {
        case "weekly":
            recurrenceType = "weekly"
            weeklyDays = (r["day"]?.stringValue).map { [$0] } ?? []
        case "daily":
            let days = (r["days"]?.arrayValue ?? []).compactMap(\.stringValue)
            recurrenceType = days.isEmpty ? "daily" : "weekly"
            weeklyDays = days
        default:
            recurrenceType = row.recurrenceType
        }

        if taskType == "timed" { durationSeconds = Int(vj.first("duration_seconds", "target_seconds")?.doubleValue ?? 300) }
        if isGps {
            latitude = Self.format(vj.first("latitude", "lat")?.doubleValue ?? 0)
            longitude = Self.format(vj.first("longitude", "lng")?.doubleValue ?? 0)
            radiusMeters = Int(vj.first("radius_meters", "radius_m")?.doubleValue ?? 100)
            gpsMode = vj.first("mode")?.stringValue ?? "enter"
            gpsMinDurationMinutes = Int(vj.first("min_duration_m")?.doubleValue ?? 0)
        }
        if taskType == "shopping", let window = v["notify_window"]?.objectValue {
            notifyDays = (window["days"]?.arrayValue ?? []).compactMap(\.stringValue)
            notifyStart = window["start"]?.stringValue ?? ""
            notifyEnd = window["end"]?.stringValue ?? ""
        }
        if isMeditation {
            meditationSeconds = Int(vj.first("duration_seconds")?.doubleValue ?? 300)
            shortSitSeconds = Int(vj.first("short_sit_seconds")?.doubleValue ?? Double(defaultShortSitSeconds))
            companionApps = (v["companion_apps"]?.arrayValue ?? []).filter { $0["platform"]?.stringValue == localPlatform }
        }
        if taskType == "report" { reportId = BrainfenceCore.reportId(fromConfig: row.verificationConfig) }
        if isRoutineType(taskType) {
            routineSteps = steps.map { s in
                let c = JSONValue.parse(s.config) ?? .object([:])
                return EditableStep(
                    id: s.id,
                    title: s.title,
                    stepType: s.stepType,
                    defaultSets: Int(c.first("default_sets", "reps")?.doubleValue ?? 3),
                    durationSeconds: Int(c.first("duration_seconds", "work_seconds")?.doubleValue ?? 60),
                    supersetGroup: s.supersetGroup,
                    reportId: BrainfenceCore.reportId(fromConfig: s.config),
                    description: s.description ?? ""
                )
            }
        }

        isBlockingCondition = row.isBlockingCondition
        homeOnlyBlocking = row.homeOnlyBlocking
        blockingDaysOfWeek = JSONValue.stringArray(row.blockingDaysOfWeek)
        availableFrom = row.availableFrom ?? ""
        dueAt = row.dueAt ?? ""
        editingTaskId = taskId
        originalTaskType = taskType
        originalVerificationType = effectiveVerification
        originalVerificationConfig = v
        originalRecurrenceConfig = r
    }

    private static func format(_ d: Double) -> String {
        d == d.rounded() ? String(Int(d)) : String(d)
    }

    // MARK: Wizard

    /// Shopping lists skip the schedule step: they never recur, block or expire.
    public var totalSteps: Int { taskType == "shopping" ? 2 : 3 }

    /// Changes the task type, switching to the verification it implies.
    public mutating func setTaskType(_ type: String) {
        taskType = type
        switch type {
        case "timed": verificationType = "duration"
        case "routine", "workout": verificationType = "manual"
        case "journal": verificationType = "journal"
        case "shopping": verificationType = "gps"
        case "report": verificationType = "report"
        default:
            if ["journal", "report", "duration"].contains(verificationType) { verificationType = "manual" }
        }
    }

    /// Why the wizard can't advance past [step], or nil.
    public func validateStep(_ step: Int) -> String? {
        if step == 0 && title.trimmingCharacters(in: .whitespaces).isEmpty { return "Title is required" }
        if step == 1 && isRoutineType(taskType) {
            if routineSteps.isEmpty { return "Add at least one step" }
            if routineSteps.contains(where: { $0.title.trimmingCharacters(in: .whitespaces).isEmpty }) {
                return "All steps must have a title"
            }
        }
        return nil
    }

    /// Why the task can't be saved, or nil. [reportIds] are the reports that exist.
    public func validateForSave(reportIds: Set<String>) -> String? {
        if title.trimmingCharacters(in: .whitespaces).isEmpty { return "Title is required" }
        let hasLocation = Double(latitude.trimmingCharacters(in: .whitespaces)) != nil
            && Double(longitude.trimmingCharacters(in: .whitespaces)) != nil
        if taskType == "shopping" && !hasLocation { return "A reminder location (latitude/longitude) is required" }
        if verificationType == "gps" && taskType != "shopping" && !hasLocation {
            return "Latitude and longitude are required for GPS verification"
        }
        if taskType == "report" {
            guard let reportId else { return "Choose which report this task asks" }
            if !reportIds.contains(reportId) { return "That report no longer exists" }
        }
        if isRoutineType(taskType) && routineSteps.contains(where: { $0.stepType == "report" && $0.reportId == nil }) {
            return "Choose a report for each report step"
        }
        if let error = validateStep(1) { return error }
        for (label, value) in [("Available from", availableFrom), ("Due at", dueAt)] {
            let v = value.trimmingCharacters(in: .whitespaces)
            if !v.isEmpty && TimeOfDay(v) == nil { return "\(label) must be HH:mm" }
        }
        return nil
    }

    // MARK: Save

    public var verificationConfig: JSONValue {
        let sameKind = originalTaskType == taskType && originalVerificationType == verificationType
        var carried = sameKind ? originalVerificationConfig : [:]
        let lat = Double(latitude.trimmingCharacters(in: .whitespaces)) ?? 0
        let lng = Double(longitude.trimmingCharacters(in: .whitespaces)) ?? 0
        if taskType == "timed" {
            carried["duration_seconds"] = .int(durationSeconds)
            return .object(carried)
        }
        if taskType == "report" {
            carried["report_id"] = reportId.map(JSONValue.string) ?? .null
            return .object(carried)
        }
        if taskType == "shopping" {
            var window: [String: JSONValue] = ["days": .array(of: notifyDays)]
            let start = notifyStart.trimmingCharacters(in: .whitespaces)
            let end = notifyEnd.trimmingCharacters(in: .whitespaces)
            if !start.isEmpty { window["start"] = .string(start) }
            if !end.isEmpty { window["end"] = .string(end) }
            return .object([
                "latitude": .double(lat), "longitude": .double(lng), "radius_meters": .int(radiusMeters),
                "mode": .string("notify"), "notify_window": .object(window),
            ])
        }
        if isRoutineType(taskType) { return .object(carried) }
        if verificationType == "gps" {
            // The spec's lat/lng/radius_m spellings are rewritten as the editor's.
            for key in ["lat", "lng", "radius_m"] { carried[key] = nil }
            carried["latitude"] = .double(lat)
            carried["longitude"] = .double(lng)
            carried["radius_meters"] = .int(radiusMeters)
            carried["mode"] = .string(gpsMode)
            carried["min_duration_m"] = .int(gpsMode == "enter" ? gpsMinDurationMinutes : 0)
            return .object(carried)
        }
        if verificationType == "meditation" {
            let others = (carried["companion_apps"]?.arrayValue ?? []).filter { entry in
                if case .string = entry { return true }
                return entry["platform"]?.stringValue != localPlatform
            }
            carried["duration_seconds"] = .int(meditationSeconds)
            carried["short_sit_seconds"] = .int(shortSitSeconds)
            carried["companion_apps"] = .array(others + companionApps)
            return .object(carried)
        }
        return .object([:])
    }

    public var recurrenceConfig: JSONValue {
        if recurrenceType == "weekly" && !weeklyDays.isEmpty {
            return weeklyDays.count == 1
                ? .object(["day": .string(weeklyDays[0])])
                : .object(["days": .array(of: sortDays(weeklyDays))])
        }
        if recurrenceType == "monthly" || recurrenceType == "interval" { return .object(originalRecurrenceConfig) }
        return .object([:])
    }

    /// The values to write. Call [validateForSave] first.
    public var write: TaskWrite {
        let isShopping = taskType == "shopping"
        let storedVerification: String? = switch taskType {
        case "timed": "duration"
        case "routine", "workout": nil
        case "shopping": "gps"
        case "report": "report"
        default: verificationType == "manual" ? nil : verificationType
        }
        let storedRecurrence: String? = if isShopping {
            nil
        } else if recurrenceType == "weekly" {
            weeklyDays.isEmpty ? nil : weeklyDays.count > 1 ? "daily" : "weekly"
        } else {
            recurrenceType
        }
        func trimmedOrNil(_ s: String) -> String? {
            let t = s.trimmingCharacters(in: .whitespaces)
            return t.isEmpty ? nil : t
        }
        return TaskWrite(
            title: title.trimmingCharacters(in: .whitespaces),
            description: description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : description,
            taskType: taskType,
            recurrenceType: storedRecurrence,
            recurrenceConfig: recurrenceConfig.jsonString,
            verificationType: storedVerification,
            verificationConfig: verificationConfig.jsonString,
            isBlockingCondition: !isShopping && isBlockingCondition,
            availableFrom: isShopping ? nil : trimmedOrNil(availableFrom),
            dueAt: isShopping ? nil : trimmedOrNil(dueAt),
            homeOnlyBlocking: homeOnlyBlocking,
            blockingDaysOfWeek: JSONValue.array(of: sortDays(blockingDaysOfWeek)).jsonString,
            routineSteps: isRoutineType(taskType)
                ? routineSteps.map { step in
                    TaskWrite.Step(
                        id: step.id,
                        title: step.title.trimmingCharacters(in: .whitespaces),
                        stepType: step.stepType,
                        config: step.configJSON.jsonString,
                        supersetGroup: step.supersetGroup,
                        description: trimmedOrNil(step.description)
                    )
                }
                : nil
        )
    }
}
