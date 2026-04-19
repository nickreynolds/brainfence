package dev.brainfence.data.blocking

import com.powersync.PowerSyncDatabase
import com.powersync.db.SqlCursor
import dev.brainfence.domain.model.BlockingRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val ACTIVE_RULES_SQL = """
    SELECT
        id,
        user_id,
        name,
        blocked_apps,
        blocked_domains,
        condition_task_ids,
        condition_logic,
        active_schedule,
        config_lock_hours,
        pending_changes,
        changes_apply_at,
        is_active
    FROM blocking_rules
    WHERE is_active = 1
"""

@Singleton
class BlockingRepository @Inject constructor(
    private val database: PowerSyncDatabase,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchActiveRules(): Flow<List<BlockingRule>> =
        database.onChange(tables = setOf("blocking_rules"))
            .onStart { emit(emptySet()) }
            .flatMapLatest {
                flow {
                    emit(database.getAll(ACTIVE_RULES_SQL, mapper = ::mapRule))
                }
            }

    /**
     * Schedule a change to a blocking rule with a time-lock delay.
     *
     * Instead of applying the change immediately, the new config is stored in
     * `pending_changes` and will be applied after `config_lock_hours` elapse.
     *
     * @param ruleId     The blocking rule to modify.
     * @param changes    A JSON object describing the new values, e.g.
     *                   {"blocked_apps": [...], "condition_task_ids": [...], "is_active": 0}
     */
    suspend fun scheduleRuleChange(ruleId: String, changes: JSONObject) {
        val applyAt = Instant.now()
            .plusSeconds(getLockHours(ruleId) * 3600L)
            .toString()
        val now = Instant.now().toString()

        database.execute(
            sql = """
                UPDATE blocking_rules
                SET pending_changes  = ?,
                    changes_apply_at = ?,
                    updated_at       = ?
                WHERE id = ?
            """.trimIndent(),
            parameters = listOf(changes.toString(), applyAt, now, ruleId),
        )
    }

    /**
     * Check all active rules for expired time-locks and apply pending changes.
     * Called periodically by the foreground service.
     *
     * @return number of rules that had pending changes applied.
     */
    suspend fun applyExpiredPendingChanges(): Int {
        val now = Instant.now().toString()
        val rules = database.getAll(
            sql = """
                SELECT id, pending_changes
                FROM blocking_rules
                WHERE pending_changes IS NOT NULL
                  AND changes_apply_at IS NOT NULL
                  AND changes_apply_at <= ?
            """.trimIndent(),
            parameters = listOf(now),
        ) { cursor ->
            Pair(cursor.getString(0)!!, cursor.getString(1)!!)
        }

        for ((id, pendingJson) in rules) {
            applyPendingToRule(id, pendingJson)
        }
        return rules.size
    }

    /**
     * Cancel a pending change (reverts the rule to its current live config).
     */
    suspend fun cancelPendingChange(ruleId: String) {
        val now = Instant.now().toString()
        database.execute(
            sql = """
                UPDATE blocking_rules
                SET pending_changes  = NULL,
                    changes_apply_at = NULL,
                    updated_at       = ?
                WHERE id = ?
            """.trimIndent(),
            parameters = listOf(now, ruleId),
        )
    }

    /**
     * Promote pending changes into the live columns for a single rule
     * and clear the pending state.
     */
    private suspend fun applyPendingToRule(ruleId: String, pendingJson: String) {
        val changes = JSONObject(pendingJson)
        val setClauses = mutableListOf<String>()
        val params = mutableListOf<Any?>()

        // Map each possible change key to its column
        val textFields = mapOf(
            "name" to "name",
            "blocked_apps" to "blocked_apps",
            "blocked_domains" to "blocked_domains",
            "condition_task_ids" to "condition_task_ids",
            "condition_logic" to "condition_logic",
            "active_schedule" to "active_schedule",
        )
        val intFields = mapOf(
            "is_active" to "is_active",
            "config_lock_hours" to "config_lock_hours",
        )

        for ((jsonKey, col) in textFields) {
            if (changes.has(jsonKey)) {
                setClauses.add("$col = ?")
                val value = changes.get(jsonKey)
                params.add(if (value is JSONArray || value is JSONObject) value.toString() else value.toString())
            }
        }
        for ((jsonKey, col) in intFields) {
            if (changes.has(jsonKey)) {
                setClauses.add("$col = ?")
                params.add(changes.getInt(jsonKey).toLong())
            }
        }

        if (setClauses.isEmpty()) {
            // Nothing to apply — just clear the pending state
            cancelPendingChange(ruleId)
            return
        }

        // Clear pending state and set updated_at alongside the real changes
        setClauses.add("pending_changes = NULL")
        setClauses.add("changes_apply_at = NULL")
        setClauses.add("updated_at = ?")
        params.add(Instant.now().toString())
        params.add(ruleId)

        database.execute(
            sql = "UPDATE blocking_rules SET ${setClauses.joinToString(", ")} WHERE id = ?",
            parameters = params,
        )
    }

    private suspend fun getLockHours(ruleId: String): Int {
        return database.getOptional(
            sql = "SELECT config_lock_hours FROM blocking_rules WHERE id = ?",
            parameters = listOf(ruleId),
        ) { cursor ->
            cursor.getLong(0)?.toInt() ?: 24
        } ?: 24
    }

    private fun mapRule(cursor: SqlCursor): BlockingRule = BlockingRule(
        id               = cursor.getString(0)!!,
        userId           = cursor.getString(1)!!,
        name             = cursor.getString(2) ?: "",
        blockedApps      = parseBlockedApps(cursor.getString(3)),
        blockedDomains   = parseJsonStringArray(cursor.getString(4)),
        conditionTaskIds = parseJsonStringArray(cursor.getString(5)),
        conditionLogic   = cursor.getString(6) ?: "all",
        activeSchedule   = cursor.getString(7) ?: "{}",
        configLockHours  = cursor.getLong(8)?.toInt() ?: 24,
        pendingChanges   = cursor.getString(9),
        changesApplyAt   = cursor.getString(10),
        isActive         = (cursor.getLong(11) ?: 0L) != 0L,
    )

    /**
     * Parse a JSON array of strings (e.g. condition_task_ids, blocked_domains).
     */
    private fun parseJsonStringArray(json: String?): List<String> {
        if (json.isNullOrBlank() || json == "null") return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { array.getString(it) }
    }

    /**
     * Parse the blocked_apps JSON array, which contains objects like
     * {"platform":"android","package":"com.example.app"}.
     * Extracts only Android package names.
     */
    private fun parseBlockedApps(json: String?): List<String> {
        if (json.isNullOrBlank() || json == "null") return emptyList()
        val array = JSONArray(json)
        val packages = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i)
            if (item != null) {
                if (item.optString("platform") == "android") {
                    val pkg = item.optString("package", "")
                    if (pkg.isNotEmpty()) packages.add(pkg)
                }
            } else {
                // Fallback: treat as plain string (bare package name)
                val str = array.optString(i, "")
                if (str.isNotEmpty()) packages.add(str)
            }
        }
        return packages
    }
}
