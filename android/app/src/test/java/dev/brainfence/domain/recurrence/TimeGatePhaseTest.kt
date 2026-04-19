package dev.brainfence.domain.recurrence

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TimeGatePhaseTest {

    private val zone = ZoneId.of("America/New_York")
    private val config = """{"start_time": "07:00", "end_time": "10:00", "timezone": "America/New_York"}"""

    private fun instant(hour: Int, minute: Int = 0) =
        LocalDate.parse("2026-04-16").atTime(hour, minute).atZone(zone).toInstant()

    @Test
    fun `before start time is BEFORE_START`() {
        assertEquals(
            TimeGatePhase.BEFORE_START,
            computeTimeGatePhase(config, instant(6, 59), zone),
        )
    }

    @Test
    fun `at start time is ACTIVE`() {
        assertEquals(
            TimeGatePhase.ACTIVE,
            computeTimeGatePhase(config, instant(7, 0), zone),
        )
    }

    @Test
    fun `during window is ACTIVE`() {
        assertEquals(
            TimeGatePhase.ACTIVE,
            computeTimeGatePhase(config, instant(8, 30), zone),
        )
    }

    @Test
    fun `at end time is PAST_END`() {
        assertEquals(
            TimeGatePhase.PAST_END,
            computeTimeGatePhase(config, instant(10, 0), zone),
        )
    }

    @Test
    fun `after end time is PAST_END`() {
        assertEquals(
            TimeGatePhase.PAST_END,
            computeTimeGatePhase(config, instant(15, 0), zone),
        )
    }

    @Test
    fun `respects configured timezone`() {
        // Config is in LA time (PT), current time is in ET
        val laConfig = """{"start_time": "07:00", "end_time": "10:00", "timezone": "America/Los_Angeles"}"""
        // 9 AM ET = 6 AM PT → before 7 AM start → BEFORE_START
        assertEquals(
            TimeGatePhase.BEFORE_START,
            computeTimeGatePhase(laConfig, instant(9, 0), zone),
        )
        // 11 AM ET = 8 AM PT → between 7 and 10 → ACTIVE
        assertEquals(
            TimeGatePhase.ACTIVE,
            computeTimeGatePhase(laConfig, instant(11, 0), zone),
        )
        // 2 PM ET = 11 AM PT → after 10 AM end → PAST_END
        assertEquals(
            TimeGatePhase.PAST_END,
            computeTimeGatePhase(laConfig, instant(14, 0), zone),
        )
    }

    @Test
    fun `uses default timezone when none configured`() {
        val noTzConfig = """{"start_time": "07:00", "end_time": "10:00"}"""
        assertEquals(
            TimeGatePhase.BEFORE_START,
            computeTimeGatePhase(noTzConfig, instant(6, 0), zone),
        )
        assertEquals(
            TimeGatePhase.ACTIVE,
            computeTimeGatePhase(noTzConfig, instant(8, 0), zone),
        )
        assertEquals(
            TimeGatePhase.PAST_END,
            computeTimeGatePhase(noTzConfig, instant(12, 0), zone),
        )
    }
}
