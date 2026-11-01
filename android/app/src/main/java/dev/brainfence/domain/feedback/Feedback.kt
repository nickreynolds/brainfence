package dev.brainfence.domain.feedback

/**
 * Feedback the user sends from the app. Shared semantics: shared/CONTRACTS.md
 * (Feedback); cases: shared/fixtures/feedback.json, which macOS and Windows run too.
 */
object Feedback {
    /** In Unicode code points, as Postgres counts them. */
    const val MAX_LENGTH = 4000

    /** Where it was sent from (the `source` column). */
    enum class Source(val key: String) { SETTINGS("settings"), SETUP_GUIDE("setup_guide") }

    /** The message as stored: trimmed, or null when it can't be sent. */
    fun normalize(message: String): String? {
        val trimmed = message.trim()
        return trimmed.takeIf { it.codePointCount(0, it.length) in 1..MAX_LENGTH }
    }
}
