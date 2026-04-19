package dev.brainfence.domain.recurrence

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

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
    if (availableFrom == null && dueAt == null) return null

    val now = currentTime.atZone(timeZone).toLocalTime()
    val start = availableFrom?.let { LocalTime.parse(it) }
    val end = dueAt?.let { LocalTime.parse(it) }

    return when {
        start != null && now < start -> TimeGatePhase.BEFORE_START
        end != null && now >= end -> TimeGatePhase.PAST_DUE
        else -> TimeGatePhase.ACTIVE
    }
}
