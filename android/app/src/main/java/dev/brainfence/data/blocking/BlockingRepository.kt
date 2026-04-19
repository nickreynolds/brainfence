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
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val RULES_COLUMNS = """
        id,
        user_id,
        name,
        blocked_apps,
        blocked_domains,
        condition_task_ids,
        condition_logic,
        config_lock_hours,
        pending_changes,
        changes_apply_at,
        is_active
"""

private const val ACTIVE_RULES_SQL = """
    SELECT $RULES_COLUMNS
    FROM blocking_rules
    WHERE is_active = 1
"""

private const val ALL_RULES_SQL = """
    SELECT $RULES_COLUMNS
    FROM blocking_rules
    ORDER BY name
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

    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchAllRules(): Flow<List<BlockingRule>> =
        database.onChange(tables = setOf("blocking_rules"))
            .onStart { emit(emptySet()) }
            .flatMapLatest {
                flow {
                    emit(database.getAll(ALL_RULES_SQL, mapper = ::mapRule))
                }
            }

    suspend fun getRuleById(ruleId: String): BlockingRule? =
        database.getOptional(
            sql = "SELECT $RULES_COLUMNS FROM blocking_rules WHERE id = ?",
            parameters = listOf(ruleId),
            mapper = ::mapRule,
        )

    /**
     * Create a new blocking rule. New rules are inserted directly (no time-lock).
     */
    suspend fun createRule(
        userId: String,
        name: String,
        blockedApps: JSONArray,
        blockedDomains: JSONArray,
        conditionTaskIds: JSONArray,
        conditionLogic: String,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        database.execute(
            sql = """
                INSERT INTO blocking_rules
                    (id, user_id, name, blocked_apps, blocked_domains,
                     condition_task_ids, condition_logic,
                     config_lock_hours, is_active, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            parameters = listOf(
                id, userId, name,
                blockedApps.toString(), blockedDomains.toString(),
                conditionTaskIds.toString(), conditionLogic,
                24, 1L, now, now,
            ),
        )
        return id
    }

    /**
     * Delete a blocking rule permanently.
     */
    suspend fun deleteRule(ruleId: String) {
        database.execute(
            sql = "DELETE FROM blocking_rules WHERE id = ?",
            parameters = listOf(ruleId),
        )
    }

    /**
     * Schedule a change to a blocking rule with a time-lock delay.
     *
     * If the change is strictly more restrictive (adds blocked apps/domains,
     * adds condition tasks, tightens logic, or activates the rule) it is
     * applied immediately — the time-lock only protects against weakening.
     *
     * @param ruleId     The blocking rule to modify.
     * @param changes    A JSON object describing the new values, e.g.
     *                   {"blocked_apps": [...], "condition_task_ids": [...], "is_active": 0}
     */
    suspend fun scheduleRuleChange(ruleId: String, changes: JSONObject) {
        val current = getRuleById(ruleId)
        if (current != null && isMoreRestrictive(current, changes)) {
            // More restrictive — apply immediately, no time-lock
            applyPendingToRule(ruleId, changes.toString())
            return
        }

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
     * Returns true if the proposed [changes] are strictly more restrictive
     * than the [current] rule — meaning they only add blocking, never remove it.
     *
     * A change is more restrictive when ALL of the following hold for each
     * field present in changes:
     * - blocked_apps: new set is a superset of the current set
     * - blocked_domains: new set is a superset of the current set
     * - condition_task_ids: new set is a superset of the current set
     * - condition_logic: "any" → "all" is more restrictive; "all" → "any" is not
     * - is_active: 0 → 1 is more restrictive; 1 → 0 is not
     * - name / config_lock_hours: neutral, ignored
     */
    private fun isMoreRestrictive(current: BlockingRule, changes: JSONObject): Boolean {
        if (changes.has("blocked_apps")) {
            val newApps = parseBlockedApps(changes.get("blocked_apps").let {
                if (it is JSONArray) it.toString() else it.toString()
            }).toSet()
            if (!newApps.containsAll(current.blockedApps.toSet())) return false
        }

        if (changes.has("blocked_domains")) {
            val newDomains = parseJsonStringArray(changes.get("blocked_domains").let {
                if (it is JSONArray) it.toString() else it.toString()
            }).toSet()
            if (!newDomains.containsAll(current.blockedDomains.toSet())) return false
        }

        if (changes.has("condition_task_ids")) {
            val newIds = parseJsonStringArray(changes.get("condition_task_ids").let {
                if (it is JSONArray) it.toString() else it.toString()
            }).toSet()
            if (!newIds.containsAll(current.conditionTaskIds.toSet())) return false
        }

        if (changes.has("condition_logic")) {
            val newLogic = changes.getString("condition_logic")
            // "any" → "all" is more restrictive; "all" → "any" is less
            if (current.conditionLogic == "all" && newLogic == "any") return false
        }

        if (changes.has("is_active")) {
            val newActive = changes.getInt("is_active") != 0
            // Deactivating is less restrictive
            if (current.isActive && !newActive) return false
        }

        return true
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
        // PowerSync can double-encode JSONB: strip surrounding quotes and retry
        val changes = try {
            JSONObject(pendingJson)
        } catch (_: Exception) {
            JSONObject(pendingJson.trim().removeSurrounding("\""))
        }
        val setClauses = mutableListOf<String>()
        val params = mutableListOf<Any?>()

        // Map each possible change key to its column
        val textFields = mapOf(
            "name" to "name",
            "blocked_apps" to "blocked_apps",
            "blocked_domains" to "blocked_domains",
            "condition_task_ids" to "condition_task_ids",
            "condition_logic" to "condition_logic",
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
        configLockHours  = cursor.getLong(7)?.toInt() ?: 24,
        pendingChanges   = cursor.getString(8),
        changesApplyAt   = cursor.getString(9),
        isActive         = (cursor.getLong(10) ?: 0L) != 0L,
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
