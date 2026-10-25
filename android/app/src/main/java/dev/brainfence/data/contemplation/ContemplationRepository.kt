package dev.brainfence.data.contemplation

import com.powersync.PowerSyncDatabase
import com.powersync.db.SqlCursor
import com.powersync.db.internal.PowerSyncTransaction
import dev.brainfence.data.auth.SessionRepository
import dev.brainfence.domain.contemplation.pickItemIndex
import dev.brainfence.domain.contemplation.pickWeightedIndex
import dev.brainfence.domain.model.Contemplation
import dev.brainfence.domain.model.ContemplationDeck
import dev.brainfence.domain.model.ContemplationDelivery
import dev.brainfence.domain.model.ContemplationResponseType
import dev.brainfence.domain.model.DeckOrdering
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** A deck and the item chosen from it for the next delivery. */
data class ContemplationChoice(val deck: ContemplationDeck, val item: Contemplation)

private val DECK_COLUMNS = """
    d.id, d.name, d.description, d.ordering, d.response_type, d.response_label,
    d.weight, d.is_enabled, d.library_key, d.sort_order,
    (SELECT COUNT(*) FROM contemplations c WHERE c.deck_id = d.id) AS item_count
""".trimIndent()

@Singleton
class ContemplationRepository @Inject constructor(
    private val database: PowerSyncDatabase,
    private val sessionRepository: SessionRepository,
) {
    // ── Decks ────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchDecks(): Flow<List<ContemplationDeck>> =
        database.onChange(tables = setOf("contemplation_decks", "contemplations"))
            .onStart { emit(emptySet()) }
            .flatMapLatest { flow { emit(getDecks()) } }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchDeck(deckId: String): Flow<ContemplationDeck?> =
        database.onChange(tables = setOf("contemplation_decks", "contemplations"))
            .onStart { emit(emptySet()) }
            .flatMapLatest {
                flow {
                    emit(
                        database.getOptional(
                            sql = "SELECT $DECK_COLUMNS FROM contemplation_decks d WHERE d.id = ?",
                            parameters = listOf(deckId),
                            mapper = ::mapDeck,
                        ),
                    )
                }
            }

    private suspend fun getDecks(): List<ContemplationDeck> =
        database.getAll(
            sql = "SELECT $DECK_COLUMNS FROM contemplation_decks d ORDER BY d.sort_order, d.created_at",
            mapper = ::mapDeck,
        )

    /** Creates an empty, enabled deck and returns its id. */
    suspend fun createDeck(name: String): String {
        val userId = requireUserId()
        val now = Instant.now().toString()
        val deckId = UUID.randomUUID().toString()
        database.execute(
            sql = """
                INSERT INTO contemplation_decks
                    (id, user_id, name, description, ordering, response_type, response_label,
                     weight, is_enabled, library_key, sort_order, created_at, updated_at)
                VALUES (?, ?, ?, NULL, ?, ?, NULL, 1, 1, NULL, 0, ?, ?)
            """.trimIndent(),
            parameters = listOf(
                deckId, userId, name, DeckOrdering.RANDOM.key,
                ContemplationResponseType.NONE.key, now, now,
            ),
        )
        return deckId
    }

    /** Copies a starter deck and its items into the user's own tables. */
    suspend fun addFromLibrary(libraryDeck: ContemplationLibrary.LibraryDeck): String {
        val userId = requireUserId()
        val deckId = UUID.randomUUID().toString()
        database.writeTransaction { tx -> insertLibraryDeck(tx, libraryDeck, userId, deckId) }
        return deckId
    }

    companion object {
        /** The inserts of [addFromLibrary], for use inside a larger transaction. */
        fun insertLibraryDeck(
            tx: PowerSyncTransaction,
            libraryDeck: ContemplationLibrary.LibraryDeck,
            userId: String,
            deckId: String = UUID.randomUUID().toString(),
        ) {
            val now = Instant.now().toString()
            tx.execute(
                sql = """
                    INSERT INTO contemplation_decks
                        (id, user_id, name, description, ordering, response_type, response_label,
                         weight, is_enabled, library_key, sort_order, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, 0, ?, ?)
                """.trimIndent(),
                parameters = listOf(
                    deckId, userId, libraryDeck.name, libraryDeck.description,
                    libraryDeck.ordering.key, libraryDeck.responseType.key,
                    libraryDeck.responseLabel, libraryDeck.weight.toLong(),
                    libraryDeck.key, now, now,
                ),
            )
            libraryDeck.items.forEachIndexed { index, item ->
                tx.execute(
                    sql = """
                        INSERT INTO contemplations
                            (id, user_id, deck_id, title, body, attribution, sort_order, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    parameters = listOf(
                        UUID.randomUUID().toString(), userId, deckId,
                        item.title, item.body, item.attribution, index.toLong(), now, now,
                    ),
                )
            }
        }
    }

    suspend fun updateDeck(
        deckId: String,
        name: String,
        description: String?,
        ordering: DeckOrdering,
        responseType: ContemplationResponseType,
        responseLabel: String?,
        weight: Int,
    ) {
        database.execute(
            sql = """
                UPDATE contemplation_decks
                SET name = ?, description = ?, ordering = ?, response_type = ?,
                    response_label = ?, weight = ?, updated_at = ?
                WHERE id = ?
            """.trimIndent(),
            parameters = listOf(
                name, description, ordering.key, responseType.key, responseLabel,
                weight.coerceAtLeast(1).toLong(), Instant.now().toString(), deckId,
            ),
        )
    }

    suspend fun setDeckEnabled(deckId: String, enabled: Boolean) {
        database.execute(
            sql = "UPDATE contemplation_decks SET is_enabled = ?, updated_at = ? WHERE id = ?",
            parameters = listOf(if (enabled) 1L else 0L, Instant.now().toString(), deckId),
        )
    }

    /**
     * Deletes a deck and its items. Items are deleted explicitly because the
     * server-side ON DELETE CASCADE doesn't apply to the local SQLite views.
     * Past deliveries are kept (their content is snapshotted).
     */
    suspend fun deleteDeck(deckId: String) {
        database.writeTransaction { tx ->
            tx.execute(sql = "DELETE FROM contemplations WHERE deck_id = ?", parameters = listOf(deckId))
            tx.execute(sql = "DELETE FROM contemplation_decks WHERE id = ?", parameters = listOf(deckId))
        }
    }

    // ── Items ────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchItems(deckId: String): Flow<List<Contemplation>> =
        database.onChange(tables = setOf("contemplations"))
            .onStart { emit(emptySet()) }
            .flatMapLatest { flow { emit(getItems(deckId)) } }

    private suspend fun getItems(deckId: String): List<Contemplation> =
        database.getAll(
            sql = """
                SELECT id, deck_id, title, body, attribution, sort_order
                FROM contemplations
                WHERE deck_id = ?
                ORDER BY sort_order, created_at
            """.trimIndent(),
            parameters = listOf(deckId),
            mapper = ::mapItem,
        )

    suspend fun addItem(deckId: String, title: String?, body: String, attribution: String?) {
        val userId = requireUserId()
        val now = Instant.now().toString()
        database.execute(
            sql = """
                INSERT INTO contemplations
                    (id, user_id, deck_id, title, body, attribution, sort_order, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?,
                    (SELECT COALESCE(MAX(sort_order), -1) + 1 FROM contemplations WHERE deck_id = ?),
                    ?, ?)
            """.trimIndent(),
            parameters = listOf(
                UUID.randomUUID().toString(), userId, deckId, title, body, attribution,
                deckId, now, now,
            ),
        )
    }

    suspend fun updateItem(itemId: String, title: String?, body: String, attribution: String?) {
        database.execute(
            sql = """
                UPDATE contemplations SET title = ?, body = ?, attribution = ?, updated_at = ?
                WHERE id = ?
            """.trimIndent(),
            parameters = listOf(title, body, attribution, Instant.now().toString(), itemId),
        )
    }

    suspend fun deleteItem(itemId: String) {
        database.execute(sql = "DELETE FROM contemplations WHERE id = ?", parameters = listOf(itemId))
    }

    // ── Delivery ─────────────────────────────────────────────────────

    /**
     * Chooses what to deliver next: a deck by weight among enabled decks that
     * have items, then an item per the deck's ordering. Null if nothing is
     * deliverable.
     */
    suspend fun pickNext(random: Random = Random.Default): ContemplationChoice? {
        val decks = getDecks().filter { it.isEnabled && it.itemCount > 0 }
        if (decks.isEmpty()) return null
        val deck = decks[pickWeightedIndex(decks.map { it.weight }, random)]
        val items = getItems(deck.id)
        if (items.isEmpty()) return null
        val lastDeliveredId = database.getOptional(
            sql = """
                SELECT COALESCE(contemplation_id, '') FROM contemplation_deliveries
                WHERE deck_id = ?
                ORDER BY delivered_at DESC
                LIMIT 1
            """.trimIndent(),
            parameters = listOf(deck.id),
        ) { cursor: SqlCursor -> cursor.getString(0) ?: "" }
        val index = pickItemIndex(deck.ordering, items.map { it.id }, lastDeliveredId, random)
        return ContemplationChoice(deck, items[index])
    }

    /** Logs a delivery (snapshotting its content) and returns the delivery id. */
    suspend fun recordDelivery(choice: ContemplationChoice): String {
        val userId = requireUserId()
        val now = Instant.now().toString()
        val deliveryId = UUID.randomUUID().toString()
        database.execute(
            sql = """
                INSERT INTO contemplation_deliveries
                    (id, user_id, deck_id, contemplation_id, title, body, attribution,
                     delivered_at, response, responded_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?)
            """.trimIndent(),
            parameters = listOf(
                deliveryId, userId, choice.deck.id, choice.item.id,
                choice.item.title, choice.item.body, choice.item.attribution, now, now,
            ),
        )
        return deliveryId
    }

    /** Records the user's response; the first response wins. */
    suspend fun recordResponse(deliveryId: String, response: String) {
        database.execute(
            sql = """
                UPDATE contemplation_deliveries SET response = ?, responded_at = ?
                WHERE id = ? AND response IS NULL
            """.trimIndent(),
            parameters = listOf(response, Instant.now().toString(), deliveryId),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchRecentDeliveries(limit: Int = 20): Flow<List<ContemplationDelivery>> =
        database.onChange(tables = setOf("contemplation_deliveries"))
            .onStart { emit(emptySet()) }
            .flatMapLatest {
                flow {
                    emit(
                        database.getAll(
                            sql = """
                                SELECT id, deck_id, contemplation_id, title, body, attribution,
                                       delivered_at, response, responded_at
                                FROM contemplation_deliveries
                                ORDER BY delivered_at DESC
                                LIMIT ?
                            """.trimIndent(),
                            parameters = listOf(limit.toLong()),
                            mapper = ::mapDelivery,
                        ),
                    )
                }
            }

    // ── Mapping ──────────────────────────────────────────────────────

    private suspend fun requireUserId(): String =
        sessionRepository.userIdForLocalWrites() ?: error("Not authenticated")

    private fun mapDeck(cursor: SqlCursor): ContemplationDeck = ContemplationDeck(
        id            = cursor.getString(0)!!,
        name          = cursor.getString(1) ?: "",
        description   = cursor.getString(2),
        ordering      = DeckOrdering.from(cursor.getString(3)),
        responseType  = ContemplationResponseType.from(cursor.getString(4)),
        responseLabel = cursor.getString(5),
        weight        = (cursor.getLong(6) ?: 1L).toInt(),
        isEnabled     = (cursor.getLong(7) ?: 1L) != 0L,
        libraryKey    = cursor.getString(8),
        sortOrder     = (cursor.getLong(9) ?: 0L).toInt(),
        itemCount     = (cursor.getLong(10) ?: 0L).toInt(),
    )

    private fun mapItem(cursor: SqlCursor): Contemplation = Contemplation(
        id          = cursor.getString(0)!!,
        deckId      = cursor.getString(1)!!,
        title       = cursor.getString(2),
        body        = cursor.getString(3) ?: "",
        attribution = cursor.getString(4),
        sortOrder   = (cursor.getLong(5) ?: 0L).toInt(),
    )

    private fun mapDelivery(cursor: SqlCursor): ContemplationDelivery = ContemplationDelivery(
        id              = cursor.getString(0)!!,
        deckId          = cursor.getString(1),
        contemplationId = cursor.getString(2),
        title           = cursor.getString(3),
        body            = cursor.getString(4) ?: "",
        attribution     = cursor.getString(5),
        deliveredAt     = cursor.getString(6) ?: "",
        response        = cursor.getString(7),
        respondedAt     = cursor.getString(8),
    )
}
