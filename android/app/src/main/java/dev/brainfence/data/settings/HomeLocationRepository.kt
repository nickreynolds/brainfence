package dev.brainfence.data.settings

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates the user's designated "home" location — a single lat/lng/radius
 * stored locally on the device. Tasks flagged `home_only_blocking` only
 * contribute to app blocking when the user's current location falls inside
 * this radius. Stored in plain SharedPreferences (no PII beyond coordinates,
 * and we don't sync across devices — home is per-device by design).
 */
data class HomeLocation(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
)

@Singleton
class HomeLocationRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): HomeLocation? {
        if (!prefs.contains(KEY_LAT)) return null
        val lat = prefs.getFloat(KEY_LAT, 0f).toDouble()
        val lng = prefs.getFloat(KEY_LNG, 0f).toDouble()
        val radius = prefs.getInt(KEY_RADIUS, DEFAULT_RADIUS_M)
        return HomeLocation(lat, lng, radius)
    }

    fun set(home: HomeLocation) {
        prefs.edit()
            .putFloat(KEY_LAT, home.latitude.toFloat())
            .putFloat(KEY_LNG, home.longitude.toFloat())
            .putInt(KEY_RADIUS, home.radiusMeters)
            .apply()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_LAT)
            .remove(KEY_LNG)
            .remove(KEY_RADIUS)
            .apply()
    }

    /** Emits current home on subscription, then re-emits when any home pref changes. */
    fun watch(): Flow<HomeLocation?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_LAT || key == KEY_LNG || key == KEY_RADIUS) {
                trySend(get())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.onStart { emit(get()) }

    companion object {
        private const val PREFS_NAME = "brainfence_home_location"
        private const val KEY_LAT = "latitude"
        private const val KEY_LNG = "longitude"
        private const val KEY_RADIUS = "radius_meters"
        const val DEFAULT_RADIUS_M = 100
    }
}
