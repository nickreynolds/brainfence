package dev.brainfence.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.brainfence.data.contemplation.ContemplationSettingsRepository
import dev.brainfence.data.debug.DebugLogRepository
import dev.brainfence.domain.contemplation.nextContemplationSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps exactly one alarm pending for the next contemplation slot. The day's
 * slots are derived deterministically (see `contemplationSlotsForDay`), so
 * rescheduling is stateless and safe to call from anywhere: app start (which
 * also covers boot and app updates), settings changes, and each delivery.
 */
@Singleton
class ContemplationScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: ContemplationSettingsRepository,
    private val debugLog: DebugLogRepository,
) {
    companion object {
        const val ACTION_DELIVER = "dev.brainfence.ACTION_CONTEMPLATION_DELIVER"
        const val EXTRA_SLOT_MILLIS = "slot_millis"
        private const val TAG = "ContemplationScheduler"
        private const val REQUEST_CODE = 7302
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Schedule the first slot strictly after [after], or cancel if disabled. */
    fun reschedule(after: ZonedDateTime = ZonedDateTime.now()) {
        val settings = settingsRepository.get()
        val next = if (settings.enabled) {
            nextContemplationSlot(after, settings.scheduleConfig, settingsRepository.scheduleSeed())
        } else {
            null
        }
        val am = alarmManager() ?: return
        if (next == null) {
            am.cancel(pendingIntent(0L))
            Log.d(TAG, "No contemplation scheduled (enabled=${settings.enabled})")
            scope.launch {
                debugLog.log("contemplation", "No contemplation scheduled (enabled=${settings.enabled})")
            }
            return
        }
        val slotMillis = next.toInstant().toEpochMilli()
        am.setWhileIdleCompat(slotMillis, pendingIntent(slotMillis))
        Log.d(TAG, "Next contemplation at $next")
        scope.launch {
            debugLog.log(
                "contemplation",
                "Next contemplation scheduled for ${next.toLocalDateTime()}",
                data = """{"exact":${am.canScheduleExactAlarmsCompat()}}""",
            )
        }
    }

    private fun pendingIntent(slotMillis: Long): PendingIntent {
        val intent = Intent(context, ContemplationReceiver::class.java).apply {
            action = ACTION_DELIVER
            putExtra(EXTRA_SLOT_MILLIS, slotMillis)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun alarmManager(): AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
}
