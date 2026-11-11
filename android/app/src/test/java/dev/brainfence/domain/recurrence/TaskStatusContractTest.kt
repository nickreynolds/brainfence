package dev.brainfence.domain.recurrence

import dev.brainfence.domain.blocking.HomePresence
import dev.brainfence.domain.blocking.isConditionUnmet
import dev.brainfence.domain.model.Task
import dev.brainfence.domain.verification.CompletionAlternatives
import dev.brainfence.domain.verification.TimedSetsConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Runs shared/fixtures/task-status.json, completion-alternatives.json and
 * timed-sets.json, which the macOS and Windows test suites load too.
 */
class TaskStatusContractTest {

    private fun fixture(name: String): JSONObject {
        val dir = System.getProperty("brainfence.fixtures") ?: "../../shared/fixtures"
        return JSONObject(File(dir, name).readText())
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun JSONObject.nullableString(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)

    @Test
    fun taskStatus() {
        val fixture = fixture("task-status.json")
        val zone = ZoneId.of(fixture.getString("time_zone"))
        val base = fixture.getJSONObject("base_task")
        val cases = fixture.getJSONArray("cases").objects()
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val row = JSONObject(base.toString())
            case.optJSONObject("task")?.let { o -> o.keys().forEach { row.put(it, o.get(it)) } }
            val task = Task(
                id = "t", userId = "u", title = "T", description = null, taskType = "simple", status = "active",
                recurrenceType = row.nullableString("recurrence_type"),
                recurrenceConfig = row.getJSONObject("recurrence_config").toString(),
                verificationType = null, verificationConfig = "{}", tags = "[]", groupId = null, sortOrder = 0,
                isBlockingCondition = true, blockingRuleIds = "[]",
                availableFrom = row.nullableString("available_from"),
                dueAt = row.nullableString("due_at"),
                homeOnlyBlocking = row.getInt("home_only_blocking") == 1,
                blockingDaysOfWeek = row.getJSONArray("blocking_days_of_week").toString(),
                carryOver = row.getInt("carry_over") == 1,
                createdAt = row.getString("created_at"),
                updatedAt = row.getString("created_at"),
                completedToday = false,
                lastCompletionAt = case.nullableString("last_completion_at"),
            )
            val now = OffsetDateTime.parse(case.getString("now")).toInstant()
            val status = task.status(now, zone)
            val home = HomePresence.valueOf(case.getString("home"))
            val expect = case.getJSONObject("expect")
            val name = case.getString("name")
            assertEquals(name, expect.getBoolean("scheduled"), status.scheduled)
            assertEquals(name, expect.getBoolean("completed"), status.completed)
            assertEquals(name, expect.getBoolean("carried_over"), status.carriedOver)
            assertEquals(name, expect.nullableString("phase"), status.phase?.name)
            assertEquals(name, expect.getBoolean("unmet"), isConditionUnmet(task, now, zone, home))
        }
    }

    @Test
    fun completionAlternatives() {
        for (case in fixture("completion-alternatives.json").getJSONArray("cases").objects()) {
            val actual = CompletionAlternatives.parseGps(case.get("input").toString())
            val expected = case.getJSONArray("gps").objects()
            val name = case.getString("name")
            assertEquals(name, expected.size, actual.size)
            for ((e, a) in expected.zip(actual)) {
                assertEquals(name, e.getInt("index"), a.index)
                assertEquals(name, e.nullableString("label"), a.label)
                assertEquals(name, e.getDouble("lat"), a.lat, 1e-9)
                assertEquals(name, e.getDouble("lng"), a.lng, 1e-9)
                assertEquals(name, e.getDouble("radius_m"), a.radiusM, 1e-9)
                assertEquals(name, e.getString("mode"), a.mode)
                assertEquals(name, e.getInt("min_duration_m"), a.minDurationM)
            }
        }
    }

    @Test
    fun timedSets() {
        for (case in fixture("timed-sets.json").getJSONArray("cases").objects()) {
            val config = TimedSetsConfig.parse(case.getJSONObject("config").toString())
            val name = case.getString("name")
            assertEquals(name, case.getInt("hangs"), config.hangCount)
            assertEquals(name, case.nullableString("weight"), config.weight)
            assertEquals(name, case.getString("weight_prompt"), config.weightPrompt)
            fun ints(key: String) = case.getJSONArray(key).let { a -> (0 until a.length()).map { a.getInt(it) } }
            assertEquals(name, ints("set_of"), (0 until config.hangCount).map(config::setOf))
            assertEquals(name, ints("side_of"), (0 until config.hangCount).map(config::sideOf))
            val rests = case.getJSONArray("rest_after")
            val lastStep = case.getBoolean("last_step")
            assertEquals(name, (0 until rests.length()).map { rests.getInt(it) }, (0 until config.hangCount).map { config.restAfter(it, lastStep) })
        }
    }
}
