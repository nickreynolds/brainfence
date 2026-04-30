package dev.brainfence.domain.model

data class RoutineStep(
    val id: String,
    val taskId: String,
    val title: String,
    val stepOrder: Int,
    val stepType: String, // "checkbox", "weight_reps", "just_reps", "timed", "timed_sets"
    val config: String,   // JSONB as JSON string
    val supersetGroup: String?, // steps with the same non-null value form a superset
    val createdAt: String,
)
