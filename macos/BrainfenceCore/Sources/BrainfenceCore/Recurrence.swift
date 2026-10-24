import Foundation

// Port of android/…/domain/recurrence (RecurrenceEngine.kt, TimeGatePhase.kt,
// OccurrenceStatus.kt). Keep the two implementations behaviourally identical:
// the same task must show the same status on every device.

/// The three phases of a time-gated task within a single day.
public enum TimeGatePhase: Sendable, Equatable {
    /// Before available_from — task is not yet completable.
    case beforeStart
    /// Between available_from and due_at — completable, not yet blocking.
    case active
    /// After due_at — still completable; if incomplete, it triggers blocking.
    case pastDue
}

/// Which phase a task is in, or nil if it has no time constraints.
public func computeTaskPhase(
    availableFrom: String?,
    dueAt: String?,
    now: Date,
    timeZone: TimeZone = .current
) -> TimeGatePhase? {
    let start = TimeOfDay(availableFrom)
    let end = TimeOfDay(dueAt)
    if start == nil && end == nil { return nil }

    let nowSeconds = ZonedClock(timeZone: timeZone).secondsIntoDay(of: now)
    if let start, nowSeconds < start.seconds { return .beforeStart }
    if let end, nowSeconds >= end.seconds { return .pastDue }
    return .active
}

public enum OccurrenceStatus: Sendable, Equatable {
    case due
    case completed
    case notDue
    case upcoming(Date)
}

/// Computes the occurrence status of a task.
///
/// - Parameters:
///   - recurrenceType: nil (one-off), "daily", "weekly", "monthly", "interval"
///   - recurrenceConfig: JSON text with type-specific configuration
///   - availableFrom / dueAt: "HH:MM" availability window, or nil
///   - lastCompletionAt: the most recent completion, or nil if never completed
public func computeOccurrenceStatus(
    recurrenceType: String?,
    recurrenceConfig: String,
    availableFrom: String?,
    dueAt: String?,
    lastCompletionAt: Date?,
    now: Date,
    timeZone: TimeZone = .current
) -> OccurrenceStatus {
    if computeTaskPhase(availableFrom: availableFrom, dueAt: dueAt, now: now, timeZone: timeZone) == .beforeStart {
        return .notDue
    }

    let clock = ZonedClock(timeZone: timeZone)
    let config = JSONValue.parse(recurrenceConfig) ?? .object([:])

    switch recurrenceType {
    case nil:
        return lastCompletionAt != nil ? .completed : .due
    case "daily":
        return dailyStatus(config, lastCompletionAt, now, clock)
    case "weekly":
        return weeklyStatus(config, lastCompletionAt, now, clock)
    case "monthly":
        return monthlyStatus(config, lastCompletionAt, now, clock)
    case "interval":
        return intervalStatus(config, lastCompletionAt, now, clock)
    default:
        return .notDue
    }
}

// MARK: - Daily

private func dailyStatus(_ config: JSONValue, _ last: Date?, _ now: Date, _ clock: ZonedClock) -> OccurrenceStatus {
    let today = clock.date(of: now)
    if let days = config["days"] {
        let allowed = parseDays(days)
        if !allowed.contains(today.dayOfWeek) {
            return nextAllowedDay(today, allowed, clock)
        }
    }
    return completedOn(last, today, clock) ? .completed : .due
}

// MARK: - Weekly

private func weeklyStatus(_ config: JSONValue, _ last: Date?, _ now: Date, _ clock: ZonedClock) -> OccurrenceStatus {
    guard let targetDay = config["day"]?.stringValue.flatMap(Weekday.init(abbreviation:)) else { return .notDue }
    let interval = config["interval"]?.intValue ?? 1
    let today = clock.date(of: now)
    let dueDate = findWeeklyDueDate(today: today, targetDay: targetDay, interval: interval)

    if today == dueDate {
        return completedOn(last, today, clock) ? .completed : .due
    }
    return .upcoming(clock.startOfDay(dueDate))
}

/// The due date of the current or next weekly occurrence. With interval > 1,
/// epoch-week modular arithmetic decides which weeks are "on" (as on Android).
func findWeeklyDueDate(today: CivilDate, targetDay: Weekday, interval: Int) -> CivilDate {
    let thisWeekTarget = today.previousOrSame(.monday).nextOrSame(targetDay)

    if interval <= 1 {
        return today <= thisWeekTarget ? thisWeekTarget : today.next(targetDay)
    }

    // Kotlin Long division/remainder truncate toward zero; epoch days here are positive.
    let epochWeek = today.epochDay / 7
    let targetWeekOffset = epochWeek % interval
    if targetWeekOffset == 0 && today <= thisWeekTarget {
        return thisWeekTarget
    }
    let weeksUntilNext = (interval - targetWeekOffset) % interval
    let nextWeekStart = today.plusDays(weeksUntilNext * 7 - today.dayOfWeek.ordinal)
    return nextWeekStart.nextOrSame(targetDay)
}

// MARK: - Monthly

private func monthlyStatus(_ config: JSONValue, _ last: Date?, _ now: Date, _ clock: ZonedClock) -> OccurrenceStatus {
    guard let week = config["week"]?.intValue,
          let targetDay = config["day"]?.stringValue.flatMap(Weekday.init(abbreviation:))
    else { return .notDue }

    let today = clock.date(of: now)
    let dueThisMonth = nthDayOfWeekInMonth(year: today.year, month: today.month, n: week, day: targetDay)

    if today == dueThisMonth {
        return completedOn(last, today, clock) ? .completed : .due
    }
    if today < dueThisMonth {
        return .upcoming(clock.startOfDay(dueThisMonth))
    }
    let nextMonth = today.plusMonths(1)
    return .upcoming(clock.startOfDay(
        nthDayOfWeekInMonth(year: nextMonth.year, month: nextMonth.month, n: week, day: targetDay)
    ))
}

/// The nth occurrence (1-indexed) of a weekday in a month.
func nthDayOfWeekInMonth(year: Int, month: Int, n: Int, day: Weekday) -> CivilDate {
    CivilDate(year: year, month: month, day: 1).nextOrSame(day).plusDays((n - 1) * 7)
}

// MARK: - Interval

private func intervalStatus(_ config: JSONValue, _ last: Date?, _ now: Date, _ clock: ZonedClock) -> OccurrenceStatus {
    guard let minutes = config["minutes"]?.intValue else { return .notDue }

    let hasActiveWindow = config["active_days"] != nil || config["active_start"] != nil
    if hasActiveWindow && !inActiveWindow(config, now, clock) {
        return .notDue
    }

    guard let last else { return .due }
    let nextDue = last.addingTimeInterval(TimeInterval(minutes * 60))
    return now >= nextDue ? .due : .upcoming(nextDue)
}

private func inActiveWindow(_ config: JSONValue, _ now: Date, _ clock: ZonedClock) -> Bool {
    if let days = config["active_days"] {
        if !parseDays(days).contains(clock.date(of: now).dayOfWeek) { return false }
    }
    if let start = TimeOfDay(config["active_start"]?.stringValue),
       let end = TimeOfDay(config["active_end"]?.stringValue) {
        let s = clock.secondsIntoDay(of: now)
        if s < start.seconds || s >= end.seconds { return false }
    }
    return true
}

// MARK: - Next occurrence

/// The next occurrence instant for a recurring task completed in its current
/// window. Returns nil for one-off tasks.
public func computeNextOccurrence(
    recurrenceType: String?,
    recurrenceConfig: String,
    lastCompletionAt: Date?,
    now: Date,
    timeZone: TimeZone = .current
) -> Date? {
    guard let recurrenceType else { return nil }
    let clock = ZonedClock(timeZone: timeZone)
    let config = JSONValue.parse(recurrenceConfig) ?? .object([:])
    let today = clock.date(of: now)

    switch recurrenceType {
    case "daily":
        if let days = config["days"] {
            let allowed = parseDays(days)
            for offset in 1...7 {
                let candidate = today.plusDays(offset)
                if allowed.contains(candidate.dayOfWeek) { return clock.startOfDay(candidate) }
            }
            return nil
        }
        return clock.startOfDay(today.plusDays(1))
    case "weekly":
        guard let targetDay = config["day"]?.stringValue.flatMap(Weekday.init(abbreviation:)) else { return nil }
        let interval = config["interval"]?.intValue ?? 1
        return clock.startOfDay(findWeeklyDueDate(today: today.plusDays(1), targetDay: targetDay, interval: interval))
    case "monthly":
        guard let week = config["week"]?.intValue,
              let targetDay = config["day"]?.stringValue.flatMap(Weekday.init(abbreviation:)) else { return nil }
        let dueThisMonth = nthDayOfWeekInMonth(year: today.year, month: today.month, n: week, day: targetDay)
        if today < dueThisMonth { return clock.startOfDay(dueThisMonth) }
        let nextMonth = today.plusMonths(1)
        return clock.startOfDay(nthDayOfWeekInMonth(year: nextMonth.year, month: nextMonth.month, n: week, day: targetDay))
    case "interval":
        guard let last = lastCompletionAt else { return now }
        guard let minutes = config["minutes"]?.intValue else { return nil }
        return last.addingTimeInterval(TimeInterval(minutes * 60))
    default:
        return nil
    }
}

// MARK: - Helpers

private func completedOn(_ completion: Date?, _ date: CivilDate, _ clock: ZonedClock) -> Bool {
    guard let completion else { return false }
    return clock.date(of: completion) == date
}

private func nextAllowedDay(_ today: CivilDate, _ allowed: Set<Weekday>, _ clock: ZonedClock) -> OccurrenceStatus {
    for offset in 1...7 {
        let candidate = today.plusDays(offset)
        if allowed.contains(candidate.dayOfWeek) { return .upcoming(clock.startOfDay(candidate)) }
    }
    return .notDue
}

func parseDays(_ value: JSONValue) -> Set<Weekday> {
    Set((value.arrayValue ?? []).compactMap { $0.stringValue.flatMap(Weekday.init(abbreviation:)) })
}
