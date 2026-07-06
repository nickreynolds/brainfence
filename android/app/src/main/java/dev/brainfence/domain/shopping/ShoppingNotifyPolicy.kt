package dev.brainfence.domain.shopping

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * When a shopping-list geofence may fire its reminder notification.
 *
 * Parsed from the task's verification_config `notify_window`:
 * `{"days": ["mon","tue"], "start": "15:00", "end": "21:00"}`.
 */
data class NotifyWindow(
    /** Days of week ("mon".."sun") the notification may fire. Empty = every day. */
    val days: Set<String>,
    /** Earliest local time the notification may fire, or null (from midnight). */
    val start: LocalTime?,
    /** Latest local time the notification may fire, or null (until midnight). */
    val end: LocalTime?,
)

/** Minimum gap between notifications when no window is configured. */
val NO_WINDOW_RENOTIFY_GAP: Duration = Duration.ofHours(4)

private val DAY_NAMES = mapOf(
    DayOfWeek.MONDAY to "mon",
    DayOfWeek.TUESDAY to "tue",
    DayOfWeek.WEDNESDAY to "wed",
    DayOfWeek.THURSDAY to "thu",
    DayOfWeek.FRIDAY to "fri",
    DayOfWeek.SATURDAY to "sat",
    DayOfWeek.SUNDAY to "sun",
)

/**
 * Decides whether a shopping reminder should fire on geofence entry.
 *
 * - Outside the configured days or time window → never.
 * - Within a window → at most once per window occurrence per day
 *   (GPS jitter near the geofence can produce repeated ENTER events).
 * - No window configured → at most once per [NO_WINDOW_RENOTIFY_GAP].
 *
 * The caller is responsible for checking that the list has at least one open item.
 */
fun shouldNotifyShopping(
    window: NotifyWindow?,
    lastNotifiedAt: Instant?,
    now: ZonedDateTime,
): Boolean {
    if (window != null) {
        if (window.days.isNotEmpty() && DAY_NAMES[now.dayOfWeek] !in window.days) return false
        val time = now.toLocalTime()
        if (window.start != null && time < window.start) return false
        if (window.end != null && time > window.end) return false
    }

    if (lastNotifiedAt == null) return true

    val hasTimeWindow = window?.start != null || window?.end != null
    return if (hasTimeWindow) {
        // Already notified since this window opened today → suppress.
        val windowStartToday = now.toLocalDate()
            .atTime(window?.start ?: LocalTime.MIDNIGHT)
            .atZone(now.zone)
            .toInstant()
        lastNotifiedAt < windowStartToday
    } else {
        Duration.between(lastNotifiedAt, now.toInstant()) >= NO_WINDOW_RENOTIFY_GAP
    }
}
