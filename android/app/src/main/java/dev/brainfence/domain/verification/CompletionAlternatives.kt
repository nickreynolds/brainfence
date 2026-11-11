package dev.brainfence.domain.verification

import org.json.JSONArray
import org.json.JSONObject

/**
 * A GPS way to complete a task, from `tasks.completion_alternatives`
 * (shared/CONTRACTS.md: Completion alternatives).
 */
data class GpsAlternative(
    /** Position in the completion_alternatives array. */
    val index: Int,
    val label: String?,
    val lat: Double,
    val lng: Double,
    val radiusM: Double,
    /** "enter" or "leave". */
    val mode: String,
    val minDurationM: Int,
) {
    /** "Climbing gym", or "the location" without a label. */
    val place: String get() = label ?: "the location"

    /** A short "or …" phrase, e.g. "be at Climbing gym for 30 min". */
    fun describe(): String = when {
        mode == "leave" -> "leave $place"
        minDurationM > 0 -> "be at $place for $minDurationM min"
        else -> "arrive at $place"
    }
}

object CompletionAlternatives {
    /**
     * The supported GPS entries of a task's completion_alternatives: mode enter
     * (default) or leave, with a valid location. Everything else is skipped
     * (`shared/fixtures/completion-alternatives.json`).
     */
    fun parseGps(json: String?): List<GpsAlternative> {
        val array = try {
            JSONArray(json ?: return emptyList())
        } catch (_: Exception) {
            return emptyList()
        }
        return (0 until array.length()).mapNotNull { index ->
            val entry = array.opt(index) as? JSONObject ?: return@mapNotNull null
            if (entry.optString("verification_type") != "gps") return@mapNotNull null
            val config = entry.optJSONObject("verification_config") ?: JSONObject()
            val lat = config.number("lat", "latitude") ?: return@mapNotNull null
            val lng = config.number("lng", "longitude") ?: return@mapNotNull null
            val mode = config.optString("mode", "enter").ifEmpty { "enter" }
            if (mode != "enter" && mode != "leave") return@mapNotNull null
            GpsAlternative(
                index = index,
                label = entry.optString("label").takeIf { entry.has("label") && !entry.isNull("label") && it.isNotEmpty() },
                lat = lat,
                lng = lng,
                radiusM = config.number("radius_m", "radius_meters")?.takeIf { it > 0 } ?: 100.0,
                mode = mode,
                minDurationM = config.number("min_duration_m")?.toInt() ?: 0,
            )
        }
    }

    /** First present key that holds a finite number (numbers or numeric strings). */
    private fun JSONObject.number(vararg keys: String): Double? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val value = when (val raw = opt(key)) {
                is Number -> raw.toDouble()
                is String -> raw.toDoubleOrNull()
                else -> null
            }
            return value?.takeIf { it.isFinite() }
        }
        return null
    }
}

/**
 * A timed_sets step's config (shared/CONTRACTS.md: Timed sets,
 * `shared/fixtures/timed-sets.json`).
 */
data class TimedSetsConfig(
    /** Hangs per side per set. */
    val reps: Int,
    val workSeconds: Int,
    /** Rest between hangs. */
    val restSeconds: Int,
    val sets: Int,
    /** 1, or 2 for one arm at a time (left, then right). */
    val sides: Int,
    /** Rest between the left and right halves of a set. */
    val sideRestSeconds: Int,
    /** Rest after each set, including before the next step. */
    val setRestSeconds: Int,
    /** "added" (negative = assisted), "load", or null for no weight. */
    val weight: String?,
    /** "start" (once, before the routine) or "each_set" (before each set). */
    val weightPrompt: String,
) {
    val hangsPerSet: Int get() = reps * sides

    /** Number of hangs (entries) in the step. */
    val hangCount: Int get() = sets * hangsPerSet

    /** 0-based set a hang belongs to. */
    fun setOf(hangIndex: Int): Int = hangIndex / hangsPerSet

    /** 0-based side a hang is on (0 = left). */
    fun sideOf(hangIndex: Int): Int = (hangIndex / reps) % sides

    fun startsSet(hangIndex: Int): Boolean = hangIndex % hangsPerSet == 0

    /** Whether the runner asks for this set's weight right before it starts. */
    fun promptsBefore(hangIndex: Int): Boolean = weight != null && weightPrompt == "each_set" && startsSet(hangIndex)

    /** Rest after hang [hangIndex]; 0 after the routine's final hang. */
    fun restAfter(hangIndex: Int, isLastStep: Boolean): Int = when {
        isLastStep && hangIndex == hangCount - 1 -> 0
        (hangIndex + 1) % hangsPerSet == 0 -> setRestSeconds
        (hangIndex + 1) % reps == 0 -> sideRestSeconds
        else -> restSeconds
    }

    companion object {
        fun parse(json: String?): TimedSetsConfig {
            val c = try { JSONObject(json ?: "{}") } catch (_: Exception) { JSONObject() }
            fun int(key: String, fallback: Int, min: Int): Int {
                if (!c.has(key) || c.isNull(key)) return fallback
                val n = c.optDouble(key, Double.NaN)
                return if (n.isFinite() && n.toInt() >= min) n.toInt() else fallback
            }
            val rest = int("rest_seconds", 20, 0)
            return TimedSetsConfig(
                reps = int("reps", 1, 1),
                workSeconds = int("work_seconds", 10, 1),
                restSeconds = rest,
                sets = int("sets", 1, 1),
                sides = if (c.optInt("sides", 1) == 2) 2 else 1,
                sideRestSeconds = int("side_rest_seconds", rest, 0),
                setRestSeconds = int("set_rest_seconds", rest, 0),
                weight = c.optString("weight").takeIf { it == "added" || it == "load" },
                weightPrompt = if (c.optString("weight_prompt") == "each_set") "each_set" else "start",
            )
        }

        /** "+10 lb", "10 lb assisted", "Bodyweight" (added) or "25 lb" (load). */
        fun formatWeight(kind: String?, weight: Double?): String {
            fun text(w: Double) = if (w == Math.floor(w)) w.toLong().toString() else "%.1f".format(w)
            if (kind == "load") return weight?.let { "${text(it)} lb" } ?: "No weight set"
            return when {
                weight == null || weight == 0.0 -> "Bodyweight"
                weight > 0 -> "+${text(weight)} lb"
                else -> "${text(-weight)} lb assisted"
            }
        }
    }
}
