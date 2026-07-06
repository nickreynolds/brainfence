package dev.brainfence.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.brainfence.data.auth.AuthState
import dev.brainfence.data.auth.SessionRepository
import dev.brainfence.data.completion.CompletionRepository
import dev.brainfence.data.debug.DebugLogRepository
import dev.brainfence.data.shopping.ShoppingRepository
import dev.brainfence.data.task.TaskRepository
import dev.brainfence.domain.model.Task
import dev.brainfence.domain.recurrence.parseTaskTime
import dev.brainfence.domain.shopping.NotifyWindow
import dev.brainfence.domain.shopping.shouldNotifyShopping
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
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.Instant
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parsed GPS verification config from a task's verification_config JSON.
 */
data class GpsConfig(
    val lat: Double,
    val lng: Double,
    val radiusM: Float,
    val mode: String,        // "enter", "leave", or "notify"
    val minDurationM: Int,   // minutes to stay at location (enter mode)
    /** Notify mode only: when the reminder is allowed to fire. */
    val notifyWindow: NotifyWindow? = null,
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
)

/**
 * Manages GPS geofence verification for tasks with verificationType == "gps".
 *
 * Watches active tasks, registers/unregisters geofences as GPS tasks appear or
 * are completed, and handles geofence transitions to complete tasks automatically.
 *
 * Leave mode: completes immediately when the user is detected outside the geofence.
 * No requirement to have been inside first.
 *
 * Notify mode (shopping lists): the task is never completed. On geofence entry,
 * a reminder notification fires if the list has at least one open item and the
 * configured notify window / debounce allows it.
 */
@Singleton
class GpsVerificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskRepository: TaskRepository,
    private val completionRepository: CompletionRepository,
    private val sessionRepository: SessionRepository,
    private val shoppingRepository: ShoppingRepository,
    private val taskNotificationManager: TaskNotificationManager,
    private val debugLog: DebugLogRepository,
) {
    companion object {
        private const val TAG = "GpsVerificationMgr"
        const val ACTION_GEOFENCE_EVENT = "dev.brainfence.ACTION_GEOFENCE_EVENT"

        fun parseGpsConfig(json: String): GpsConfig? = try {
            val obj = JSONObject(json)
            // The task editor writes latitude/longitude/radius_meters; the spec
            // (and hand-created tasks) use lat/lng/radius_m. Accept both.
            GpsConfig(
                lat = if (obj.has("lat")) obj.getDouble("lat") else obj.getDouble("latitude"),
                lng = if (obj.has("lng")) obj.getDouble("lng") else obj.getDouble("longitude"),
                radiusM = when {
                    obj.has("radius_m") -> obj.getDouble("radius_m")
                    obj.has("radius_meters") -> obj.getDouble("radius_meters")
                    else -> 100.0
                }.toFloat(),
                mode = obj.optString("mode", "enter"),
                minDurationM = obj.optInt("min_duration_m", 0),
                notifyWindow = parseNotifyWindow(obj.optJSONObject("notify_window")),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse GPS config: $json", e)
            null
        }

        private fun parseNotifyWindow(obj: JSONObject?): NotifyWindow? {
            if (obj == null) return null
            val daysArr = obj.optJSONArray("days")
            val days = buildSet {
                if (daysArr != null) for (i in 0 until daysArr.length()) add(daysArr.getString(i))
            }
            return NotifyWindow(
                days = days,
                start = parseTaskTime(obj.optString("start", "")),
                end = parseTaskTime(obj.optString("end", "")),
            )
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

    /** Completions queued because auth was not available; drained when auth restores. */
    private data class PendingCompletion(
        val taskId: String,
        val taskTitle: String,
        val verificationData: String,
    )
    private val pendingCompletions = mutableListOf<PendingCompletion>()

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
            sessionRepository.authState.collect { state ->
                if (state is AuthState.SignedIn) {
                    drainPendingCompletions()
                }
            }
        }
        scope.launch {
            debugLog.log("service", "GpsVerificationManager started watching")
            taskRepository.watchActiveTasks().collect { tasks ->
                val gpsTasks = tasks.filter { task ->
                    if (task.verificationType != "gps") return@filter false
                    when (parseGpsConfig(task.verificationConfig)?.mode) {
                        // Notify-mode tasks (shopping lists) are never completed,
                        // so their geofences stay registered permanently.
                        "notify" -> true
                        "enter", "leave" -> !task.completedToday
                        else -> false
                    }
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
            scope.launch {
                debugLog.log("error", "Geofence event error code: ${event.errorCode}")
            }
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
     * Sync registered geofences with the current set of active GPS tasks.
     * Enter-mode and leave-mode tasks are registered separately with
     * different initial triggers.
     */
    private fun syncGeofences(gpsTasks: List<Task>) {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission not granted, skipping geofence sync")
            scope.launch {
                debugLog.log("error", "Location permission not granted, skipping geofence sync")
            }
            return
        }

        val desiredTaskIds = gpsTasks.map { it.id }.toSet()
        val currentTaskIds = trackingStates.keys.toSet()

        // Remove geofences for tasks no longer active
        val toRemove = currentTaskIds - desiredTaskIds
        if (toRemove.isNotEmpty()) {
            removeGeofences(toRemove)
        }

        // Add geofences for new GPS tasks, split by mode.
        // Notify-mode geofences register like enter-mode: INITIAL_TRIGGER_ENTER
        // is harmless because the notify window/debounce suppresses spurious fires.
        val toAdd = gpsTasks.filter { it.id !in currentTaskIds }
        if (toAdd.isNotEmpty()) {
            val enterTasks = toAdd.filter {
                parseGpsConfig(it.verificationConfig)?.mode in setOf("enter", "notify")
            }
            val leaveTasks = toAdd.filter {
                parseGpsConfig(it.verificationConfig)?.mode == "leave"
            }
            if (enterTasks.isNotEmpty()) addGeofences(enterTasks, isLeaveMode = false)
            if (leaveTasks.isNotEmpty()) addGeofences(leaveTasks, isLeaveMode = true)
        }

        _trackedTaskIds.value = trackingStates.keys.toSet()
    }

    @SuppressLint("MissingPermission")
    private fun addGeofences(tasks: List<Task>, isLeaveMode: Boolean) {
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

        // Leave-mode geofences use INITIAL_TRIGGER_EXIT so that if the user is
        // already outside the geofence at registration, an EXIT event fires immediately.
        val initialTrigger = if (isLeaveMode) {
            GeofencingRequest.INITIAL_TRIGGER_EXIT
        } else {
            GeofencingRequest.INITIAL_TRIGGER_ENTER
        }

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(initialTrigger)
            .addGeofences(geofences)
            .build()

        val modeLabel = if (isLeaveMode) "leave" else "enter"

        try {
            geofencingClient.addGeofences(request, geofencePendingIntent)
                .addOnSuccessListener {
                    Log.i(TAG, "Added ${geofences.size} $modeLabel-mode geofence(s)")
                    scope.launch {
                        for (task in tasks) {
                            debugLog.log(
                                "geofence",
                                "Registered $modeLabel-mode geofence for '${task.title}'",
                            )
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to add $modeLabel-mode geofences", e)
                    scope.launch {
                        debugLog.log("error", "Failed to register $modeLabel-mode geofences: ${e.message}")
                    }
                    tasks.forEach { trackingStates.remove(it.id) }
                    _trackedTaskIds.value = trackingStates.keys.toSet()
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception adding geofences", e)
        }

        // For leave-mode tasks, also proactively check the current location.
        // INITIAL_TRIGGER_EXIT can be unreliable, so this is a belt-and-suspenders approach.
        if (isLeaveMode) {
            checkCurrentLocationForLeaveTasks(tasks)
        }
    }

    /**
     * Proactively check the device's last known location against leave-mode geofences.
     * If the user is already outside, complete the task immediately.
     * This supplements INITIAL_TRIGGER_EXIT which can be unreliable.
     */
    @SuppressLint("MissingPermission")
    private fun checkCurrentLocationForLeaveTasks(tasks: List<Task>) {
        if (!hasLocationPermission()) return
        val locationClient = LocationServices.getFusedLocationProviderClient(context)
        locationClient.lastLocation.addOnSuccessListener { location ->
            if (location == null) {
                scope.launch {
                    debugLog.log("geofence", "No last known location for leave-mode check")
                }
                return@addOnSuccessListener
            }
            for (task in tasks) {
                val config = parseGpsConfig(task.verificationConfig) ?: continue
                val distance = FloatArray(1)
                android.location.Location.distanceBetween(
                    location.latitude, location.longitude,
                    config.lat, config.lng,
                    distance,
                )
                if (distance[0] > config.radiusM) {
                    scope.launch {
                        debugLog.log(
                            category = "geofence",
                            message = "Already outside geofence for '${task.title}' (${distance[0].toInt()}m away), completing",
                            lat = location.latitude,
                            lng = location.longitude,
                            accuracyM = location.accuracy,
                        )
                        completeGpsLeaveTask(task.id, location.latitude, location.longitude, location.accuracy)
                    }
                } else {
                    scope.launch {
                        debugLog.log(
                            category = "geofence",
                            message = "Inside geofence for '${task.title}' (${distance[0].toInt()}m from center), waiting for exit",
                            lat = location.latitude,
                            lng = location.longitude,
                            accuracyM = location.accuracy,
                        )
                    }
                }
            }
        }
    }

    /**
     * Periodically request a fresh location fix and check it against all tracked
     * leave-mode geofences. This compensates for Android geofencing being unreliable
     * during Doze mode — the passive lastLocation API often returns stale data, so
     * we use getCurrentLocation() for an actual GPS fix.
     *
     * Called from BrainfenceService on the breadcrumb interval (~5 min).
     */
    @SuppressLint("MissingPermission")
    fun periodicLeaveCheck() {
        val leaveStates = trackingStates.values.filter { it.config.mode == "leave" }
        if (leaveStates.isEmpty() || !hasLocationPermission()) return

        val locationClient = LocationServices.getFusedLocationProviderClient(context)
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(60_000L)
            .build()
        locationClient.getCurrentLocation(request, null)
            .addOnSuccessListener { location ->
                if (location == null) {
                    scope.launch {
                        debugLog.log("geofence", "Periodic leave check: no location available")
                    }
                    return@addOnSuccessListener
                }
                for (state in leaveStates) {
                    val distance = FloatArray(1)
                    android.location.Location.distanceBetween(
                        location.latitude, location.longitude,
                        state.config.lat, state.config.lng,
                        distance,
                    )
                    if (distance[0] > state.config.radiusM) {
                        scope.launch {
                            debugLog.log(
                                category = "geofence",
                                message = "Periodic check: outside geofence for '${state.taskTitle}' (${distance[0].toInt()}m away), completing",
                                lat = location.latitude,
                                lng = location.longitude,
                                accuracyM = location.accuracy,
                            )
                            completeGpsLeaveTask(state.taskId, location.latitude, location.longitude, location.accuracy)
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Periodic leave check: location request failed", e)
                scope.launch {
                    debugLog.log("error", "Periodic leave check failed: ${e.message}")
                }
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

        scope.launch {
            debugLog.log(
                category = "geofence",
                message = "ENTER event for '${state.taskTitle}' (mode=${state.config.mode})",
                lat = lat,
                lng = lng,
                accuracyM = accuracyM,
            )
        }

        if (state.config.mode == "leave") {
            // Leave mode: ENTER just means user is inside. No action needed.
            return
        }

        if (state.config.mode == "notify") {
            scope.launch { maybeNotifyShopping(state) }
            return
        }

        // Enter mode
        val updatedState = state.copy(enteredAt = now)

        val minDurationMinutes = state.config.minDurationM
        if (minDurationMinutes > 0) {
            val job = scope.launch {
                Log.d(TAG, "Starting ${minDurationMinutes}m timer for '${state.taskTitle}'")
                debugLog.log("geofence", "Starting ${minDurationMinutes}m dwell timer for '${state.taskTitle}'")
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

        scope.launch {
            debugLog.log(
                category = "geofence",
                message = "EXIT event for '${state.taskTitle}' (mode=${state.config.mode})",
                lat = lat,
                lng = lng,
                accuracyM = accuracyM,
            )
        }

        if (state.config.mode == "leave") {
            // Leave mode: complete immediately on exit — no requirement
            // to have been inside first.
            scope.launch {
                completeGpsLeaveTask(taskId, lat, lng, accuracyM)
            }
            return
        }

        if (state.config.mode == "notify") {
            // Notify mode: nothing to do on exit.
            return
        }

        // Enter mode: cancel the duration timer — user left before required time elapsed
        state.durationJob?.cancel()
        trackingStates[taskId] = state.copy(enteredAt = null, durationJob = null)
    }

    /** Last-notified timestamps per shopping task, surviving service restarts. */
    private val shoppingNotifyPrefs by lazy {
        context.getSharedPreferences("brainfence_shopping_notify", Context.MODE_PRIVATE)
    }

    /**
     * Geofence ENTER for a notify-mode (shopping) task: fire a reminder listing
     * the open items, if there is at least one and the window/debounce allows.
     * Requires no auth — everything is read from the local database.
     */
    private suspend fun maybeNotifyShopping(state: GeofenceTrackingState) {
        val items = shoppingRepository.getOpenItems(state.taskId)
        if (items.isEmpty()) {
            debugLog.log("geofence", "Shopping list '${state.taskTitle}' is empty, no reminder")
            return
        }

        val lastMillis = shoppingNotifyPrefs.getLong(state.taskId, 0L)
        val lastNotifiedAt = if (lastMillis > 0) Instant.ofEpochMilli(lastMillis) else null
        val now = ZonedDateTime.now()
        if (!shouldNotifyShopping(state.config.notifyWindow, lastNotifiedAt, now)) {
            debugLog.log(
                "geofence",
                "Shopping reminder for '${state.taskTitle}' suppressed (outside window or already notified)",
            )
            return
        }

        shoppingNotifyPrefs.edit().putLong(state.taskId, now.toInstant().toEpochMilli()).apply()
        taskNotificationManager.showShoppingReminder(
            taskId = state.taskId,
            taskTitle = state.taskTitle,
            items = items.map { it.title },
        )
        debugLog.log(
            "geofence",
            "Shopping reminder for '${state.taskTitle}' (${items.size} open item(s))",
        )
    }

    /**
     * Wait for the auth session to be available.
     *
     * If auth already resolved to SignedOut (e.g. token expired during Doze
     * and the automatic refresh failed due to network restrictions), nudge
     * the SDK to retry — by the time a geofence wakes us, network is
     * typically available again.
     */
    private suspend fun awaitAuth(): Boolean {
        if (sessionRepository.currentUser != null) return true

        // If auth already gave up, nudge it to retry the refresh.
        if (sessionRepository.authState.value is AuthState.SignedOut) {
            try {
                debugLog.log("geofence", "Auth is signed-out, nudging session refresh")
                sessionRepository.refreshSession()
            } catch (e: Exception) {
                debugLog.log("error", "Session refresh failed: ${e.message}")
            }
        }

        val result = withTimeoutOrNull(15_000L) {
            sessionRepository.authState.first { it is AuthState.SignedIn }
        }
        return result != null
    }

    private suspend fun drainPendingCompletions() {
        val pending = synchronized(pendingCompletions) {
            val copy = pendingCompletions.toList()
            pendingCompletions.clear()
            copy
        }
        if (pending.isEmpty()) return

        for (item in pending) {
            try {
                debugLog.log("geofence", "Retrying queued completion for '${item.taskTitle}'")
                completionRepository.completeTask(
                    taskId = item.taskId,
                    verificationData = item.verificationData,
                )
                debugLog.log("geofence", "Successfully completed queued task '${item.taskTitle}'")
                trackingStates[item.taskId]?.durationJob?.cancel()
            } catch (e: Exception) {
                debugLog.log("error", "Failed to complete queued task '${item.taskTitle}': ${e.message}")
                synchronized(pendingCompletions) {
                    pendingCompletions.add(item)
                }
            }
        }
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
        debugLog.log(
            category = "geofence",
            message = "Completing enter-mode task '${state.taskTitle}'",
            data = verificationData,
            lat = lat,
            lng = lng,
            accuracyM = accuracyM,
        )

        if (!awaitAuth()) {
            debugLog.log("geofence", "Auth not available for '${state.taskTitle}', queuing for retry when auth restores")
            synchronized(pendingCompletions) {
                pendingCompletions.add(PendingCompletion(taskId, state.taskTitle, verificationData))
            }
            return
        }

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
        debugLog.log(
            category = "geofence",
            message = "Completing leave-mode task '${state.taskTitle}'",
            data = verificationData,
            lat = lat,
            lng = lng,
            accuracyM = accuracyM,
        )

        if (!awaitAuth()) {
            debugLog.log("geofence", "Auth not available for '${state.taskTitle}', queuing for retry when auth restores")
            synchronized(pendingCompletions) {
                pendingCompletions.add(PendingCompletion(taskId, state.taskTitle, verificationData))
            }
            return
        }

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
