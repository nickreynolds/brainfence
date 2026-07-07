package dev.brainfence.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.brainfence.domain.model.Task
import dev.brainfence.domain.recurrence.parseTaskTime
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules a Doze-piercing alarm at the next moment a blocking task needs a
 * fresh check:
 *  - ~1 hour before due, so the "blocking soon" warning can fire; and
 *  - shortly before due, so a fresh GPS/verification refresh can register a
 *    completed-but-unsynced task before it wrongly blocks apps.
 *
 * Android freezes location delivery in Doze, so the foreground service's poll
 * loop can evaluate against a stale "incomplete" state (e.g. a morning walk
 * whose geofence EXIT hasn't flushed). An `allow-while-idle` alarm wakes the app
 * through Doze so [BrainfenceService] can force a fresh location fix and then
 * re-evaluate.
 *
 * Only the single next relevant instant is scheduled; it is rescheduled whenever
 * task/rule data changes (e.g. a completion) or an alarm fires.
 */
@Singleton
class BlockingAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val ACTION_DUE_CHECK = "dev.brainfence.ACTION_DUE_CHECK"
        private const val TAG = "BlockingAlarmScheduler"
        private const val REQUEST_CODE = 7301
        private const val WARN_LEAD_SECONDS = 3600L    // 1 hour before due
        private const val ENFORCE_LEAD_SECONDS = 120L  // 2 minutes before due
    }

    /**
     * Recompute and (re)schedule the next due-check alarm from the current
     * tasks. Cancels any existing alarm when nothing is upcoming.
     */
    fun reschedule(
        tasks: List<Task>,
        enforcedTaskIds: Set<String>,
        timeZone: ZoneId = ZoneId.systemDefault(),
    ) {
        val now = Instant.now()
        val today = now.atZone(timeZone).toLocalDate()
        val candidates = mutableListOf<Instant>()

        // Look at today and tomorrow. Rolling to tomorrow keeps a pending alarm
        // even after everything today is done, so the chain never goes cold
        // overnight (when the poll loop is frozen by Doze) and the morning's
        // warn alarm is already set. `completedToday` only applies to today's
        // occurrence — tomorrow's is fresh.
        for (dayOffset in 0L..1L) {
            val date = today.plusDays(dayOffset)
            for (task in tasks) {
                if (task.id !in enforcedTaskIds) continue
                if (dayOffset == 0L && task.completedToday) continue
                val due = parseTaskTime(task.dueAt) ?: continue
                val dueInstant = date.atTime(due).atZone(timeZone).toInstant()
                val warnInstant = dueInstant.minusSeconds(WARN_LEAD_SECONDS)
                val enforceInstant = dueInstant.minusSeconds(ENFORCE_LEAD_SECONDS)
                if (warnInstant.isAfter(now)) candidates.add(warnInstant)
                if (enforceInstant.isAfter(now)) candidates.add(enforceInstant)
            }
        }

        val next = candidates.minOrNull()
        if (next == null) {
            cancel()
            return
        }
        scheduleAt(next.toEpochMilli())
    }

    fun cancel() {
        alarmManager()?.cancel(pendingIntent())
    }

    private fun scheduleAt(triggerAtMillis: Long) {
        val am = alarmManager() ?: return
        val pi = pendingIntent()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                // Exact-alarm permission not granted — inexact still pierces Doze,
                // just with looser timing, which is fine for a ~minute-scale check.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call — degrade gracefully.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
        Log.d(TAG, "Scheduled due-check alarm at $triggerAtMillis")
    }

    private fun alarmManager(): AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, DueCheckReceiver::class.java).apply {
            action = ACTION_DUE_CHECK
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
