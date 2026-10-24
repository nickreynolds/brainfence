// Ports of service/ContemplationScheduler.kt, ContemplationNotifier.kt and
// ContemplationReceiver.kt. One timer is kept pending for the next slot; the
// day's slots derive deterministically from a per-install seed, so
// rescheduling is stateless and safe to call any time (start, settings
// change, delivery, resume from sleep).

import { Notification } from 'electron'
import { DEFAULT_AFFIRM_LABEL } from '@shared/contemplationLibrary'
import { nextContemplationSlot } from '@shared/contemplationSchedule'
import type { ContemplationSettings } from '@shared/ipc'
import { AFFIRMED } from '@shared/models'
import { getDeliveryDeck, pickNext, recordDelivery, recordResponse } from '@shared/repos/contemplations'
import { parseTaskTime } from '@shared/time'
import { userIdForLocalWrites } from './auth'
import { PROTOCOL } from './config'
import { log } from './debugLog'
import { mainDb } from './powersync'
import { getValue, setValue } from './store'
import { resourcePath, showMainWindow } from './windows'

const DEFAULTS: ContemplationSettings = { enabled: false, perDay: 5, windowStart: '08:00', windowEnd: '21:00' }

let timer: NodeJS.Timeout | null = null
let current: Notification | null = null

export function getContemplationSettings(): ContemplationSettings {
  return { ...DEFAULTS, ...getValue<Partial<ContemplationSettings>>('contemplationSettings', {}) }
}

export function setContemplationSettings(settings: ContemplationSettings): void {
  setValue('contemplationSettings', settings)
  rescheduleContemplations()
}

function scheduleSeed(): number {
  let seed = getValue<number | null>('contemplationSeed', null)
  if (seed == null) {
    seed = Math.floor(Math.random() * 2 ** 31)
    setValue('contemplationSeed', seed)
  }
  return seed
}

export function rescheduleContemplations(after: Date = new Date()): void {
  if (timer) clearTimeout(timer)
  timer = null
  const s = getContemplationSettings()
  const start = parseTaskTime(s.windowStart)
  const end = parseTaskTime(s.windowEnd)
  const next = s.enabled && start != null && end != null
    ? nextContemplationSlot(after, { perDay: s.perDay, windowStart: start, windowEnd: end }, scheduleSeed())
    : null
  if (!next) {
    log('contemplation', `No contemplation scheduled (enabled=${s.enabled})`)
    return
  }
  const slotMs = next.getTime()
  // setTimeout drifts across sleep; cap the wait and re-check.
  const wait = Math.min(Math.max(0, slotMs - Date.now()), 15 * 60_000)
  timer = setTimeout(() => {
    if (Date.now() + 1000 < slotMs) rescheduleContemplations(after)
    else void onSlot(slotMs)
  }, wait)
  log('contemplation', `Next contemplation scheduled for ${next.toLocaleString()}`)
}

async function onSlot(slotMs: number): Promise<void> {
  // Schedule the next slot first so the chain survives a failed delivery.
  rescheduleContemplations(new Date(Math.max(Date.now(), slotMs)))
  if (!getContemplationSettings().enabled) return
  if (slotMs <= getValue<number>('lastDeliveredSlotMs', 0)) return
  setValue('lastDeliveredSlotMs', slotMs)
  await deliverContemplation()
}

/** Deliver one now. False (and logged) when nothing could be shown. */
export async function deliverContemplation(): Promise<boolean> {
  if (!userIdForLocalWrites()) {
    log('contemplation', 'Skipped delivery: no one is signed in')
    return false
  }
  if (!Notification.isSupported()) {
    log('contemplation', 'Skipped delivery: notifications unsupported')
    return false
  }
  const choice = await pickNext(mainDb)
  if (!choice) {
    log('contemplation', 'Skipped delivery: no enabled deck with items')
    return false
  }
  const deliveryId = await recordDelivery(mainDb, choice)
  const { item, deck } = choice
  const body = item.attribution ? `${item.body}\n— ${item.attribution}` : item.body
  const title = item.title ?? deck.name
  const affirm = deck.responseType === 'affirm' ? deck.responseLabel?.trim() || DEFAULT_AFFIRM_LABEL : null

  current?.close()
  current = new Notification({
    title,
    body,
    icon: resourcePath('icon.png'),
    // Windows toasts can't carry Electron action handlers; a protocol
    // activation brings the response back through the single-instance lock.
    toastXml: toastXml(title, item.title ? deck.name : null, body, affirm ? { label: affirm, deliveryId } : null),
  })
  current.on('click', () => showMainWindow('contemplations'))
  current.show()
  log('contemplation', `Delivered '${item.title ?? item.body.slice(0, 40)}' from '${deck.name}'`)
  return true
}

export async function respondToContemplation(deliveryId: string, response: string): Promise<void> {
  await recordResponse(mainDb, deliveryId, response)
  current?.close()
  log('contemplation', `Response '${response}' to delivery ${deliveryId}`)
}

/** Handles brainfence://contemplation/respond?id=…&r=affirmed. */
export async function handleProtocolUrl(url: string): Promise<boolean> {
  try {
    const u = new URL(url)
    if (u.protocol !== `${PROTOCOL}:` || u.hostname !== 'contemplation') return false
    const id = u.searchParams.get('id')
    const r = u.searchParams.get('r')
    if (u.pathname === '/respond' && id && r === AFFIRMED) {
      const deck = await getDeliveryDeck(mainDb, id)
      if (!deck || deck.responseType === 'affirm') await respondToContemplation(id, r)
      return true
    }
  } catch {
    // Not ours.
  }
  return false
}

function escapeXml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&apos;')
}

function toastXml(title: string, attribution: string | null, body: string, action: { label: string; deliveryId: string } | null): string {
  const actions = action
    ? `<actions><action content="${escapeXml(action.label)}" activationType="protocol" arguments="${escapeXml(
        `${PROTOCOL}://contemplation/respond?id=${encodeURIComponent(action.deliveryId)}&r=${AFFIRMED}`,
      )}"/></actions>`
    : ''
  return `<toast activationType="protocol" launch="${PROTOCOL}://open/contemplations" scenario="default">
  <visual><binding template="ToastGeneric">
    <text>${escapeXml(title)}</text>
    <text>${escapeXml(body)}</text>
    ${attribution ? `<text placement="attribution">${escapeXml(attribution)}</text>` : ''}
  </binding></visual>
  ${actions}
</toast>`
}
