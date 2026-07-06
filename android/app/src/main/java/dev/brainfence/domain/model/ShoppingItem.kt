package dev.brainfence.domain.model

data class ShoppingItem(
    val id: String,
    val userId: String,
    val taskId: String,
    val title: String,
    val sortOrder: Int,
    val completedAt: String?, // null = still to buy
    val createdAt: String,
)
