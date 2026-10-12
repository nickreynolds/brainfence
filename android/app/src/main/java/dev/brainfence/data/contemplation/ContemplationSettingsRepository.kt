package dev.brainfence.data.contemplation

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.brainfence.domain.contemplation.ContemplationScheduleConfig
import dev.brainfence.domain.recurrence.parseTaskTime
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onStart
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

data class ContemplationSettings(
    val enabled: Boolean,
    val perDay: Int,
    val windowStart: LocalTime,
    val windowEnd: LocalTime,
) {
    val scheduleConfig: ContemplationScheduleConfig
        get() = ContemplationScheduleConfig(perDay, windowStart, windowEnd)
}

/**
 * When and how often contemplations are delivered. Device-local like the home
 * location: notifications are scheduled per device, while the content itself
 * (decks, items, delivery log) syncs via PowerSync.
 */
@Singleton
class ContemplationSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): ContemplationSettings = ContemplationSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        perDay = prefs.getInt(KEY_PER_DAY, DEFAULT_PER_DAY),
        windowStart = parseTaskTime(prefs.getString(KEY_WINDOW_START, null)) ?: DEFAULT_WINDOW_START,
        windowEnd = parseTaskTime(prefs.getString(KEY_WINDOW_END, null)) ?: DEFAULT_WINDOW_END,
    )

    fun set(settings: ContemplationSettings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putInt(KEY_PER_DAY, settings.perDay)
            .putString(KEY_WINDOW_START, settings.windowStart.format(TIME_FORMAT))
            .putString(KEY_WINDOW_END, settings.windowEnd.format(TIME_FORMAT))
            .apply()
    }

    /** Emits current settings on subscription, then on any change. */
    fun watch(): Flow<ContemplationSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in SETTINGS_KEYS) trySend(get())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.onStart { emit(get()) }

    /** Per-install seed for the day's random delivery times; created on first use. */
    fun scheduleSeed(): Long {
        if (prefs.contains(KEY_SEED)) return prefs.getLong(KEY_SEED, 0L)
        val seed = Random.nextLong()
        prefs.edit().putLong(KEY_SEED, seed).apply()
        return seed
    }

    /** Epoch millis of the last slot delivered, so a re-fired alarm can't double up. */
    var lastDeliveredSlotMillis: Long
        get() = prefs.getLong(KEY_LAST_SLOT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SLOT, value).apply()

    companion object {
        private const val PREFS_NAME = "brainfence_contemplations"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PER_DAY = "per_day"
        private const val KEY_WINDOW_START = "window_start"
        private const val KEY_WINDOW_END = "window_end"
        private const val KEY_SEED = "schedule_seed"
        private const val KEY_LAST_SLOT = "last_delivered_slot_millis"
        private val SETTINGS_KEYS = setOf(KEY_ENABLED, KEY_PER_DAY, KEY_WINDOW_START, KEY_WINDOW_END)
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        const val DEFAULT_PER_DAY = 5
        val DEFAULT_WINDOW_START: LocalTime = LocalTime.of(8, 0)
        val DEFAULT_WINDOW_END: LocalTime = LocalTime.of(21, 0)
    }
}
