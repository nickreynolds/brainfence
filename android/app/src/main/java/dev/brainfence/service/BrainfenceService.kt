package dev.brainfence.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
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
import dev.brainfence.data.settings.HomeLocation
import dev.brainfence.data.settings.HomeLocationRepository
import dev.brainfence.data.task.TaskRepository
import dev.brainfence.domain.blocking.BlockingState
import dev.brainfence.domain.blocking.HomePresence
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
import kotlinx.coroutines.flow.first
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

        /** Intent action: run a due-check (refresh verification + re-evaluate). */
        const val ACTION_DUE_CHECK = "dev.brainfence.service.DUE_CHECK"

        private val _blockingState = MutableStateFlow(
            BlockingState(emptySet(), emptySet(), emptyMap())
        )
        /** Current blocking state, observable from anywhere (e.g. AccessibilityService). */
        val blockingState: StateFlow<BlockingState> = _blockingState.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, BrainfenceService::class.java)
            context.startForegroundService(intent)
        }

        /** Poke the service to run a due-check when a [DueCheckReceiver] alarm fires. */
        fun requestDueCheck(context: Context) {
            val intent = Intent(context, BrainfenceService::class.java).apply {
                action = ACTION_DUE_CHECK
            }
            context.startForegroundService(intent)
        }
    }

    @Inject lateinit var taskRepository: TaskRepository
    @Inject lateinit var blockingRepository: BlockingRepository
    @Inject lateinit var gpsVerificationManager: GpsVerificationManager
    @Inject lateinit var durationTimerManager: DurationTimerManager
    @Inject lateinit var meditationTimerManager: MeditationTimerManager
    @Inject lateinit var taskNotificationManager: TaskNotificationManager
    @Inject lateinit var homeLocationRepository: HomeLocationRepository
    @Inject lateinit var companionUsageVerifier: CompanionUsageVerifier
    @Inject lateinit var debugLog: DebugLogRepository
    @Inject lateinit var blockingAlarmScheduler: BlockingAlarmScheduler

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var evalJob: Job? = null
    private var currentTasks: List<Task> = emptyList()
    private var currentRules: List<BlockingRule> = emptyList()
    private var currentHome: HomeLocation? = null
    private var lastKnownLocation: Location? = null
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
        logLocationBreadcrumb() // seed last-known location so home-presence is known sooner
        gpsVerificationManager.startWatching()
        durationTimerManager.restoreTimers()
        meditationTimerManager.restoreTimers()
        Log.i(TAG, "Service created")
        scope.launch {
            debugLog.log("service", "BrainfenceService created (location permission: $hasLocation)")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DUE_CHECK) {
            handleDueCheck()
        }
        return START_STICKY
    }

    /**
     * Handle a due-check alarm: pierce Doze with a fresh verification refresh so
     * a completed-but-unsynced task registers, then re-evaluate blocking and
     * notifications against fresh data, and schedule the next alarm.
     */
    private fun handleDueCheck() {
        scope.launch {
            try {
                gpsVerificationManager.refreshLeaveCompletions()
                companionUsageVerifier.reconcile()
            } catch (e: Exception) {
                Log.w(TAG, "Due-check refresh failed", e)
            }
            // Re-read fresh data — also covers a cold start where the reactive
            // fields aren't populated yet — and reflects any completion just written.
            val tasks = taskRepository.watchActiveTasks().first()
            val rules = blockingRepository.watchActiveRules().first()
            currentTasks = tasks
            currentRules = rules
            runEvaluation()
            val enforcedTaskIds = rules.flatMap { it.conditionTaskIds }.toSet()
            taskNotificationManager.evaluate(tasks, enforcedTaskIds)
            blockingAlarmScheduler.reschedule(tasks, enforcedTaskIds)
            debugLog.log("service", "Ran due-check (refresh + evaluate)")
        }
    }

    /** (Re)schedule the next Doze-piercing due-check alarm from current data. */
    private fun rescheduleBlockingAlarm() {
        val enforcedTaskIds = currentRules.flatMap { it.conditionTaskIds }.toSet()
        blockingAlarmScheduler.reschedule(currentTasks, enforcedTaskIds)
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
                rescheduleBlockingAlarm()
                autoStartCompanionTracking(tasks)
            }
        }
        scope.launch {
            blockingRepository.watchActiveRules().collect { rules ->
                currentRules = rules
                runEvaluation()
                rescheduleBlockingAlarm()
            }
        }
        scope.launch {
            homeLocationRepository.watch().collect { home ->
                currentHome = home
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
                // Tasks that actually block apps = conditions of active rules.
                // watchActiveRules() already filters to active rules.
                val enforcedTaskIds = currentRules.flatMap { it.conditionTaskIds }.toSet()
                taskNotificationManager.evaluate(currentTasks, enforcedTaskIds)
                blockingAlarmScheduler.reschedule(currentTasks, enforcedTaskIds)
                breadcrumbCounter++
                if (breadcrumbCounter % BREADCRUMB_INTERVAL_EVALS == 0) {
                    logLocationBreadcrumb()
                    gpsVerificationManager.periodicLeaveCheck()
                    // Pull companion-app foreground time from Android's usage
                    // stats so the meditation tally stays accurate even when
                    // accessibility events were missed (service restart, etc.).
                    try {
                        companionUsageVerifier.reconcile()
                    } catch (e: Exception) {
                        Log.w(TAG, "Companion usage reconciliation failed", e)
                    }
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
            if (task.verificationType != "meditation") continue
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
        val state = evaluateBlocking(
            rules = currentRules,
            tasks = currentTasks,
            homePresence = computeHomePresence(),
        )
        _blockingState.value = state
        taskNotificationManager.updateBlockingNotification(state)
        Log.d(TAG, "Evaluated: ${state.blockedApps.size} apps blocked")
    }

    /**
     * Classify the user's current location relative to the configured home.
     * Strict-by-default: any "we don't really know" case keeps blocking on
     * (treated as AT_HOME by the evaluator) so users can't sidestep blocking
     * by denying location permission or sitting somewhere with no GPS fix.
     */
    private fun computeHomePresence(): HomePresence {
        val home = currentHome ?: return HomePresence.UNCONFIGURED
        val loc = lastKnownLocation ?: return HomePresence.UNKNOWN
        val distance = FloatArray(1)
        Location.distanceBetween(
            loc.latitude, loc.longitude,
            home.latitude, home.longitude,
            distance,
        )
        return if (distance[0] <= home.radiusMeters) HomePresence.AT_HOME else HomePresence.AWAY
    }

    /**
     * Periodically refresh the device's last known location: powers the
     * home-presence check for blocking and feeds a debug breadcrumb.
     * Uses getLastLocation() which returns a cached location — no battery impact.
     */
    @SuppressLint("MissingPermission")
    private fun logLocationBreadcrumb() {
        if (!hasLocationPermission()) return
        val client = LocationServices.getFusedLocationProviderClient(this)
        client.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                lastKnownLocation = location
                runEvaluation()
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
