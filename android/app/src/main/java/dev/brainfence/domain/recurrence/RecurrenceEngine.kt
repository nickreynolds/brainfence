package dev.brainfence.domain.recurrence

import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * Pure function that computes the occurrence status of a task.
 *
 * @param recurrenceType  null (one-off), "daily", "weekly", "monthly", "interval"
 * @param recurrenceConfig JSON string with type-specific configuration
 * @param availableFrom    HH:MM string — when the task becomes completable, or null
 * @param dueAt            HH:MM string — when the task becomes overdue, or null
 * @param lastCompletionAt  the most recent completion timestamp, or null if never completed
 * @param currentTime       the current instant
 * @param timeZone          the device/user timezone for day-boundary calculations
 */
fun computeOccurrenceStatus(
    recurrenceType: String?,
    recurrenceConfig: String,
    availableFrom: String?,
    dueAt: String?,
    lastCompletionAt: Instant?,
    currentTime: Instant,
    timeZone: ZoneId = ZoneId.systemDefault(),
): OccurrenceStatus {
    // Availability check: if the task has an available_from and current time is before it,
    // the task is not yet actionable today.
    val phase = computeTaskPhase(availableFrom, dueAt, currentTime, timeZone)
    if (phase == TimeGatePhase.BEFORE_START) {
        return OccurrenceStatus.NotDue
    }

    return when (recurrenceType) {
        null -> oneOffStatus(lastCompletionAt)
        "daily" -> dailyStatus(recurrenceConfig, lastCompletionAt, currentTime, timeZone)
        "weekly" -> weeklyStatus(recurrenceConfig, lastCompletionAt, currentTime, timeZone)
        "monthly" -> monthlyStatus(recurrenceConfig, lastCompletionAt, currentTime, timeZone)
        "interval" -> intervalStatus(recurrenceConfig, lastCompletionAt, currentTime, timeZone)
        else -> OccurrenceStatus.NotDue
    }
}

// ---------------------------------------------------------------------------
// One-off
// ---------------------------------------------------------------------------

private fun oneOffStatus(lastCompletionAt: Instant?): OccurrenceStatus =
    if (lastCompletionAt != null) OccurrenceStatus.Completed else OccurrenceStatus.Due

// ---------------------------------------------------------------------------
// Daily
// ---------------------------------------------------------------------------

private fun dailyStatus(
    configJson: String,
    lastCompletionAt: Instant?,
    currentTime: Instant,
    timeZone: ZoneId,
): OccurrenceStatus {
    val config = JSONObject(configJson)
    val today = currentTime.atZone(timeZone).toLocalDate()

    // If specific days are configured, check if today is one of them
    if (config.has("days")) {
        val days = config.getJSONArray("days")
        val allowedDays = (0 until days.length()).map { parseDayOfWeek(days.getString(it)) }.toSet()
        if (today.dayOfWeek !in allowedDays) {
            return nextAllowedDay(today, allowedDays, timeZone)
        }
    }

    return if (completedOnDate(lastCompletionAt, today, timeZone)) {
        OccurrenceStatus.Completed
    } else {
        OccurrenceStatus.Due
    }
}

// ---------------------------------------------------------------------------
// Weekly
// ---------------------------------------------------------------------------

private fun weeklyStatus(
    configJson: String,
    lastCompletionAt: Instant?,
    currentTime: Instant,
    timeZone: ZoneId,
): OccurrenceStatus {
    val config = JSONObject(configJson)
    val today = currentTime.atZone(timeZone).toLocalDate()
    val targetDay = parseDayOfWeek(config.getString("day"))
    val interval = if (config.has("interval")) config.getInt("interval") else 1

    // Find the due date for this occurrence
    val dueDate = findWeeklyDueDate(today, targetDay, interval)

    return if (today == dueDate) {
        if (completedOnDate(lastCompletionAt, today, timeZone)) {
            OccurrenceStatus.Completed
        } else {
            OccurrenceStatus.Due
        }
    } else {
        OccurrenceStatus.Upcoming(dueDate.atStartOfDay(timeZone).toInstant())
    }
}

/**
 * For weekly recurrence: finds the due date for the current or next occurrence.
 * With interval > 1, we use epoch week counting to determine which weeks are "on".
 */
private fun findWeeklyDueDate(today: LocalDate, targetDay: DayOfWeek, interval: Int): LocalDate {
    // Current week's target day
    val thisWeekTarget = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .with(TemporalAdjusters.nextOrSame(targetDay))

    if (interval == 1) {
        return if (today <= thisWeekTarget) thisWeekTarget
        else today.with(TemporalAdjusters.next(targetDay))
    }

    // For intervals > 1, use epoch-week modular arithmetic
    val epochWeek = today.toEpochDay() / 7
    val targetWeekOffset = epochWeek % interval
    return if (targetWeekOffset == 0L && today <= thisWeekTarget) {
        thisWeekTarget
    } else {
        val weeksUntilNext = (interval - targetWeekOffset) % interval
        val nextWeekStart = today.plusDays(weeksUntilNext * 7 - today.dayOfWeek.ordinal.toLong())
        nextWeekStart.with(TemporalAdjusters.nextOrSame(targetDay))
    }
}

// ---------------------------------------------------------------------------
// Monthly
// ---------------------------------------------------------------------------

private fun monthlyStatus(
    configJson: String,
    lastCompletionAt: Instant?,
    currentTime: Instant,
    timeZone: ZoneId,
): OccurrenceStatus {
    val config = JSONObject(configJson)
    val today = currentTime.atZone(timeZone).toLocalDate()
    val targetWeek = config.getInt("week")     // 1-based: 1st, 2nd, 3rd, 4th
    val targetDay = parseDayOfWeek(config.getString("day"))

    val dueThisMonth = nthDayOfWeekInMonth(today.year, today.monthValue, targetWeek, targetDay)

    return when {
        today == dueThisMonth -> {
            if (completedOnDate(lastCompletionAt, today, timeZone)) {
                OccurrenceStatus.Completed
            } else {
                OccurrenceStatus.Due
            }
        }
        today < dueThisMonth -> {
            OccurrenceStatus.Upcoming(dueThisMonth.atStartOfDay(timeZone).toInstant())
        }
        else -> {
            // Past this month's occurrence — find next month's
            val nextMonth = today.plusMonths(1)
            val dueNextMonth = nthDayOfWeekInMonth(nextMonth.year, nextMonth.monthValue, targetWeek, targetDay)
            OccurrenceStatus.Upcoming(dueNextMonth.atStartOfDay(timeZone).toInstant())
        }
    }
}

/** Returns the nth occurrence of a day-of-week in a given month (1-indexed). */
private fun nthDayOfWeekInMonth(year: Int, month: Int, n: Int, day: DayOfWeek): LocalDate {
    val first = LocalDate.of(year, month, 1).with(TemporalAdjusters.firstInMonth(day))
    return first.plusWeeks((n - 1).toLong())
}

// ---------------------------------------------------------------------------
// Interval
// ---------------------------------------------------------------------------

private fun intervalStatus(
    configJson: String,
    lastCompletionAt: Instant?,
    currentTime: Instant,
    timeZone: ZoneId,
): OccurrenceStatus {
    val config = JSONObject(configJson)
    val intervalMinutes = config.getLong("minutes")

    // Active window constraints
    val hasActiveWindow = config.has("active_days") || config.has("active_start")
    if (hasActiveWindow && !inActiveWindow(config, currentTime, timeZone)) {
        return OccurrenceStatus.NotDue
    }

    if (lastCompletionAt == null) return OccurrenceStatus.Due

    val nextDue = lastCompletionAt.plusSeconds(intervalMinutes * 60)
    return if (currentTime >= nextDue) {
        OccurrenceStatus.Due
    } else {
        OccurrenceStatus.Upcoming(nextDue)
    }
}

private fun inActiveWindow(config: JSONObject, currentTime: Instant, timeZone: ZoneId): Boolean {
    val zoned = currentTime.atZone(timeZone)

    if (config.has("active_days")) {
        val days = config.getJSONArray("active_days")
        val allowedDays = (0 until days.length()).map { parseDayOfWeek(days.getString(it)) }.toSet()
        if (zoned.dayOfWeek !in allowedDays) return false
    }

    if (config.has("active_start") && config.has("active_end")) {
        val start = LocalTime.parse(config.getString("active_start"))
        val end = LocalTime.parse(config.getString("active_end"))
        val now = zoned.toLocalTime()
        if (now < start || now >= end) return false
    }

    return true
}

// ---------------------------------------------------------------------------
// Next occurrence (for completed recurring tasks)
// ---------------------------------------------------------------------------

/**
 * Returns the next occurrence instant for a recurring task that has been completed
 * in the current window. Returns null for one-off tasks.
 */
fun computeNextOccurrence(
    recurrenceType: String?,
    recurrenceConfig: String,
    lastCompletionAt: Instant?,
    currentTime: Instant,
    timeZone: ZoneId = ZoneId.systemDefault(),
): Instant? {
    if (recurrenceType == null) return null

    val today = currentTime.atZone(timeZone).toLocalDate()

    return when (recurrenceType) {
        "daily" -> {
            val config = JSONObject(recurrenceConfig)
            if (config.has("days")) {
                val days = config.getJSONArray("days")
                val allowedDays = (0 until days.length()).map { parseDayOfWeek(days.getString(it)) }.toSet()
                (1..7).firstNotNullOfOrNull { offset ->
                    val candidate = today.plusDays(offset.toLong())
                    if (candidate.dayOfWeek in allowedDays) candidate.atStartOfDay(timeZone).toInstant() else null
                }
            } else {
                today.plusDays(1).atStartOfDay(timeZone).toInstant()
            }
        }
        "weekly" -> {
            val config = JSONObject(recurrenceConfig)
            val targetDay = parseDayOfWeek(config.getString("day"))
            val interval = if (config.has("interval")) config.getInt("interval") else 1
            val tomorrow = today.plusDays(1)
            findWeeklyDueDate(tomorrow, targetDay, interval).atStartOfDay(timeZone).toInstant()
        }
        "monthly" -> {
            val config = JSONObject(recurrenceConfig)
            val targetWeek = config.getInt("week")
            val targetDay = parseDayOfWeek(config.getString("day"))
            val dueThisMonth = nthDayOfWeekInMonth(today.year, today.monthValue, targetWeek, targetDay)
            val nextDue = if (today < dueThisMonth) dueThisMonth else {
                val nextMonth = today.plusMonths(1)
                nthDayOfWeekInMonth(nextMonth.year, nextMonth.monthValue, targetWeek, targetDay)
            }
            nextDue.atStartOfDay(timeZone).toInstant()
        }
        "interval" -> {
            if (lastCompletionAt == null) return currentTime
            val config = JSONObject(recurrenceConfig)
            val intervalMinutes = config.getLong("minutes")
            lastCompletionAt.plusSeconds(intervalMinutes * 60)
        }
        else -> null
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun completedOnDate(completionAt: Instant?, date: LocalDate, timeZone: ZoneId): Boolean {
    if (completionAt == null) return false
    return completionAt.atZone(timeZone).toLocalDate() == date
}

private fun nextAllowedDay(
    today: LocalDate,
    allowedDays: Set<DayOfWeek>,
    timeZone: ZoneId,
): OccurrenceStatus {
    for (offset in 1..7) {
        val candidate = today.plusDays(offset.toLong())
        if (candidate.dayOfWeek in allowedDays) {
            return OccurrenceStatus.Upcoming(candidate.atStartOfDay(timeZone).toInstant())
        }
    }
    // Should never reach here if allowedDays is non-empty
    return OccurrenceStatus.NotDue
}

private fun parseDayOfWeek(abbrev: String): DayOfWeek = when (abbrev.lowercase()) {
    "mon" -> DayOfWeek.MONDAY
    "tue" -> DayOfWeek.TUESDAY
    "wed" -> DayOfWeek.WEDNESDAY
    "thu" -> DayOfWeek.THURSDAY
    "fri" -> DayOfWeek.FRIDAY
    "sat" -> DayOfWeek.SATURDAY
    "sun" -> DayOfWeek.SUNDAY
    else -> error("Unknown day abbreviation: $abbrev")
}
