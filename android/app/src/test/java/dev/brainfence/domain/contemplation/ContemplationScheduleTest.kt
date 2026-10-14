package dev.brainfence.domain.contemplation

import dev.brainfence.domain.model.DeckOrdering
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

class ContemplationScheduleTest {

    private val zone = ZoneId.of("America/New_York")
    private val date = LocalDate.parse("2026-09-28")
    private val config = ContemplationScheduleConfig(
        perDay = 5,
        windowStart = LocalTime.of(8, 0),
        windowEnd = LocalTime.of(21, 0),
    )

    // --- Slots for a day ---

    @Test
    fun `produces perDay slots, sorted, inside the window`() {
        val slots = contemplationSlotsForDay(date, config, seed = 42)
        assertEquals(5, slots.size)
        assertEquals(slots.sorted(), slots)
        slots.forEach {
            assertTrue(!it.isBefore(config.windowStart))
            assertTrue(it.isBefore(config.windowEnd))
        }
    }

    @Test
    fun `each slot falls in its own segment of the window`() {
        val segmentMinutes = Duration.between(config.windowStart, config.windowEnd).toMinutes() / 5
        repeat(50) { seed ->
            contemplationSlotsForDay(date, config, seed.toLong()).forEachIndexed { i, slot ->
                val minutesIn = Duration.between(config.windowStart, slot).toMinutes()
                assertEquals("seed=$seed slot=$i", i.toLong(), minutesIn / segmentMinutes)
            }
        }
    }

    @Test
    fun `neighbouring slots are never bunched together`() {
        // 13h window / 5 = 156 min segments; 20% margins → at least ~62 min apart.
        repeat(200) { seed ->
            val slots = contemplationSlotsForDay(date, config, seed.toLong())
            slots.zipWithNext().forEach { (a, b) ->
                assertTrue("seed=$seed $a→$b", Duration.between(a, b).toMinutes() >= 62)
            }
        }
    }

    @Test
    fun `same date and seed give the same times`() {
        assertEquals(
            contemplationSlotsForDay(date, config, seed = 7),
            contemplationSlotsForDay(date, config, seed = 7),
        )
    }

    @Test
    fun `different days give different times`() {
        assertNotEquals(
            contemplationSlotsForDay(date, config, seed = 7),
            contemplationSlotsForDay(date.plusDays(1), config, seed = 7),
        )
    }

    @Test
    fun `unusable config gives no slots`() {
        assertTrue(contemplationSlotsForDay(date, config.copy(perDay = 0), 1).isEmpty())
        assertTrue(
            contemplationSlotsForDay(
                date, config.copy(windowStart = LocalTime.of(21, 0), windowEnd = LocalTime.of(8, 0)), 1,
            ).isEmpty(),
        )
    }

    // --- Next slot ---

    @Test
    fun `next slot is the first one after now today`() {
        val slots = contemplationSlotsForDay(date, config, seed = 3)
        val now = date.atTime(slots[1]).atZone(zone) // exactly at slot 1 → strictly after means slot 2
        val next = nextContemplationSlot(now, config, seed = 3)
        assertEquals(date.atTime(slots[2]).atZone(zone), next)
    }

    @Test
    fun `after the last slot, rolls over to tomorrow's first`() {
        val now = date.atTime(22, 0).atZone(zone)
        val tomorrow = date.plusDays(1)
        val expected = tomorrow.atTime(contemplationSlotsForDay(tomorrow, config, 3).first()).atZone(zone)
        assertEquals(expected, nextContemplationSlot(now, config, seed = 3))
    }

    @Test
    fun `no next slot when schedule is unusable`() {
        assertNull(nextContemplationSlot(date.atTime(9, 0).atZone(zone), config.copy(perDay = 0), 3))
    }

    // --- Picking ---

    @Test
    fun `sequential rotates through items and wraps`() {
        val ids = listOf("a", "b", "c")
        assertEquals(0, pickItemIndex(DeckOrdering.SEQUENTIAL, ids, null, Random(1)))
        assertEquals(1, pickItemIndex(DeckOrdering.SEQUENTIAL, ids, "a", Random(1)))
        assertEquals(0, pickItemIndex(DeckOrdering.SEQUENTIAL, ids, "c", Random(1)))
    }

    @Test
    fun `sequential restarts when the last item was deleted`() {
        assertEquals(0, pickItemIndex(DeckOrdering.SEQUENTIAL, listOf("a", "b"), "gone", Random(1)))
    }

    @Test
    fun `random never repeats the last item when there is a choice`() {
        val ids = listOf("a", "b", "c")
        val random = Random(99)
        repeat(200) {
            assertNotEquals(1, pickItemIndex(DeckOrdering.RANDOM, ids, "b", random))
        }
    }

    @Test
    fun `random with a single item returns it`() {
        assertEquals(0, pickItemIndex(DeckOrdering.RANDOM, listOf("a"), "a", Random(1)))
    }

    @Test
    fun `weighted pick respects proportions`() {
        val random = Random(5)
        val counts = IntArray(2)
        repeat(10_000) { counts[pickWeightedIndex(listOf(4, 1), random)]++ }
        val share = counts[0] / 10_000.0
        assertTrue("share=$share", share in 0.77..0.83)
    }
}
