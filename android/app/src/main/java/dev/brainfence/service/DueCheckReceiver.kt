package dev.brainfence.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives the Doze-piercing alarm scheduled by [BlockingAlarmScheduler] and
 * pokes [BrainfenceService] to run a due-check (refresh GPS/verification, then
 * re-evaluate blocking + notifications).
 *
 * Starting the foreground service from the background is permitted here because
 * this fires from an `allow-while-idle` alarm — an explicit FGS-launch exemption.
 */
class DueCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BlockingAlarmScheduler.ACTION_DUE_CHECK) {
            Log.i("DueCheckReceiver", "Due-check alarm fired")
            BrainfenceService.requestDueCheck(context)
        }
    }
}
