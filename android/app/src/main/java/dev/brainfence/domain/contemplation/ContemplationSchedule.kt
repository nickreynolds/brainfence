package dev.brainfence.domain.contemplation

import dev.brainfence.domain.model.DeckOrdering
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.random.Random

data class ContemplationScheduleConfig(
    val perDay: Int,
    val windowStart: LocalTime,
    val windowEnd: LocalTime,
)

/**
 * Fraction of each segment kept clear at either end, so neighbouring slots are
 * at least `2 * SLOT_MARGIN` of a segment apart (e.g. ~58 min for 5 a day
 * across 12 hours) instead of possibly landing back to back at a boundary.
 */
private const val SLOT_MARGIN = 0.2

/**
 * Delivery times for [date]. The waking window is split into `perDay` equal
 * segments and one moment is drawn from the middle of each: unpredictable, but
 * always spread through the day rather than bunched together.
 *
 * Deterministic for a given date and [seed], so the day's times survive process
 * death and reboots without being stored. Empty when the config is unusable
 * (no deliveries, or the window doesn't end after it starts).
 */
fun contemplationSlotsForDay(
    date: LocalDate,
    config: ContemplationScheduleConfig,
    seed: Long,
): List<LocalTime> {
    if (config.perDay <= 0 || !config.windowEnd.isAfter(config.windowStart)) return emptyList()
    val windowSeconds = Duration.between(config.windowStart, config.windowEnd).seconds
    val segment = windowSeconds.toDouble() / config.perDay
    val random = Random(seed * 31 + date.toEpochDay())
    return (0 until config.perDay).map { i ->
        val withinSegment = SLOT_MARGIN + random.nextDouble() * (1 - 2 * SLOT_MARGIN)
        val offset = ((i + withinSegment) * segment).toLong()
        config.windowStart.plusSeconds(offset.coerceAtMost(windowSeconds - 1))
    }
}

/** The first delivery time strictly after [now] (today's, else tomorrow's), or null. */
fun nextContemplationSlot(
    now: ZonedDateTime,
    config: ContemplationScheduleConfig,
    seed: Long,
): ZonedDateTime? {
    for (dayOffset in 0L..1L) {
        val date = now.toLocalDate().plusDays(dayOffset)
        val next = contemplationSlotsForDay(date, config, seed)
            .map { date.atTime(it).atZone(now.zone) }
            .firstOrNull { it.isAfter(now) }
        if (next != null) return next
    }
    return null
}

/** Weighted choice: index into [weights], each weight's share proportional to its value. */
fun pickWeightedIndex(weights: List<Int>, random: Random): Int {
    require(weights.isNotEmpty()) { "No weights to pick from" }
    val clamped = weights.map { it.coerceAtLeast(1) }
    var roll = random.nextInt(clamped.sum())
    for ((i, w) in clamped.withIndex()) {
        if (roll < w) return i
        roll -= w
    }
    return clamped.lastIndex
}

/**
 * Index of the next item to deliver from a deck, given the deck's items in
 * order and the item it last delivered (null if none, or since deleted).
 */
fun pickItemIndex(
    ordering: DeckOrdering,
    itemIds: List<String>,
    lastDeliveredId: String?,
    random: Random,
): Int {
    require(itemIds.isNotEmpty()) { "Deck has no items" }
    val lastIndex = itemIds.indexOf(lastDeliveredId)
    return when (ordering) {
        DeckOrdering.SEQUENTIAL -> if (lastIndex < 0) 0 else (lastIndex + 1) % itemIds.size
        DeckOrdering.RANDOM -> {
            if (itemIds.size == 1 || lastIndex < 0) {
                random.nextInt(itemIds.size)
            } else {
                // Draw from the others, skipping over the last-delivered slot.
                val i = random.nextInt(itemIds.size - 1)
                if (i >= lastIndex) i + 1 else i
            }
        }
    }
}
