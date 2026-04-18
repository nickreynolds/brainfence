package dev.brainfence.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.brainfence.data.completion.CompletionRepository
import dev.brainfence.data.task.TaskRepository
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
import org.json.JSONObject
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parsed GPS verification config from a task's verification_config JSON.
 */
data class GpsConfig(
    val lat: Double,
    val lng: Double,
    val radiusM: Float,
    val mode: String,        // "enter" or "leave"
    val minDurationM: Int,   // minutes to stay at location (enter mode)
)

/**
 * Tracks the state of an active GPS geofence for a task.
 */
data class GeofenceTrackingState(
    val taskId: String,
    val taskTitle: String,
    val config: GpsConfig,
    val enteredAt: Instant? = null,
    val durationJob: Job? = null,
    /** For leave mode: tracks whether the user has been inside the geofence at least once. */
    val insideGeofence: Boolean = false,
)

/**
 * Manages GPS geofence verification for tasks with verificationType == "gps".
 *
 * Watches active tasks, registers/unregisters geofences as GPS tasks appear or
 * are completed, and handles geofence transitions to complete tasks automatically.
 */
@Singleton
class GpsVerificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskRepository: TaskRepository,
    private val completionRepository: CompletionRepository,
) {
    companion object {
        private const val TAG = "GpsVerificationMgr"
        const val ACTION_GEOFENCE_EVENT = "dev.brainfence.ACTION_GEOFENCE_EVENT"

        fun parseGpsConfig(json: String): GpsConfig? = try {
            val obj = JSONObject(json)
            GpsConfig(
                lat = obj.getDouble("lat"),
                lng = obj.getDouble("lng"),
                radiusM = obj.optDouble("radius_m", 100.0).toFloat(),
                mode = obj.optString("mode", "enter"),
                minDurationM = obj.optInt("min_duration_m", 0),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse GPS config: $json", e)
            null
        }
    }

    private val geofencingClient =
        LocationServices.getGeofencingClient(context)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Active geofence tracking states keyed by task ID. */
    private val trackingStates = mutableMapOf<String, GeofenceTrackingState>()

    /** Publicly observable set of task IDs currently being tracked for GPS. */
    private val _trackedTaskIds = MutableStateFlow<Set<String>>(emptySet())
    val trackedTaskIds: StateFlow<Set<String>> = _trackedTaskIds.asStateFlow()

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            action = ACTION_GEOFENCE_EVENT
        }
        PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    /**
     * Start watching tasks and managing geofences.
     * Called from BrainfenceService.onCreate().
     */
    fun startWatching() {
        scope.launch {
            taskRepository.watchActiveTasks().collect { tasks ->
                val gpsTasks = tasks.filter { task ->
                    task.verificationType == "gps"
                        && !task.completedToday
                        && parseGpsConfig(task.verificationConfig)?.mode in setOf("enter", "leave")
                }
                syncGeofences(gpsTasks)
            }
        }
        Log.i(TAG, "Started watching GPS tasks")
    }

    /**
     * Stop watching and clean up all geofences.
     */
    fun stop() {
        removeAllGeofences()
        scope.cancel()
        Log.i(TAG, "Stopped")
    }

    /**
     * Called by [GeofenceBroadcastReceiver] when a geofence transition occurs.
     */
    fun handleGeofenceEvent(intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.e(TAG, "Geofence event error: ${event.errorCode}")
            return
        }

        val triggeringGeofences = event.triggeringGeofences ?: return
        val triggeringLocation = event.triggeringLocation

        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                for (geofence in triggeringGeofences) {
                    handleEnter(
                        taskId = geofence.requestId,
                        lat = triggeringLocation?.latitude,
                        lng = triggeringLocation?.longitude,
                        accuracyM = triggeringLocation?.accuracy,
                    )
                }
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                for (geofence in triggeringGeofences) {
                    handleExit(
                        taskId = geofence.requestId,
                        lat = triggeringLocation?.latitude,
                        lng = triggeringLocation?.longitude,
                        accuracyM = triggeringLocation?.accuracy,
                    )
                }
            }
        }
    }

    /**
     * Sync registered geofences with the current set of active GPS tasks (enter and leave modes).
     */
    private fun syncGeofences(gpsTasks: List<Task>) {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission not granted, skipping geofence sync")
            return
        }

        val desiredTaskIds = gpsTasks.map { it.id }.toSet()
        val currentTaskIds = trackingStates.keys.toSet()

        // Remove geofences for tasks no longer active
        val toRemove = currentTaskIds - desiredTaskIds
        if (toRemove.isNotEmpty()) {
            removeGeofences(toRemove)
        }

        // Add geofences for new GPS tasks
        val toAdd = gpsTasks.filter { it.id !in currentTaskIds }
        if (toAdd.isNotEmpty()) {
            addGeofences(toAdd)
        }

        _trackedTaskIds.value = trackingStates.keys.toSet()
    }

    @SuppressLint("MissingPermission")
    private fun addGeofences(tasks: List<Task>) {
        if (!hasLocationPermission()) return

        val geofences = tasks.mapNotNull { task ->
            val config = parseGpsConfig(task.verificationConfig) ?: return@mapNotNull null
            trackingStates[task.id] = GeofenceTrackingState(
                taskId = task.id,
                taskTitle = task.title,
                config = config,
            )
            buildGeofence(task.id, config)
        }

        if (geofences.isEmpty()) return

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofences)
            .build()

        try {
            geofencingClient.addGeofences(request, geofencePendingIntent)
                .addOnSuccessListener {
                    Log.i(TAG, "Added ${geofences.size} geofence(s)")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to add geofences", e)
                    tasks.forEach { trackingStates.remove(it.id) }
                    _trackedTaskIds.value = trackingStates.keys.toSet()
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception adding geofences", e)
        }
    }

    private fun removeGeofences(taskIds: Set<String>) {
        taskIds.forEach { taskId ->
            trackingStates[taskId]?.durationJob?.cancel()
            trackingStates.remove(taskId)
        }
        geofencingClient.removeGeofences(taskIds.toList())
            .addOnSuccessListener { Log.d(TAG, "Removed ${taskIds.size} geofence(s)") }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to remove geofences", e) }

        _trackedTaskIds.value = trackingStates.keys.toSet()
    }

    private fun removeAllGeofences() {
        trackingStates.values.forEach { it.durationJob?.cancel() }
        trackingStates.clear()
        geofencingClient.removeGeofences(geofencePendingIntent)
        _trackedTaskIds.value = emptySet()
    }

    private fun handleEnter(
        taskId: String,
        lat: Double?,
        lng: Double?,
        accuracyM: Float?,
    ) {
        val state = trackingStates[taskId] ?: return
        val now = Instant.now()
        Log.i(TAG, "Geofence ENTER for task '${state.taskTitle}' (id=$taskId)")

        if (state.config.mode == "leave") {
            // Leave mode: just record that the user is inside the geofence.
            // Completion happens on EXIT, but only if they were inside first.
            Log.d(TAG, "Leave-mode task '${state.taskTitle}': user is now inside geofence")
            trackingStates[taskId] = state.copy(insideGeofence = true)
            return
        }

        // Enter mode
        val updatedState = state.copy(enteredAt = now)

        val minDurationMinutes = state.config.minDurationM
        if (minDurationMinutes > 0) {
            val job = scope.launch {
                Log.d(TAG, "Starting ${minDurationMinutes}m timer for '${state.taskTitle}'")
                delay(minDurationMinutes * 60_000L)
                completeGpsTask(taskId, lat, lng, accuracyM)
            }
            trackingStates[taskId] = updatedState.copy(durationJob = job)
        } else {
            trackingStates[taskId] = updatedState
            scope.launch {
                completeGpsTask(taskId, lat, lng, accuracyM)
            }
        }
    }

    private fun handleExit(
        taskId: String,
        lat: Double? = null,
        lng: Double? = null,
        accuracyM: Float? = null,
    ) {
        val state = trackingStates[taskId] ?: return
        Log.i(TAG, "Geofence EXIT for task '${state.taskTitle}' (id=$taskId)")

        if (state.config.mode == "leave") {
            // Leave mode: complete immediately on exit, but only if user was inside first
            if (!state.insideGeofence) {
                Log.w(TAG, "Leave-mode task '${state.taskTitle}': ignoring exit — user was never inside")
                return
            }
            scope.launch {
                completeGpsLeaveTask(taskId, lat, lng, accuracyM)
            }
            return
        }

        // Enter mode: cancel the duration timer — user left before required time elapsed
        state.durationJob?.cancel()
        trackingStates[taskId] = state.copy(enteredAt = null, durationJob = null)
    }

    private suspend fun completeGpsTask(
        taskId: String,
        lat: Double?,
        lng: Double?,
        accuracyM: Float?,
    ) {
        val state = trackingStates[taskId] ?: return
        val arrivedAt = state.enteredAt ?: Instant.now()

        val verificationData = JSONObject().apply {
            put("lat", lat ?: state.config.lat)
            put("lng", lng ?: state.config.lng)
            if (accuracyM != null) put("accuracy_m", accuracyM.toDouble())
            put("arrived_at", arrivedAt.toString())
            put("duration_m", state.config.minDurationM)
        }.toString()

        Log.i(TAG, "Completing GPS task '${state.taskTitle}' with proof: $verificationData")
        completionRepository.completeTask(
            taskId = taskId,
            verificationData = verificationData,
        )

        // Clean up — the task watcher will remove the geofence on next sync
        // since completedToday will be true
        state.durationJob?.cancel()
        trackingStates[taskId] = state.copy(durationJob = null)
    }

    private suspend fun completeGpsLeaveTask(
        taskId: String,
        lat: Double?,
        lng: Double?,
        accuracyM: Float?,
    ) {
        val state = trackingStates[taskId] ?: return

        val verificationData = JSONObject().apply {
            put("departed_at", Instant.now().toString())
            put("lat", lat ?: state.config.lat)
            put("lng", lng ?: state.config.lng)
            if (accuracyM != null) put("accuracy_m", accuracyM.toDouble())
        }.toString()

        Log.i(TAG, "Completing GPS leave task '${state.taskTitle}' with proof: $verificationData")
        completionRepository.completeTask(
            taskId = taskId,
            verificationData = verificationData,
        )
    }

    private fun buildGeofence(taskId: String, config: GpsConfig): Geofence =
        Geofence.Builder()
            .setRequestId(taskId)
            .setCircularRegion(config.lat, config.lng, config.radiusM)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
            )
            .build()

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
}
