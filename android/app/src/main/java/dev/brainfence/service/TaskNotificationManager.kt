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
    }

    // Track which notifications we've already sent today to avoid repeats.
    // Key: "ready:<date>:<availableFrom>" or "blocking:<date>:<taskId>"
    private val sentToday = mutableSetOf<String>()
    private var lastResetDate: LocalDate? = null

    /**
     * Called periodically from the service evaluation loop.
     * Checks all active tasks and fires notifications as needed.
     */
    fun evaluate(tasks: List<Task>) {
        val timeZone = ZoneId.systemDefault()
        val now = LocalTime.now(timeZone)
        val today = LocalDate.now(timeZone)

        // Reset sent tracking on day change
        if (lastResetDate != today) {
            sentToday.clear()
            lastResetDate = today
        }

        checkTasksReady(tasks, now, today)
        checkBlockingSoon(tasks, now, today)
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

            val startTime = try {
                LocalTime.parse(availableFrom)
            } catch (e: Exception) {
                continue
            }

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
    private fun checkBlockingSoon(tasks: List<Task>, now: LocalTime, today: LocalDate) {
        val tasksToWarn = mutableListOf<Task>()

        for (task in tasks) {
            if (task.completedToday) continue
            if (task.dueAt == null) continue
            if (!task.isBlockingCondition) continue

            val key = "blocking:$today:${task.id}"
            if (key in sentToday) continue

            val dueTime = try {
                LocalTime.parse(task.dueAt)
            } catch (e: Exception) {
                continue
            }

            val warningTime = dueTime.minusHours(1)
            // Fire when within the warning window (warningTime <= now < warningTime + 2min)
            if (now >= warningTime && now < warningTime.plusMinutes(2)) {
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

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationManager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
