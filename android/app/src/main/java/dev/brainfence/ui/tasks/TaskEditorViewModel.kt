package dev.brainfence.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.powersync.PowerSyncDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.brainfence.data.auth.SessionRepository
import dev.brainfence.data.routine.NewRoutineStep
import dev.brainfence.data.routine.RoutineRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
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
    val taskType: String = "simple", // simple, timed, routine, workout
    // Step 1: Type-specific config
    val verificationType: String = "manual", // manual, duration, gps, meditation, time_gate
    // -- duration config --
    val durationSeconds: Int = 300,
    // -- GPS config --
    val latitude: String = "",
    val longitude: String = "",
    val radiusMeters: Int = 100,
    // -- meditation config --
    val meditationSeconds: Int = 300,
    val allowCompanion: Boolean = true,
    // -- time gate config --
    val startTime: String = "",
    val endTime: String = "",
    // -- routine steps (sub-tasks) --
    val routineSteps: List<EditableStep> = emptyList(),
    // Step 2: Recurrence + Blocking
    val recurrenceType: String? = null, // null, "daily", "weekly"
    val weeklyDays: Set<String> = emptySet(),
    val isBlockingCondition: Boolean = false,
    // General
    val isSaving: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class TaskEditorViewModel @Inject constructor(
    private val database: PowerSyncDatabase,
    private val sessionRepository: SessionRepository,
    private val routineRepository: RoutineRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TaskEditorState())
    val state: StateFlow<TaskEditorState> = _state.asStateFlow()

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

    fun setLongitude(lng: String) {
        _state.value = _state.value.copy(longitude = lng)
    }

    fun setRadiusMeters(radius: Int) {
        _state.value = _state.value.copy(radiusMeters = radius.coerceAtLeast(10))
    }

    fun setMeditationSeconds(seconds: Int) {
        _state.value = _state.value.copy(meditationSeconds = seconds.coerceAtLeast(1))
    }

    fun setAllowCompanion(allow: Boolean) {
        _state.value = _state.value.copy(allowCompanion = allow)
    }

    fun setStartTime(time: String) {
        _state.value = _state.value.copy(startTime = time)
    }

    fun setEndTime(time: String) {
        _state.value = _state.value.copy(endTime = time)
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
        _state.value = s.copy(isSaving = true, error = null)

        viewModelScope.launch {
            try {
                val userId = sessionRepository.currentUser?.id
                    ?: error("Not authenticated")
                val taskId = UUID.randomUUID().toString()
                val now = Instant.now().toString()

                val taskType = s.taskType
                val verificationType = when (taskType) {
                    "timed" -> "duration"
                    "routine", "workout" -> null
                    else -> s.verificationType.takeIf { it != "manual" }
                }
                val verificationConfig = buildVerificationConfig(s)
                val recurrenceConfig = buildRecurrenceConfig(s)

                // RecurrenceEngine uses "daily" with a "days" array for multi-day
                // schedules, and "weekly" with a single "day" string.
                val effectiveRecurrenceType = when {
                    s.recurrenceType == "weekly" && s.weeklyDays.size > 1 -> "daily"
                    else -> s.recurrenceType
                }

                database.execute(
                    sql = """
                        INSERT INTO tasks
                            (id, user_id, title, description, task_type, status,
                             recurrence_type, recurrence_config,
                             verification_type, verification_config,
                             tags, sort_order, is_blocking_condition, blocking_rule_ids,
                             created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    parameters = listOf(
                        taskId, userId, s.title, s.description.ifBlank { null },
                        taskType, "active",
                        effectiveRecurrenceType, recurrenceConfig,
                        verificationType, verificationConfig,
                        "{}", 0, if (s.isBlockingCondition) 1 else 0, "{}",
                        now, now,
                    ),
                )

                // Insert routine steps if applicable
                if (taskType == "routine" || taskType == "workout") {
                    val newSteps = s.routineSteps.map { step ->
                        val config = JSONObject()
                        when (step.stepType) {
                            "weight_reps", "just_reps" -> config.put("default_sets", step.defaultSets)
                            "timed" -> config.put("duration_seconds", step.durationSeconds)
                        }
                        NewRoutineStep(
                            title = step.title,
                            stepType = step.stepType,
                            config = config.toString(),
                            supersetGroup = step.supersetGroup,
                        )
                    }
                    routineRepository.insertRoutineSteps(taskId, newSteps)
                }

                _state.value = _state.value.copy(isSaving = false)
                onComplete()
            } catch (e: Exception) {
                _state.value = _state.value.copy(isSaving = false, error = e.message)
            }
        }
    }

    private fun buildVerificationConfig(s: TaskEditorState): String {
        val config = JSONObject()
        when {
            s.taskType == "timed" -> config.put("duration_seconds", s.durationSeconds)
            s.verificationType == "gps" -> {
                val lat = s.latitude.toDoubleOrNull() ?: 0.0
                val lng = s.longitude.toDoubleOrNull() ?: 0.0
                config.put("latitude", lat)
                config.put("longitude", lng)
                config.put("radius_meters", s.radiusMeters)
            }
            s.verificationType == "meditation" -> {
                config.put("duration_seconds", s.meditationSeconds)
                config.put("allow_companion", s.allowCompanion)
            }
            s.verificationType == "time_gate" -> {
                config.put("start_time", s.startTime)
                config.put("end_time", s.endTime)
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
}
