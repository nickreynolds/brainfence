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
