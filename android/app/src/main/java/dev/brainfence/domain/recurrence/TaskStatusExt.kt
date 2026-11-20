package dev.brainfence.domain.recurrence

import dev.brainfence.domain.blocking.RuleContracts
import dev.brainfence.domain.model.Task
import java.time.Instant
import java.time.ZoneId

/** [computeTaskStatus] for a loaded task. */
fun Task.status(
    currentTime: Instant = Instant.now(),
    timeZone: ZoneId = ZoneId.systemDefault(),
): TaskStatus = computeTaskStatus(
    recurrenceType = recurrenceType,
    recurrenceConfig = recurrenceConfig,
    carryOver = carryOver,
    availableFrom = availableFrom,
    dueAt = dueAt,
    createdAt = RuleContracts.parseTimestamp(createdAt),
    lastCompletionAt = RuleContracts.parseTimestamp(lastCompletionAt),
    currentTime = currentTime,
    timeZone = timeZone,
)

/**
 * Done for its current occurrence, unless it's optional today: then a task
 * screen lets it be done again (shared/CONTRACTS.md: Optional).
 */
fun Task.isDoneForNow(currentTime: Instant = Instant.now(), timeZone: ZoneId = ZoneId.systemDefault()): Boolean =
    status(currentTime, timeZone).let { it.completed && !it.optional }
