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
    val isActive: Boolean,
)
