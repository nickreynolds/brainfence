package dev.brainfence.data.shopping

import com.powersync.PowerSyncDatabase
import com.powersync.db.SqlCursor
import dev.brainfence.data.auth.SessionRepository
import dev.brainfence.domain.model.ShoppingItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShoppingRepository @Inject constructor(
    private val database: PowerSyncDatabase,
    private val sessionRepository: SessionRepository,
) {
    /** Watch open (not yet bought) items for a shopping task, oldest first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchOpenItems(taskId: String): Flow<List<ShoppingItem>> =
        database.onChange(tables = setOf("shopping_items"))
            .onStart { emit(emptySet()) }
            .flatMapLatest {
                flow { emit(getOpenItems(taskId)) }
            }

    /** Open-item counts for all shopping tasks, keyed by task ID. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchOpenItemCounts(): Flow<Map<String, Int>> =
        database.onChange(tables = setOf("shopping_items"))
            .onStart { emit(emptySet()) }
            .flatMapLatest {
                flow {
                    val rows = database.getAll(
                        sql = """
                            SELECT task_id, COUNT(*)
                            FROM shopping_items
                            WHERE completed_at IS NULL
                            GROUP BY task_id
                        """.trimIndent(),
                    ) { cursor -> cursor.getString(0)!! to (cursor.getLong(1) ?: 0L).toInt() }
                    emit(rows.toMap())
                }
            }

    /** One-shot read of open items — used by the geofence notification path. */
    suspend fun getOpenItems(taskId: String): List<ShoppingItem> =
        database.getAll(
            sql = """
                SELECT id, user_id, task_id, title, sort_order, completed_at, created_at
                FROM shopping_items
                WHERE task_id = ? AND completed_at IS NULL
                ORDER BY sort_order, created_at
            """.trimIndent(),
            parameters = listOf(taskId),
            mapper = ::mapShoppingItem,
        )

    suspend fun addItem(taskId: String, title: String): String {
        val userId = sessionRepository.currentUser?.id
            ?: error("addItem called while not authenticated")
        val now = Instant.now().toString()
        val itemId = UUID.randomUUID().toString()
        database.execute(
            sql = """
                INSERT INTO shopping_items
                    (id, user_id, task_id, title, sort_order, completed_at, created_at)
                VALUES (?, ?, ?, ?, ?, NULL, ?)
            """.trimIndent(),
            parameters = listOf(itemId, userId, taskId, title, 0, now),
        )
        return itemId
    }

    /** Check off an item: it disappears from the open list but is kept as history. */
    suspend fun completeItem(itemId: String) {
        database.execute(
            sql = "UPDATE shopping_items SET completed_at = ? WHERE id = ?",
            parameters = listOf(Instant.now().toString(), itemId),
        )
    }

    /** Undo an accidental check-off. */
    suspend fun uncompleteItem(itemId: String) {
        database.execute(
            sql = "UPDATE shopping_items SET completed_at = NULL WHERE id = ?",
            parameters = listOf(itemId),
        )
    }

    suspend fun deleteItem(itemId: String) {
        database.execute(
            sql = "DELETE FROM shopping_items WHERE id = ?",
            parameters = listOf(itemId),
        )
    }

    private fun mapShoppingItem(cursor: SqlCursor): ShoppingItem = ShoppingItem(
        id          = cursor.getString(0)!!,
        userId      = cursor.getString(1)!!,
        taskId      = cursor.getString(2)!!,
        title       = cursor.getString(3)!!,
        sortOrder   = (cursor.getLong(4) ?: 0L).toInt(),
        completedAt = cursor.getString(5),
        createdAt   = cursor.getString(6)!!,
    )
}
