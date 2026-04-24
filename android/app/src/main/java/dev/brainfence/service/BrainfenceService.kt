package dev.brainfence.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.AndroidEntryPoint
import dev.brainfence.MainActivity
import dev.brainfence.R
import dev.brainfence.data.blocking.BlockingRepository
import dev.brainfence.data.debug.DebugLogRepository
import dev.brainfence.data.task.TaskRepository
import dev.brainfence.domain.blocking.BlockingState
import dev.brainfence.domain.blocking.evaluateBlocking
import dev.brainfence.domain.model.BlockingRule
import dev.brainfence.domain.model.Task
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
import javax.inject.Inject

@AndroidEntryPoint
class BrainfenceService : Service() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "brainfence_service"
        const val NOTIFICATION_ID = 1
        private const val EVAL_INTERVAL_MS = 60_000L
        private const val BREADCRUMB_INTERVAL_EVALS = 5  // Log location every 5 evals (5 min)
        private const val TAG = "BrainfenceService"

        private val _blockingState = MutableStateFlow(
            BlockingState(emptySet(), emptySet(), emptyMap())
        )
        /** Current blocking state, observable from anywhere (e.g. AccessibilityService). */
        val blockingState: StateFlow<BlockingState> = _blockingState.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, BrainfenceService::class.java)
            context.startForegroundService(intent)
        }
    }

    @Inject lateinit var taskRepository: TaskRepository
    @Inject lateinit var blockingRepository: BlockingRepository
    @Inject lateinit var gpsVerificationManager: GpsVerificationManager
    @Inject lateinit var durationTimerManager: DurationTimerManager
    @Inject lateinit var meditationTimerManager: MeditationTimerManager
    @Inject lateinit var taskNotificationManager: TaskNotificationManager
    @Inject lateinit var debugLog: DebugLogRepository

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var evalJob: Job? = null
    private var currentTasks: List<Task> = emptyList()
    private var currentRules: List<BlockingRule> = emptyList()
    private var breadcrumbCounter = 0

    override fun onCreate() {
        super.onCreate()
        val hasLocation = hasLocationPermission()

        val fgsType = if (hasLocation) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        startForeground(NOTIFICATION_ID, buildNotification(), fgsType)
        observeData()
        startPeriodicEvaluation()
        gpsVerificationManager.startWatching()
        durationTimerManager.restoreTimers()
        meditationTimerManager.restoreTimers()
        Log.i(TAG, "Service created")
        scope.launch {
            debugLog.log("service", "BrainfenceService created (location permission: $hasLocation)")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        durationTimerManager.stop()
        meditationTimerManager.stop()
        gpsVerificationManager.stop()
        scope.launch { debugLog.log("service", "BrainfenceService destroyed") }
        scope.cancel()
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    /**
     * Observe tasks and blocking rules reactively.
     * Any change triggers an immediate re-evaluation.
     */
    private fun observeData() {
        scope.launch {
            taskRepository.watchActiveTasks().collect { tasks ->
                currentTasks = tasks
                runEvaluation()
                autoStartCompanionTracking(tasks)
            }
        }
        scope.launch {
            blockingRepository.watchActiveRules().collect { rules ->
                currentRules = rules
                runEvaluation()
            }
        }
    }

    /**
     * Periodic re-evaluation for time-based checks (schedule windows, time gates).
     * Data-driven changes are handled reactively by [observeData].
     * Also applies expired pending config changes and logs location breadcrumbs.
     */
    private fun startPeriodicEvaluation() {
        evalJob = scope.launch {
            while (true) {
                delay(EVAL_INTERVAL_MS)
                applyExpiredConfigChanges()
                runEvaluation()
                taskNotificationManager.evaluate(currentTasks)
                breadcrumbCounter++
                if (breadcrumbCounter % BREADCRUMB_INTERVAL_EVALS == 0) {
                    logLocationBreadcrumb()
                }
            }
        }
    }

    /**
     * Check for blocking rules with expired time-locks and promote their
     * pending changes to live config. The subsequent [runEvaluation] will
     * pick up the updated rules via the reactive [observeData] flow.
     */
    private suspend fun applyExpiredConfigChanges() {
        try {
            val applied = blockingRepository.applyExpiredPendingChanges()
            if (applied > 0) {
                Log.i(TAG, "Applied pending config changes to $applied rule(s)")
                debugLog.log("config_lock", "Applied pending config changes to $applied rule(s)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying pending config changes", e)
        }
    }

    /**
     * Automatically start companion app tracking for any active meditation task
     * that has companion apps configured and isn't already being tracked.
     */
    private fun autoStartCompanionTracking(tasks: List<Task>) {
        val activeTimers = meditationTimerManager.timerStates.value
        for (task in tasks) {
            if (task.taskType != "meditation") continue
            if (task.completedToday) continue

            val config = task.verificationConfig?.let(MeditationTimerManager::parseMeditationConfig)
            if (config == null || config.companionApps.isEmpty()) continue

            // If already tracking, verify companion apps match the current config.
            // A stale restored session may have wrong data — cancel and re-start.
            if (activeTimers.containsKey(task.id)) {
                val persisted = meditationTimerManager.getPersistedCompanionApps(task.id)
                if (persisted != null && persisted.toSet() == config.companionApps.toSet()) continue
                Log.w(TAG, "Companion apps mismatch for '${task.title}': persisted=$persisted, config=${config.companionApps} — restarting")
                scope.launch {
                    debugLog.log(
                        "companion",
                        "Restarting tracking for '${task.title}' — stale companion apps detected",
                        data = """{"persisted":"${persisted?.joinToString()}","config":"${config.companionApps.joinToString()}"}""",
                    )
                }
                meditationTimerManager.cancelTimer(task.id)
            }

            meditationTimerManager.persistCompanionApps(task.id, config.companionApps)
            meditationTimerManager.startCompanionTracking(
                taskId = task.id,
                taskTitle = task.title,
                targetSeconds = config.durationSeconds,
                companionApps = config.companionApps,
            )
            Log.i(TAG, "Auto-started companion tracking for '${task.title}' (apps=${config.companionApps})")
            scope.launch {
                debugLog.log(
                    "companion",
                    "Auto-started companion tracking for '${task.title}'",
                    data = config.companionApps.joinToString(),
                )
            }
        }
    }

    private fun runEvaluation() {
        val state = evaluateBlocking(currentRules, currentTasks)
        _blockingState.value = state
        taskNotificationManager.updateBlockingNotification(state)
        Log.d(TAG, "Evaluated: ${state.blockedApps.size} apps blocked")
    }

    /**
     * Periodically log the device's last known location for debugging.
     * Uses getLastLocation() which returns a cached location — no battery impact.
     */
    @SuppressLint("MissingPermission")
    private fun logLocationBreadcrumb() {
        if (!hasLocationPermission()) return
        val client = LocationServices.getFusedLocationProviderClient(this)
        client.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                scope.launch {
                    debugLog.log(
                        category = "location",
                        message = "Location breadcrumb",
                        lat = location.latitude,
                        lng = location.longitude,
                        accuracyM = location.accuracy,
                    )
                }
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
