// Port of service/TaskNotificationManager.kt: "task ready" when available_from
// is reached and "blocking soon" in the hour before due_at. While apps are
// blocked, the tray icon and tooltip play the role of Android's ongoing
// "Apps are blocked" notification.

import { Notification } from 'electron'
import type { Task } from '@shared/models'
import { parseTaskTime, secondsOfDay, todayKey } from '@shared/time'
import { resourcePath, showMainWindow } from './windows'

const sentToday = new Set<string>()
let lastResetDate: string | null = null

export function notify(title: string, body: string, onClick?: () => void): void {
  if (!Notification.isSupported()) return
  const n = new Notification({ title, body, icon: resourcePath('icon.png') })
  n.on('click', onClick ?? (() => showMainWindow()))
  n.show()
}

/**
 * @param enforcedTaskIds tasks that are a condition of an active blocking rule —
 *   the only ones a "blocking soon" warning is meaningful for.
 */
export function evaluateTaskNotifications(tasks: Task[], enforcedTaskIds: Set<string>, now = new Date()): void {
  const today = todayKey(now)
  if (lastResetDate !== today) {
    sentToday.clear()
    lastResetDate = today
  }
  const t = secondsOfDay(now)

  // Task ready: grouped by available_from, within 2 minutes of it.
  const byStart = new Map<string, Task[]>()
  for (const task of tasks) {
    if (task.completedToday || !task.availableFrom || task.taskType === 'shopping') continue
    byStart.set(task.availableFrom, [...(byStart.get(task.availableFrom) ?? []), task])
  }
  for (const [availableFrom, group] of byStart) {
    const key = `ready:${today}:${availableFrom}`
    const start = parseTaskTime(availableFrom)
    if (sentToday.has(key) || start == null) continue
    if (t >= start && t < start + 120) {
      sentToday.add(key)
      notify(
        group.length === 1 ? `${group[0].title} is ready` : `${group.length} tasks are ready`,
        group.length === 1 ? `Available since ${availableFrom}` : group.map((g) => g.title).join(', '),
      )
    }
  }

  // Blocking soon: once, anytime in the hour before due_at.
  const warn: Task[] = []
  for (const task of tasks) {
    if (task.completedToday || !task.dueAt || !enforcedTaskIds.has(task.id)) continue
    const key = `blocking:${today}:${task.id}`
    const due = parseTaskTime(task.dueAt)
    if (sentToday.has(key) || due == null) continue
    if (t >= due - 3600 && t < due) {
      sentToday.add(key)
      warn.push(task)
    }
  }
  if (warn.length > 0) {
    notify(
      warn.length === 1 ? `${warn[0].title} will block in 1 hour` : `${warn.length} tasks will block in 1 hour`,
      warn.length === 1 ? `Due at ${warn[0].dueAt} — complete it to avoid blocking` : warn.map((w) => `${w.title} (${w.dueAt})`).join(', '),
    )
  }
}
