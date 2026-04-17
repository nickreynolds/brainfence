package dev.brainfence.domain.recurrence

import java.time.Instant

sealed interface OccurrenceStatus {
    data object Due : OccurrenceStatus
    data object Completed : OccurrenceStatus
    data object NotDue : OccurrenceStatus
    data class Upcoming(val nextDueAt: Instant) : OccurrenceStatus
}
