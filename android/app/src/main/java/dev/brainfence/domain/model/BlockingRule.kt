package dev.brainfence.domain.model

data class BlockingRule(
    val id: String,
    val userId: String,
    val name: String,
    val blockedApps: List<String>,         // package names
    val blockedDomains: List<String>,
    val conditionTaskIds: List<String>,     // task IDs that must be completed
    val conditionLogic: String,            // "all" or "any"
    val activeSchedule: String,            // JSONB as string
    val configLockHours: Int,              // delay before config changes apply (default 24)
    val pendingChanges: String?,           // JSONB of staged changes awaiting apply
    val changesApplyAt: String?,           // ISO-8601 timestamp when pending changes go live
    val isActive: Boolean,
)
