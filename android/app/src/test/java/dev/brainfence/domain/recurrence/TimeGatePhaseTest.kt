package dev.brainfence.domain.recurrence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TimeGatePhaseTest {

    private val zone = ZoneId.of("America/New_York")

    private fun instant(hour: Int, minute: Int = 0) =
        LocalDate.parse("2026-04-16").atTime(hour, minute).atZone(zone).toInstant()

    @Test
    fun `before available_from is BEFORE_START`() {
        assertEquals(
            TimeGatePhase.BEFORE_START,
            computeTaskPhase("07:00", "10:00", instant(6, 59), zone),
        )
    }

    @Test
    fun `at available_from is ACTIVE`() {
        assertEquals(
            TimeGatePhase.ACTIVE,
            computeTaskPhase("07:00", "10:00", instant(7, 0), zone),
        )
    }

    @Test
    fun `during window is ACTIVE`() {
        assertEquals(
            TimeGatePhase.ACTIVE,
            computeTaskPhase("07:00", "10:00", instant(8, 30), zone),
        )
    }

    @Test
    fun `at due_at is PAST_DUE`() {
        assertEquals(
            TimeGatePhase.PAST_DUE,
            computeTaskPhase("07:00", "10:00", instant(10, 0), zone),
        )
    }

    @Test
    fun `after due_at is PAST_DUE`() {
        assertEquals(
            TimeGatePhase.PAST_DUE,
            computeTaskPhase("07:00", "10:00", instant(15, 0), zone),
        )
    }

    @Test
    fun `null available_from and null due_at returns null`() {
        assertNull(
            computeTaskPhase(null, null, instant(12, 0), zone),
        )
    }

    @Test
    fun `only available_from set - before is BEFORE_START`() {
        assertEquals(
            TimeGatePhase.BEFORE_START,
            computeTaskPhase("07:00", null, instant(6, 0), zone),
        )
    }

    @Test
    fun `only available_from set - after is ACTIVE`() {
        assertEquals(
            TimeGatePhase.ACTIVE,
            computeTaskPhase("07:00", null, instant(8, 0), zone),
        )
    }

    @Test
    fun `only due_at set - before is ACTIVE`() {
        assertEquals(
            TimeGatePhase.ACTIVE,
            computeTaskPhase(null, "10:00", instant(8, 0), zone),
        )
    }

    @Test
    fun `only due_at set - after is PAST_DUE`() {
        assertEquals(
            TimeGatePhase.PAST_DUE,
            computeTaskPhase(null, "10:00", instant(12, 0), zone),
        )
    }
}
