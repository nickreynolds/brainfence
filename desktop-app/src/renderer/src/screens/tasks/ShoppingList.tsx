import { useState } from 'react'
import type { ShoppingItem, Task } from '@shared/models'
import { SHOPPING_TABLES, addItem, completeItem, deleteItem, getOpenItems, uncompleteItem } from '@shared/repos/shopping'
import { Icon } from '../../components/Icon'
import { IconButton, TextInput, Toast, TopBar } from '../../components/ui'
import { plural } from '../../lib/format'
import { db, useQuery } from '../../lib/hooks'
import { useRouter } from '../../lib/router'

/** Port of ShoppingListScreen: a permanent list; checked items disappear (with undo). */
export function ShoppingListScreen({ task }: { task: Task }) {
  const { navigate } = useRouter()
  const { data: items } = useQuery(() => getOpenItems(db, task.id), SHOPPING_TABLES, [task.id], [] as ShoppingItem[])
  const [draft, setDraft] = useState('')
  const [undo, setUndo] = useState<ShoppingItem | null>(null)

  const add = () => {
    const title = draft.trim()
    if (!title) return
    setDraft('')
    void addItem(db, task.id, title)
  }

  return (
    <>
      <TopBar title={task.title} actions={<IconButton icon="edit" title="Edit list" onClick={() => navigate(`editor/${task.id}`)} />} />
      <div className="stack">
        <div className="row">
          <div className="grow"><TextInput value={draft} onChange={setDraft} placeholder="Add item" onEnter={add} autoFocus /></div>
          <button className="btn" onClick={add} disabled={!draft.trim()}><Icon name="add" /> Add</button>
        </div>
        {items.length === 0 ? (
          <div className="empty">
            <div className="title-sm">Nothing to buy</div>
            <div className="small">Items you add here trigger a reminder when you arrive at the configured location.</div>
          </div>
        ) : (
          <>
            <div className="small muted">{plural(items.length, 'item')} to buy</div>
            <div className="list">
              {items.map((item) => (
                <div key={item.id} className="list-item" style={{ minHeight: 48 }}>
                  <button className="icon-btn" title="Check off" onClick={() => { void completeItem(db, item.id); setUndo(item) }}>
                    <Icon name="circle" />
                  </button>
                  <div className="grow headline">{item.title}</div>
                  <IconButton icon="delete" title="Delete item" small onClick={() => void deleteItem(db, item.id)} />
                </div>
              ))}
            </div>
          </>
        )}
      </div>
      {undo && (
        <Toast
          key={undo.id}
          message={`Checked off "${undo.title}"`}
          action="Undo"
          onAction={() => void uncompleteItem(db, undo.id)}
          onDone={() => setUndo(null)}
        />
      )}
    </>
  )
}
