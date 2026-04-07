package dev.brainfence

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.brainfence.data.db.SyncManager
import javax.inject.Inject

@HiltAndroidApp
class BrainfenceApp : Application() {

    @Inject lateinit var syncManager: SyncManager

    override fun onCreate() {
        super.onCreate()
        syncManager.initialize()
    }
}
