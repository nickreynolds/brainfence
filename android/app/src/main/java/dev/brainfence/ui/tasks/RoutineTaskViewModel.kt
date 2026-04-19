package dev.brainfence.ui.tasks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.brainfence.data.routine.RoutineRepository
import dev.brainfence.data.task.TaskRepository
import dev.brainfence.domain.model.RoutineStep
import dev.brainfence.domain.model.Task
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

/** Per-set data entered by the user for a single step. */
data class StepSetEntry(
    val weight: Double? = null,
    val reps: Int? = null,
    val completed: Boolean = false,
    val timerElapsed: Int = 0,
    val timerRunning: Boolean = false,
)

/** Full UI state for one routine step across all its sets. */
data class StepUiState(
    val step: RoutineStep,
    val sets: List<StepSetEntry>,
)

@HiltViewModel
class RoutineTaskViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    taskRepository: TaskRepository,
    private val routineRepository: RoutineRepository,
) : ViewModel() {

    private val taskId: String = checkNotNull(savedStateHandle["taskId"])

    val task: StateFlow<Task?> = taskRepository.watchActiveTasks()
        .map { tasks -> tasks.find { it.id == taskId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val steps: StateFlow<List<RoutineStep>> = routineRepository.watchRoutineSteps(taskId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _stepStates = MutableStateFlow<Map<String, StepUiState>>(emptyMap())
    val stepStates: StateFlow<Map<String, StepUiState>> = _stepStates.asStateFlow()

    private val _isCompleting = MutableStateFlow(false)
    val isCompleting: StateFlow<Boolean> = _isCompleting.asStateFlow()

    private var prefilled = false

    init {
        // When steps arrive, initialise UI state and pre-fill from last completion
        viewModelScope.launch {
            steps.collect { stepList ->
                if (stepList.isNotEmpty() && !prefilled) {
                    prefilled = true
                    initStepStates(stepList)
                }
            }
        }
    }

    private suspend fun initStepStates(stepList: List<RoutineStep>) {
        val lastCompletions = routineRepository.getLastStepCompletions(taskId)
        // Group last completions by step id
        val byStep = lastCompletions.groupBy { it.routineStepId }

        val states = stepList.associate { step ->
            val config = JSONObject(step.config)
            val defaultSets = when (step.stepType) {
                "weight_reps", "just_reps" -> config.optInt("default_sets", 3)
                else -> 1
            }
            val previous = byStep[step.id]
            val numSets = previous?.size?.coerceAtLeast(defaultSets) ?: defaultSets

            val sets = (1..numSets).map { setNum ->
                val prev = previous?.find { it.setNumber == setNum }
                val prevData = prev?.let { JSONObject(it.data) }
                StepSetEntry(
                    weight = prevData?.optDouble("weight")?.takeIf { !it.isNaN() },
                    reps = prevData?.optInt("reps", 0)?.takeIf { it > 0 },
                )
            }
            step.id to StepUiState(step = step, sets = sets)
        }
        _stepStates.value = states
    }

    fun updateSet(stepId: String, setIndex: Int, entry: StepSetEntry) {
        _stepStates.value = _stepStates.value.toMutableMap().apply {
            val state = this[stepId] ?: return
            val newSets = state.sets.toMutableList()
            if (setIndex in newSets.indices) {
                newSets[setIndex] = entry
            }
            this[stepId] = state.copy(sets = newSets)
        }
    }

    fun toggleCheckbox(stepId: String) {
        val state = _stepStates.value[stepId] ?: return
        val current = state.sets.firstOrNull() ?: return
        updateSet(stepId, 0, current.copy(completed = !current.completed))
    }

    fun addSet(stepId: String) {
        _stepStates.value = _stepStates.value.toMutableMap().apply {
            val state = this[stepId] ?: return
            val lastSet = state.sets.lastOrNull() ?: StepSetEntry()
            // Copy weight/reps from last set as a convenience
            this[stepId] = state.copy(sets = state.sets + lastSet.copy(completed = false))
        }
    }

    fun removeSet(stepId: String) {
        _stepStates.value = _stepStates.value.toMutableMap().apply {
            val state = this[stepId] ?: return
            if (state.sets.size > 1) {
                this[stepId] = state.copy(sets = state.sets.dropLast(1))
            }
        }
    }

    fun startStepTimer(stepId: String, setIndex: Int = 0) {
        val state = _stepStates.value[stepId] ?: return
        val entry = state.sets.getOrNull(setIndex) ?: return
        updateSet(stepId, setIndex, entry.copy(timerRunning = true, timerElapsed = 0))

        val config = JSONObject(state.step.config)
        val target = config.optInt("duration_seconds", 60)

        viewModelScope.launch {
            var elapsed = 0
            while (elapsed < target) {
                kotlinx.coroutines.delay(1_000)
                val current = _stepStates.value[stepId]?.sets?.getOrNull(setIndex) ?: break
                if (!current.timerRunning) break
                elapsed++
                updateSet(stepId, setIndex, current.copy(timerElapsed = elapsed))
            }
            // Mark completed when timer finishes
            val final = _stepStates.value[stepId]?.sets?.getOrNull(setIndex) ?: return@launch
            if (final.timerRunning) {
                updateSet(stepId, setIndex, final.copy(timerRunning = false, completed = true))
            }
        }
    }

    fun stopStepTimer(stepId: String, setIndex: Int = 0) {
        val state = _stepStates.value[stepId] ?: return
        val entry = state.sets.getOrNull(setIndex) ?: return
        updateSet(stepId, setIndex, entry.copy(timerRunning = false))
    }

    fun finishRoutine(onDone: () -> Unit) {
        if (_isCompleting.value) return
        _isCompleting.value = true

        viewModelScope.launch {
            val states = _stepStates.value
            val stepData = states.mapValues { (_, state) ->
                state.sets.map { entry ->
                    val json = JSONObject()
                    when (state.step.stepType) {
                        "checkbox" -> json.put("completed", entry.completed)
                        "weight_reps" -> {
                            entry.weight?.let { json.put("weight", it) }
                            entry.reps?.let { json.put("reps", it) }
                        }
                        "just_reps" -> {
                            entry.reps?.let { json.put("reps", it) }
                        }
                        "timed" -> {
                            json.put("completed", entry.completed)
                            json.put("actual_seconds", entry.timerElapsed)
                        }
                    }
                    json.toString()
                }
            }

            routineRepository.completeRoutine(taskId, stepData)
            _isCompleting.value = false
            onDone()
        }
    }
}
