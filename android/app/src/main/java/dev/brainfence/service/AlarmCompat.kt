package dev.brainfence.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.os.Build

/** Whether exact alarms are allowed (always true before Android 12). */
internal fun AlarmManager.canScheduleExactAlarmsCompat(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || canScheduleExactAlarms()

/**
 * Schedule a Doze-piercing wakeup alarm: exact when the app may schedule exact
 * alarms, otherwise inexact (still fires through Doze, with looser timing).
 */
internal fun AlarmManager.setWhileIdleCompat(triggerAtMillis: Long, operation: PendingIntent) {
    try {
        if (!canScheduleExactAlarmsCompat()) {
            setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        } else {
            setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        }
    } catch (e: SecurityException) {
        // Permission revoked between the check and the call — degrade gracefully.
        setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
    }
}
