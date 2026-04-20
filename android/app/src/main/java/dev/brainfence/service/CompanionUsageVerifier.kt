package dev.brainfence.service

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.brainfence.data.completion.CompletionRepository
import dev.brainfence.data.debug.DebugLogRepository
import dev.brainfence.data.task.TaskRepository
import dev.brainfence.domain.model.Task
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fallback verification that queries Android's UsageStatsManager to detect
 * companion app usage for meditation tasks, even if the foreground service
 * wasn't running at the time.
 *
 * Called on app open (resume) as a reconciliation step.
 */
@Singleton
class CompanionUsageVerifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskRepository: TaskRepository,
    private val completionRepository: CompletionRepository,
    private val debugLog: DebugLogRepository,
) {
    companion object {
        private const val TAG = "CompanionUsageVerifier"
    }

    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Check all incomplete meditation tasks with companion apps.
     * If UsageStats shows a companion app was used for the required duration
     * within the task's availability window today, auto-complete the task.
     *
     * @return number of tasks auto-completed
     */
    suspend fun reconcile(): Int {
        if (!hasUsageStatsPermission()) {
            Log.d(TAG, "No usage stats permission — skipping reconciliation")
            return 0
        }

        val tasks = taskRepository.watchActiveTasks().first()
        var completed = 0

        for (task in tasks) {
            if (task.taskType != "meditation") continue
            if (task.completedToday) continue

            val config = task.verificationConfig?.let(MeditationTimerManager::parseMeditationConfig)
                ?: continue
            if (config.companionApps.isEmpty()) continue

            val usageSeconds = queryCompanionUsage(task, config)
            if (usageSeconds >= config.durationSeconds) {
                Log.i(TAG, "Completing '${task.title}' via usage stats (${usageSeconds}s >= ${config.durationSeconds}s)")
                debugLog.log(
                    "companion",
                    "Auto-completed '${task.title}' via usage stats (${usageSeconds}s)",
                    data = """{"method":"usage_stats","actual_seconds":$usageSeconds}""",
                )

                val verificationData = JSONObject().apply {
                    put("method", "usage_stats")
                    put("actual_seconds", usageSeconds)
                }.toString()

                completionRepository.completeTask(
                    taskId = task.id,
                    verificationData = verificationData,
                )
                completed++
            } else if (usageSeconds > 0) {
                Log.d(TAG, "'${task.title}': companion usage ${usageSeconds}s / ${config.durationSeconds}s (not enough)")
                debugLog.log(
                    "companion",
                    "Usage stats check: '${task.title}' — ${usageSeconds}s / ${config.durationSeconds}s (insufficient)",
                )
            }
        }

        if (completed > 0) {
            debugLog.log("companion", "Reconciliation completed $completed task(s) via usage stats")
        }
        return completed
    }

    /**
     * Query UsageEvents for the total foreground time of any companion app
     * within the task's availability window today.
     */
    private fun queryCompanionUsage(task: Task, config: MeditationConfig): Int {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        val windowStart = if (task.availableFrom != null) {
            val parts = task.availableFrom.split(":")
            today.atTime(LocalTime.of(parts[0].toInt(), parts[1].toInt()))
                .atZone(zone).toInstant().toEpochMilli()
        } else {
            today.atStartOfDay(zone).toInstant().toEpochMilli()
        }

        val windowEnd = System.currentTimeMillis()

        val events = usm.queryEvents(windowStart, windowEnd) ?: return 0

        // Track foreground sessions per companion app
        val foregroundStartTimes = mutableMapOf<String, Long>()
        var totalMs = 0L
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            if (pkg !in config.companionApps) continue

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    foregroundStartTimes[pkg] = event.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    val start = foregroundStartTimes.remove(pkg) ?: continue
                    totalMs += event.timeStamp - start
                }
            }
        }

        // If a companion app is still in the foreground, count up to now
        for ((_, start) in foregroundStartTimes) {
            totalMs += windowEnd - start
        }

        return (totalMs / 1_000).toInt()
    }
}
