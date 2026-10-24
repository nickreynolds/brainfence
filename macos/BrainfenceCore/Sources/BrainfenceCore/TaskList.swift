import Foundation

/// The task list's Active / Done / Upcoming tabs. Same ordering and filtering
/// as Android's TaskListViewModel (and desktop-app's partitionTasks).
public struct TaskPartition: Sendable, Equatable {
    public let active: [BrainTask]
    public let done: [BrainTask]
    public let upcoming: [BrainTask]
}

public func partitionTasks(_ tasks: [BrainTask], now: Date, timeZone: TimeZone = .current) -> TaskPartition {
    func phase(_ t: BrainTask) -> TimeGatePhase? {
        computeTaskPhase(availableFrom: t.availableFrom, dueAt: t.dueAt, now: now, timeZone: timeZone)
    }
    func urgency(_ t: BrainTask, _ p: TimeGatePhase?) -> Int {
        if t.isBlockingCondition && (p == .pastDue || t.dueAt == nil) { return 0 }
        if t.isBlockingCondition { return 1 }
        if t.dueAt != nil { return 2 }
        return 3
    }

    let active = tasks
        .map { (task: $0, phase: phase($0)) }
        .filter { !$0.task.completedToday && $0.phase != .beforeStart }
        .sorted { a, b in
            let ua = urgency(a.task, a.phase), ub = urgency(b.task, b.phase)
            if ua != ub { return ua < ub }
            // Tasks without a due time sort after those with one.
            let da = a.task.dueAt ?? "\u{FFFF}", db = b.task.dueAt ?? "\u{FFFF}"
            if da != db { return da < db }
            return a.task.sortOrder < b.task.sortOrder
        }
        .map(\.task)
    let done = tasks.filter(\.completedToday)
    let upcoming = tasks.filter { t in
        if t.completedToday { return t.recurrenceType != nil }
        return phase(t) == .beforeStart
    }
    return TaskPartition(active: active, done: done, upcoming: upcoming)
}

// MARK: - Task notifications

/// A local notification about tasks.
public struct TaskNotification: Sendable, Equatable {
    public let title: String
    public let body: String
}

/// Port of Android's TaskNotificationManager policy: "task ready" when
/// `available_from` is reached (within 2 minutes of it, grouped by time) and
/// "blocking soon" once in the hour before `due_at`, for tasks that are a
/// condition of an active rule. `sentKeys` remembers what was sent; keys
/// include the date, so callers can clear it daily.
public func dueTaskNotifications(
    tasks: [BrainTask],
    enforcedTaskIds: Set<String>,
    now: Date,
    sentKeys: inout Set<String>,
    timeZone: TimeZone = .current
) -> [TaskNotification] {
    let clock = ZonedClock(timeZone: timeZone)
    let today = clock.date(of: now).description
    let t = clock.secondsIntoDay(of: now)
    var out: [TaskNotification] = []

    var byStart: [String: [BrainTask]] = [:]
    for task in tasks where !task.completedToday && task.taskType != "shopping" {
        guard let from = task.availableFrom else { continue }
        byStart[from, default: []].append(task)
    }
    for (from, group) in byStart.sorted(by: { $0.key < $1.key }) {
        let key = "ready:\(today):\(from)"
        guard !sentKeys.contains(key), let start = TimeOfDay(from)?.seconds else { continue }
        if t >= start && t < start + 120 {
            sentKeys.insert(key)
            out.append(group.count == 1
                ? TaskNotification(title: "\(group[0].title) is ready", body: "Available since \(from)")
                : TaskNotification(title: "\(group.count) tasks are ready", body: group.map(\.title).joined(separator: ", ")))
        }
    }

    var warn: [BrainTask] = []
    for task in tasks where !task.completedToday && enforcedTaskIds.contains(task.id) {
        guard let dueAt = task.dueAt, let due = TimeOfDay(dueAt)?.seconds else { continue }
        let key = "blocking:\(today):\(task.id)"
        if !sentKeys.contains(key) && t >= due - 3600 && t < due {
            sentKeys.insert(key)
            warn.append(task)
        }
    }
    if !warn.isEmpty {
        out.append(warn.count == 1
            ? TaskNotification(
                title: "\(warn[0].title) will block in 1 hour",
                body: "Due at \(warn[0].dueAt ?? "") — complete it to avoid blocking")
            : TaskNotification(
                title: "\(warn.count) tasks will block in 1 hour",
                body: warn.map { "\($0.title) (\($0.dueAt ?? ""))" }.joined(separator: ", ")))
    }
    return out
}
