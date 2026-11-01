// Sends feedback through the insert-only `feedback` table: queued locally,
// uploaded when online, never synced back. Same as Android's
// FeedbackRepository. See shared/CONTRACTS.md (Feedback).

import { Db, PLATFORM, requireUserId } from '../db'
import { FEEDBACK_MAX_LENGTH, FeedbackSource, normalizeFeedback } from '../feedback'
import { nowIso, uuid } from '../json'

export async function sendFeedback(db: Db, message: string, source: FeedbackSource, appVersion: string): Promise<void> {
  const text = normalizeFeedback(message)
  if (text == null) throw new Error(`Write something first (up to ${FEEDBACK_MAX_LENGTH} characters).`)
  const userId = await requireUserId(db)
  await db.execute(
    `INSERT INTO feedback (id, user_id, message, platform, app_version, source, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)`,
    [uuid(), userId, text, PLATFORM, appVersion, source, nowIso()],
  )
}
