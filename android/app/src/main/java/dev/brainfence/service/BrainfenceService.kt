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
     * Also logs location breadcrumbs every [BREADCRUMB_INTERVAL_EVALS] cycles.
     */
    private fun startPeriodicEvaluation() {
        evalJob = scope.launch {
            while (true) {
                delay(EVAL_INTERVAL_MS)
                runEvaluation()
                breadcrumbCounter++
                if (breadcrumbCounter % BREADCRUMB_INTERVAL_EVALS == 0) {
                    logLocationBreadcrumb()
                }
            }
        }
    }

    private fun runEvaluation() {
        val state = evaluateBlocking(currentRules, currentTasks)
        _blockingState.value = state
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
