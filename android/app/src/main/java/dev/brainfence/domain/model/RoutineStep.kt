package dev.brainfence.domain.model

data class RoutineStep(
    val id: String,
    val taskId: String,
    val title: String,
    val stepOrder: Int,
    val stepType: String, // "checkbox", "weight_reps", "just_reps", "timed"
    val config: String,   // JSONB as JSON string
    val createdAt: String,
)
