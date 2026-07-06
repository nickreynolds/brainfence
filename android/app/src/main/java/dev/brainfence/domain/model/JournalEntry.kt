package dev.brainfence.domain.model

data class JournalEntry(
    val id: String,
    val taskId: String,
    val taskTitle: String,
    val text: String,
    val completedAt: String,
)
