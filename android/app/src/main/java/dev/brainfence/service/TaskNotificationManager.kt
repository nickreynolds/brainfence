package dev.brainfence.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.brainfence.MainActivity
import dev.brainfence.R
import dev.brainfence.domain.blocking.BlockingState
import dev.brainfence.domain.model.Task
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages task-related notifications:
 * - "Task ready" when available_from is reached
 * - "Blocking soon" 1 hour before due_at
 */
@Singleton
class TaskNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val CHANNEL_ID = "brainfence_task_alerts"
        private const val TAG = "TaskNotificationMgr"
        private const val NOTIF_ID_TASK_READY = 100
        private const val NOTIF_ID_BLOCKING_SOON = 200
        private const val NOTIF_ID_BLOCKING_ACTIVE = 300
        private const val NOTIF_ID_SHOPPING_BASE = 400
    }

    // Track which notifications we've already sent today to avoid repeats.
    // Key: "ready:<date>:<availableFrom>" or "blocking:<date>:<taskId>"
    private val sentToday = mutableSetOf<String>()
    private var lastResetDate: LocalDate? = null

    /**
     * Called periodically from the service evaluation loop.
     * Checks all active tasks and fires notifications as needed.
     *
     * @param enforcedTaskIds IDs of tasks that are a condition of an active
     *   blocking rule — i.e. tasks that actually block apps. The "blocking soon"
     *   warning is only meaningful for these; the per-task `is_blocking_condition`
     *   flag is ignored by the blocking engine and so must not drive warnings.
     */
    fun evaluate(tasks: List<Task>, enforcedTaskIds: Set<String>) {
        val timeZone = ZoneId.systemDefault()
        val now = LocalTime.now(timeZone)
        val today = LocalDate.now(timeZone)

        // Reset sent tracking on day change
        if (lastResetDate != today) {
            sentToday.clear()
            lastResetDate = today
        }

        checkTasksReady(tasks, now, today)
        checkBlockingSoon(tasks, enforcedTaskIds, now, today)
    }

    /**
     * When a task's available_from time is reached, notify.
     * Groups tasks with the same available_from into one notification.
     */
    private fun checkTasksReady(tasks: List<Task>, now: LocalTime, today: LocalDate) {
        // Group incomplete tasks by their available_from time
        val byAvailableFrom = tasks
            .filter { !it.completedToday && it.availableFrom != null }
            .groupBy { it.availableFrom!! }

        for ((availableFrom, groupedTasks) in byAvailableFrom) {
            val key = "ready:$today:$availableFrom"
            if (key in sentToday) continue

            val startTime = dev.brainfence.domain.recurrence.parseTaskTime(availableFrom)
                ?: continue

            // Fire when we're at or past available_from (within a 2-minute window to avoid
            // missing it between eval cycles, but not so wide that it re-fires)
            if (now >= startTime && now < startTime.plusMinutes(2)) {
                sentToday.add(key)
                showTaskReadyNotification(groupedTasks, availableFrom)
            }
        }
    }

    /**
     * 1 hour before due_at, notify for each incomplete task that will start blocking.
     */
    private fun checkBlockingSoon(
        tasks: List<Task>,
        enforcedTaskIds: Set<String>,
        now: LocalTime,
        today: LocalDate,
    ) {
        val tasksToWarn = mutableListOf<Task>()

        for (task in tasks) {
            if (task.completedToday) continue
            if (task.dueAt == null) continue
            // Only warn for tasks that actually block apps (wired into an active
            // rule). A task with is_blocking_condition set but no rule never
            // blocks, so warning about it would be a false alarm.
            if (task.id !in enforcedTaskIds) continue

            val key = "blocking:$today:${task.id}"
            if (key in sentToday) continue

            val dueTime = dev.brainfence.domain.recurrence.parseTaskTime(task.dueAt)
                ?: continue

            val warningTime = dueTime.minusHours(1)
            // Fire once anytime in the hour before due (deduped via sentToday).
            // A fixed short window is fragile under Doze — the eval loop or the
            // due-check alarm may not land inside it — so warn across the whole
            // pre-due hour instead.
            if (now >= warningTime && now < dueTime) {
                sentToday.add(key)
                tasksToWarn.add(task)
            }
        }

        if (tasksToWarn.isNotEmpty()) {
            showBlockingSoonNotification(tasksToWarn)
        }
    }

    private fun showTaskReadyNotification(tasks: List<Task>, availableFrom: String) {
        val title = if (tasks.size == 1) {
            context.getString(R.string.notif_task_ready_title_single, tasks[0].title)
        } else {
            context.getString(R.string.notif_task_ready_title_multiple, tasks.size)
        }

        val body = if (tasks.size == 1) {
            context.getString(R.string.notif_task_ready_body_single, availableFrom)
        } else {
            tasks.joinToString(", ") { it.title }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()

        notificationManager().notify(NOTIF_ID_TASK_READY, notification)
        Log.i(TAG, "Showed task-ready notification for ${tasks.size} task(s) at $availableFrom")
    }

    private fun showBlockingSoonNotification(tasks: List<Task>) {
        val title = if (tasks.size == 1) {
            context.getString(R.string.notif_blocking_soon_title_single, tasks[0].title)
        } else {
            context.getString(R.string.notif_blocking_soon_title_multiple, tasks.size)
        }

        val body = if (tasks.size == 1) {
            context.getString(R.string.notif_blocking_soon_body_single, tasks[0].dueAt ?: "")
        } else {
            tasks.joinToString(", ") { "${it.title} (${it.dueAt})" }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager().notify(NOTIF_ID_BLOCKING_SOON, notification)
        Log.i(TAG, "Showed blocking-soon notification for ${tasks.size} task(s)")
    }

    /**
     * Reminder fired when arriving at a shopping list's geofence with open items.
     * Lists the items inline; tapping opens the app. One notification slot per
     * task so multiple shopping lists don't overwrite each other.
     */
    fun showShoppingReminder(taskId: String, taskTitle: String, items: List<String>) {
        val title = context.resources.getQuantityString(
            R.plurals.notif_shopping_title, items.size, taskTitle, items.size,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(items.joinToString(", "))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(items.joinToString("\n") { "• $it" })
            )
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notifId = NOTIF_ID_SHOPPING_BASE + (taskId.hashCode() and 0x7FFF)
        notificationManager().notify(notifId, notification)
        Log.i(TAG, "Showed shopping reminder for '$taskTitle' (${items.size} item(s))")
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Show a persistent notification while apps are actively blocked.
     * Dismissed automatically when blocking clears.
     */
    fun updateBlockingNotification(state: BlockingState) {
        val nm = notificationManager()
        if (state.blockedApps.isEmpty()) {
            nm.cancel(NOTIF_ID_BLOCKING_ACTIVE)
            return
        }

        val count = state.blockedApps.size
        val title = context.getString(R.string.notif_blocking_active_title)
        val body = context.resources.getQuantityString(
            R.plurals.notif_blocking_active_body, count, count,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify(NOTIF_ID_BLOCKING_ACTIVE, notification)
    }

    private fun notificationManager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
