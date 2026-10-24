package dev.brainfence.ui.blocking

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.brainfence.data.blocking.BlockingRepository
import dev.brainfence.domain.model.BlockingRule
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

data class RuleListItem(
    val rule: BlockingRule,
    val blockedAppLabels: List<String>,
    val hasPendingChanges: Boolean,
)

@HiltViewModel
class BlockingRuleListViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val blockingRepository: BlockingRepository,
) : ViewModel() {

    val rules: StateFlow<List<RuleListItem>> = blockingRepository.watchAllRules()
        .map { rules ->
            rules.map { rule ->
                RuleListItem(
                    rule = rule,
                    blockedAppLabels = rule.blockedApps.map { resolveAppLabel(it) },
                    hasPendingChanges = rule.pendingChanges != null,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Deletes an inactive rule. An active rule is deactivated instead, which is
     * time-locked like any other loosening edit; it can be deleted once that
     * applies. (Deleting it outright would lift its blocks immediately.)
     */
    fun deleteRule(ruleId: String) {
        val rule = rules.value.firstOrNull { it.rule.id == ruleId }?.rule ?: return
        viewModelScope.launch {
            if (rule.isActive) {
                blockingRepository.scheduleRuleChange(ruleId, JSONObject().put("is_active", 0))
            } else {
                blockingRepository.deleteRule(ruleId)
            }
        }
    }

    private fun resolveAppLabel(packageName: String): String = try {
        val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(appInfo).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName.substringAfterLast('.')
    }
}
