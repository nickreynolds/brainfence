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
    val activeSetIndex: Int = 0,
)

/** Tracks round progress for a superset group. */
data class SupersetRoundState(
    val groupId: String,
    val stepIds: List<String>,
    val totalRounds: Int,
    val currentRound: Int = 1,
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

    private val _supersetRounds = MutableStateFlow<Map<String, SupersetRoundState>>(emptyMap())
    val supersetRounds: StateFlow<Map<String, SupersetRoundState>> = _supersetRounds.asStateFlow()

    private val _isCompleting = MutableStateFlow(false)
    val isCompleting: StateFlow<Boolean> = _isCompleting.asStateFlow()

    val allStepsCompleted: StateFlow<Boolean> = _stepStates
        .map { states -> states.isNotEmpty() && states.values.all { s -> s.sets.all { it.completed } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var prefilled = false

    init {
        // When steps arrive, initialise UI state and pre-fill from last completion
        viewModelScope.launch {
            steps.collect { stepList ->
                if (stepList.isNotEmpty() && !prefilled) {
                    prefilled = true
                    initStepStates(stepList)
                } else if (prefilled && stepList.isNotEmpty()) {
                    // Handle newly added steps after initial load
                    val currentIds = _stepStates.value.keys
                    val newSteps = stepList.filter { it.id !in currentIds }
                    if (newSteps.isNotEmpty()) {
                        val newStates = newSteps.associate { step ->
                            val config = JSONObject(step.config)
                            val defaultSets = when (step.stepType) {
                                "weight_reps", "just_reps" -> config.optInt("default_sets", 3)
                                else -> 1
                            }
                            val sets = (1..defaultSets).map { StepSetEntry() }
                            step.id to StepUiState(step = step, sets = sets)
                        }
                        _stepStates.value = _stepStates.value + newStates
                    }
                    // Remove states for deleted steps
                    val activeIds = stepList.map { it.id }.toSet()
                    val removed = currentIds - activeIds
                    if (removed.isNotEmpty()) {
                        _stepStates.value = _stepStates.value.filterKeys { it in activeIds }
                    }
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

            // For superset steps, sets represent rounds (1 set per round)
            val inSuperset = step.supersetGroup != null
            val numSets = if (inSuperset) {
                // Round count comes from the default_sets of the step
                defaultSets
            } else {
                val previous = byStep[step.id]
                previous?.size?.coerceAtLeast(defaultSets) ?: defaultSets
            }

            val previous = byStep[step.id]
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

        // Initialize superset round tracking
        val supersetGroups = stepList
            .filter { it.supersetGroup != null }
            .groupBy { it.supersetGroup!! }

        val rounds = supersetGroups.map { (groupId, groupSteps) ->
            // Use the max default_sets among the group's steps as the round count
            val totalRounds = groupSteps.maxOf { step ->
                val config = JSONObject(step.config)
                when (step.stepType) {
                    "weight_reps", "just_reps" -> config.optInt("default_sets", 3)
                    else -> 1
                }
            }
            groupId to SupersetRoundState(
                groupId = groupId,
                stepIds = groupSteps.map { it.id },
                totalRounds = totalRounds,
            )
        }.toMap()
        _supersetRounds.value = rounds
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
            val newSets = state.sets + lastSet.copy(completed = false)
            // If all previous sets were completed, move to the new set
            val newActiveIndex = if (state.sets.all { it.completed }) newSets.size - 1 else state.activeSetIndex
            this[stepId] = state.copy(sets = newSets, activeSetIndex = newActiveIndex)
        }
    }

    fun removeSet(stepId: String) {
        _stepStates.value = _stepStates.value.toMutableMap().apply {
            val state = this[stepId] ?: return
            if (state.sets.size > 1) {
                val newSets = state.sets.dropLast(1)
                val newActiveIndex = state.activeSetIndex.coerceAtMost(newSets.size - 1)
                this[stepId] = state.copy(sets = newSets, activeSetIndex = newActiveIndex)
            }
        }
    }

    fun completeCurrentSet(stepId: String) {
        _stepStates.value = _stepStates.value.toMutableMap().apply {
            val state = this[stepId] ?: return
            val idx = state.activeSetIndex
            val entry = state.sets.getOrNull(idx) ?: return
            val newSets = state.sets.toMutableList()
            newSets[idx] = entry.copy(completed = true)
            val nextIdx = if (idx + 1 < newSets.size) idx + 1 else idx
            this[stepId] = state.copy(sets = newSets, activeSetIndex = nextIdx)
        }
    }

    fun goToSet(stepId: String, setIndex: Int) {
        _stepStates.value = _stepStates.value.toMutableMap().apply {
            val state = this[stepId] ?: return
            if (setIndex in state.sets.indices) {
                this[stepId] = state.copy(activeSetIndex = setIndex)
            }
        }
    }

    fun removeStep(stepId: String) {
        viewModelScope.launch {
            routineRepository.deleteStep(stepId)
        }
    }

    fun addStep(title: String, stepType: String, defaultSets: Int, durationSeconds: Int) {
        viewModelScope.launch {
            val currentSteps = steps.value
            val nextOrder = (currentSteps.maxOfOrNull { it.stepOrder } ?: -1) + 1
            val config = JSONObject()
            when (stepType) {
                "weight_reps", "just_reps" -> config.put("default_sets", defaultSets)
                "timed" -> config.put("duration_seconds", durationSeconds)
            }
            routineRepository.insertSingleStep(
                taskId = taskId,
                step = dev.brainfence.data.routine.NewRoutineStep(
                    title = title,
                    stepType = stepType,
                    config = config.toString(),
                    supersetGroup = null,
                ),
                stepOrder = nextOrder,
            )
        }
    }

    fun advanceRound(groupId: String) {
        _supersetRounds.value = _supersetRounds.value.toMutableMap().apply {
            val state = this[groupId] ?: return
            if (state.currentRound < state.totalRounds) {
                // Mark current round's sets as completed for all steps in the group
                for (stepId in state.stepIds) {
                    val stepState = _stepStates.value[stepId] ?: continue
                    val setIndex = state.currentRound - 1
                    if (setIndex in stepState.sets.indices) {
                        val entry = stepState.sets[setIndex]
                        if (!entry.completed) {
                            updateSet(stepId, setIndex, entry.copy(completed = true))
                        }
                    }
                }
                this[groupId] = state.copy(currentRound = state.currentRound + 1)
            }
        }
    }

    fun goToRound(groupId: String, round: Int) {
        _supersetRounds.value = _supersetRounds.value.toMutableMap().apply {
            val state = this[groupId] ?: return
            if (round in 1..state.totalRounds) {
                this[groupId] = state.copy(currentRound = round)
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
