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

    fun deleteRule(ruleId: String) {
        viewModelScope.launch { blockingRepository.deleteRule(ruleId) }
    }

    private fun resolveAppLabel(packageName: String): String = try {
        val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(appInfo).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName.substringAfterLast('.')
    }
}
