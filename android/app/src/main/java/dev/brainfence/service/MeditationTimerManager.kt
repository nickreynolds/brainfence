package dev.brainfence.service

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.brainfence.R
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
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parsed meditation verification config.
 */
data class MeditationConfig(
    val durationSeconds: Int,
    val allowPause: Boolean,
    /** Interval in seconds between bell chimes (0 = no bells). */
    val bellIntervalSeconds: Int,
    /** Package names of companion apps (empty = in-app only). */
    val companionApps: List<String>,
)

/**
 * Observable state of an active meditation timer.
 */
data class MeditationTimerState(
    val taskId: String,
    val taskTitle: String,
    val targetSeconds: Int,
    val elapsedSeconds: Int,
    val startedAtMillis: Long,
    val running: Boolean,
    val pauses: Int,
    /** Which method is being used: "in_app_timer" or "companion_app". */
    val method: String,
    /** If companion_app method, which app is accumulating time. */
    val companionApp: String?,
    /** Whether the user navigated away (for in-app timer warning). */
    val navigatedAway: Boolean,
)

@Singleton
class MeditationTimerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val completionRepository: CompletionRepository,
    private val debugLog: DebugLogRepository,
) {
    companion object {
        private const val TAG = "MeditationTimerMgr"
        private const val PREFS_NAME = "meditation_timers"
        private const val TICK_INTERVAL_MS = 1_000L

        fun parseMeditationConfig(json: String): MeditationConfig? = try {
            val obj = try {
                JSONObject(json)
            } catch (_: Exception) {
                JSONObject(json.trim().removeSurrounding("\""))
            }
            val durationSeconds = when {
                obj.has("duration_seconds") -> obj.optInt("duration_seconds", 0)
                obj.has("target_seconds") -> obj.optInt("target_seconds", 0)
                else -> 0
            }
            if (durationSeconds <= 0) {
                null
            } else {
                val companionApps = mutableListOf<String>()
                val appsArray = obj.optJSONArray("companion_apps")
                if (appsArray != null) {
                    for (i in 0 until appsArray.length()) {
                        val entry = appsArray.get(i)
                        val pkg: String? = when (entry) {
                            is JSONObject -> {
                                // Only include apps for the current platform
                                val platform = entry.optString("platform", "")
                                if (platform == "android") entry.optString("package", null)
                                else null
                            }
                            is String -> entry.takeIf { it.isNotBlank() }
                            else -> null
                        }
                        pkg?.let { companionApps.add(it) }
                    }
                }
                Log.d(TAG, "Parsed meditation config: duration=${durationSeconds}s, companionApps=$companionApps")
                MeditationConfig(
                    durationSeconds = durationSeconds,
                    allowPause = obj.optBoolean("allow_pause", false),
                    bellIntervalSeconds = obj.optInt("bell_interval_seconds", 0),
                    companionApps = companionApps,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse meditation config: $json", e)
            null
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val timerJobs = mutableMapOf<String, Job>()
    private val companionJobs = mutableMapOf<String, Job>()

    private val _timerStates = MutableStateFlow<Map<String, MeditationTimerState>>(emptyMap())
    val timerStates: StateFlow<Map<String, MeditationTimerState>> = _timerStates.asStateFlow()

    private var soundPool: SoundPool? = null
    private var chimeId: Int = 0

    // ── In-App Timer ─────────────────────────────────────────────────

    /**
     * Start an in-app meditation timer.
     */
    fun startInAppTimer(
        taskId: String,
        taskTitle: String,
        targetSeconds: Int,
        bellIntervalSeconds: Int,
    ) {
        if (_timerStates.value.containsKey(taskId)) {
            Log.d(TAG, "Timer already active for task $taskId")
            return
        }

        val savedElapsed = prefs.getInt("elapsed_$taskId", 0)
        val savedPauses = prefs.getInt("pauses_$taskId", 0)

        val state = MeditationTimerState(
            taskId = taskId,
            taskTitle = taskTitle,
            targetSeconds = targetSeconds,
            elapsedSeconds = savedElapsed,
            startedAtMillis = System.currentTimeMillis(),
            running = true,
            pauses = savedPauses,
            method = "in_app_timer",
            companionApp = null,
            navigatedAway = false,
        )

        updateState(taskId, state)
        persistTimer(taskId, state)
        launchInAppTickJob(taskId, bellIntervalSeconds)

        if (bellIntervalSeconds > 0) {
            ensureSoundPool()
        }

        scope.launch {
            debugLog.log(
                "meditation",
                "Started in-app meditation for '$taskTitle' (target=${targetSeconds}s)",
            )
        }
        Log.i(TAG, "Started in-app timer for '$taskTitle' (target=${targetSeconds}s)")
    }

    /**
     * Called when the user navigates away from the meditation screen.
     * Pauses the timer and marks navigatedAway.
     */
    fun onNavigatedAway(taskId: String) {
        val state = _timerStates.value[taskId] ?: return
        if (state.method != "in_app_timer") return
        if (!state.running) return

        timerJobs[taskId]?.cancel()
        timerJobs.remove(taskId)

        val elapsed = computeCurrentElapsed(state)
        val pausedState = state.copy(
            elapsedSeconds = elapsed,
            running = false,
            pauses = state.pauses + 1,
            navigatedAway = true,
        )
        updateState(taskId, pausedState)
        persistTimer(taskId, pausedState)

        Log.d(TAG, "User navigated away — paused at ${elapsed}s (pause #${pausedState.pauses})")
    }

    /**
     * Called when the user returns to the meditation screen after navigating away.
     * Clears the navigatedAway flag but does NOT auto-resume — user must tap Resume.
     */
    fun onReturnedToApp(taskId: String) {
        val state = _timerStates.value[taskId] ?: return
        if (!state.navigatedAway) return
        updateState(taskId, state.copy(navigatedAway = false))
    }

    fun pauseTimer(taskId: String) {
        val state = _timerStates.value[taskId] ?: return
        if (!state.running) return

        timerJobs[taskId]?.cancel()
        timerJobs.remove(taskId)

        val elapsed = computeCurrentElapsed(state)
        val pausedState = state.copy(
            elapsedSeconds = elapsed,
            running = false,
            pauses = state.pauses + 1,
        )
        updateState(taskId, pausedState)
        persistTimer(taskId, pausedState)

        Log.d(TAG, "Paused meditation for '${state.taskTitle}' at ${elapsed}s")
    }

    fun resumeTimer(taskId: String) {
        val state = _timerStates.value[taskId] ?: return
        if (state.running) return

        val resumedState = state.copy(
            startedAtMillis = System.currentTimeMillis(),
            running = true,
            navigatedAway = false,
        )
        updateState(taskId, resumedState)
        persistTimer(taskId, resumedState)

        val bellInterval = prefs.getInt("bell_$taskId", 0)
        launchInAppTickJob(taskId, bellInterval)

        Log.d(TAG, "Resumed meditation for '${state.taskTitle}' at ${state.elapsedSeconds}s")
    }

    fun cancelTimer(taskId: String) {
        timerJobs[taskId]?.cancel()
        timerJobs.remove(taskId)
        companionJobs[taskId]?.cancel()
        companionJobs.remove(taskId)
        removeState(taskId)
        clearPersistedTimer(taskId)
        Log.d(TAG, "Cancelled timer for task $taskId")
    }

    fun getCurrentElapsed(taskId: String): Int {
        val state = _timerStates.value[taskId] ?: return 0
        return computeCurrentElapsed(state)
    }

    // ── Companion App Detection ──────────────────────────────────────

    /**
     * Start tracking companion app usage for a meditation task.
     * The timer accumulates time whenever any of the companion apps are in the foreground.
     */
    fun startCompanionTracking(
        taskId: String,
        taskTitle: String,
        targetSeconds: Int,
        companionApps: List<String>,
    ) {
        if (_timerStates.value.containsKey(taskId)) {
            Log.d(TAG, "Companion tracking already active for task $taskId")
            return
        }

        val savedElapsed = prefs.getInt("elapsed_$taskId", 0)

        val state = MeditationTimerState(
            taskId = taskId,
            taskTitle = taskTitle,
            targetSeconds = targetSeconds,
            elapsedSeconds = savedElapsed,
            startedAtMillis = System.currentTimeMillis(),
            running = false, // Not ticking until companion app is in foreground
            pauses = 0,
            method = "companion_app",
            companionApp = null,
            navigatedAway = false,
        )

        updateState(taskId, state)
        persistTimer(taskId, state)
        launchCompanionTrackingJob(taskId, companionApps)

        scope.launch {
            debugLog.log(
                "meditation",
                "Started companion tracking for '$taskTitle' (apps=${companionApps.joinToString()})",
            )
        }
        Log.i(TAG, "Started companion tracking for '$taskTitle'")
    }

    private fun launchCompanionTrackingJob(taskId: String, companionApps: List<String>) {
        companionJobs[taskId]?.cancel()
        Log.d(TAG, "Launching companion tracking job for task $taskId, watching for: $companionApps")
        scope.launch {
            debugLog.log(
                "companion",
                "Started watching for companion apps (task=$taskId)",
                data = companionApps.joinToString(),
            )
        }
        companionJobs[taskId] = scope.launch {
            BrainfenceAccessibilityService.foregroundApp.collect { foregroundPkg ->
                if (foregroundPkg == null) {
                    Log.d(TAG, "Foreground app is null (no app detected yet)")
                    return@collect
                }
                val state = _timerStates.value[taskId] ?: run {
                    Log.w(TAG, "No timer state for task $taskId — tracking job orphaned?")
                    return@collect
                }
                val isCompanionInForeground = foregroundPkg in companionApps

                Log.d(TAG, "Foreground: $foregroundPkg | companion=$isCompanionInForeground | running=${state.running} | elapsed=${state.elapsedSeconds}s/${state.targetSeconds}s")

                if (isCompanionInForeground && !state.running) {
                    // Companion app came to foreground — start accumulating
                    Log.i(TAG, "Companion app opened: $foregroundPkg — starting accumulation")
                    scope.launch {
                        debugLog.log(
                            "companion",
                            "Companion app opened: $foregroundPkg",
                            data = """{"taskId":"$taskId","elapsed":${state.elapsedSeconds},"target":${state.targetSeconds}}""",
                        )
                    }
                    val resumedState = state.copy(
                        startedAtMillis = System.currentTimeMillis(),
                        running = true,
                        companionApp = foregroundPkg,
                    )
                    updateState(taskId, resumedState)
                    launchCompanionTickJob(taskId)
                } else if (!isCompanionInForeground && state.running) {
                    // Companion app left foreground — pause accumulation
                    timerJobs[taskId]?.cancel()
                    timerJobs.remove(taskId)
                    val elapsed = computeCurrentElapsed(state)
                    Log.i(TAG, "Companion app lost foreground — paused at ${elapsed}s")
                    scope.launch {
                        debugLog.log(
                            "companion",
                            "Companion app left foreground — paused at ${elapsed}s",
                            data = """{"taskId":"$taskId","elapsed":$elapsed,"lastApp":"${state.companionApp}"}""",
                        )
                    }
                    updateState(taskId, state.copy(
                        elapsedSeconds = elapsed,
                        running = false,
                    ))
                    persistTimer(taskId, state.copy(elapsedSeconds = elapsed))
                } else if (!isCompanionInForeground && !state.running) {
                    // Non-companion app in foreground, not running — just log for visibility
                    scope.launch {
                        debugLog.log(
                            "companion",
                            "App in foreground (not companion): $foregroundPkg",
                            data = """{"watching_for":${companionApps.joinToString(prefix="[\"", postfix="\"]", separator="\",\"")}}""",
                        )
                    }
                }
            }
        }
    }

    private fun launchCompanionTickJob(taskId: String) {
        timerJobs[taskId]?.cancel()
        timerJobs[taskId] = scope.launch {
            while (true) {
                delay(TICK_INTERVAL_MS)
                val state = _timerStates.value[taskId] ?: break
                if (!state.running) break

                val elapsed = computeCurrentElapsed(state)
                if (elapsed >= state.targetSeconds) {
                    Log.i(TAG, "Companion meditation complete for '${state.taskTitle}' (${elapsed}s)")
                    scope.launch {
                        debugLog.log(
                            "companion",
                            "Meditation complete via companion app (${elapsed}s / ${state.targetSeconds}s)",
                            data = """{"taskId":"$taskId","app":"${state.companionApp}"}""",
                        )
                    }
                    completeTimer(taskId, elapsed)
                    // Also cancel the companion tracking job
                    companionJobs[taskId]?.cancel()
                    companionJobs.remove(taskId)
                    break
                }

                updateState(taskId, state.copy(
                    elapsedSeconds = elapsed,
                    startedAtMillis = System.currentTimeMillis(),
                ))
                if (elapsed % 10 == 0) {
                    persistTimer(taskId, state.copy(elapsedSeconds = elapsed))
                }
                // Log progress every 30 seconds
                if (elapsed % 30 == 0 && elapsed > 0) {
                    Log.d(TAG, "Companion accumulation: ${elapsed}s / ${state.targetSeconds}s (${state.companionApp})")
                    scope.launch {
                        debugLog.log(
                            "companion",
                            "Progress: ${elapsed}s / ${state.targetSeconds}s (${state.companionApp})",
                        )
                    }
                }
            }
        }
    }

    // ── Restore / Persist / Stop ─────────────────────────────────────

    fun restoreTimers() {
        val taskIds = prefs.getStringSet("active_task_ids", emptySet()) ?: emptySet()
        if (taskIds.isEmpty()) return

        for (taskId in taskIds) {
            val title = prefs.getString("title_$taskId", "") ?: ""
            val target = prefs.getInt("target_$taskId", 0)
            val elapsed = prefs.getInt("elapsed_$taskId", 0)
            val pauses = prefs.getInt("pauses_$taskId", 0)
            val method = prefs.getString("method_$taskId", "in_app_timer") ?: "in_app_timer"
            val companionApp = prefs.getString("companion_app_$taskId", null)

            if (target <= 0) {
                clearPersistedTimer(taskId)
                continue
            }

            if (elapsed >= target) {
                scope.launch { completeTimer(taskId, elapsed) }
                continue
            }

            val state = MeditationTimerState(
                taskId = taskId,
                taskTitle = title,
                targetSeconds = target,
                elapsedSeconds = elapsed,
                startedAtMillis = System.currentTimeMillis(),
                running = false,
                pauses = pauses,
                method = method,
                companionApp = companionApp,
                navigatedAway = false,
            )
            updateState(taskId, state)

            // For companion app method, re-launch tracking
            if (method == "companion_app") {
                val appsJson = prefs.getString("companion_apps_$taskId", null)
                Log.d(TAG, "Restoring companion tracking for '$title': raw companion_apps=$appsJson")
                if (appsJson != null) {
                    try {
                        val arr = JSONArray(appsJson)
                        val apps = (0 until arr.length()).map { arr.getString(it) }
                        Log.d(TAG, "Restored companion apps list: $apps")
                        scope.launch {
                            debugLog.log(
                                "companion",
                                "Restored companion tracking for '$title'",
                                data = apps.joinToString(),
                            )
                        }
                        launchCompanionTrackingJob(taskId, apps)
                    } catch (_: Exception) {}
                }
            }

            Log.i(TAG, "Restored paused meditation for '$title' (${elapsed}s / ${target}s, method=$method)")
            scope.launch {
                debugLog.log("meditation", "Restored meditation for '$title' (${elapsed}s / ${target}s)")
            }
        }
    }

    fun persistAllTimers() {
        for ((taskId, state) in _timerStates.value) {
            val elapsed = computeCurrentElapsed(state)
            persistTimer(taskId, state.copy(elapsedSeconds = elapsed))
        }
    }

    fun stop() {
        persistAllTimers()
        soundPool?.release()
        soundPool = null
        scope.cancel()
    }

    // ── Internal ─────────────────────────────────────────────────────

    private fun launchInAppTickJob(taskId: String, bellIntervalSeconds: Int) {
        timerJobs[taskId]?.cancel()
        timerJobs[taskId] = scope.launch {
            while (true) {
                delay(TICK_INTERVAL_MS)
                val state = _timerStates.value[taskId] ?: break
                if (!state.running) break

                val elapsed = computeCurrentElapsed(state)
                if (elapsed >= state.targetSeconds) {
                    playChime()
                    completeTimer(taskId, elapsed)
                    break
                }

                // Play interval bell
                if (bellIntervalSeconds > 0 && elapsed > 0 && elapsed % bellIntervalSeconds == 0) {
                    playChime()
                }

                updateState(taskId, state.copy(
                    elapsedSeconds = elapsed,
                    startedAtMillis = System.currentTimeMillis(),
                ))
                if (elapsed % 10 == 0) {
                    persistTimer(taskId, state.copy(elapsedSeconds = elapsed))
                }
            }
        }
    }

    private suspend fun completeTimer(taskId: String, actualSeconds: Int) {
        val state = _timerStates.value[taskId]
        val title = state?.taskTitle ?: taskId
        val method = state?.method ?: "in_app_timer"

        val verificationData = JSONObject().apply {
            put("method", method)
            if (method == "in_app_timer") {
                put("pauses", state?.pauses ?: 0)
            } else {
                put("app", state?.companionApp ?: "unknown")
            }
            put("actual_seconds", actualSeconds)
        }.toString()

        Log.i(TAG, "Meditation complete for '$title' (${actualSeconds}s, method=$method)")
        debugLog.log(
            "meditation",
            "Meditation complete for '$title' (${actualSeconds}s, method=$method)",
            data = verificationData,
        )

        completionRepository.completeTask(
            taskId = taskId,
            verificationData = verificationData,
        )

        timerJobs[taskId]?.cancel()
        timerJobs.remove(taskId)
        companionJobs[taskId]?.cancel()
        companionJobs.remove(taskId)
        removeState(taskId)
        clearPersistedTimer(taskId)
    }

    private fun computeCurrentElapsed(state: MeditationTimerState): Int {
        if (!state.running) return state.elapsedSeconds
        val now = System.currentTimeMillis()
        val runningSecs = ((now - state.startedAtMillis) / 1_000).toInt()
        return state.elapsedSeconds + runningSecs
    }

    private fun updateState(taskId: String, state: MeditationTimerState) {
        _timerStates.value = _timerStates.value + (taskId to state)
    }

    private fun removeState(taskId: String) {
        _timerStates.value = _timerStates.value - taskId
    }

    private fun persistTimer(taskId: String, state: MeditationTimerState) {
        val taskIds = (prefs.getStringSet("active_task_ids", emptySet()) ?: emptySet()) + taskId
        prefs.edit()
            .putStringSet("active_task_ids", taskIds)
            .putString("title_$taskId", state.taskTitle)
            .putInt("target_$taskId", state.targetSeconds)
            .putInt("elapsed_$taskId", state.elapsedSeconds)
            .putInt("pauses_$taskId", state.pauses)
            .putString("method_$taskId", state.method)
            .putString("companion_app_$taskId", state.companionApp)
            .apply()
    }

    fun getPersistedCompanionApps(taskId: String): List<String>? {
        val json = prefs.getString("companion_apps_$taskId", null) ?: return null
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { null }
    }

    fun persistCompanionApps(taskId: String, companionApps: List<String>) {
        prefs.edit()
            .putString("companion_apps_$taskId", JSONArray(companionApps).toString())
            .putInt("bell_$taskId", 0)
            .apply()
    }

    fun persistBellInterval(taskId: String, bellIntervalSeconds: Int) {
        prefs.edit()
            .putInt("bell_$taskId", bellIntervalSeconds)
            .apply()
    }

    private fun clearPersistedTimer(taskId: String) {
        val taskIds = (prefs.getStringSet("active_task_ids", emptySet()) ?: emptySet()) - taskId
        prefs.edit()
            .putStringSet("active_task_ids", taskIds)
            .remove("title_$taskId")
            .remove("target_$taskId")
            .remove("elapsed_$taskId")
            .remove("pauses_$taskId")
            .remove("method_$taskId")
            .remove("companion_app_$taskId")
            .remove("companion_apps_$taskId")
            .remove("bell_$taskId")
            .apply()
    }

    // ── Sound ────────────────────────────────────────────────────────

    private fun ensureSoundPool() {
        if (soundPool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(attrs)
            .build()
        chimeId = soundPool!!.load(context, R.raw.meditation_bell, 1)
    }

    private fun playChime() {
        soundPool?.play(chimeId, 1f, 1f, 1, 0, 1f)
    }
}
