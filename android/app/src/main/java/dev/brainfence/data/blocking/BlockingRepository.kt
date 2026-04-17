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

    private fun mapRule(cursor: SqlCursor): BlockingRule = BlockingRule(
        id               = cursor.getString(0)!!,
        userId           = cursor.getString(1)!!,
        name             = cursor.getString(2) ?: "",
        blockedApps      = parseJsonStringArray(cursor.getString(3)),
        blockedDomains   = parseJsonStringArray(cursor.getString(4)),
        conditionTaskIds = parseJsonStringArray(cursor.getString(5)),
        conditionLogic   = cursor.getString(6) ?: "all",
        activeSchedule   = cursor.getString(7) ?: "{}",
        isActive         = (cursor.getLong(8) ?: 0L) != 0L,
    )

    private fun parseJsonStringArray(json: String?): List<String> {
        if (json.isNullOrBlank() || json == "null") return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { array.getString(it) }
    }
}
