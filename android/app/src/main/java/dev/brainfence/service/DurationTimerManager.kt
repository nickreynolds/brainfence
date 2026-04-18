package dev.brainfence.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.brainfence.data.completion.CompletionRepository
import dev.brainfence.data.debug.DebugLogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parsed duration verification config from a task's verification_config JSON.
 */
data class DurationConfig(
    val targetSeconds: Int,
)

/**
 * Observable state of an active duration timer.
 */
data class DurationTimerState(
    val taskId: String,
    val taskTitle: String,
    val targetSeconds: Int,
    /** Seconds already elapsed (from previous runs if paused/killed). */
    val elapsedSeconds: Int,
    /** System time (millis) when the timer was last started/resumed. */
    val startedAtMillis: Long,
    /** Whether the timer is currently ticking. */
    val running: Boolean,
)

/**
 * Manages duration-based task timers.
 *
 * Timers run in the foreground service scope and survive navigation.
 * On app kill, elapsed time is persisted to SharedPreferences and
 * restored on next launch via [restoreTimers].
 */
@Singleton
class DurationTimerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val completionRepository: CompletionRepository,
    private val debugLog: DebugLogRepository,
) {
    companion object {
        private const val TAG = "DurationTimerMgr"
        private const val PREFS_NAME = "duration_timers"
        private const val TICK_INTERVAL_MS = 1_000L

        fun parseDurationConfig(json: String): DurationConfig? = try {
            val obj = try {
                JSONObject(json)
            } catch (_: Exception) {
                // PowerSync can double-encode JSONB: strip surrounding quotes and retry
                JSONObject(json.trim().removeSurrounding("\""))
            }
            val targetSeconds = when {
                obj.has("target_seconds") -> obj.optInt("target_seconds", 0)
                obj.has("duration_seconds") -> obj.optInt("duration_seconds", 0)
                else -> 0
            }
            if (targetSeconds > 0) DurationConfig(targetSeconds) else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse duration config: $json", e)
            null
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Active timer states keyed by task ID. */
    private val timerJobs = mutableMapOf<String, Job>()

    /** Observable map of active timer states. */
    private val _timerStates = MutableStateFlow<Map<String, DurationTimerState>>(emptyMap())
    val timerStates: StateFlow<Map<String, DurationTimerState>> = _timerStates.asStateFlow()

    /**
     * Start a duration timer for a task.
     * If a timer is already running for this task, this is a no-op.
     */
    fun startTimer(taskId: String, taskTitle: String, targetSeconds: Int) {
        if (_timerStates.value.containsKey(taskId)) {
            Log.d(TAG, "Timer already active for task $taskId")
            return
        }

        // Check for persisted elapsed time from a previous app session
        val savedElapsed = prefs.getInt("elapsed_$taskId", 0)

        val state = DurationTimerState(
            taskId = taskId,
            taskTitle = taskTitle,
            targetSeconds = targetSeconds,
            elapsedSeconds = savedElapsed,
            startedAtMillis = System.currentTimeMillis(),
            running = true,
        )

        updateState(taskId, state)
        persistTimer(taskId, state)
        launchTickJob(taskId)

        scope.launch {
            debugLog.log(
                "duration",
                "Started timer for '$taskTitle' (target=${targetSeconds}s, resumed=${savedElapsed}s)",
            )
        }
        Log.i(TAG, "Started timer for '$taskTitle' (target=${targetSeconds}s)")
    }

    /**
     * Pause a running timer.
     * Elapsed time is persisted so it survives app kill.
     */
    fun pauseTimer(taskId: String) {
        val state = _timerStates.value[taskId] ?: return
        if (!state.running) return

        timerJobs[taskId]?.cancel()
        timerJobs.remove(taskId)

        val elapsed = computeCurrentElapsed(state)
        val pausedState = state.copy(
            elapsedSeconds = elapsed,
            running = false,
        )
        updateState(taskId, pausedState)
        persistTimer(taskId, pausedState)

        Log.d(TAG, "Paused timer for '${state.taskTitle}' at ${elapsed}s")
    }

    /**
     * Resume a paused timer.
     */
    fun resumeTimer(taskId: String) {
        val state = _timerStates.value[taskId] ?: return
        if (state.running) return

        val resumedState = state.copy(
            startedAtMillis = System.currentTimeMillis(),
            running = true,
        )
        updateState(taskId, resumedState)
        persistTimer(taskId, resumedState)
        launchTickJob(taskId)

        Log.d(TAG, "Resumed timer for '${state.taskTitle}' at ${state.elapsedSeconds}s")
    }

    /**
     * Cancel a timer entirely (e.g. user navigates away and explicitly cancels).
     */
    fun cancelTimer(taskId: String) {
        timerJobs[taskId]?.cancel()
        timerJobs.remove(taskId)
        removeState(taskId)
        clearPersistedTimer(taskId)
        Log.d(TAG, "Cancelled timer for task $taskId")
    }

    /**
     * Get the current elapsed seconds for a timer (computed live).
     */
    fun getCurrentElapsed(taskId: String): Int {
        val state = _timerStates.value[taskId] ?: return 0
        return computeCurrentElapsed(state)
    }

    /**
     * Restore any persisted timers from SharedPreferences.
     * Called on service start to recover from app kill.
     * Timers resume in paused state — user must manually resume.
     */
    fun restoreTimers() {
        val taskIds = prefs.getStringSet("active_task_ids", emptySet()) ?: emptySet()
        if (taskIds.isEmpty()) return

        for (taskId in taskIds) {
            val title = prefs.getString("title_$taskId", "") ?: ""
            val target = prefs.getInt("target_$taskId", 0)
            val elapsed = prefs.getInt("elapsed_$taskId", 0)

            if (target <= 0) {
                clearPersistedTimer(taskId)
                continue
            }

            if (elapsed >= target) {
                // Timer was complete but completion wasn't recorded — complete now
                scope.launch {
                    completeTimer(taskId, elapsed)
                }
                continue
            }

            val state = DurationTimerState(
                taskId = taskId,
                taskTitle = title,
                targetSeconds = target,
                elapsedSeconds = elapsed,
                startedAtMillis = System.currentTimeMillis(),
                running = false, // Paused on restore — user resumes
            )
            updateState(taskId, state)
            Log.i(TAG, "Restored paused timer for '$title' (${elapsed}s / ${target}s)")
            scope.launch {
                debugLog.log("duration", "Restored paused timer for '$title' (${elapsed}s / ${target}s)")
            }
        }
    }

    /**
     * Persist all running timers' current elapsed time.
     * Call this from onDestroy / onTrimMemory to survive app kill.
     */
    fun persistAllTimers() {
        for ((taskId, state) in _timerStates.value) {
            val elapsed = computeCurrentElapsed(state)
            persistTimer(taskId, state.copy(elapsedSeconds = elapsed))
        }
    }

    fun stop() {
        persistAllTimers()
        scope.cancel()
    }

    // ── Internal ──────────────────────────────────────────────────────

    private fun launchTickJob(taskId: String) {
        timerJobs[taskId]?.cancel()
        timerJobs[taskId] = scope.launch {
            while (true) {
                delay(TICK_INTERVAL_MS)
                val state = _timerStates.value[taskId] ?: break
                if (!state.running) break

                val elapsed = computeCurrentElapsed(state)
                if (elapsed >= state.targetSeconds) {
                    completeTimer(taskId, elapsed)
                    break
                }

                // Update state so UI observers see the tick
                updateState(taskId, state.copy(elapsedSeconds = elapsed, startedAtMillis = System.currentTimeMillis()))
                // Periodically persist in case of kill
                if (elapsed % 10 == 0) {
                    persistTimer(taskId, state.copy(elapsedSeconds = elapsed))
                }
            }
        }
    }

    private suspend fun completeTimer(taskId: String, actualSeconds: Int) {
        val state = _timerStates.value[taskId]
        val title = state?.taskTitle ?: taskId

        val verificationData = JSONObject().apply {
            put("actual_seconds", actualSeconds)
        }.toString()

        Log.i(TAG, "Duration timer complete for '$title' (${actualSeconds}s)")
        debugLog.log("duration", "Timer complete for '$title' (${actualSeconds}s)", data = verificationData)

        completionRepository.completeTask(
            taskId = taskId,
            verificationData = verificationData,
        )

        timerJobs[taskId]?.cancel()
        timerJobs.remove(taskId)
        removeState(taskId)
        clearPersistedTimer(taskId)
    }

    private fun computeCurrentElapsed(state: DurationTimerState): Int {
        if (!state.running) return state.elapsedSeconds
        val now = System.currentTimeMillis()
        val runningSecs = ((now - state.startedAtMillis) / 1_000).toInt()
        return state.elapsedSeconds + runningSecs
    }

    private fun updateState(taskId: String, state: DurationTimerState) {
        _timerStates.value = _timerStates.value + (taskId to state)
    }

    private fun removeState(taskId: String) {
        _timerStates.value = _timerStates.value - taskId
    }

    private fun persistTimer(taskId: String, state: DurationTimerState) {
        val taskIds = (prefs.getStringSet("active_task_ids", emptySet()) ?: emptySet()) + taskId
        prefs.edit()
            .putStringSet("active_task_ids", taskIds)
            .putString("title_$taskId", state.taskTitle)
            .putInt("target_$taskId", state.targetSeconds)
            .putInt("elapsed_$taskId", state.elapsedSeconds)
            .apply()
    }

    private fun clearPersistedTimer(taskId: String) {
        val taskIds = (prefs.getStringSet("active_task_ids", emptySet()) ?: emptySet()) - taskId
        prefs.edit()
            .putStringSet("active_task_ids", taskIds)
            .remove("title_$taskId")
            .remove("target_$taskId")
            .remove("elapsed_$taskId")
            .apply()
    }
}
