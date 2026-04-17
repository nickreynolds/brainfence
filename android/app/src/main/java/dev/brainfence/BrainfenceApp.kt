package dev.brainfence

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp
import dev.brainfence.data.db.SyncManager
import dev.brainfence.service.BrainfenceService
import javax.inject.Inject

@HiltAndroidApp
class BrainfenceApp : Application() {

    @Inject lateinit var syncManager: SyncManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        syncManager.initialize()
        BrainfenceService.start(this)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            BrainfenceService.NOTIFICATION_CHANNEL_ID,
            getString(R.string.service_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.service_notification_text)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}
