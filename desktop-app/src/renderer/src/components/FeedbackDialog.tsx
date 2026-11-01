import { useState } from 'react'
import { FEEDBACK_MAX_LENGTH, FeedbackSource, codePointLength, normalizeFeedback } from '@shared/feedback'
import { sendFeedback } from '@shared/repos/feedback'
import { cleanError, db } from '../lib/hooks'
import { Dialog, TextInput } from './ui'

/**
 * The short feedback form, from Settings and the setup guide's last page.
 * shared/CONTRACTS.md (Feedback).
 */
export function FeedbackDialog({ source, onClose }: { source: FeedbackSource; onClose: () => void }) {
  const [message, setMessage] = useState('')
  const [sending, setSending] = useState(false)
  const [sent, setSent] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (sent) {
    return (
      <Dialog title="Thanks!" onClose={onClose} actions={<button className="btn text" onClick={onClose}>Done</button>}>
        <div className="muted">Your feedback is on its way. If you're offline, it's sent when you're back online.</div>
      </Dialog>
    )
  }

  const length = codePointLength(message.trim())
  const send = () => {
    setSending(true)
    setError(null)
    sendFeedback(db, message, source, __APP_VERSION__)
      .then(() => setSent(true))
      .catch((e) => setError(cleanError(e)))
      .finally(() => setSending(false))
  }
  return (
    <Dialog
      title="Send feedback"
      onClose={() => { if (!sending) onClose() }}
      actions={
        <>
          <button className="btn text" disabled={sending} onClick={onClose}>Cancel</button>
          <button className="btn text" disabled={sending || normalizeFeedback(message) == null} onClick={send}>{sending ? 'Sending…' : 'Send'}</button>
        </>
      }
    >
      <div className="stack sm">
        <div className="muted">Something confusing, broken or missing? Or something you like? It comes straight to us.</div>
        <TextInput value={message} onChange={setMessage} placeholder="What's on your mind?" multiline rows={6} autoFocus disabled={sending} invalid={length > FEEDBACK_MAX_LENGTH} />
        {length > FEEDBACK_MAX_LENGTH - 500 && <div className={`small ${length > FEEDBACK_MAX_LENGTH ? 'err-text' : 'muted'}`}>{length} / {FEEDBACK_MAX_LENGTH}</div>}
        <div className="small muted">Saved with your account, along with the platform and app version.</div>
        {error && <div className="small err-text">Couldn't send it: {error}</div>}
      </div>
    </Dialog>
  )
}
