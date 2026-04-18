package dev.brainfence.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import dev.brainfence.data.debug.DebugLogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    @Inject lateinit var gpsVerificationManager: GpsVerificationManager
    @Inject lateinit var debugLog: DebugLogRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GpsVerificationManager.ACTION_GEOFENCE_EVENT) return
        Log.d("GeofenceReceiver", "Received geofence event")
        CoroutineScope(Dispatchers.IO).launch {
            debugLog.log("geofence", "BroadcastReceiver received geofence event")
        }
        gpsVerificationManager.handleGeofenceEvent(intent)
    }
}
