package dev.brainfence.domain.model

data class StepCompletion(
    val id: String,
    val taskCompletionId: String,
    val routineStepId: String,
    val setNumber: Int,
    val data: String,   // JSONB as JSON string, e.g. {"reps": 10, "weight": 135}
    val completedAt: String,
)
