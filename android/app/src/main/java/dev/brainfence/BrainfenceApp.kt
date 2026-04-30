package dev.brainfence

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import dev.brainfence.data.db.SyncManager
import dev.brainfence.service.BrainfenceService
import dev.brainfence.service.TaskNotificationManager
import javax.inject.Inject

@HiltAndroidApp
class BrainfenceApp : Application() {

    @Inject lateinit var syncManager: SyncManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        syncManager.initialize()
        try {
            BrainfenceService.start(this)
        } catch (e: IllegalStateException) {
            // On Android 12+, startForegroundService() throws
            // ForegroundServiceStartNotAllowedException when the app process
            // is restarted in the background (e.g. via START_STICKY).
            // The service will be started when the user next opens the app
            // or via BootReceiver.
            Log.w("BrainfenceApp", "Cannot start foreground service from background", e)
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)

        val serviceChannel = NotificationChannel(
            BrainfenceService.NOTIFICATION_CHANNEL_ID,
            getString(R.string.service_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.service_notification_text)
            setShowBadge(false)
        }
        nm.createNotificationChannel(serviceChannel)

        val alertsChannel = NotificationChannel(
            TaskNotificationManager.CHANNEL_ID,
            getString(R.string.task_alerts_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.task_alerts_channel_description)
        }
        nm.createNotificationChannel(alertsChannel)
    }
}
