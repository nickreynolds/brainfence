package dev.brainfence.domain.blocking

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Runs the cross-platform cases in shared/fixtures, which the macOS and Windows
 * test suites load too. See shared/CONTRACTS.md.
 */
class RuleContractsTest {

    private fun fixture(name: String): JSONObject {
        val dir = System.getProperty("brainfence.fixtures") ?: "../../shared/fixtures"
        return JSONObject(File(dir, name).readText())
    }

    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    @Test
    fun ruleRestrictiveness() {
        val fixture = fixture("rule-restrictiveness.json")
        val base = fixture.getJSONObject("base_rule")
        val cases = fixture.getJSONArray("cases").objects()
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val row = JSONObject(base.toString())
            case.optJSONObject("rule")?.let { overrides -> overrides.keys().forEach { row.put(it, overrides.get(it)) } }
            val rule = RuleContracts.RuleSnapshot(
                blockedAppKeys   = RuleContracts.appKeys(row.getJSONArray("blocked_apps")),
                blockedDomains   = row.getJSONArray("blocked_domains").strings(),
                conditionTaskIds = row.getJSONArray("condition_task_ids").strings().toSet(),
                conditionLogic   = row.getString("condition_logic"),
                isActive         = row.getInt("is_active") != 0,
                configLockHours  = row.getInt("config_lock_hours"),
            )
            assertEquals(
                case.getString("name"),
                case.getBoolean("more_restrictive"),
                RuleContracts.isMoreRestrictive(rule, case.getJSONObject("changes")),
            )
        }
    }

    @Test
    fun blockedAppKeys() {
        for (case in fixture("blocked-app-keys.json").getJSONArray("cases").objects()) {
            val entry = case.get("entry")
            assertEquals(entry.toString(), case.nullableString("key"), RuleContracts.appKey(entry))
        }
    }

    @Test
    fun domainNormalization() {
        for (case in fixture("domains.json").getJSONArray("normalize").objects()) {
            val input = case.getString("input")
            assertEquals(input, case.nullableString("normalized"), RuleContracts.normalizeDomain(input))
        }
        for (case in fixture("domains.json").getJSONArray("matches").objects()) {
            val host = case.getString("host")
            val domain = case.getString("domain")
            assertEquals("$host / $domain", case.getBoolean("matches"), RuleContracts.hostMatches(host, domain))
        }
    }

    @Test
    fun timestamps() {
        for (case in fixture("timestamps.json").getJSONArray("cases").objects()) {
            val input = case.getString("input")
            assertEquals(input, case.nullableString("utc"), RuleContracts.parseTimestamp(input)?.let(::isoMillis))
        }
    }

    /** Instant as the fixtures spell it: always three fractional digits. */
    private fun isoMillis(instant: Instant): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC).format(instant)
}
