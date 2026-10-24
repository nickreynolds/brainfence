package dev.brainfence.domain.blocking

import org.json.JSONArray
import org.json.JSONObject
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Rules every client must apply identically to synced blocking rules: app entry
 * identity, domain normalization, which edits skip the time-lock, and timestamp
 * parsing. Semantics: shared/CONTRACTS.md. Cases: shared/fixtures, run by
 * RuleContractsTest here and by the macOS and Windows test suites.
 */
object RuleContracts {

    /** The parts of a rule that decide how much it blocks, across all platforms. */
    data class RuleSnapshot(
        val blockedAppKeys: Set<String>,
        val blockedDomains: List<String>,
        val conditionTaskIds: Set<String>,
        val conditionLogic: String,
        val isActive: Boolean,
        val configLockHours: Int,
    )

    /**
     * `<platform>:<id>` identity of a blocked_apps / companion_apps entry, or
     * null to skip it. Bare strings are legacy Android package names; unknown
     * platforms are identified by their canonical JSON.
     */
    fun appKey(entry: Any?): String? = when (entry) {
        is String -> if (entry.isEmpty()) null else "android:$entry"
        is JSONObject -> {
            val platform = entry.optString("platform", "")
            val id = when (platform) {
                "android" -> entry.optString("package", "")
                "macos" -> entry.optString("bundle_id", "")
                "windows" -> entry.optString("exe", "").lowercase()
                else -> canonicalJson(entry)
            }
            "$platform:$id"
        }
        else -> null
    }

    /** Identities of every entry in a blocked_apps value (a JSONArray or JSON text). */
    fun appKeys(value: Any?): Set<String> {
        val array = jsonArray(value)
        return (0 until array.length()).mapNotNull { appKey(array.opt(it)) }.toSet()
    }

    /** "https://www.Reddit.com/r/all" → "reddit.com"; null when nothing usable is left. */
    fun normalizeDomain(raw: String): String? {
        var d = raw.trim().lowercase()
        val scheme = d.indexOf("://")
        if (scheme >= 0) d = d.substring(scheme + 3)
        d = d.split('/', '?', '#').first()
        d = d.substringAfterLast('@')
        d = d.substringBeforeLast(':')
        for (prefix in listOf("*.", "www.")) d = d.removePrefix(prefix)
        d = d.trimEnd('.')
        return d.takeIf { '.' in it }
    }

    /** True if [host] is [domain] (normalized) or one of its subdomains. */
    fun hostMatches(host: String, domain: String): Boolean {
        val h = host.lowercase()
        return h == domain || h.endsWith(".$domain")
    }

    /**
     * True if [changes] (the pending_changes shape) can only make [current]
     * block more, never less — so it may skip the time-lock.
     */
    fun isMoreRestrictive(current: RuleSnapshot, changes: JSONObject): Boolean {
        if (changes.has("blocked_apps")) {
            if (!appKeys(changes.get("blocked_apps")).containsAll(current.blockedAppKeys)) return false
        }
        if (changes.has("blocked_domains")) {
            val next = strings(changes.get("blocked_domains")).mapNotNull(::normalizeDomain).toSet()
            if (!next.containsAll(current.blockedDomains.mapNotNull(::normalizeDomain))) return false
        }
        val nextLogic = if (changes.has("condition_logic")) changes.getString("condition_logic") else current.conditionLogic
        if (current.conditionLogic == "all" && nextLogic == "any") return false
        if (changes.has("condition_task_ids")) {
            val next = strings(changes.get("condition_task_ids")).toSet()
            val now = current.conditionTaskIds
            if (next != now) {
                // No conditions means the rule always blocks; giving it any loosens it.
                if (now.isEmpty()) return false
                if (nextLogic == "any") {
                    // Each "any" task is a way to unblock, so only removing them is
                    // stricter. An empty set makes the rule always block.
                    if (next.isNotEmpty() && !now.containsAll(next)) return false
                } else if (!next.containsAll(now)) {
                    return false
                }
            }
        }
        if (changes.has("is_active")) {
            if (current.isActive && !truthy(changes.get("is_active"))) return false
        }
        if (changes.has("config_lock_hours")) {
            if (changes.getInt("config_lock_hours") < current.configLockHours) return false
        }
        return true
    }

    private val TIMESTAMP = Regex("""^(\d{4}-\d{2}-\d{2})[Tt ](\d{2}:\d{2}(?::\d{2})?(?:\.\d+)?)([Zz]|[+-]\d{2}(?::?\d{2})?)?$""")

    /**
     * Parses timestamp text from a synced row: ISO-8601 written by a client, or
     * Postgres text ("2026-04-16 14:00:00.123+00") replicated from the server.
     * No offset means UTC. Compare the results, never the strings.
     */
    fun parseTimestamp(text: String?): Instant? {
        val match = TIMESTAMP.matchEntire(text?.trim() ?: return null) ?: return null
        val (date, time, zone) = match.destructured
        return try {
            val offset = when {
                zone.isEmpty() || zone.equals("z", ignoreCase = true) -> ZoneOffset.UTC
                zone.length == 3 -> ZoneOffset.of(zone)
                else -> ZoneOffset.of(zone.take(3) + ":" + zone.takeLast(2))
            }
            LocalDateTime.parse("${date}T$time").toInstant(offset)
        } catch (_: DateTimeException) {
            null
        }
    }

    /** JSON with object keys sorted and no whitespace: a stable identity for an entry. */
    fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted()
            .joinToString(",", "{", "}") { "${quote(it)}:${canonicalJson(value.opt(it))}" }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.opt(it)) }
        is String -> quote(value)
        else -> value.toString()
    }

    private fun quote(s: String) = JSONObject.quote(s).replace("\\/", "/")

    private fun jsonArray(value: Any?): JSONArray = when (value) {
        is JSONArray -> value
        is String -> try { JSONArray(value) } catch (_: Exception) { JSONArray() }
        else -> JSONArray()
    }

    private fun strings(value: Any?): List<String> {
        val array = jsonArray(value)
        return (0 until array.length()).map { array.get(it).toString() }
    }

    private fun truthy(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> value.toString().let { it == "1" || it.equals("true", ignoreCase = true) }
    }
}
