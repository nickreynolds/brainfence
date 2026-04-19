package dev.brainfence.ui.blocking

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.brainfence.data.auth.SessionRepository
import dev.brainfence.data.blocking.BlockingRepository
import dev.brainfence.data.task.TaskRepository
import dev.brainfence.domain.model.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
)

data class EditorUiState(
    val isNew: Boolean = true,
    val name: String = "",
    val selectedApps: Set<String> = emptySet(),
    val blockedDomains: List<String> = emptyList(),
    val conditionTaskIds: Set<String> = emptySet(),
    val conditionLogic: String = "all",
    val scheduleDays: Set<String> = emptySet(),
    val scheduleStartTime: String = "",
    val scheduleEndTime: String = "",
    val isActive: Boolean = true,
    val hasPendingChanges: Boolean = false,
    val changesApplyAt: String? = null,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class BlockingRuleEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val blockingRepository: BlockingRepository,
    private val taskRepository: TaskRepository,
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    private val ruleId: String? = savedStateHandle.get<String>("ruleId")
        ?.takeIf { it.isNotBlank() && it != "new" }

    private val _state = MutableStateFlow(EditorUiState(isNew = ruleId == null))
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    val tasks: StateFlow<List<Task>> = taskRepository.watchActiveTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        loadInstalledApps()
        if (ruleId != null) loadExistingRule()
    }

    private fun loadInstalledApps() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || pm.getLaunchIntentForPackage(it.packageName) != null }
                    .filter { it.packageName != context.packageName }
                    .map { appInfo ->
                        InstalledApp(
                            packageName = appInfo.packageName,
                            label = pm.getApplicationLabel(appInfo).toString(),
                            icon = loadAppIcon(pm, appInfo),
                        )
                    }
                    .sortedBy { it.label.lowercase() }
            }
            _installedApps.value = apps
        }
    }

    private fun loadAppIcon(pm: PackageManager, appInfo: ApplicationInfo): ImageBitmap? = try {
        val drawable: Drawable = pm.getApplicationIcon(appInfo)
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        bmp.asImageBitmap()
    } catch (_: Exception) {
        null
    }

    private fun loadExistingRule() {
        viewModelScope.launch {
            val rule = blockingRepository.getRuleById(ruleId!!) ?: return@launch
            val schedule = try {
                JSONObject(rule.activeSchedule)
            } catch (_: Exception) {
                JSONObject()
            }
            val days = mutableSetOf<String>()
            if (schedule.has("days")) {
                val arr = schedule.getJSONArray("days")
                for (i in 0 until arr.length()) days.add(arr.getString(i))
            }
            val startKey = if (schedule.has("start_time")) "start_time" else "start"
            val endKey = if (schedule.has("end_time")) "end_time" else "end"
            _state.value = EditorUiState(
                isNew = false,
                name = rule.name,
                selectedApps = rule.blockedApps.toSet(),
                blockedDomains = rule.blockedDomains,
                conditionTaskIds = rule.conditionTaskIds.toSet(),
                conditionLogic = rule.conditionLogic,
                scheduleDays = days,
                scheduleStartTime = schedule.optString(startKey, ""),
                scheduleEndTime = schedule.optString(endKey, ""),
                isActive = rule.isActive,
                hasPendingChanges = rule.pendingChanges != null,
                changesApplyAt = rule.changesApplyAt,
            )
        }
    }

    fun updateName(name: String) {
        _state.value = _state.value.copy(name = name)
    }

    fun toggleApp(packageName: String) {
        _state.value = _state.value.let { s ->
            s.copy(selectedApps = if (packageName in s.selectedApps) s.selectedApps - packageName else s.selectedApps + packageName)
        }
    }

    fun addDomain(domain: String) {
        val trimmed = domain.trim().lowercase()
        if (trimmed.isNotEmpty()) {
            _state.value = _state.value.copy(blockedDomains = _state.value.blockedDomains + trimmed)
        }
    }

    fun removeDomain(domain: String) {
        _state.value = _state.value.copy(blockedDomains = _state.value.blockedDomains - domain)
    }

    fun toggleConditionTask(taskId: String) {
        _state.value = _state.value.let { s ->
            s.copy(conditionTaskIds = if (taskId in s.conditionTaskIds) s.conditionTaskIds - taskId else s.conditionTaskIds + taskId)
        }
    }

    fun setConditionLogic(logic: String) {
        _state.value = _state.value.copy(conditionLogic = logic)
    }

    fun toggleDay(day: String) {
        _state.value = _state.value.let { s ->
            s.copy(scheduleDays = if (day in s.scheduleDays) s.scheduleDays - day else s.scheduleDays + day)
        }
    }

    fun setScheduleStartTime(time: String) {
        _state.value = _state.value.copy(scheduleStartTime = time)
    }

    fun setScheduleEndTime(time: String) {
        _state.value = _state.value.copy(scheduleEndTime = time)
    }

    fun save(onComplete: () -> Unit) {
        val s = _state.value
        if (s.name.isBlank()) {
            _state.value = s.copy(error = "Name is required")
            return
        }
        _state.value = s.copy(isSaving = true, error = null)

        viewModelScope.launch {
            try {
                val blockedApps = JSONArray().apply {
                    s.selectedApps.forEach { pkg ->
                        put(JSONObject().apply {
                            put("platform", "android")
                            put("package", pkg)
                        })
                    }
                }
                val blockedDomains = JSONArray(s.blockedDomains)
                val conditionTaskIds = JSONArray(s.conditionTaskIds.toList())
                val activeSchedule = buildScheduleJson(s)

                if (s.isNew) {
                    val userId = sessionRepository.currentUser?.id
                        ?: error("Not authenticated")
                    blockingRepository.createRule(
                        userId = userId,
                        name = s.name,
                        blockedApps = blockedApps,
                        blockedDomains = blockedDomains,
                        conditionTaskIds = conditionTaskIds,
                        conditionLogic = s.conditionLogic,
                        activeSchedule = activeSchedule,
                    )
                } else {
                    val changes = JSONObject().apply {
                        put("name", s.name)
                        put("blocked_apps", blockedApps)
                        put("blocked_domains", blockedDomains)
                        put("condition_task_ids", conditionTaskIds)
                        put("condition_logic", s.conditionLogic)
                        put("active_schedule", activeSchedule)
                        put("is_active", if (s.isActive) 1 else 0)
                    }
                    blockingRepository.scheduleRuleChange(ruleId!!, changes)
                }
                _state.value = _state.value.copy(isSaving = false, saveSuccess = true)
                onComplete()
            } catch (e: Exception) {
                _state.value = _state.value.copy(isSaving = false, error = e.message)
            }
        }
    }

    fun cancelPendingChanges() {
        if (ruleId == null) return
        viewModelScope.launch {
            blockingRepository.cancelPendingChange(ruleId)
            loadExistingRule()
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun buildScheduleJson(s: EditorUiState): JSONObject {
        val schedule = JSONObject()
        if (s.scheduleDays.isNotEmpty()) {
            schedule.put("days", JSONArray(s.scheduleDays.toList()))
        }
        if (s.scheduleStartTime.isNotBlank() && s.scheduleEndTime.isNotBlank()) {
            schedule.put("start_time", s.scheduleStartTime)
            schedule.put("end_time", s.scheduleEndTime)
        }
        return schedule
    }
}
