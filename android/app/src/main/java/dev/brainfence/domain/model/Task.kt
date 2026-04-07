package dev.brainfence.domain.model

data class Task(
    val id: String,
    val userId: String,
    val title: String,
    val description: String?,
    val taskType: String,
    val status: String,
    val recurrenceType: String?,
    val recurrenceConfig: String,    // JSONB stored as string
    val verificationType: String?,
    val verificationConfig: String,  // JSONB stored as string
    val tags: String,                // TEXT[] stored as JSON array string
    val groupId: String?,
    val sortOrder: Int,
    val isBlockingCondition: Boolean,
    val blockingRuleIds: String,     // UUID[] stored as JSON array string
    val createdAt: String,
    val updatedAt: String,
    // Derived from join with task_completions — not a stored column
    val completedToday: Boolean,
)
