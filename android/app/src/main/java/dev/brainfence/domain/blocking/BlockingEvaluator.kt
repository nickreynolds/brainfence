package dev.brainfence.domain.blocking

import dev.brainfence.domain.model.BlockingRule
import dev.brainfence.domain.model.Task
import dev.brainfence.domain.recurrence.TimeGatePhase
import dev.brainfence.domain.recurrence.computeTaskPhase
import org.json.JSONArray
import java.time.DayOfWeek
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
    /** Maps each blocked package to the task IDs that are actively unmet (causing the block). */
    val unmetTaskIdsByApp: Map<String, Set<String>> = emptyMap(),
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
 * Tasks with a non-empty `blockingDaysOfWeek` are treated as met on days
 * not listed — they still show as completable, but don't enforce blocking
 * on off-days (e.g. a journal task that only blocks Mon–Thu).
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
    val unmetTaskIdsByApp = mutableMapOf<String, MutableSet<String>>()

    val taskById = tasks.associateBy { it.id }

    for (rule in rules) {
        if (!rule.isActive) continue
        val unmetIds = conditionUnmetTaskIds(rule, taskById, currentTime, timeZone, homePresence)
        if (isConditionMet(rule, unmetIds)) continue

        // Rule is active and conditions NOT met → block
        blockedApps.addAll(rule.blockedApps)
        blockedDomains.addAll(rule.blockedDomains)
        for (app in rule.blockedApps) {
            rulesByApp.getOrPut(app) { mutableListOf() }.add(rule)
            unmetTaskIdsByApp.getOrPut(app) { mutableSetOf() }.addAll(unmetIds)
        }
    }

    return BlockingState(blockedApps, blockedDomains, rulesByApp, unmetTaskIdsByApp)
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
private fun isConditionMet(rule: BlockingRule, unmetIds: Set<String>): Boolean {
    if (rule.conditionTaskIds.isEmpty()) return false
    return when (rule.conditionLogic) {
        "any" -> unmetIds.size < rule.conditionTaskIds.size // at least one task is met
        else  -> unmetIds.isEmpty() // all tasks must be met (default)
    }
}

/**
 * Returns the IDs of tasks in [rule] that are currently unmet (actively causing blocking).
 */
private fun conditionUnmetTaskIds(
    rule: BlockingRule,
    taskById: Map<String, Task>,
    currentTime: Instant,
    timeZone: ZoneId,
    homePresence: HomePresence,
): Set<String> {
    return rule.conditionTaskIds.filter { taskId ->
        val task = taskById[taskId] ?: return@filter false
        if (task.completedToday) return@filter false
        if (task.homeOnlyBlocking && homePresence == HomePresence.AWAY) return@filter false
        val allowedDays = parseBlockingDays(task.blockingDaysOfWeek)
        if (allowedDays.isNotEmpty()) {
            val dow = currentTime.atZone(timeZone).dayOfWeek
            if (dow !in allowedDays) return@filter false
        }
        val phase = computeTaskPhase(task.availableFrom, task.dueAt, currentTime, timeZone)
        phase == null || phase == TimeGatePhase.PAST_DUE
    }.toSet()
}

private val DAY_ABBREVIATIONS = mapOf(
    "mon" to DayOfWeek.MONDAY,
    "tue" to DayOfWeek.TUESDAY,
    "wed" to DayOfWeek.WEDNESDAY,
    "thu" to DayOfWeek.THURSDAY,
    "fri" to DayOfWeek.FRIDAY,
    "sat" to DayOfWeek.SATURDAY,
    "sun" to DayOfWeek.SUNDAY,
)

/**
 * Parses the task's `blocking_days_of_week` JSON array (e.g. `["mon","tue"]`)
 * into a set of [DayOfWeek]. Returns empty set when the config is empty or
 * malformed — callers treat "empty" as "block every day".
 */
private fun parseBlockingDays(json: String): Set<DayOfWeek> {
    if (json.isBlank() || json == "[]") return emptySet()
    return try {
        val arr = JSONArray(json)
        val out = mutableSetOf<DayOfWeek>()
        for (i in 0 until arr.length()) {
            DAY_ABBREVIATIONS[arr.optString(i).lowercase()]?.let(out::add)
        }
        out
    } catch (_: Exception) {
        emptySet()
    }
}
