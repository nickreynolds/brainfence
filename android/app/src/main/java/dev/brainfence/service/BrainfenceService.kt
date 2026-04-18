package dev.brainfence.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.brainfence.MainActivity
import dev.brainfence.R
import dev.brainfence.data.blocking.BlockingRepository
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

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var evalJob: Job? = null
    private var currentTasks: List<Task> = emptyList()
    private var currentRules: List<BlockingRule> = emptyList()

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        observeData()
        startPeriodicEvaluation()
        gpsVerificationManager.startWatching()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        gpsVerificationManager.stop()
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
     */
    private fun startPeriodicEvaluation() {
        evalJob = scope.launch {
            while (true) {
                delay(EVAL_INTERVAL_MS)
                runEvaluation()
            }
        }
    }

    private fun runEvaluation() {
        val state = evaluateBlocking(currentRules, currentTasks)
        _blockingState.value = state
        Log.d(TAG, "Evaluated: ${state.blockedApps.size} apps blocked")
    }

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
