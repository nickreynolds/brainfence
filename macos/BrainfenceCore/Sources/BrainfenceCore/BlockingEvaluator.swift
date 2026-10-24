import Foundation

// Port of android/…/domain/blocking/BlockingEvaluator.kt. The decision of
// *whether* a rule blocks must be identical on every platform; only what gets
// enforced differs (Android enforces packages, macOS enforces bundle IDs and
// web domains).

/// Whether the user is currently within their configured home radius.
///
/// `unconfigured` and `unknown` keep home-only tasks blocking as if at home:
/// strict by default, only lift blocking when we positively know the user is away.
public enum HomePresence: Sendable, Equatable {
    case atHome, away, unknown, unconfigured
}

/// A rule that is currently blocking, and the condition tasks causing it.
public struct RuleBlock: Sendable, Hashable, Identifiable {
    public let rule: BlockingRule
    public let unmetTaskIds: Set<String>
    public var id: String { rule.id }
}

public struct BlockingState: Sendable, Equatable {
    /// Rules that are active and whose conditions are unmet, in rule order.
    public let activeBlocks: [RuleBlock]
    /// macOS bundle identifiers to block.
    public let blockedBundleIDs: Set<String>
    /// Normalized web domains to block (see `DomainMatcher.normalize`).
    public let blockedDomains: Set<String>

    public static let none = BlockingState(activeBlocks: [])

    public init(activeBlocks: [RuleBlock]) {
        self.activeBlocks = activeBlocks
        self.blockedBundleIDs = Set(activeBlocks.flatMap(\.rule.macOSBundleIDs))
        self.blockedDomains = Set(activeBlocks.flatMap(\.rule.blockedDomains).compactMap(DomainMatcher.normalize))
    }

    public var isBlocking: Bool { !activeBlocks.isEmpty }

    /// Union of unmet condition tasks across all blocking rules.
    public var unmetTaskIds: Set<String> { Set(activeBlocks.flatMap(\.unmetTaskIds)) }

    public func blocks(forBundleID bundleID: String) -> [RuleBlock] {
        activeBlocks.filter { $0.rule.macOSBundleIDs.contains(bundleID) }
    }

    public func blocks(forHost host: String) -> [RuleBlock] {
        activeBlocks.filter { block in
            block.rule.blockedDomains.contains { domain in
                DomainMatcher.normalize(domain).map { DomainMatcher.host(host, matches: $0) } ?? false
            }
        }
    }

    public func blockedDomain(forHost host: String) -> String? {
        blockedDomains.first { DomainMatcher.host(host, matches: $0) }
    }
}

/// Evaluates blocking rules against current task completion state.
///
/// A rule blocks when it is active and its condition tasks are not met. A
/// condition task only causes blocking once it is past its due_at (or, with
/// no time window, as soon as it is incomplete). Pure function.
public func evaluateBlocking(
    rules: [BlockingRule],
    tasks: [BrainTask],
    now: Date = Date(),
    timeZone: TimeZone = .current,
    homePresence: HomePresence = .unconfigured
) -> BlockingState {
    let taskById = Dictionary(tasks.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
    var blocks: [RuleBlock] = []

    for rule in rules where rule.isActive {
        let unmet = conditionUnmetTaskIds(rule, taskById, now, timeZone, homePresence)
        if isConditionMet(rule, unmet) { continue }
        blocks.append(RuleBlock(rule: rule, unmetTaskIds: unmet))
    }
    return BlockingState(activeBlocks: blocks)
}

/// True if the rule's conditions are satisfied (blocking lifted). A rule with
/// no condition tasks is never satisfied — it always blocks while active.
private func isConditionMet(_ rule: BlockingRule, _ unmet: Set<String>) -> Bool {
    if rule.conditionTaskIds.isEmpty { return false }
    switch rule.conditionLogic {
    case "any": return unmet.count < rule.conditionTaskIds.count
    default: return unmet.isEmpty
    }
}

/// IDs of the rule's condition tasks that are currently unmet.
private func conditionUnmetTaskIds(
    _ rule: BlockingRule,
    _ taskById: [String: BrainTask],
    _ now: Date,
    _ timeZone: TimeZone,
    _ homePresence: HomePresence
) -> Set<String> {
    Set(rule.conditionTaskIds.filter { taskId in
        // Fail closed: a condition task we can't resolve (deleted/archived task,
        // or tasks not loaded yet) counts as unmet so blocking never silently lifts.
        guard let task = taskById[taskId] else { return true }
        if task.completedToday { return false }
        if task.homeOnlyBlocking && homePresence == .away { return false }
        let allowedDays = parseDays(JSONValue.parse(task.blockingDaysOfWeek) ?? .array([]))
        if !allowedDays.isEmpty {
            let dow = ZonedClock(timeZone: timeZone).date(of: now).dayOfWeek
            if !allowedDays.contains(dow) { return false }
        }
        let phase = computeTaskPhase(availableFrom: task.availableFrom, dueAt: task.dueAt, now: now, timeZone: timeZone)
        return phase == nil || phase == .pastDue
    })
}

/// Earliest future instant at which the evaluation could change purely due to
/// the clock (a condition task's due_at or available_from, or midnight). Used
/// to schedule a precise re-evaluation in addition to the 60s safety tick.
public func nextTimeBoundary(
    rules: [BlockingRule],
    tasks: [BrainTask],
    now: Date,
    timeZone: TimeZone = .current
) -> Date {
    let clock = ZonedClock(timeZone: timeZone)
    let today = clock.date(of: now)
    var candidates: [Date] = [clock.startOfDay(today.plusDays(1))]
    let enforced = Set(rules.filter(\.isActive).flatMap(\.conditionTaskIds))
    for task in tasks where enforced.contains(task.id) {
        for time in [TimeOfDay(task.availableFrom), TimeOfDay(task.dueAt)].compactMap({ $0 }) {
            let at = clock.instant(today, hour: time.hour, minute: time.minute)
            if at > now { candidates.append(at) }
        }
    }
    return candidates.min()!
}
