package dev.brainfence.domain.onboarding

import dev.brainfence.domain.blocking.RuleContracts
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the onboarding cases in shared/fixtures/onboarding.json, which the
 * macOS and Windows test suites load too. See shared/CONTRACTS.md.
 */
class OnboardingContractTest {

    private fun fixture(): JSONObject {
        val dir = System.getProperty("brainfence.fixtures") ?: "../../shared/fixtures"
        return JSONObject(File(dir, "onboarding.json").readText())
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)

    @Test
    fun route() {
        val cases = fixture().getJSONArray("route").objects()
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val row = case.optJSONObject("onboarding")?.let { o ->
                OnboardingRow(
                    status = o.getString("status"),
                    deferredSteps = DeferredStep.parseList(o.getJSONArray("deferred_steps").toString()),
                    blockingRuleId = null,
                    meditationTaskId = null,
                    setupAt = OnboardingRow.PLATFORMS
                        .mapNotNull { p -> o.nullableString(OnboardingRow.setupColumn(p))?.let { p to it } }
                        .toMap(),
                )
            }
            val route = onboardingRoute(
                platform = case.getString("platform"),
                hasSynced = case.getBoolean("has_synced"),
                onboarding = row,
                taskCount = case.getInt("task_count"),
            )
            assertEquals(case.getString("name"), case.getString("route"), route.key)
        }
    }

    @Test
    fun plan() {
        val cases = fixture().getJSONArray("plan").objects()
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val c = case.getJSONObject("choices")
            fun place(key: String) = c.optJSONObject(key)?.let {
                PlaceChoice(it.getDouble("latitude"), it.getDouble("longitude"), it.optInt("radius_meters", 100))
            }
            val startersJson = c.getJSONObject("starters")
            val plan = buildOnboardingPlan(
                OnboardingChoices(
                    starters = startersJson.keys().asSequence().associate { key ->
                        val v = startersJson.getJSONObject(key)
                        StarterKey.fromKey(key)!! to StarterChoice(
                            if (v.has("available_from")) v.getString("available_from") else null,
                            if (v.has("due_at")) v.getString("due_at") else null,
                        )
                    },
                    home = place("home"),
                    store = place("store"),
                    companionApps = c.getJSONArray("companion_apps").objects(),
                    blockedApps = c.getJSONArray("blocked_apps").objects(),
                    blockedDomains = c.getJSONArray("blocked_domains").let { a -> (0 until a.length()).map { a.getString(it) } },
                ),
            )
            val actual = JSONObject().apply {
                put("tasks", JSONArray(plan.tasks.map { t ->
                    JSONObject().apply {
                        put("key", t.key.key)
                        put("title", t.title)
                        put("task_type", t.taskType)
                        put("verification_type", t.verificationType ?: JSONObject.NULL)
                        put("verification_config", t.verificationConfig)
                        put("recurrence_type", t.recurrenceType ?: JSONObject.NULL)
                        put("recurrence_config", t.recurrenceConfig)
                        put("is_blocking_condition", t.isBlockingCondition)
                        put("available_from", t.availableFrom ?: JSONObject.NULL)
                        put("due_at", t.dueAt ?: JSONObject.NULL)
                        put("sort_order", t.sortOrder)
                        put("steps", JSONArray(t.steps.map { s ->
                            JSONObject().put("title", s.title).put("step_type", s.stepType).put("config", s.config)
                        }))
                    }
                }))
                put("deferred_steps", JSONArray(plan.deferredSteps.map { it.toJson() }))
                put("add_remembrances", plan.addRemembrances)
                put("rule", plan.rule?.let { r ->
                    JSONObject().apply {
                        put("name", r.name)
                        put("blocked_apps", JSONArray(r.blockedApps))
                        put("blocked_domains", JSONArray(r.blockedDomains))
                        put("condition_keys", JSONArray(r.conditionKeys.map { it.key }))
                        put("condition_logic", r.conditionLogic)
                    }
                } ?: JSONObject.NULL)
            }
            assertEquals(
                case.getString("name"),
                RuleContracts.canonicalJson(case.getJSONObject("expect")),
                RuleContracts.canonicalJson(actual),
            )
        }
    }
}
