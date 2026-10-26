import { useEffect, useState } from 'react'
import { CONTEMPLATION_LIBRARY, DEFAULT_AFFIRM_LABEL } from '@shared/contemplationLibrary'
import type { ContemplationSettings } from '@shared/ipc'
import { AFFIRMED, Contemplation, ContemplationDeck, ContemplationDelivery, ContemplationResponseType, DeckOrdering } from '@shared/models'
import {
  DECK_TABLES, DELIVERY_TABLES, addDeckFromLibrary, addItem, createDeck, deleteDeck, deleteItem, getDeck, getDecks, getItems,
  getRecentDeliveries, recordResponse, setDeckEnabled, updateDeck, updateItem,
} from '@shared/repos/contemplations'
import { parseTaskTime } from '@shared/time'
import { Icon } from '../components/Icon'
import { Chip, ConfirmDialog, Dialog, ErrorText, Field, IconButton, Loading, NumberInput, Switch, TextInput, TopBar } from '../components/ui'
import { formatTime, isToday, plural } from '../lib/format'
import { db, useQuery } from '../lib/hooks'
import { useRouter } from '../lib/router'

const MAX_PER_DAY = 24

/** Port of ContemplationSettingsScreen. Scheduling is per device; decks and history sync. */
export function ContemplationsScreen() {
  const { navigate } = useRouter()
  const [form, setForm] = useState<ContemplationSettings | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const { data: decks } = useQuery(() => getDecks(db), DECK_TABLES, [], [] as ContemplationDeck[])
  const { data: recent } = useQuery(() => getRecentDeliveries(db), [...DELIVERY_TABLES, ...DECK_TABLES], [], [] as ContemplationDelivery[])

  useEffect(() => {
    void window.api.settings.getContemplation().then(setForm)
  }, [])
  if (!form) return <Loading />

  const added = new Set(decks.map((d) => d.libraryKey).filter(Boolean))
  const library = CONTEMPLATION_LIBRARY.filter((d) => !added.has(d.key))
  const todays = recent.filter((d) => isToday(d.deliveredAt))
  const affirmedToday = todays.filter((d) => d.response === AFFIRMED).length

  const setEnabled = async (enabled: boolean) => {
    // Toggling takes effect immediately, using the last saved schedule.
    const saved = await window.api.settings.getContemplation()
    await window.api.settings.setContemplation({ ...saved, enabled })
    setForm({ ...form, enabled })
    setMessage(null)
  }
  const saveSchedule = async () => {
    const start = parseTaskTime(form.windowStart)
    const end = parseTaskTime(form.windowEnd)
    if (!(form.perDay >= 1 && form.perDay <= MAX_PER_DAY)) return setError(`Per day must be between 1 and ${MAX_PER_DAY}`)
    if (start == null || end == null) return setError('Times must be HH:MM (e.g. 08:00)')
    if (end <= start) return setError('The window must end after it starts')
    setError(null)
    await window.api.settings.setContemplation(form)
    setMessage('Schedule saved')
  }
  const sendNow = async () => {
    const sent = await window.api.settings.sendContemplationNow()
    if (sent) {
      setMessage('Sent')
      setError(null)
    } else setError('Nothing sent — needs an enabled deck with items and notifications allowed for Brainfence')
  }

  return (
    <>
      <TopBar title="Contemplations" />
      <div className="stack lg">
        <div className="small muted">"To be a happy person, one must contemplate death five times daily." Short passages arrive as notifications at random times through your day.</div>

        <div className="card stack">
          <div className="row">
            <div className="grow">
              <div className="title-sm">Deliver on this PC</div>
              <div className="small muted">Each device keeps its own schedule.</div>
            </div>
            <Switch checked={form.enabled} onChange={(v) => void setEnabled(v)} label="Deliver contemplations" />
          </div>
          <div className="grid2">
            <Field label="Per day"><NumberInput value={form.perDay} min={1} max={MAX_PER_DAY} onChange={(perDay) => setForm({ ...form, perDay })} /></Field>
            <div />
            <Field label="From"><input className="input" type="time" value={form.windowStart} onChange={(e) => setForm({ ...form, windowStart: e.target.value })} /></Field>
            <Field label="Until"><input className="input" type="time" value={form.windowEnd} onChange={(e) => setForm({ ...form, windowEnd: e.target.value })} /></Field>
          </div>
          <ErrorText error={error} />
          {message && <div className="small primary-text">{message}</div>}
          <div className="row wrap">
            <button className="btn" onClick={() => void saveSchedule()}>Save schedule</button>
            <button className="btn outlined" onClick={() => void sendNow()}>Send one now</button>
          </div>
        </div>

        <div className="stack sm">
          <div className="row between">
            <div className="title-md">Decks</div>
            <button className="btn text sm" onClick={() => void createDeck(db, 'New deck').then((id) => navigate(`contemplations/deck/${id}`))}><Icon name="add" size="sm" /> New deck</button>
          </div>
          {decks.length > 1 && <div className="small muted">When several decks are on, each delivery picks one in proportion to its frequency.</div>}
          {decks.map((deck) => (
            <div key={deck.id} className="card row clickable" onClick={() => navigate(`contemplations/deck/${deck.id}`)}>
              <div className="grow">
                <div className="title-sm">{deck.name}</div>
                <div className="small muted">
                  {plural(deck.itemCount, 'item')} · {deck.ordering === 'sequential' ? 'in turn' : 'random'} · frequency {deck.weight}
                  {deck.responseType === 'affirm' ? ` · “${deck.responseLabel || DEFAULT_AFFIRM_LABEL}”` : ''}
                </div>
              </div>
              <div onClick={(e) => e.stopPropagation()}>
                <Switch checked={deck.isEnabled} onChange={(v) => void setDeckEnabled(db, deck.id, v)} label={`Enable ${deck.name}`} />
              </div>
            </div>
          ))}
          {library.map((d) => (
            <div key={d.key} className="card outlined row">
              <div className="grow">
                <div className="title-sm">Starter: {d.name}</div>
                <div className="small muted">{d.description}</div>
              </div>
              <button className="btn text sm" onClick={() => void addDeckFromLibrary(db, d)}>Add</button>
            </div>
          ))}
        </div>

        {recent.length > 0 && (
          <div className="stack sm">
            <div className="title-md">Recent</div>
            <div className="small muted">Today: {todays.length} delivered · {affirmedToday} affirmed</div>
            {recent.map((d) => {
              const deck = decks.find((x) => x.id === d.deckId)
              const canAffirm = !d.response && deck?.responseType === 'affirm'
              return (
                <div key={d.id} className="card stack sm">
                  <div className="row between">
                    <span className="title-sm">{d.title ?? deck?.name ?? 'Contemplation'}</span>
                    <span className="small muted">{isToday(d.deliveredAt) ? formatTime(d.deliveredAt) : new Date(d.deliveredAt).toLocaleDateString()}</span>
                  </div>
                  <div className="small">{d.body}</div>
                  {d.attribution && <div className="small muted">— {d.attribution}</div>}
                  {d.response === AFFIRMED && <div className="small primary-text row"><Icon name="check" size="sm" /> Affirmed {formatTime(d.respondedAt)}</div>}
                  {canAffirm && (
                    <div><button className="btn tonal sm" onClick={() => void recordResponse(db, d.id, AFFIRMED)}>{deck!.responseLabel || DEFAULT_AFFIRM_LABEL}</button></div>
                  )}
                </div>
              )
            })}
          </div>
        )}
      </div>
    </>
  )
}

/** Port of ContemplationDeckScreen. */
export function DeckEditorScreen({ deckId }: { deckId: string }) {
  const { back } = useRouter()
  const { data: deck, loading } = useQuery(() => getDeck(db, deckId), DECK_TABLES, [deckId], null as ContemplationDeck | null)
  const { data: items } = useQuery(() => getItems(db, deckId), ['contemplations'], [deckId], [] as Contemplation[])
  const [form, setForm] = useState<{ name: string; description: string; ordering: DeckOrdering; responseType: ContemplationResponseType; responseLabel: string; weight: number } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState<{ id: string | null; title: string; body: string; attribution: string } | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)

  // Seed once so later item edits don't clobber unsaved fields.
  useEffect(() => {
    if (deck && !form) {
      setForm({ name: deck.name, description: deck.description ?? '', ordering: deck.ordering, responseType: deck.responseType, responseLabel: deck.responseLabel ?? '', weight: deck.weight })
    }
  }, [deck, form])

  if (loading) return <Loading />
  if (!deck || !form) return (<><TopBar title="Deck" /><div className="empty">This deck no longer exists.</div></>)

  const save = async () => {
    if (!form.name.trim()) return setError('Name is required')
    if (!(form.weight >= 1)) return setError('Frequency must be a whole number of at least 1')
    await updateDeck(db, deckId, {
      name: form.name.trim(), description: form.description.trim() || null, ordering: form.ordering,
      responseType: form.responseType, responseLabel: form.responseLabel.trim() || null, weight: Math.trunc(form.weight),
    })
    back()
  }
  const saveItem = async () => {
    if (!editing || !editing.body.trim()) return
    const t = editing.title.trim() || null
    const a = editing.attribution.trim() || null
    if (editing.id) await updateItem(db, editing.id, t, editing.body.trim(), a)
    else await addItem(db, deckId, t, editing.body.trim(), a)
    setEditing(null)
  }

  return (
    <>
      <TopBar title={deck.name} actions={<IconButton icon="delete" title="Delete deck" onClick={() => setConfirmDelete(true)} />} />
      <div className="stack lg">
        <Field label="Name"><TextInput value={form.name} onChange={(name) => setForm({ ...form, name })} /></Field>
        <Field label="Description (optional)"><TextInput value={form.description} onChange={(description) => setForm({ ...form, description })} /></Field>
        <div className="section-label">Order</div>
        <div className="chips">
          <Chip selected={form.ordering === 'sequential'} onClick={() => setForm({ ...form, ordering: 'sequential' })}>In turn</Chip>
          <Chip selected={form.ordering === 'random'} onClick={() => setForm({ ...form, ordering: 'random' })}>Random</Chip>
        </div>
        <div className="section-label">Response</div>
        <div className="chips">
          <Chip selected={form.responseType === 'none'} onClick={() => setForm({ ...form, responseType: 'none' })}>Just read</Chip>
          <Chip selected={form.responseType === 'affirm'} onClick={() => setForm({ ...form, responseType: 'affirm' })}>Affirm button</Chip>
        </div>
        {form.responseType === 'affirm' && (
          <Field label="Button label"><TextInput value={form.responseLabel} onChange={(responseLabel) => setForm({ ...form, responseLabel })} placeholder={DEFAULT_AFFIRM_LABEL} /></Field>
        )}
        <Field label="Frequency" help="Relative to other enabled decks, e.g. 4 vs 1 ≈ 80% of deliveries">
          <NumberInput value={form.weight} min={1} onChange={(weight) => setForm({ ...form, weight })} />
        </Field>
        <ErrorText error={error} />
        <button className="btn block" onClick={() => void save()}>Save deck</button>

        <div className="stack sm">
          <div className="title-md">Contemplations</div>
          <div className="small muted">Changes here save immediately.</div>
          {items.map((item) => (
            <div key={item.id} className="card clickable stack sm" onClick={() => setEditing({ id: item.id, title: item.title ?? '', body: item.body, attribution: item.attribution ?? '' })}>
              {item.title && <div className="title-sm">{item.title}</div>}
              <div className="small">{item.body}</div>
              {item.attribution && <div className="small muted">— {item.attribution}</div>}
            </div>
          ))}
          <button className="btn outlined" onClick={() => setEditing({ id: null, title: '', body: '', attribution: '' })}><Icon name="add" /> Add contemplation</button>
        </div>
      </div>

      {editing && (
        <Dialog
          title={editing.id ? 'Edit contemplation' : 'New contemplation'}
          onClose={() => setEditing(null)}
          actions={
            <>
              {editing.id && <button className="btn text danger" style={{ marginRight: 'auto' }} onClick={() => { void deleteItem(db, editing.id!); setEditing(null) }}>Delete</button>}
              <button className="btn text" onClick={() => setEditing(null)}>Cancel</button>
              <button className="btn text" disabled={!editing.body.trim()} onClick={() => void saveItem()}>Save</button>
            </>
          }
        >
          <div className="stack">
            <Field label="Title (optional)"><TextInput value={editing.title} onChange={(title) => setEditing({ ...editing, title })} /></Field>
            <Field label="Text"><TextInput value={editing.body} onChange={(body) => setEditing({ ...editing, body })} multiline rows={4} autoFocus /></Field>
            <Field label="Attribution (optional)"><TextInput value={editing.attribution} onChange={(attribution) => setEditing({ ...editing, attribution })} /></Field>
          </div>
        </Dialog>
      )}
      {confirmDelete && (
        <ConfirmDialog
          title="Delete deck?"
          message="Its contemplations are deleted too. Past deliveries stay in your history."
          confirmLabel="Delete"
          cancelLabel="Keep"
          danger
          onCancel={() => setConfirmDelete(false)}
          onConfirm={() => void deleteDeck(db, deckId).then(back)}
        />
      )}
    </>
  )
}
