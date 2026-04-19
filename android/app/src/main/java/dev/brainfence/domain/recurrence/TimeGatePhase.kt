package dev.brainfence.domain.recurrence

import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * The three phases of a time-gated task within a single day.
 */
enum class TimeGatePhase {
    /** Before start_time — task is not yet completable. */
    BEFORE_START,
    /** Between start_time and end_time — task is completable but not yet blocking. */
    ACTIVE,
    /** After end_time — task is still completable; if incomplete, it triggers blocking. */
    PAST_END,
}

/**
 * Computes which phase a time_gate task is currently in.
 * Returns null if the verification config cannot be parsed.
 */
fun computeTimeGatePhase(
    verificationConfig: String,
    currentTime: Instant,
    timeZone: ZoneId = ZoneId.systemDefault(),
): TimeGatePhase {
    val config = JSONObject(verificationConfig)
    val start = LocalTime.parse(config.getString("start_time"))
    val end = LocalTime.parse(config.getString("end_time"))
    val zone = if (config.has("timezone")) ZoneId.of(config.getString("timezone")) else timeZone
    val now = currentTime.atZone(zone).toLocalTime()

    return when {
        now < start -> TimeGatePhase.BEFORE_START
        now < end -> TimeGatePhase.ACTIVE
        else -> TimeGatePhase.PAST_END
    }
}
