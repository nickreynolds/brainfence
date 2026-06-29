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
 * Whether the user is currently within their configured home radius.
 *
 * `UNCONFIGURED` keeps home-only tasks blocking as if at home — we don't
 * lift blocking when the feature isn't set up. `UNKNOWN` is treated the
 * same (strict-by-default: only lift blocking when we positively know the
 * user is away).
 */
enum class HomePresence {
    AT_HOME,
    AWAY,
    UNKNOWN,
    UNCONFIGURED,
}

/**
 * Evaluates blocking rules against current task completion state.
 *
 * A rule blocks when it is active and its condition tasks are not met.
 * Blocking is driven by each task's due_at time — a task only triggers
 * blocking once it is past its due_at and still incomplete.
 *
 * Tasks flagged `homeOnlyBlocking` are treated as met (non-blocking) when
 * [homePresence] is `AWAY` — useful for routines tied to equipment at home
 * (e.g. a hangboard) so they don't block on vacation.
 *
 * Pure function — no side effects or dependencies.
 */
fun evaluateBlocking(
    rules: List<BlockingRule>,
    tasks: List<Task>,
    currentTime: Instant = Instant.now(),
    timeZone: ZoneId = ZoneId.systemDefault(),
    homePresence: HomePresence = HomePresence.UNCONFIGURED,
): BlockingState {
    val blockedApps = mutableSetOf<String>()
    val blockedDomains = mutableSetOf<String>()
    val rulesByApp = mutableMapOf<String, MutableList<BlockingRule>>()

    val taskById = tasks.associateBy { it.id }

    for (rule in rules) {
        if (!rule.isActive) continue
        if (conditionsMet(rule, taskById, currentTime, timeZone, homePresence)) continue

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
 * - It is `homeOnlyBlocking` and the user is known to be away from home, OR
 * - It has no due_at and is completed (for tasks without time constraints that
 *   aren't yet completed, they block immediately)
 */
private fun conditionsMet(
    rule: BlockingRule,
    taskById: Map<String, Task>,
    currentTime: Instant,
    timeZone: ZoneId,
    homePresence: HomePresence,
): Boolean {
    if (rule.conditionTaskIds.isEmpty()) return false

    val results = rule.conditionTaskIds.map { taskId ->
        val task = taskById[taskId] ?: return@map false
        if (task.completedToday) return@map true

        // Home-only tasks don't contribute to blocking when the user is away.
        if (task.homeOnlyBlocking && homePresence == HomePresence.AWAY) return@map true

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
