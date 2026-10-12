package dev.brainfence.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.brainfence.MainActivity
import dev.brainfence.R
import dev.brainfence.data.auth.SessionRepository
import dev.brainfence.data.contemplation.ContemplationChoice
import dev.brainfence.data.contemplation.ContemplationRepository
import dev.brainfence.data.debug.DebugLogRepository
import dev.brainfence.domain.model.ContemplationResponseType
import dev.brainfence.domain.model.ContemplationResponses
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks, logs and shows a contemplation notification, and records responses
 * to it. Response kinds are driven by the deck's [ContemplationResponseType];
 * [addResponseActions] is where a new kind gets its notification UI.
 */
@Singleton
class ContemplationNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ContemplationRepository,
    private val sessionRepository: SessionRepository,
    private val debugLog: DebugLogRepository,
) {
    companion object {
        const val CHANNEL_ID = "brainfence_contemplations"
        const val ACTION_RESPOND = "dev.brainfence.ACTION_CONTEMPLATION_RESPOND"
        const val EXTRA_DELIVERY_ID = "delivery_id"
        const val EXTRA_RESPONSE = "response"
        private const val TAG = "ContemplationNotifier"
        /** One slot: a new contemplation replaces an unread one rather than piling up. */
        private const val NOTIF_ID = 500
        private const val DEFAULT_AFFIRM_LABEL = "I remember"
    }

    /**
     * Deliver one contemplation now. Returns false (and logs why) when nothing
     * could be shown — signed out, notifications off, or no deliverable deck.
     */
    suspend fun deliver(): Boolean {
        if (sessionRepository.userIdForLocalWrites() == null) {
            debugLog.log("contemplation", "Skipped delivery: no one is signed in")
            return false
        }
        // Checked before logging so the history never claims a delivery the
        // user couldn't have seen.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            debugLog.log("contemplation", "Skipped delivery: notifications disabled")
            return false
        }
        val choice = repository.pickNext() ?: run {
            debugLog.log("contemplation", "Skipped delivery: no enabled deck with items")
            return false
        }
        val deliveryId = repository.recordDelivery(choice)
        show(deliveryId, choice)
        debugLog.log(
            "contemplation",
            "Delivered '${choice.item.title ?: choice.item.body.take(40)}' from '${choice.deck.name}'",
        )
        return true
    }

    suspend fun respond(deliveryId: String, response: String) {
        repository.recordResponse(deliveryId, response)
        notificationManager().cancel(NOTIF_ID)
        debugLog.log("contemplation", "Response '$response' to delivery $deliveryId")
    }

    private fun show(deliveryId: String, choice: ContemplationChoice) {
        val item = choice.item
        val bigText = if (item.attribution.isNullOrBlank()) item.body
                      else "${item.body}\n\n— ${item.attribution}"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(item.title ?: choice.deck.name)
            .setContentText(item.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        if (item.title != null) builder.setSubText(choice.deck.name)
        addResponseActions(builder, deliveryId, choice)
        notificationManager().notify(NOTIF_ID, builder.build())
        Log.i(TAG, "Showed contemplation $deliveryId from '${choice.deck.name}'")
    }

    private fun addResponseActions(
        builder: NotificationCompat.Builder,
        deliveryId: String,
        choice: ContemplationChoice,
    ) {
        when (choice.deck.responseType) {
            ContemplationResponseType.NONE -> Unit
            ContemplationResponseType.AFFIRM -> builder.addAction(
                0,
                choice.deck.responseLabel?.takeIf { it.isNotBlank() } ?: DEFAULT_AFFIRM_LABEL,
                responseIntent(deliveryId, ContemplationResponses.AFFIRMED),
            )
        }
    }

    private fun responseIntent(deliveryId: String, response: String): PendingIntent {
        val intent = Intent(context, ContemplationReceiver::class.java).apply {
            action = ACTION_RESPOND
            putExtra(EXTRA_DELIVERY_ID, deliveryId)
            putExtra(EXTRA_RESPONSE, response)
        }
        return PendingIntent.getBroadcast(
            context,
            deliveryId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notificationManager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
