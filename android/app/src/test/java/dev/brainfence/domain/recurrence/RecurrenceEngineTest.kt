package dev.brainfence.domain.recurrence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class RecurrenceEngineTest {

    private val zone = ZoneId.of("America/New_York")

    // Helper: create an Instant from a local date string at a given hour
    private fun instant(date: String, hour: Int = 12, minute: Int = 0): Instant =
        LocalDate.parse(date)
            .atTime(hour, minute)
            .atZone(zone)
            .toInstant()

    // -----------------------------------------------------------------------
    // One-off
    // -----------------------------------------------------------------------

    @Test
    fun `one-off task with no completion is due`() {
        val result = computeOccurrenceStatus(
            recurrenceType = null,
            recurrenceConfig = "{}",
            availableFrom = null,
            dueAt = null,
            lastCompletionAt = null,
            currentTime = instant("2026-04-16"),
            timeZone = zone,
        )
        assertEquals(OccurrenceStatus.Due, result)
    }

    @Test
    fun `one-off task already completed is completed`() {
        val result = computeOccurrenceStatus(
            recurrenceType = null,
            recurrenceConfig = "{}",
            availableFrom = null,
            dueAt = null,
            lastCompletionAt = instant("2026-04-15"),
            currentTime = instant("2026-04-16"),
            timeZone = zone,
        )
        assertEquals(OccurrenceStatus.Completed, result)
    }

    // -----------------------------------------------------------------------
    // Daily — every day
    // -----------------------------------------------------------------------

    @Test
    fun `daily task not completed today is due`() {
        val result = computeOccurrenceStatus(
            recurrenceType = "daily",
            recurrenceConfig = "{}",
            availableFrom = null,
            dueAt = null,
            lastCompletionAt = instant("2026-04-15"),
            currentTime = instant("2026-04-16"),
            timeZone = zone,
        )
        assertEquals(OccurrenceStatus.Due, result)
    }

    @Test
    fun `daily task completed today is completed`() {
        val now = instant("2026-04-16", 14)
        val result = computeOccurrenceStatus(
            recurrenceType = "daily",
            recurrenceConfig = "{}",
            availableFrom = null,
            dueAt = null,
            lastCompletionAt = instant("2026-04-16", 8),
            currentTime = now,
            timeZone = zone,
        )
        assertEquals(OccurrenceStatus.Completed, result)
    }

    @Test
    fun `daily task never completed is due`() {
        val result = computeOccurrenceStatus(
            recurrenceType = "daily",
            recurrenceConfig = "{}",
            availableFrom = null,
            dueAt = null,
            lastCompletionAt = null,
            currentTime = instant("2026-04-16"),
            timeZone = zone,
        )
        assertEquals(OccurrenceStatus.Due, result)
    }

    // -----------------------------------------------------------------------
    // Daily — specific days
    // -----------------------------------------------------------------------

    @Test
    fun `daily with specific days - today is an allowed day and not completed`() {
        // 2026-04-16 is a Thursday
        val result = computeOccurrenceStatus(
            recurrenceType = "daily",
            recurrenceConfig = """{"days": ["mon", "thu", "fri"]}""",
            availableFrom = null,
            dueAt = null,
            lastCompletionAt = null,
            currentTime = instant("2026-04-16"), // Thursday
            timeZone = zone,
        )
        assertEquals(OccurrenceStatus.Due, result)
    }

    @Test
    fun `daily with specific days - today is not an allowed day`() {
        // 2026-04-15 is a Wednesday
        val result = computeOccurrenceStatus(
            recurrenceType = "daily",
            recurrenceConfig = """{"days": ["mon", "thu", "fri"]}""",
            availableFrom = null,
            dueAt = null,
            lastCompletionAt = null,
            currentTime = instant("2026-04-15"), // Wednesday
            timeZone = zone,
        )
        assertTrue(result is OccurrenceStatus.Upcoming)
        // Next allowed day is Thursday (2026-04-16)
        val upcoming = result as OccurrenceStatus.Upcoming
        val nextDate = upcoming.nextDueAt.atZone(zone).toLocalDate()
        assertEquals(LocalDate.parse("2026-04-16"), nextDate)
    }

    // -----------------------------------------------------------------------
    // Weekly
    // -----------------------------------------------------------------------

    @Test
    fun `weekly task - today is the target day and not completed`() {
        // 2026-04-16 is a Thursday
        val result = computeOccurrenceStatus(
            recurrenceType = "weekly",
            recurrenceConfig = """{"day": "thu"}""",
            availableFrom = null,
            dueAt = null,
            lastCompletionAt = null,
            currentTime = instant("2026-04-16"), // Thursday
            timeZone = zone,
        )
        assertEquals(OccurrenceStatus.Due, result)
    }

    @Test
    fun `weekly task - today is the target day and completed`() {
        val result = computeOccurrenceStatus(
            recurrenceType = "weekly",
            recurrenceConfig = """{"day": "thu"}""",
            availableFrom = null,
            dueAt = null,
            lastCompletionAt = instant("2026-04-16", 8),
            currentTime = instant("2026-04-16", 14),
            timeZone = zone,
        )
        assertEquals(OccurrenceStatus.Completed, result)
    }

    @Test
    fun `weekly task - today is not the target day`() {
        // 2026-04-15 is Wednesday, target is Thursday
        val result = computeOccurrenceStatus(
            recurrenceType = "weekly",
            recurrenceConfig = """{"day": "thu"}""",
            availableFrom = null,
            dueAt = null,
            lastCompletionAt = null,
            currentTime = instant("2026-04-15"), // Wednesday
            timeZone = zone,
        )
        assertTrue(result is OccurrenceStatus.Upcoming)
        val nextDate = (result as OccurrenceStatus.Upcoming).nextDueAt.atZone(zone).toLocalDate()
        assertEquals(LocalDate.parse("2026-04-16"), nextDate)
    }

    // -----------------------------------------------------------------------
    // Monthly
    // -----------------------------------------------------------------------

    @Test
    fun `monthly task - today is the target day`() {
        // 2nd Wednesday of April 2026 = April 8
        val result = computeOccurrenceStatus(
            recurrenceType = "monthly",
            recurrenceConfig = """{"week": 2, "day": "wed"}""",
            availableFrom = null,
            dueAt = null,
            lastCompletionAt = null,
            currentTime = instant("2026-04-08"), // 2nd Wednesday
            timeZone = zone,
        )
        assertEquals(OccurrenceStatus.Due, result)
    }

    @Test
    fun `monthly task - before the target day this month`() {
        val result = computeOccurrenceStatus(
            recurrenceType = "monthly",
            recurrenceConfig = """{"week": 2, "day": "wed"}""",
            availableFrom = null,
            dueAt = null,
            lastCompletionAt = null,
            currentTime = instant("2026-04-06"), // Monday before 2nd Wed
            timeZone = zone,
        )
        assertTrue(result is OccurrenceStatus.Upcoming)
        val nextDate = (result as OccurrenceStatus.Upcoming).nextDueAt.atZone(zone).toLocalDate()
        assertEquals(LocalDate.parse("2026-04-08"), nextDate)
    }

    @Test
    fun `monthly task - after the target day this month`() {
        val result = computeOccurrenceStatus(
            recurrenceType = "monthly",
            recurrenceConfig = """{"week": 2, "day": "wed"}""",
            availableFrom = null,
            dueAt = null,
            lastCompletionAt = null,
            currentTime = instant("2026-04-16"), // Past 2nd Wed
            timeZone = zone,
        )
        assertTrue(result is OccurrenceStatus.Upcoming)
        // 2nd Wednesday of May 2026 = May 13
        val nextDate = (result as OccurrenceStatus.Upcoming).nextDueAt.atZone(zone).toLocalDate()
        assertEquals(LocalDate.parse("2026-05-13"), nextDate)
    }

    // -----------------------------------------------------------------------
    // Interval
    // -----------------------------------------------------------------------

    @Test
    fun `interval task - never completed is due`() {
        val result = computeOccurrenceStatus(
            recurrenceType = "interval",
            recurrenceConfig = """{"minutes": 480}""",
            availableFrom = null,
            dueAt = null,
            lastCompletionAt = null,
            currentTime = instant("2026-04-16"),
            timeZone = zone,
        )
        assertEquals(OccurrenceStatus.Due, result)
    }

    @Test
    fun `interval task - interval has elapsed`() {
        val result = computeOccurrenceStatus(
            recurrenceType = "interval",
            recurrenceConfig = """{"minutes": 480}""",
            availableFrom = null,
            dueAt = null,
            lastCompletionAt = instant("2026-04-16", 0),
            currentTime = instant("2026-04-16", 9), // 9 hours later > 8 hours
            timeZone = zone,
        )
        assertEquals(OccurrenceStatus.Due, result)
    }

    @Test
    fun `interval task - interval has not elapsed`() {
        val result = computeOccurrenceStatus(
            recurrenceType = "interval",
            recurrenceConfig = """{"minutes": 480}""",
            availableFrom = null,
            dueAt = null,
            lastCompletionAt = instant("2026-04-16", 6),
            currentTime = instant("2026-04-16", 10), // 4 hours later < 8 hours
            timeZone = zone,
        )
        assertTrue(result is OccurrenceStatus.Upcoming)
        val expected = instant("2026-04-16", 14) // 6 + 8 = 14:00
        assertEquals(expected, (result as OccurrenceStatus.Upcoming).nextDueAt)
    }

    @Test
    fun `interval task - outside active window`() {
        val result = computeOccurrenceStatus(
            recurrenceType = "interval",
            recurrenceConfig = """{"minutes": 60, "active_days": ["mon", "tue", "wed", "thu", "fri"], "active_start": "09:00", "active_end": "17:00"}""",
            availableFrom = null,
            dueAt = null,
            lastCompletionAt = null,
            currentTime = instant("2026-04-18", 20), // Saturday 8 PM — not an active day
            timeZone = zone,
        )
        assertEquals(OccurrenceStatus.NotDue, result)
    }

    @Test
    fun `interval task - outside active hours`() {
        // 2026-04-16 is a Thursday (active day), but at 20:00 (outside 09-17)
        val result = computeOccurrenceStatus(
            recurrenceType = "interval",
            recurrenceConfig = """{"minutes": 60, "active_start": "09:00", "active_end": "17:00"}""",
            availableFrom = null,
            dueAt = null,
            lastCompletionAt = null,
            currentTime = instant("2026-04-16", 20),
            timeZone = zone,
        )
        assertEquals(OccurrenceStatus.NotDue, result)
    }

    @Test
    fun `interval task - inside active window and due`() {
        val result = computeOccurrenceStatus(
            recurrenceType = "interval",
            recurrenceConfig = """{"minutes": 60, "active_days": ["mon", "tue", "wed", "thu", "fri"], "active_start": "09:00", "active_end": "17:00"}""",
            availableFrom = null,
            dueAt = null,
            lastCompletionAt = null,
            currentTime = instant("2026-04-16", 12), // Thursday noon
            timeZone = zone,
        )
        assertEquals(OccurrenceStatus.Due, result)
    }

    // -----------------------------------------------------------------------
    // Availability window (available_from / due_at)
    // -----------------------------------------------------------------------

    @Test
    fun `available_from - before start is not due`() {
        val result = computeOccurrenceStatus(
            recurrenceType = "daily",
            recurrenceConfig = "{}",
            availableFrom = "07:00",
            dueAt = "10:00",
            lastCompletionAt = null,
            currentTime = instant("2026-04-16", 6), // 6 AM, before 7 AM available_from
            timeZone = zone,
        )
        assertEquals(OccurrenceStatus.NotDue, result)
    }

    @Test
    fun `available_from - inside window is due`() {
        val result = computeOccurrenceStatus(
            recurrenceType = "daily",
            recurrenceConfig = "{}",
            availableFrom = "07:00",
            dueAt = "10:00",
            lastCompletionAt = null,
            currentTime = instant("2026-04-16", 8), // 8 AM, between 7 and 10
            timeZone = zone,
        )
        assertEquals(OccurrenceStatus.Due, result)
    }

    @Test
    fun `available_from - after due_at is still due (completable)`() {
        val result = computeOccurrenceStatus(
            recurrenceType = "daily",
            recurrenceConfig = "{}",
            availableFrom = "07:00",
            dueAt = "10:00",
            lastCompletionAt = null,
            currentTime = instant("2026-04-16", 15), // 3 PM, past due_at
            timeZone = zone,
        )
        assertEquals(OccurrenceStatus.Due, result)
    }

    @Test
    fun `available_from - inside window and already completed today`() {
        val result = computeOccurrenceStatus(
            recurrenceType = "daily",
            recurrenceConfig = "{}",
            availableFrom = "07:00",
            dueAt = "10:00",
            lastCompletionAt = instant("2026-04-16", 8),
            currentTime = instant("2026-04-16", 9),
            timeZone = zone,
        )
        assertEquals(OccurrenceStatus.Completed, result)
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    fun `unknown recurrence type returns not due`() {
        val result = computeOccurrenceStatus(
            recurrenceType = "unknown_future_type",
            recurrenceConfig = "{}",
            availableFrom = null,
            dueAt = null,
            lastCompletionAt = null,
            currentTime = instant("2026-04-16"),
            timeZone = zone,
        )
        assertEquals(OccurrenceStatus.NotDue, result)
    }

    @Test
    fun `daily with empty days array treated as every day`() {
        // No "days" key in config = every day
        val result = computeOccurrenceStatus(
            recurrenceType = "daily",
            recurrenceConfig = "{}",
            availableFrom = null,
            dueAt = null,
            lastCompletionAt = null,
            currentTime = instant("2026-04-16"),
            timeZone = zone,
        )
        assertEquals(OccurrenceStatus.Due, result)
    }
}
