package dev.brainfence.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    @Inject lateinit var gpsVerificationManager: GpsVerificationManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GpsVerificationManager.ACTION_GEOFENCE_EVENT) return
        Log.d("GeofenceReceiver", "Received geofence event")
        gpsVerificationManager.handleGeofenceEvent(intent)
    }
}
