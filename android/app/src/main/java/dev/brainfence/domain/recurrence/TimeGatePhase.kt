package dev.brainfence.domain.recurrence

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The three phases of a time-gated task within a single day.
 */
enum class TimeGatePhase {
    /** Before available_from — task is not yet completable. */
    BEFORE_START,
    /** Between available_from and due_at — task is completable but not yet blocking. */
    ACTIVE,
    /** After due_at — task is still completable; if incomplete, it triggers blocking. */
    PAST_DUE,
}

/** Accepts single- and double-digit hours ("9:00" and "09:00"). */
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

/** Parses "H:mm" or "HH:mm" time strings tolerantly. Returns null for blank or malformed input. */
fun parseTaskTime(value: String?): LocalTime? {
    if (value.isNullOrBlank()) return null
    return runCatching { LocalTime.parse(value, TIME_FORMATTER) }.getOrNull()
}

/**
 * Computes which phase a task is currently in based on its availability window.
 *
 * @param availableFrom HH:MM string for when the task becomes completable, or null (always available)
 * @param dueAt         HH:MM string for when the task becomes overdue, or null (never overdue by time)
 * @return the current phase, or null if the task has no time constraints
 */
fun computeTaskPhase(
    availableFrom: String?,
    dueAt: String?,
    currentTime: Instant,
    timeZone: ZoneId = ZoneId.systemDefault(),
): TimeGatePhase? {
    val start = parseTaskTime(availableFrom)
    val end = parseTaskTime(dueAt)
    if (start == null && end == null) return null

    val now = currentTime.atZone(timeZone).toLocalTime()
    return when {
        start != null && now < start -> TimeGatePhase.BEFORE_START
        end != null && now >= end -> TimeGatePhase.PAST_DUE
        else -> TimeGatePhase.ACTIVE
    }
}

