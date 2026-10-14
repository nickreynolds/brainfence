package dev.brainfence.data.contemplation

import dev.brainfence.domain.model.ContemplationResponseType
import dev.brainfence.domain.model.DeckOrdering

/**
 * Starter decks the user can add with one tap. Adding copies the deck into the
 * user's own tables (tagged with [LibraryDeck.key]), where it can be edited
 * freely — the library is a template, never read at delivery time.
 *
 * To offer a new starter deck, add it to [decks].
 */
object ContemplationLibrary {

    data class LibraryItem(val title: String?, val body: String, val attribution: String?)

    data class LibraryDeck(
        val key: String,
        val name: String,
        val description: String,
        val ordering: DeckOrdering,
        val responseType: ContemplationResponseType,
        val responseLabel: String?,
        val weight: Int,
        val items: List<LibraryItem>,
    )

    private const val REMEMBRANCES_SOURCE = "Upajjhatthana Sutta (AN 5.57), tr. Thich Nhat Hanh"

    val FIVE_REMEMBRANCES = LibraryDeck(
        key = "five_remembrances",
        name = "The Five Remembrances",
        description = "The Buddha's five daily recollections, delivered in turn.",
        ordering = DeckOrdering.SEQUENTIAL,
        responseType = ContemplationResponseType.AFFIRM,
        responseLabel = "I remember",
        weight = 4,
        items = listOf(
            LibraryItem(
                "The First Remembrance",
                "I am of the nature to grow old. There is no way to escape growing old.",
                REMEMBRANCES_SOURCE,
            ),
            LibraryItem(
                "The Second Remembrance",
                "I am of the nature to have ill health. There is no way to escape having ill health.",
                REMEMBRANCES_SOURCE,
            ),
            LibraryItem(
                "The Third Remembrance",
                "I am of the nature to die. There is no way to escape death.",
                REMEMBRANCES_SOURCE,
            ),
            LibraryItem(
                "The Fourth Remembrance",
                "All that is dear to me and everyone I love are of the nature to change. " +
                    "There is no way to escape being separated from them.",
                REMEMBRANCES_SOURCE,
            ),
            LibraryItem(
                "The Fifth Remembrance",
                "My actions are my only true belongings. I cannot escape the consequences " +
                    "of my actions. My actions are the ground upon which I stand.",
                REMEMBRANCES_SOURCE,
            ),
        ),
    )

    val SAYINGS = LibraryDeck(
        key = "sayings",
        name = "Sayings",
        description = "Occasional sayings on death, comparison and contentment.",
        ordering = DeckOrdering.RANDOM,
        responseType = ContemplationResponseType.NONE,
        responseLabel = null,
        weight = 1,
        items = listOf(
            LibraryItem(
                null,
                "To be a happy person, one must contemplate death five times daily.",
                "Bhutanese saying",
            ),
            LibraryItem(null, "Comparison is the thief of joy.", "Attributed to Theodore Roosevelt"),
            LibraryItem(
                null,
                "There is no such thing as bad weather, only bad clothing.",
                "Scandinavian saying",
            ),
            LibraryItem(
                null,
                "You could leave life right now. Let that determine what you do and say and think.",
                "Marcus Aurelius, Meditations 2.11",
            ),
            LibraryItem(null, "Enough is as good as a feast.", "English proverb"),
        ),
    )

    val decks: List<LibraryDeck> = listOf(FIVE_REMEMBRANCES, SAYINGS)
}
