package dev.brainfence.domain.model

/** How a deck chooses its next item. */
enum class DeckOrdering(val key: String) {
    /** Rotate through items in order across deliveries (none skipped). */
    SEQUENTIAL("sequential"),
    /** Any item, avoiding an immediate repeat. */
    RANDOM("random");

    companion object {
        fun from(key: String?): DeckOrdering = entries.firstOrNull { it.key == key } ?: SEQUENTIAL
    }
}

/**
 * What a contemplation notification asks of the user.
 *
 * To add a kind (e.g. an inline text reply for gratitude prompts): add it here,
 * build its notification action in `ContemplationNotifier`, handle the result in
 * `ContemplationReceiver`, and extend the CHECK constraints on
 * `contemplation_decks.response_type` / `contemplation_deliveries.response`.
 */
enum class ContemplationResponseType(val key: String) {
    /** Just read it. */
    NONE("none"),
    /** A single action button (e.g. "I remember"), logged as `affirmed`. */
    AFFIRM("affirm");

    companion object {
        fun from(key: String?): ContemplationResponseType =
            entries.firstOrNull { it.key == key } ?: NONE
    }
}

data class ContemplationDeck(
    val id: String,
    val name: String,
    val description: String?,
    val ordering: DeckOrdering,
    val responseType: ContemplationResponseType,
    /** Action button label for [ContemplationResponseType.AFFIRM]; null = default. */
    val responseLabel: String?,
    /** Relative share of delivery slots when several decks are enabled. */
    val weight: Int,
    val isEnabled: Boolean,
    /** Set when added from the built-in library. */
    val libraryKey: String?,
    val sortOrder: Int,
    val itemCount: Int,
)

data class Contemplation(
    val id: String,
    val deckId: String,
    val title: String?,
    val body: String,
    val attribution: String?,
    val sortOrder: Int,
)

/** One notification shown, with a snapshot of its content and the user's response. */
data class ContemplationDelivery(
    val id: String,
    val deckId: String?,
    val contemplationId: String?,
    val title: String?,
    val body: String,
    val attribution: String?,
    val deliveredAt: String,
    /** null = no response; otherwise e.g. `affirmed`. */
    val response: String?,
    val respondedAt: String?,
)

object ContemplationResponses {
    const val AFFIRMED = "affirmed"
}
