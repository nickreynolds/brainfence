package dev.brainfence.ui.tasks

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.brainfence.data.auth.SessionRepository
import dev.brainfence.data.blocking.BlockingRepository
import dev.brainfence.data.completion.CompletionRepository
import dev.brainfence.data.task.TaskRepository
import dev.brainfence.domain.model.Task
import dev.brainfence.domain.recurrence.TimeGatePhase
import dev.brainfence.domain.recurrence.computeTaskPhase
import dev.brainfence.service.BrainfenceService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

data class BlockedAppInfo(
    val packageName: String,
    val label: String,
)

data class BlockingStatusState(
    val blockedApps: List<BlockedAppInfo>,
    val incompleteTasks: List<Task>,
    val hasActiveRules: Boolean,
)

enum class HomeTab { ACTIVE, COMPLETED, UPCOMING }

@HiltViewModel
class TaskListViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskRepository: TaskRepository,
    private val completionRepository: CompletionRepository,
    private val sessionRepository: SessionRepository,
    private val blockingRepository: BlockingRepository,
) : ViewModel() {

    val tasks = taskRepository.watchActiveTasks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList<Task>(),
        )

    /** Tasks that are available now and not yet completed. */
    val activeTasks = tasks.map { allTasks ->
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        allTasks.filter { task ->
            if (task.completedToday) return@filter false
            val phase = computeTaskPhase(task.availableFrom, task.dueAt, now, zone)
            phase != TimeGatePhase.BEFORE_START
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /** Tasks completed today. */
    val completedTasks = tasks.map { allTasks ->
        allTasks.filter { it.completedToday }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /** Tasks not yet available (before their available_from time). */
    val upcomingTasks = tasks.map { allTasks ->
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        allTasks.filter { task ->
            if (task.completedToday) return@filter false
            val phase = computeTaskPhase(task.availableFrom, task.dueAt, now, zone)
            phase == TimeGatePhase.BEFORE_START
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val _selectedTab = MutableStateFlow(HomeTab.ACTIVE)
    val selectedTab = _selectedTab.asStateFlow()

    fun selectTab(tab: HomeTab) {
        _selectedTab.value = tab
    }

    val activeRules = blockingRepository.watchActiveRules()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val blockingStatus = combine(
        BrainfenceService.blockingState,
        taskRepository.watchActiveTasks(),
        blockingRepository.watchActiveRules(),
    ) { blockingState, allTasks, activeRules ->
        if (activeRules.isEmpty()) {
            return@combine BlockingStatusState(
                blockedApps = emptyList(),
                incompleteTasks = emptyList(),
                hasActiveRules = false,
            )
        }

        val taskById = allTasks.associateBy { it.id }

        val requiredTaskIds = blockingState.rulesByApp.values
            .flatten()
            .distinctBy { it.id }
            .flatMap { it.conditionTaskIds }
            .toSet()

        val incompleteTasks = requiredTaskIds
            .mapNotNull { taskById[it] }
            .filter { !it.completedToday }
            .distinctBy { it.id }

        val blockedApps = blockingState.blockedApps.map { pkg ->
            BlockedAppInfo(
                packageName = pkg,
                label = resolveAppLabel(pkg),
            )
        }.sortedBy { it.label }

        BlockingStatusState(
            blockedApps = blockedApps,
            incompleteTasks = incompleteTasks,
            hasActiveRules = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BlockingStatusState(emptyList(), emptyList(), false),
    )

    private val _hasLocationPermission = MutableStateFlow(checkLocationPermission())
    val hasLocationPermission = _hasLocationPermission.asStateFlow()

    fun onLocationPermissionResult(granted: Boolean) {
        _hasLocationPermission.value = checkLocationPermission()
    }

    private fun checkLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return fine && background
    }

    // The task currently awaiting confirmation; null = no dialog shown
    private val _pendingTask = MutableStateFlow<Task?>(null)
    val pendingTask = _pendingTask.asStateFlow()

    fun requestComplete(task: Task) {
        if (!task.completedToday) _pendingTask.value = task
    }

    fun confirmComplete() {
        val task = _pendingTask.value ?: return
        _pendingTask.value = null
        viewModelScope.launch {
            completionRepository.completeTask(taskId = task.id)
        }
    }

    fun dismissComplete() {
        _pendingTask.value = null
    }

    fun signOut() {
        viewModelScope.launch { sessionRepository.signOut() }
    }

    private fun resolveAppLabel(packageName: String): String = try {
        val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(appInfo).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName.substringAfterLast('.')
    }
}
