import { useEffect, useState } from 'react'
import type { Task } from '@shared/models'
import { completeTask } from '@shared/repos/tasks'
import { TextInput } from '../../components/ui'
import { db, useAction, useNow } from '../../lib/hooks'
import { useRouter } from '../../lib/router'
import { CompletedToday, NotYetAvailable, TaskTopBar, isBeforeStart } from './common'

/** Port of JournalTaskScreen. The draft survives closing the window. */
export function JournalTaskScreen({ task }: { task: Task }) {
  const { back, navigate } = useRouter()
  const now = useNow(30_000)
  const draftKey = `journalDraft:${task.id}`
  const [text, setText] = useState('')
  const { busy, error, run } = useAction()

  useEffect(() => {
    void window.api.kv.get(draftKey).then((v) => typeof v === 'string' && setText(v))
  }, [draftKey])

  const update = (v: string) => {
    setText(v)
    void window.api.kv.set(draftKey, v || null)
  }

  const submit = () =>
    void run(async () => {
      await completeTask(db, task.id, JSON.stringify({ text }))
      await window.api.kv.set(draftKey, null)
      back()
    })

  if (task.completedToday) {
    return (
      <>
        <TaskTopBar task={task} />
        <CompletedToday />
        <div className="center"><button className="btn text" onClick={() => navigate('journal')}>View journal history</button></div>
      </>
    )
  }
  return (
    <>
      <TaskTopBar task={task} />
      <div className="stack" style={{ marginTop: 8 }}>
        {task.description && <div className="muted">{task.description}</div>}
        <NotYetAvailable task={task} />
        <TextInput value={text} onChange={update} multiline rows={14} placeholder="Write your journal entry…" autoFocus />
        {error && <div className="err-text small">{error}</div>}
        <button className="btn" disabled={busy || text.trim() === '' || isBeforeStart(task, now)} onClick={submit}>
          {busy ? 'Saving…' : 'Save entry'}
        </button>
      </div>
    </>
  )
}
