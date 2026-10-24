// Port of data/contemplation/ContemplationRepository.kt.

import { Db, bool, requireUserId } from '../db'
import { nowIso, uuid } from '../json'
import type {
  Contemplation, ContemplationDeck, ContemplationDelivery, ContemplationResponseType, DeckOrdering,
} from '../models'
import { LibraryDeck } from '../contemplationLibrary'
import { Random, pickItemIndex, pickWeightedIndex } from '../contemplationSchedule'

export const DECK_TABLES = ['contemplation_decks', 'contemplations']
export const DELIVERY_TABLES = ['contemplation_deliveries']

const DECK_COLUMNS = `d.id, d.name, d.description, d.ordering, d.response_type, d.response_label,
  d.weight, d.is_enabled, d.library_key, d.sort_order,
  (SELECT COUNT(*) FROM contemplations c WHERE c.deck_id = d.id) AS item_count`

function mapDeck(r: Record<string, any>): ContemplationDeck {
  return {
    id: r.id,
    name: r.name ?? '',
    description: r.description ?? null,
    ordering: r.ordering === 'random' ? 'random' : 'sequential',
    responseType: r.response_type === 'affirm' ? 'affirm' : 'none',
    responseLabel: r.response_label ?? null,
    weight: Number(r.weight ?? 1),
    isEnabled: Number(r.is_enabled ?? 1) !== 0,
    libraryKey: r.library_key ?? null,
    sortOrder: Number(r.sort_order ?? 0),
    itemCount: Number(r.item_count ?? 0),
  }
}

export async function getDecks(db: Db): Promise<ContemplationDeck[]> {
  return (await db.getAll(`SELECT ${DECK_COLUMNS} FROM contemplation_decks d ORDER BY d.sort_order, d.created_at`)).map(mapDeck)
}

export async function getDeck(db: Db, deckId: string): Promise<ContemplationDeck | null> {
  const row = await db.getOptional(`SELECT ${DECK_COLUMNS} FROM contemplation_decks d WHERE d.id = ?`, [deckId])
  return row ? mapDeck(row) : null
}

export async function createDeck(db: Db, name: string): Promise<string> {
  const userId = await requireUserId(db)
  const id = uuid()
  const now = nowIso()
  await db.execute(
    `INSERT INTO contemplation_decks
       (id, user_id, name, description, ordering, response_type, response_label,
        weight, is_enabled, library_key, sort_order, created_at, updated_at)
     VALUES (?, ?, ?, NULL, ?, ?, NULL, 1, 1, NULL, 0, ?, ?)`,
    [id, userId, name, 'random', 'none', now, now],
  )
  return id
}

export async function addDeckFromLibrary(db: Db, deck: LibraryDeck): Promise<string> {
  const userId = await requireUserId(db)
  const now = nowIso()
  const deckId = uuid()
  await db.batch([
    {
      sql: `INSERT INTO contemplation_decks
              (id, user_id, name, description, ordering, response_type, response_label,
               weight, is_enabled, library_key, sort_order, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, 0, ?, ?)`,
      params: [deckId, userId, deck.name, deck.description, deck.ordering, deck.responseType, deck.responseLabel, deck.weight, deck.key, now, now],
    },
    ...deck.items.map((item, index) => ({
      sql: `INSERT INTO contemplations (id, user_id, deck_id, title, body, attribution, sort_order, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      params: [uuid(), userId, deckId, item.title, item.body, item.attribution, index, now, now],
    })),
  ])
  return deckId
}

export async function updateDeck(
  db: Db,
  deckId: string,
  fields: { name: string; description: string | null; ordering: DeckOrdering; responseType: ContemplationResponseType; responseLabel: string | null; weight: number },
): Promise<void> {
  await db.execute(
    `UPDATE contemplation_decks
     SET name = ?, description = ?, ordering = ?, response_type = ?, response_label = ?, weight = ?, updated_at = ?
     WHERE id = ?`,
    [fields.name, fields.description, fields.ordering, fields.responseType, fields.responseLabel, Math.max(1, fields.weight), nowIso(), deckId],
  )
}

export async function setDeckEnabled(db: Db, deckId: string, enabled: boolean): Promise<void> {
  await db.execute('UPDATE contemplation_decks SET is_enabled = ?, updated_at = ? WHERE id = ?', [bool(enabled), nowIso(), deckId])
}

/** Deletes a deck and its items (the server cascade doesn't apply locally). Deliveries stay. */
export async function deleteDeck(db: Db, deckId: string): Promise<void> {
  await db.batch([
    { sql: 'DELETE FROM contemplations WHERE deck_id = ?', params: [deckId] },
    { sql: 'DELETE FROM contemplation_decks WHERE id = ?', params: [deckId] },
  ])
}

export async function getItems(db: Db, deckId: string): Promise<Contemplation[]> {
  const rows = await db.getAll(
    `SELECT id, deck_id, title, body, attribution, sort_order FROM contemplations
     WHERE deck_id = ? ORDER BY sort_order, created_at`,
    [deckId],
  )
  return rows.map((r) => ({
    id: r.id,
    deckId: r.deck_id,
    title: r.title ?? null,
    body: r.body ?? '',
    attribution: r.attribution ?? null,
    sortOrder: Number(r.sort_order ?? 0),
  }))
}

export async function addItem(db: Db, deckId: string, title: string | null, body: string, attribution: string | null): Promise<void> {
  const userId = await requireUserId(db)
  const now = nowIso()
  await db.execute(
    `INSERT INTO contemplations (id, user_id, deck_id, title, body, attribution, sort_order, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, (SELECT COALESCE(MAX(sort_order), -1) + 1 FROM contemplations WHERE deck_id = ?), ?, ?)`,
    [uuid(), userId, deckId, title, body, attribution, deckId, now, now],
  )
}

export async function updateItem(db: Db, itemId: string, title: string | null, body: string, attribution: string | null): Promise<void> {
  await db.execute('UPDATE contemplations SET title = ?, body = ?, attribution = ?, updated_at = ? WHERE id = ?', [title, body, attribution, nowIso(), itemId])
}

export async function deleteItem(db: Db, itemId: string): Promise<void> {
  await db.execute('DELETE FROM contemplations WHERE id = ?', [itemId])
}

export interface ContemplationChoice {
  deck: ContemplationDeck
  item: Contemplation
}

/** A deck by weight among enabled decks with items, then an item by the deck's ordering. */
export async function pickNext(db: Db, random: Random = Math.random): Promise<ContemplationChoice | null> {
  const decks = (await getDecks(db)).filter((d) => d.isEnabled && d.itemCount > 0)
  if (decks.length === 0) return null
  const deck = decks[pickWeightedIndex(decks.map((d) => d.weight), random)]
  const items = await getItems(db, deck.id)
  if (items.length === 0) return null
  const last = await db.getOptional<{ cid: string }>(
    `SELECT COALESCE(contemplation_id, '') AS cid FROM contemplation_deliveries
     WHERE deck_id = ? ORDER BY delivered_at DESC LIMIT 1`,
    [deck.id],
  )
  const index = pickItemIndex(deck.ordering, items.map((i) => i.id), last?.cid ?? null, random)
  return { deck, item: items[index] }
}

/** Logs a delivery, snapshotting its content. */
export async function recordDelivery(db: Db, choice: ContemplationChoice): Promise<string> {
  const userId = await requireUserId(db)
  const id = uuid()
  const now = nowIso()
  await db.execute(
    `INSERT INTO contemplation_deliveries
       (id, user_id, deck_id, contemplation_id, title, body, attribution, delivered_at, response, responded_at, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?)`,
    [id, userId, choice.deck.id, choice.item.id, choice.item.title, choice.item.body, choice.item.attribution, now, now],
  )
  return id
}

/** Records the user's response; the first response wins. */
export async function recordResponse(db: Db, deliveryId: string, response: string): Promise<void> {
  await db.execute(
    'UPDATE contemplation_deliveries SET response = ?, responded_at = ? WHERE id = ? AND response IS NULL',
    [response, nowIso(), deliveryId],
  )
}

export async function getRecentDeliveries(db: Db, limit = 20): Promise<ContemplationDelivery[]> {
  const rows = await db.getAll(
    `SELECT id, deck_id, contemplation_id, title, body, attribution, delivered_at, response, responded_at
     FROM contemplation_deliveries ORDER BY delivered_at DESC LIMIT ?`,
    [limit],
  )
  return rows.map((r) => ({
    id: r.id,
    deckId: r.deck_id ?? null,
    contemplationId: r.contemplation_id ?? null,
    title: r.title ?? null,
    body: r.body ?? '',
    attribution: r.attribution ?? null,
    deliveredAt: r.delivered_at ?? '',
    response: r.response ?? null,
    respondedAt: r.responded_at ?? null,
  }))
}

/** Response type of the deck a delivery came from (null if the deck is gone). */
export async function getDeliveryDeck(db: Db, deliveryId: string): Promise<ContemplationDeck | null> {
  const row = await db.getOptional<{ deck_id: string | null }>('SELECT deck_id FROM contemplation_deliveries WHERE id = ?', [deliveryId])
  return row?.deck_id ? getDeck(db, row.deck_id) : null
}
