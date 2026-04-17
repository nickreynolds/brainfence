package dev.brainfence.domain.blocking

import dev.brainfence.domain.model.BlockingRule
import dev.brainfence.domain.model.Task
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
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
        if (!isWithinSchedule(rule.activeSchedule, currentTime, timeZone)) continue
        if (conditionsMet(rule, taskById)) continue

        // Rule is active, within schedule, and conditions NOT met → block
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
 */
private fun conditionsMet(rule: BlockingRule, taskById: Map<String, Task>): Boolean {
    if (rule.conditionTaskIds.isEmpty()) return false

    val results = rule.conditionTaskIds.map { taskId ->
        taskById[taskId]?.completedToday == true
    }

    return when (rule.conditionLogic) {
        "any" -> results.any { it }
        else  -> results.all { it } // "all" is the default
    }
}

/**
 * Returns true if the current time falls within the rule's active schedule.
 * An empty/null schedule means "always active".
 */
private fun isWithinSchedule(
    scheduleJson: String,
    currentTime: Instant,
    timeZone: ZoneId,
): Boolean {
    if (scheduleJson.isBlank() || scheduleJson == "{}" || scheduleJson == "null") return true

    val schedule = JSONObject(scheduleJson)
    val zoned = currentTime.atZone(timeZone)

    // Check days
    if (schedule.has("days")) {
        val days = schedule.getJSONArray("days")
        val allowedDays = (0 until days.length()).map { parseDayOfWeek(days.getString(it)) }.toSet()
        if (zoned.dayOfWeek !in allowedDays) return false
    }

    // Check time window — support both "start"/"end" and "start_time"/"end_time" keys
    val startKey = if (schedule.has("start")) "start" else "start_time"
    val endKey = if (schedule.has("end")) "end" else "end_time"
    if (schedule.has(startKey) && schedule.has(endKey)) {
        val start = LocalTime.parse(schedule.getString(startKey))
        val end = LocalTime.parse(schedule.getString(endKey))
        val now = zoned.toLocalTime()
        if (now < start || now >= end) return false
    }

    return true
}

private fun parseDayOfWeek(abbrev: String): DayOfWeek = when (abbrev.lowercase()) {
    "mon" -> DayOfWeek.MONDAY
    "tue" -> DayOfWeek.TUESDAY
    "wed" -> DayOfWeek.WEDNESDAY
    "thu" -> DayOfWeek.THURSDAY
    "fri" -> DayOfWeek.FRIDAY
    "sat" -> DayOfWeek.SATURDAY
    "sun" -> DayOfWeek.SUNDAY
    else -> error("Unknown day abbreviation: $abbrev")
}
