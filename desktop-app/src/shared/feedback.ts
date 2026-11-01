// Feedback the user sends from the app. Shared semantics: shared/CONTRACTS.md
// (Feedback); cases: shared/fixtures/feedback.json, which Android and macOS run too.

/** In Unicode code points, as Postgres counts them. */
export const FEEDBACK_MAX_LENGTH = 4000

/** Where it was sent from (the `source` column). */
export type FeedbackSource = 'settings' | 'setup_guide'

/** Length in Unicode code points (an emoji is one). */
export const codePointLength = (text: string): number => [...text].length

/** The message as stored: trimmed, or null when it can't be sent. */
export function normalizeFeedback(message: string): string | null {
  const trimmed = message.trim()
  const length = codePointLength(trimmed)
  return length >= 1 && length <= FEEDBACK_MAX_LENGTH ? trimmed : null
}
