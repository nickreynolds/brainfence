package dev.brainfence.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import dev.brainfence.data.contemplation.ContemplationSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/**
 * Handles the contemplation slot alarm (deliver, then schedule the next slot)
 * and notification action buttons (record the response).
 */
@AndroidEntryPoint
class ContemplationReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: ContemplationScheduler
    @Inject lateinit var notifier: ContemplationNotifier
    @Inject lateinit var settingsRepository: ContemplationSettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ContemplationScheduler.ACTION_DELIVER -> onSlot(
                intent.getLongExtra(ContemplationScheduler.EXTRA_SLOT_MILLIS, 0L),
            )
            ContemplationNotifier.ACTION_RESPOND -> {
                val deliveryId = intent.getStringExtra(ContemplationNotifier.EXTRA_DELIVERY_ID) ?: return
                val response = intent.getStringExtra(ContemplationNotifier.EXTRA_RESPONSE) ?: return
                runAsync { notifier.respond(deliveryId, response) }
            }
        }
    }

    private fun onSlot(slotMillis: Long) {
        // Schedule the next slot first so the chain survives a failed delivery.
        // Anchor on the later of now and this slot so the same slot can't be
        // rescheduled when the alarm fires a moment early.
        val anchor = maxOf(System.currentTimeMillis(), slotMillis)
        scheduler.reschedule(Instant.ofEpochMilli(anchor).atZone(ZoneId.systemDefault()))

        if (!settingsRepository.get().enabled) return
        if (slotMillis <= settingsRepository.lastDeliveredSlotMillis) {
            Log.d(TAG, "Slot $slotMillis already delivered — skipping")
            return
        }
        settingsRepository.lastDeliveredSlotMillis = slotMillis
        runAsync { notifier.deliver() }
    }

    private fun runAsync(block: suspend () -> Unit) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "Contemplation work failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ContemplationReceiver"
    }
}
