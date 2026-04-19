package dev.brainfence.domain.blocking

import dev.brainfence.domain.model.BlockingRule
import dev.brainfence.domain.model.Task
import dev.brainfence.domain.recurrence.TimeGatePhase
import dev.brainfence.domain.recurrence.computeTaskPhase
import java.time.Instant
import java.time.ZoneId

/**
 * Result of evaluating all blocking rules.
 * Contains the set of app packages and domains that should currently be blocked.
 */
data class BlockingState(
    val blockedApps: Set<String>,
    val blockedDomains: Set<String>,
    /** Maps each blocked package to the rule(s) that block it, for overlay display. */
    val rulesByApp: Map<String, List<BlockingRule>>,
)

/**
 * Evaluates blocking rules against current task completion state.
 *
 * A rule blocks when it is active and its condition tasks are not met.
 * Blocking is driven by each task's due_at time — a task only triggers
 * blocking once it is past its due_at and still incomplete.
 *
 * Pure function — no side effects or dependencies.
 */
fun evaluateBlocking(
    rules: List<BlockingRule>,
    tasks: List<Task>,
    currentTime: Instant = Instant.now(),
    timeZone: ZoneId = ZoneId.systemDefault(),
): BlockingState {
    val blockedApps = mutableSetOf<String>()
    val blockedDomains = mutableSetOf<String>()
    val rulesByApp = mutableMapOf<String, MutableList<BlockingRule>>()

    val taskById = tasks.associateBy { it.id }

    for (rule in rules) {
        if (!rule.isActive) continue
        if (conditionsMet(rule, taskById, currentTime, timeZone)) continue

        // Rule is active and conditions NOT met → block
        blockedApps.addAll(rule.blockedApps)
        blockedDomains.addAll(rule.blockedDomains)
        for (app in rule.blockedApps) {
            rulesByApp.getOrPut(app) { mutableListOf() }.add(rule)
        }
    }

    return BlockingState(blockedApps, blockedDomains, rulesByApp)
}

/**
 * Returns true if the rule's conditions are satisfied (i.e. blocking should be lifted).
 *
 * A condition task is considered "met" (non-blocking) when:
 * - It is completed today, OR
 * - It has a due_at and the current time is before that due_at (not yet overdue), OR
 * - It has no due_at and is completed (for tasks without time constraints that
 *   aren't yet completed, they block immediately)
 */
private fun conditionsMet(
    rule: BlockingRule,
    taskById: Map<String, Task>,
    currentTime: Instant,
    timeZone: ZoneId,
): Boolean {
    if (rule.conditionTaskIds.isEmpty()) return false

    val results = rule.conditionTaskIds.map { taskId ->
        val task = taskById[taskId] ?: return@map false
        if (task.completedToday) return@map true

        // If the task has a due_at, it only triggers blocking after that time
        val phase = computeTaskPhase(task.availableFrom, task.dueAt, currentTime, timeZone)
        if (phase != null && phase != TimeGatePhase.PAST_DUE) return@map true

        false
    }

    return when (rule.conditionLogic) {
        "any" -> results.any { it }
        else  -> results.all { it } // "all" is the default
    }
}
