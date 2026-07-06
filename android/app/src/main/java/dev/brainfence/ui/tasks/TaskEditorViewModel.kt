package dev.brainfence.ui.tasks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.powersync.PowerSyncDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.brainfence.data.apps.InstalledApp
import dev.brainfence.data.apps.InstalledAppsProvider
import dev.brainfence.data.auth.SessionRepository
import dev.brainfence.data.routine.NewRoutineStep
import dev.brainfence.data.routine.RoutineRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

data class EditableStep(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val stepType: String = "checkbox",
    val defaultSets: Int = 3,
    val durationSeconds: Int = 60,
    val supersetGroup: String? = null,
)

data class TaskEditorState(
    val currentStep: Int = 0, // wizard step 0-2
    // Step 0: Basics
    val title: String = "",
    val description: String = "",
    val taskType: String = "simple", // simple, timed, routine, workout, journal, shopping
    // Step 1: Type-specific config
    val verificationType: String = "manual", // manual, duration, gps, meditation
    // -- duration config --
    val durationSeconds: Int = 300,
    // -- GPS config (also the shopping reminder location) --
    val latitude: String = "",
    val longitude: String = "",
    val radiusMeters: Int = 100,
    // -- shopping notify window (prefilled for an evening commute) --
    val notifyDays: Set<String> = setOf("mon", "tue", "wed", "thu", "fri"),
    val notifyStart: String = "15:00", // HH:MM, blank = from midnight
    val notifyEnd: String = "21:00",   // HH:MM, blank = until midnight
    // -- meditation config --
    val meditationSeconds: Int = 300,
    /** Package names of companion apps that can record meditation time externally. */
    val companionApps: Set<String> = emptySet(),
    // -- routine steps (sub-tasks) --
    val routineSteps: List<EditableStep> = emptyList(),
    // Step 2: Recurrence + Blocking + Availability
    val recurrenceType: String? = null, // null, "daily", "weekly"
    val weeklyDays: Set<String> = emptySet(),
    val isBlockingCondition: Boolean = false,
    val homeOnlyBlocking: Boolean = false,
    /** Days of week the blocking condition is enforced. Empty = every day. */
    val blockingDaysOfWeek: Set<String> = emptySet(),
    val availableFrom: String = "",  // HH:MM — when task becomes completable
    val dueAt: String = "",          // HH:MM — when task becomes overdue / triggers blocking
    // General
    val isSaving: Boolean = false,
    val error: String? = null,
    /** Non-null when editing an existing task. Drives INSERT vs UPDATE on save. */
    val editingTaskId: String? = null,
    val isLoading: Boolean = false,
) {
    /** Shopping tasks skip the Schedule step — they never recur, block, or expire. */
    val totalSteps: Int get() = if (taskType == "shopping") 2 else 3
    val isLastStep: Boolean get() = currentStep == totalSteps - 1
}

@HiltViewModel
class TaskEditorViewModel @Inject constructor(
    private val database: PowerSyncDatabase,
    private val sessionRepository: SessionRepository,
    private val routineRepository: RoutineRepository,
    private val installedAppsProvider: InstalledAppsProvider,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(TaskEditorState())
    val state: StateFlow<TaskEditorState> = _state.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    init {
        viewModelScope.launch { _installedApps.value = installedAppsProvider.load() }
        val taskId: String? = savedStateHandle["taskId"]
        if (!taskId.isNullOrBlank()) loadExistingTask(taskId)
    }

    private fun loadExistingTask(taskId: String) {
        _state.value = _state.value.copy(isLoading = true, editingTaskId = taskId)
        viewModelScope.launch {
            try {
                val task = database.getOptional(
                    sql = """
                        SELECT id, title, description, task_type, recurrence_type, recurrence_config,
                               verification_type, verification_config, is_blocking_condition,
                               available_from, due_at, home_only_blocking, blocking_days_of_week
                        FROM tasks WHERE id = ?
                    """.trimIndent(),
                    parameters = listOf(taskId),
                ) { c ->
                    object {
                        val title = c.getString(1) ?: ""
                        val description = c.getString(2) ?: ""
                        val taskType = c.getString(3) ?: "simple"
                        val recurrenceType = c.getString(4)
                        val recurrenceConfig = c.getString(5) ?: "{}"
                        val verificationType = c.getString(6)
                        val verificationConfig = c.getString(7) ?: "{}"
                        val isBlockingCondition = (c.getLong(8) ?: 0L) != 0L
                        val availableFrom = c.getString(9) ?: ""
                        val dueAt = c.getString(10) ?: ""
                        val homeOnlyBlocking = (c.getLong(11) ?: 0L) != 0L
                        val blockingDaysOfWeek = c.getString(12) ?: "[]"
                    }
                } ?: run {
                    _state.value = _state.value.copy(isLoading = false, error = "Task not found")
                    return@launch
                }

                val vConfig = runCatching { JSONObject(task.verificationConfig) }.getOrDefault(JSONObject())
                val rConfig = runCatching { JSONObject(task.recurrenceConfig) }.getOrDefault(JSONObject())
                val notifyWindow = if (task.taskType == "shopping") vConfig.optJSONObject("notify_window") else null
                val (recurrenceTypeUi, weeklyDays) = uiRecurrenceFromStored(task.recurrenceType, rConfig)

                val steps = if (task.taskType == "routine" || task.taskType == "workout") {
                    routineRepository.watchRoutineSteps(taskId).first().map { rs ->
                        val sConfig = runCatching { JSONObject(rs.config) }.getOrDefault(JSONObject())
                        EditableStep(
                            id = rs.id,
                            title = rs.title,
                            stepType = rs.stepType,
                            defaultSets = sConfig.optInt("default_sets", 3),
                            durationSeconds = sConfig.optInt("duration_seconds", 60),
                            supersetGroup = rs.supersetGroup,
                        )
                    }
                } else emptyList()

                _state.value = _state.value.copy(
                    isLoading = false,
                    title = task.title,
                    description = task.description,
                    taskType = task.taskType,
                    verificationType = task.verificationType
                        ?: if (task.taskType == "timed") "duration" else "manual",
                    durationSeconds = if (task.taskType == "timed") {
                        vConfig.optInt("duration_seconds", 300)
                    } else 300,
                    latitude = if (task.verificationType == "gps") {
                        (if (vConfig.has("latitude")) vConfig.optDouble("latitude", 0.0)
                         else vConfig.optDouble("lat", 0.0)).toString()
                    } else "",
                    longitude = if (task.verificationType == "gps") {
                        (if (vConfig.has("longitude")) vConfig.optDouble("longitude", 0.0)
                         else vConfig.optDouble("lng", 0.0)).toString()
                    } else "",
                    radiusMeters = if (task.verificationType == "gps") {
                        if (vConfig.has("radius_meters")) vConfig.optInt("radius_meters", 100)
                        else vConfig.optInt("radius_m", 100)
                    } else 100,
                    notifyDays = notifyWindow?.optJSONArray("days")?.let { arr ->
                        buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) }
                    } ?: _state.value.notifyDays,
                    notifyStart = notifyWindow?.optString("start", "") ?: _state.value.notifyStart,
                    notifyEnd = notifyWindow?.optString("end", "") ?: _state.value.notifyEnd,
                    meditationSeconds = if (task.verificationType == "meditation") vConfig.optInt("duration_seconds", 300) else 300,
                    companionApps = if (task.verificationType == "meditation") parseCompanionApps(vConfig) else emptySet(),
                    routineSteps = steps,
                    recurrenceType = recurrenceTypeUi,
                    weeklyDays = weeklyDays,
                    isBlockingCondition = task.isBlockingCondition,
                    homeOnlyBlocking = task.homeOnlyBlocking,
                    blockingDaysOfWeek = parseBlockingDaysJson(task.blockingDaysOfWeek),
                    availableFrom = task.availableFrom,
                    dueAt = task.dueAt,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    /**
     * Parse companion app packages from `verification_config`. Accepts both the
     * typed form ({"platform":"android","package":"..."}) and bare-string form,
     * matching [dev.brainfence.service.MeditationTimerManager.parseMeditationConfig].
     */
    private fun parseCompanionApps(vConfig: JSONObject): Set<String> {
        val arr = vConfig.optJSONArray("companion_apps") ?: return emptySet()
        val out = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val entry = arr.opt(i)
            when (entry) {
                is JSONObject -> {
                    if (entry.optString("platform", "") == "android") {
                        entry.optString("package", "").takeIf { it.isNotBlank() }?.let(out::add)
                    }
                }
                is String -> entry.takeIf { it.isNotBlank() }?.let(out::add)
            }
        }
        return out
    }

    /**
     * Reverses [buildRecurrenceConfig]: turn the stored ("daily" + days array, "weekly" + day,
     * or null) form back into the UI's (recurrenceType, weeklyDays) pair.
     */
    private fun uiRecurrenceFromStored(
        storedType: String?,
        config: JSONObject,
    ): Pair<String?, Set<String>> = when (storedType) {
        "weekly" -> {
            val day = config.optString("day", "").ifBlank { null }
            "weekly" to (day?.let { setOf(it) } ?: emptySet())
        }
        "daily" -> {
            val daysArr = config.optJSONArray("days")
            if (daysArr != null && daysArr.length() > 0) {
                val days = (0 until daysArr.length()).map { daysArr.getString(it) }.toSet()
                "weekly" to days
            } else {
                "daily" to emptySet()
            }
        }
        else -> null to emptySet()
    }

    // --- Wizard navigation ---

    fun nextStep() {
        val s = _state.value
        when (s.currentStep) {
            0 -> {
                if (s.title.isBlank()) {
                    _state.value = s.copy(error = "Title is required")
                    return
                }
                _state.value = s.copy(currentStep = 1, error = null)
            }
            1 -> {
                if (s.isLastStep) return // shopping: step 1 is the final step
                if ((s.taskType == "routine" || s.taskType == "workout") && s.routineSteps.isEmpty()) {
                    _state.value = s.copy(error = "Add at least one step")
                    return
                }
                if ((s.taskType == "routine" || s.taskType == "workout") &&
                    s.routineSteps.any { it.title.isBlank() }) {
                    _state.value = s.copy(error = "All steps must have a title")
                    return
                }
                _state.value = s.copy(currentStep = 2, error = null)
            }
        }
    }

    fun prevStep() {
        val s = _state.value
        if (s.currentStep > 0) {
            _state.value = s.copy(currentStep = s.currentStep - 1, error = null)
        }
    }

    // --- Step 0: Basics ---

    fun updateTitle(title: String) {
        _state.value = _state.value.copy(title = title)
    }

    fun updateDescription(description: String) {
        _state.value = _state.value.copy(description = description)
    }

    fun setTaskType(type: String) {
        val s = _state.value
        // Reset type-specific fields when switching
        val verificationType = when (type) {
            "timed" -> "duration"
            "routine", "workout" -> "manual"
            "journal" -> "journal"
            "shopping" -> "gps"
            else -> s.verificationType
        }
        _state.value = s.copy(taskType = type, verificationType = verificationType)
    }

    // --- Step 1: Type-specific config ---

    fun setVerificationType(type: String) {
        _state.value = _state.value.copy(verificationType = type)
    }

    fun setDurationSeconds(seconds: Int) {
        _state.value = _state.value.copy(durationSeconds = seconds.coerceAtLeast(1))
    }

    fun setLatitude(lat: String) {
        _state.value = _state.value.copy(latitude = lat)
    }

    /** Set both coordinates at once — used by the map picker. */
    fun setLocation(lat: Double, lng: Double) {
        _state.value = _state.value.copy(
            latitude = String.format(Locale.US, "%.6f", lat),
            longitude = String.format(Locale.US, "%.6f", lng),
        )
    }

    fun setLongitude(lng: String) {
        _state.value = _state.value.copy(longitude = lng)
    }

    fun setRadiusMeters(radius: Int) {
        _state.value = _state.value.copy(radiusMeters = radius.coerceAtLeast(10))
    }

    fun setMeditationSeconds(seconds: Int) {
        _state.value = _state.value.copy(meditationSeconds = seconds.coerceAtLeast(1))
    }

    fun toggleCompanionApp(packageName: String) {
        _state.value = _state.value.let { s ->
            s.copy(
                companionApps = if (packageName in s.companionApps) s.companionApps - packageName
                                else s.companionApps + packageName,
            )
        }
    }

    fun toggleNotifyDay(day: String) {
        _state.value = _state.value.let { s ->
            s.copy(notifyDays = if (day in s.notifyDays) s.notifyDays - day else s.notifyDays + day)
        }
    }

    fun setNotifyStart(time: String) {
        _state.value = _state.value.copy(notifyStart = time)
    }

    fun setNotifyEnd(time: String) {
        _state.value = _state.value.copy(notifyEnd = time)
    }

    fun setAvailableFrom(time: String) {
        _state.value = _state.value.copy(availableFrom = time)
    }

    fun setDueAt(time: String) {
        _state.value = _state.value.copy(dueAt = time)
    }

    // --- Routine steps ---

    fun addRoutineStep() {
        _state.value = _state.value.copy(
            routineSteps = _state.value.routineSteps + EditableStep(),
        )
    }

    fun removeRoutineStep(stepId: String) {
        _state.value = _state.value.copy(
            routineSteps = _state.value.routineSteps.filter { it.id != stepId },
        )
    }

    fun updateRoutineStep(stepId: String, update: (EditableStep) -> EditableStep) {
        _state.value = _state.value.copy(
            routineSteps = _state.value.routineSteps.map {
                if (it.id == stepId) update(it) else it
            },
        )
    }

    fun moveStep(fromIndex: Int, toIndex: Int) {
        val steps = _state.value.routineSteps.toMutableList()
        if (fromIndex in steps.indices && toIndex in steps.indices) {
            val item = steps.removeAt(fromIndex)
            steps.add(toIndex, item)
            _state.value = _state.value.copy(routineSteps = steps)
        }
    }

    fun createSupersetGroup(stepIds: List<String>) {
        if (stepIds.size < 2) return
        val groupId = UUID.randomUUID().toString().take(8)
        _state.value = _state.value.copy(
            routineSteps = _state.value.routineSteps.map {
                if (it.id in stepIds) it.copy(supersetGroup = groupId) else it
            },
        )
    }

    fun removeSupersetGroup(groupId: String) {
        _state.value = _state.value.copy(
            routineSteps = _state.value.routineSteps.map {
                if (it.supersetGroup == groupId) it.copy(supersetGroup = null) else it
            },
        )
    }

    // --- Step 2: Recurrence + Blocking ---

    fun setRecurrenceType(type: String?) {
        _state.value = _state.value.copy(recurrenceType = type)
    }

    fun toggleWeeklyDay(day: String) {
        _state.value = _state.value.let { s ->
            s.copy(weeklyDays = if (day in s.weeklyDays) s.weeklyDays - day else s.weeklyDays + day)
        }
    }

    fun setBlockingCondition(enabled: Boolean) {
        _state.value = _state.value.copy(isBlockingCondition = enabled)
    }

    fun setHomeOnlyBlocking(enabled: Boolean) {
        _state.value = _state.value.copy(homeOnlyBlocking = enabled)
    }

    fun toggleBlockingDay(day: String) {
        _state.value = _state.value.let { s ->
            s.copy(
                blockingDaysOfWeek = if (day in s.blockingDaysOfWeek) s.blockingDaysOfWeek - day
                                     else s.blockingDaysOfWeek + day,
            )
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    // --- Save ---

    fun save(onComplete: () -> Unit) {
        val s = _state.value
        if (s.title.isBlank()) {
            _state.value = s.copy(error = "Title is required")
            return
        }
        if (s.taskType == "shopping" &&
            (s.latitude.toDoubleOrNull() == null || s.longitude.toDoubleOrNull() == null)
        ) {
            _state.value = s.copy(error = "A reminder location (latitude/longitude) is required")
            return
        }
        _state.value = s.copy(isSaving = true, error = null)

        viewModelScope.launch {
            try {
                val userId = sessionRepository.currentUser?.id
                    ?: error("Not authenticated")
                val now = Instant.now().toString()

                val taskType = s.taskType
                val verificationType = when (taskType) {
                    "timed" -> "duration"
                    "routine", "workout" -> null
                    "shopping" -> "gps"
                    else -> s.verificationType.takeIf { it != "manual" }
                }
                val verificationConfig = buildVerificationConfig(s)
                val recurrenceConfig = buildRecurrenceConfig(s)

                // RecurrenceEngine uses "daily" with a "days" array for multi-day
                // schedules, and "weekly" with a single "day" string.
                // Shopping lists never recur, block, or expire.
                val effectiveRecurrenceType = when {
                    taskType == "shopping" -> null
                    s.recurrenceType == "weekly" && s.weeklyDays.size > 1 -> "daily"
                    else -> s.recurrenceType
                }
                val isBlockingCondition = taskType != "shopping" && s.isBlockingCondition

                val availableFrom = if (taskType == "shopping") null else s.availableFrom.ifBlank { null }
                val dueAt = if (taskType == "shopping") null else s.dueAt.ifBlank { null }
                val blockingDaysJson = JSONArray(s.blockingDaysOfWeek.toList()).toString()

                val existingId = s.editingTaskId
                if (existingId != null) {
                    database.execute(
                        sql = """
                            UPDATE tasks SET
                                title = ?, description = ?, task_type = ?,
                                recurrence_type = ?, recurrence_config = ?,
                                verification_type = ?, verification_config = ?,
                                is_blocking_condition = ?, available_from = ?, due_at = ?,
                                home_only_blocking = ?, blocking_days_of_week = ?, updated_at = ?
                            WHERE id = ?
                        """.trimIndent(),
                        parameters = listOf(
                            s.title, s.description.ifBlank { null }, taskType,
                            effectiveRecurrenceType, recurrenceConfig,
                            verificationType, verificationConfig,
                            if (isBlockingCondition) 1 else 0, availableFrom, dueAt,
                            if (s.homeOnlyBlocking) 1 else 0, blockingDaysJson, now,
                            existingId,
                        ),
                    )
                    if (taskType == "routine" || taskType == "workout") {
                        syncRoutineSteps(existingId, s.routineSteps)
                    }
                } else {
                    val taskId = UUID.randomUUID().toString()
                    database.execute(
                        sql = """
                            INSERT INTO tasks
                                (id, user_id, title, description, task_type, status,
                                 recurrence_type, recurrence_config,
                                 verification_type, verification_config,
                                 tags, sort_order, is_blocking_condition, blocking_rule_ids,
                                 available_from, due_at, home_only_blocking, blocking_days_of_week,
                                 created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        parameters = listOf(
                            taskId, userId, s.title, s.description.ifBlank { null },
                            taskType, "active",
                            effectiveRecurrenceType, recurrenceConfig,
                            verificationType, verificationConfig,
                            "{}", 0, if (isBlockingCondition) 1 else 0, "{}",
                            availableFrom, dueAt, if (s.homeOnlyBlocking) 1 else 0, blockingDaysJson,
                            now, now,
                        ),
                    )

                    if (taskType == "routine" || taskType == "workout") {
                        val newSteps = s.routineSteps.map { step ->
                            NewRoutineStep(
                                title = step.title,
                                stepType = step.stepType,
                                config = stepConfigJson(step).toString(),
                                supersetGroup = step.supersetGroup,
                            )
                        }
                        routineRepository.insertRoutineSteps(taskId, newSteps)
                    }
                }

                _state.value = _state.value.copy(isSaving = false)
                onComplete()
            } catch (e: Exception) {
                _state.value = _state.value.copy(isSaving = false, error = e.message)
            }
        }
    }

    /**
     * Diff-sync routine steps for an existing task: insert new, update existing,
     * delete removed. Done in-place rather than delete-all-and-reinsert because
     * step_completions cascade-delete on routine_steps deletion — preserving
     * each step's UUID keeps the user's historical completion data intact.
     */
    private suspend fun syncRoutineSteps(taskId: String, edited: List<EditableStep>) {
        val now = Instant.now().toString()
        val existing = routineRepository.watchRoutineSteps(taskId).first()
        val existingById = existing.associateBy { it.id }
        val editedById = edited.associateBy { it.id }

        // Delete steps no longer present.
        for (step in existing) {
            if (step.id !in editedById) routineRepository.deleteStep(step.id)
        }

        // Insert or update.
        val userId = sessionRepository.currentUser?.id ?: error("Not authenticated")
        for ((index, step) in edited.withIndex()) {
            val configJson = stepConfigJson(step).toString()
            if (step.id in existingById) {
                database.execute(
                    sql = """
                        UPDATE routine_steps SET
                            title = ?, step_order = ?, step_type = ?,
                            config = ?, superset_group = ?
                        WHERE id = ?
                    """.trimIndent(),
                    parameters = listOf(
                        step.title, index, step.stepType,
                        configJson, step.supersetGroup,
                        step.id,
                    ),
                )
            } else {
                database.execute(
                    sql = """
                        INSERT INTO routine_steps
                            (id, user_id, task_id, title, step_order, step_type, config, superset_group, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    parameters = listOf(
                        step.id, userId, taskId, step.title, index, step.stepType,
                        configJson, step.supersetGroup, now,
                    ),
                )
            }
        }
    }

    private fun stepConfigJson(step: EditableStep): JSONObject {
        val cfg = JSONObject()
        when (step.stepType) {
            "weight_reps", "just_reps" -> cfg.put("default_sets", step.defaultSets)
            "timed" -> cfg.put("duration_seconds", step.durationSeconds)
        }
        return cfg
    }

    private fun buildVerificationConfig(s: TaskEditorState): String {
        val config = JSONObject()
        when {
            s.taskType == "timed" -> config.put("duration_seconds", s.durationSeconds)
            s.taskType == "shopping" -> {
                config.put("latitude", s.latitude.toDoubleOrNull() ?: 0.0)
                config.put("longitude", s.longitude.toDoubleOrNull() ?: 0.0)
                config.put("radius_meters", s.radiusMeters)
                config.put("mode", "notify")
                val window = JSONObject()
                window.put("days", JSONArray(s.notifyDays.toList()))
                if (s.notifyStart.isNotBlank()) window.put("start", s.notifyStart)
                if (s.notifyEnd.isNotBlank()) window.put("end", s.notifyEnd)
                config.put("notify_window", window)
            }
            s.verificationType == "gps" -> {
                val lat = s.latitude.toDoubleOrNull() ?: 0.0
                val lng = s.longitude.toDoubleOrNull() ?: 0.0
                config.put("latitude", lat)
                config.put("longitude", lng)
                config.put("radius_meters", s.radiusMeters)
            }
            s.verificationType == "meditation" -> {
                config.put("duration_seconds", s.meditationSeconds)
                val apps = JSONArray()
                for (pkg in s.companionApps) {
                    apps.put(JSONObject().apply {
                        put("platform", "android")
                        put("package", pkg)
                    })
                }
                config.put("companion_apps", apps)
            }
        }
        return config.toString()
    }

    private fun buildRecurrenceConfig(s: TaskEditorState): String {
        val config = JSONObject()
        if (s.recurrenceType == "weekly" && s.weeklyDays.isNotEmpty()) {
            if (s.weeklyDays.size == 1) {
                // RecurrenceEngine weekly expects {"day": "thu"}
                config.put("day", s.weeklyDays.first())
            } else {
                // Multiple days → stored as "daily" with a days filter
                config.put("days", org.json.JSONArray(s.weeklyDays.toList()))
            }
        }
        return config.toString()
    }

    private fun parseBlockingDaysJson(json: String): Set<String> = try {
        val arr = JSONArray(json)
        buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) }
    } catch (_: Exception) {
        emptySet()
    }
}
