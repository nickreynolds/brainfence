package dev.brainfence.data.onboarding

import com.powersync.PowerSyncDatabase
import com.powersync.db.SqlCursor
import com.powersync.db.internal.PowerSyncTransaction
import dev.brainfence.data.auth.SessionRepository
import dev.brainfence.data.blocking.BlockingRepository
import dev.brainfence.data.contemplation.ContemplationLibrary
import dev.brainfence.data.contemplation.ContemplationRepository
import dev.brainfence.domain.onboarding.DeferredStep
import dev.brainfence.domain.onboarding.Onboarding
import dev.brainfence.domain.onboarding.OnboardingPlan
import dev.brainfence.domain.onboarding.OnboardingRow
import dev.brainfence.domain.onboarding.PlannedTask
import dev.brainfence.domain.onboarding.StarterKey
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The synced setup-guide state (one `onboarding` row per user, id = user_id)
 * and the writes that finish the guide or this device's setup. Shared
 * semantics: shared/CONTRACTS.md (Onboarding); same behaviour as
 * desktop-app/src/shared/repos/onboarding.ts.
 */
@Singleton
class OnboardingRepository @Inject constructor(
    private val database: PowerSyncDatabase,
    private val sessionRepository: SessionRepository,
    private val blockingRepository: BlockingRepository,
) {
    data class GuideResult(val taskIds: Map<StarterKey, String>, val ruleId: String?)

    /** Waits for the first full sync, so a missing row really means "no row". */
    suspend fun waitForFirstSync() = database.waitForFirstSync()

    suspend fun getRow(): OnboardingRow? =
        database.getOptional(
            sql = """
                SELECT status, deferred_steps, blocking_rule_id, meditation_task_id,
                       android_setup_at, windows_setup_at, macos_setup_at
                FROM onboarding LIMIT 1
            """.trimIndent(),
            mapper = ::mapRow,
        )

    suspend fun activeTaskCount(): Int =
        database.get("SELECT COUNT(*) FROM tasks WHERE status = 'active'") { (it.getLong(0) ?: 0L).toInt() }

    /** Skipped, or an existing account that never saw the guide. */
    suspend fun dismiss() {
        val userId = requireUserId()
        val exists = rowExists(userId)
        database.writeTransaction { tx -> upsert(tx, userId, exists, mapOf("status" to "dismissed")) }
    }

    /** Writes everything the guide planned, and marks the guide completed. */
    suspend fun applyGuide(plan: OnboardingPlan): GuideResult {
        val userId = requireUserId()
        val previous = getRow()
        val exists = rowExists(userId)
        val hasRemembrances = database.getOptional(
            sql = "SELECT id FROM contemplation_decks WHERE library_key = ?",
            parameters = listOf(ContemplationLibrary.FIVE_REMEMBRANCES.key),
        ) { cursor -> cursor.getString(0) ?: "" } != null
        val taskIds = plan.tasks.associate { it.key to UUID.randomUUID().toString() }
        val ruleId = plan.rule?.let { UUID.randomUUID().toString() }
        val now = Instant.now().toString()
        val madeShared = ruleId != null || taskIds[StarterKey.MEDITATION] != null

        val columns = mutableMapOf<String, Any?>(
            "status" to "completed",
            "completed_at" to now,
            "deferred_steps" to DeferredStep.encodeList(plan.deferredSteps),
            "blocking_rule_id" to (ruleId ?: previous?.blockingRuleId),
            "meditation_task_id" to (taskIds[StarterKey.MEDITATION] ?: previous?.meditationTaskId),
        )
        // Something new for other devices to add their own apps to: ask them again.
        for (p in OnboardingRow.PLATFORMS) {
            if (p == Onboarding.PLATFORM) columns[OnboardingRow.setupColumn(p)] = now
            else if (madeShared) columns[OnboardingRow.setupColumn(p)] = null
        }

        database.writeTransaction { tx ->
            for (task in plan.tasks) insertTask(tx, task, taskIds.getValue(task.key), userId)
            if (plan.addRemembrances && !hasRemembrances) {
                ContemplationRepository.insertLibraryDeck(tx, ContemplationLibrary.FIVE_REMEMBRANCES, userId)
            }
            val rule = plan.rule
            if (rule != null && ruleId != null) {
                tx.execute(
                    sql = """
                        INSERT INTO blocking_rules
                            (id, user_id, name, blocked_apps, blocked_domains,
                             condition_task_ids, condition_logic,
                             config_lock_hours, is_active, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 24, 1, ?, ?)
                    """.trimIndent(),
                    parameters = listOf(
                        ruleId, userId, rule.name,
                        JSONArray(rule.blockedApps).toString(), JSONArray(rule.blockedDomains).toString(),
                        JSONArray(rule.conditionKeys.mapNotNull { taskIds[it] }).toString(), rule.conditionLogic,
                        now, now,
                    ),
                )
            }
            upsert(tx, userId, exists, columns)
        }
        return GuideResult(taskIds, ruleId)
    }

    /**
     * Finishes this device's setup, then records it on the synced row.
     *
     * @param blockedApps    Android entries to add to the guide's rule.
     * @param companionApps  Android companion apps for the guide's meditation
     *                       task (replacing this platform's), or null to leave them.
     * @param deferredPlan   The deferred starters the user kept, planned with
     *                       the locations now known. Those still missing one stay deferred.
     */
    suspend fun finishDeviceSetup(
        blockedApps: List<JSONObject>,
        companionApps: List<JSONObject>?,
        deferredPlan: OnboardingPlan?,
    ) {
        val userId = requireUserId()
        val row = getRow()
        val rule = row?.blockingRuleId?.let { blockingRepository.getRuleById(it) }

        val newTaskIds = mutableListOf<String>()
        val tasks = deferredPlan?.tasks.orEmpty()
        if (tasks.isNotEmpty()) {
            database.writeTransaction { tx ->
                for (task in tasks) {
                    val id = UUID.randomUUID().toString()
                    if (task.isBlockingCondition) newTaskIds += id
                    insertTask(tx, task, id, userId)
                }
            }
        }

        if (rule != null) {
            val changes = JSONObject()
            val current = blockingRepository.getOtherPlatformBlockedApps(rule.id)
            rule.blockedApps.forEach { pkg -> current.put(JSONObject().put("platform", "android").put("package", pkg)) }
            val added = blockedApps.filter { it.optString("package") !in rule.blockedApps }
            if (added.isNotEmpty()) {
                added.forEach { current.put(it) }
                changes.put("blocked_apps", current)
            }
            if (newTaskIds.isNotEmpty()) changes.put("condition_task_ids", JSONArray(rule.conditionTaskIds + newTaskIds))
            // Adding apps, or conditions under "all", is stricter: applied at once.
            if (changes.length() > 0) blockingRepository.scheduleRuleChange(rule.id, changes)
        }

        val meditationId = row?.meditationTaskId
        if (companionApps != null && meditationId != null) {
            val raw = database.getOptional(
                sql = "SELECT verification_config FROM tasks WHERE id = ?",
                parameters = listOf(meditationId),
            ) { it.getString(0) ?: "{}" }
            if (raw != null) {
                val config = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
                val apps = JSONArray()
                val existing = config.optJSONArray("companion_apps") ?: JSONArray()
                for (i in 0 until existing.length()) {
                    val entry = existing.optJSONObject(i) ?: continue // bare strings are Android's: replaced
                    if (entry.optString("platform") != Onboarding.PLATFORM) apps.put(entry)
                }
                companionApps.forEach { apps.put(it) }
                config.put("companion_apps", apps)
                database.execute(
                    sql = "UPDATE tasks SET verification_config = ?, updated_at = ? WHERE id = ?",
                    parameters = listOf(config.toString(), Instant.now().toString(), meditationId),
                )
            }
        }

        val columns = mutableMapOf<String, Any?>(
            OnboardingRow.setupColumn(Onboarding.PLATFORM) to Instant.now().toString(),
            "deferred_steps" to DeferredStep.encodeList(deferredPlan?.deferredSteps.orEmpty()),
        )
        if (row == null) columns["status"] = "completed"
        val exists = rowExists(userId)
        database.writeTransaction { tx -> upsert(tx, userId, exists, columns) }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun requireUserId(): String = sessionRepository.currentUser?.id ?: error("Not authenticated")

    private suspend fun rowExists(userId: String): Boolean =
        database.getOptional("SELECT id FROM onboarding WHERE id = ?", listOf(userId)) { cursor -> cursor.getString(0) ?: "" } != null

    /** Inserts the user's row, or updates the given columns of it. */
    private fun upsert(tx: PowerSyncTransaction, userId: String, exists: Boolean, columns: Map<String, Any?>) {
        val now = Instant.now().toString()
        if (exists) {
            val keys = columns.keys.toList()
            tx.execute(
                sql = "UPDATE onboarding SET ${keys.joinToString(", ") { "$it = ?" }}, updated_at = ? WHERE id = ?",
                parameters = keys.map { columns[it] } + listOf(now, userId),
            )
        } else {
            val row = linkedMapOf<String, Any?>("deferred_steps" to "[]")
            row.putAll(columns)
            row["user_id"] = userId
            row["created_at"] = now
            row["updated_at"] = now
            val keys = row.keys.toList()
            tx.execute(
                sql = "INSERT INTO onboarding (id, ${keys.joinToString(", ")}) VALUES (?, ${keys.joinToString(", ") { "?" }})",
                parameters = listOf<Any?>(userId) + keys.map { row[it] },
            )
        }
    }

    private fun insertTask(tx: PowerSyncTransaction, task: PlannedTask, id: String, userId: String) {
        val now = Instant.now().toString()
        tx.execute(
            sql = """
                INSERT INTO tasks
                    (id, user_id, title, description, task_type, status,
                     recurrence_type, recurrence_config,
                     verification_type, verification_config,
                     tags, sort_order, is_blocking_condition, blocking_rule_ids,
                     available_from, due_at, home_only_blocking, blocking_days_of_week,
                     created_at, updated_at)
                VALUES (?, ?, ?, NULL, ?, 'active', ?, ?, ?, ?, '[]', ?, ?, '[]', ?, ?, 0, '[]', ?, ?)
            """.trimIndent(),
            // tags / blocking_rule_ids are TEXT[] / UUID[]: locally a JSON array.
            parameters = listOf(
                id, userId, task.title, task.taskType,
                task.recurrenceType, task.recurrenceConfig.toString(),
                task.verificationType, task.verificationConfig.toString(),
                task.sortOrder.toLong(), if (task.isBlockingCondition) 1L else 0L,
                task.availableFrom, task.dueAt, now, now,
            ),
        )
        task.steps.forEachIndexed { index, step ->
            tx.execute(
                sql = """
                    INSERT INTO routine_steps
                        (id, user_id, task_id, title, step_order, step_type, config, superset_group, created_at, description)
                    VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, NULL)
                """.trimIndent(),
                parameters = listOf(
                    UUID.randomUUID().toString(), userId, id, step.title, index.toLong(), step.stepType,
                    step.config.toString(), now,
                ),
            )
        }
    }

    private fun mapRow(cursor: SqlCursor): OnboardingRow = OnboardingRow(
        status = cursor.getString(0) ?: "",
        deferredSteps = DeferredStep.parseList(cursor.getString(1)),
        blockingRuleId = cursor.getString(2),
        meditationTaskId = cursor.getString(3),
        setupAt = OnboardingRow.PLATFORMS.withIndex()
            .mapNotNull { (i, p) -> cursor.getString(4 + i)?.let { p to it } }
            .toMap(),
    )
}
