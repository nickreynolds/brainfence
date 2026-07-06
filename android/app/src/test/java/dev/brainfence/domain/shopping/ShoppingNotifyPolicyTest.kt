package dev.brainfence.domain.shopping

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ShoppingNotifyPolicyTest {

    private val zone = ZoneId.of("America/New_York")

    // 2026-04-16 is a Thursday
    private fun at(hour: Int, minute: Int = 0, date: String = "2026-04-16"): ZonedDateTime =
        LocalDate.parse(date).atTime(hour, minute).atZone(zone)

    private val commuteWindow = NotifyWindow(
        days = setOf("mon", "tue", "wed", "thu", "fri"),
        start = LocalTime.of(15, 0),
        end = LocalTime.of(21, 0),
    )

    // --- Window gating ---

    @Test
    fun `fires inside window with no prior notification`() {
        assertTrue(shouldNotifyShopping(commuteWindow, null, at(17, 30)))
    }

    @Test
    fun `suppressed before window opens - morning commute stays quiet`() {
        assertFalse(shouldNotifyShopping(commuteWindow, null, at(8, 45)))
    }

    @Test
    fun `suppressed after window closes`() {
        assertFalse(shouldNotifyShopping(commuteWindow, null, at(22, 0)))
    }

    @Test
    fun `fires exactly at window boundaries`() {
        assertTrue(shouldNotifyShopping(commuteWindow, null, at(15, 0)))
        assertTrue(shouldNotifyShopping(commuteWindow, null, at(21, 0)))
    }

    @Test
    fun `suppressed on a day outside the configured days`() {
        // 2026-04-18 is a Saturday
        assertFalse(shouldNotifyShopping(commuteWindow, null, at(17, 0, date = "2026-04-18")))
    }

    @Test
    fun `empty days means every day`() {
        val window = commuteWindow.copy(days = emptySet())
        assertTrue(shouldNotifyShopping(window, null, at(17, 0, date = "2026-04-18")))
    }

    // --- Debounce within a window ---

    @Test
    fun `suppressed when already notified during today's window`() {
        val lastNotified = at(15, 10).toInstant()
        assertFalse(shouldNotifyShopping(commuteWindow, lastNotified, at(17, 30)))
    }

    @Test
    fun `fires when last notification was during yesterday's window`() {
        val lastNotified = at(17, 30, date = "2026-04-15").toInstant()
        assertTrue(shouldNotifyShopping(commuteWindow, lastNotified, at(17, 30)))
    }

    @Test
    fun `fires when last notification was earlier today but before the window opened`() {
        // e.g. a stray fire recorded at 09:00 must not eat the evening reminder
        val lastNotified = at(9, 0).toInstant()
        assertTrue(shouldNotifyShopping(commuteWindow, lastNotified, at(17, 30)))
    }

    // --- No window configured ---

    @Test
    fun `no window - fires with no prior notification`() {
        assertTrue(shouldNotifyShopping(null, null, at(8, 0)))
    }

    @Test
    fun `no window - suppressed within the renotify gap`() {
        val lastNotified = at(8, 0).toInstant()
        assertFalse(shouldNotifyShopping(null, lastNotified, at(10, 0)))
    }

    @Test
    fun `no window - fires after the renotify gap`() {
        val lastNotified = at(8, 0).toInstant()
        assertTrue(shouldNotifyShopping(null, lastNotified, at(12, 0)))
    }

    // --- Days-only window (no start or end time) ---

    @Test
    fun `days-only window falls back to renotify gap for debounce`() {
        val window = NotifyWindow(days = setOf("thu"), start = null, end = null)
        val lastNotified = at(8, 0).toInstant()
        assertFalse(shouldNotifyShopping(window, lastNotified, at(10, 0)))
        assertTrue(shouldNotifyShopping(window, lastNotified, at(12, 30)))
    }

    @Test
    fun `never notified before - epoch-old timestamp fires`() {
        assertTrue(shouldNotifyShopping(commuteWindow, Instant.EPOCH, at(17, 0)))
    }
}
