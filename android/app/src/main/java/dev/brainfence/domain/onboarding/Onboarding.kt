package dev.brainfence.domain.onboarding

import dev.brainfence.domain.blocking.RuleContracts
import org.json.JSONArray
import org.json.JSONObject

/**
 * The setup guide's rules: where a device goes, and the rows the guide writes.
 * Shared semantics: shared/CONTRACTS.md (Onboarding); cases:
 * shared/fixtures/onboarding.json, which macOS and Windows run too.
 */
enum class StarterKey(val key: String) {
    MORNING_ROUTINE("morning_routine"),
    MORNING_WALK("morning_walk"),
    MEDITATION("meditation"),
    JOURNALING("journaling"),
    GROCERIES("groceries"),
    FIVE_REMEMBRANCES("five_remembrances");

    companion object {
        fun fromKey(key: String?): StarterKey? = entries.firstOrNull { it.key == key }
    }
}

data class StarterStep(val title: String, val stepType: String, val config: JSONObject)

data class StarterDef(
    val key: StarterKey,
    val title: String,
    /** One line for the guide: what the task is and how it's completed. */
    val summary: String,
    /** Has a time window the user can change. */
    val hasWindow: Boolean,
    val availableFrom: String?,
    val dueAt: String?,
    /** A condition of the guide's blocking rule. */
    val blocks: Boolean,
    /** The location the starter needs; deferred until it's known. */
    val needs: Place?,
    val sortOrder: Int,
) {
    enum class Place { HOME, STORE }
}

object Onboarding {
    const val RULE_NAME = "Distractions"
    const val MEDITATION_SECONDS = 600
    const val MEDITATION_SHORT_SIT_SECONDS = 120
    const val GROCERIES_RADIUS_M = 100
    const val PLATFORM = "android"
    val SUGGESTED_DOMAINS = listOf("youtube.com", "reddit.com", "x.com", "instagram.com", "facebook.com", "tiktok.com")
    internal val EVERY_DAY = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    val STARTERS = listOf(
        StarterDef(StarterKey.MORNING_ROUTINE, "Morning Routine", "A checklist you run through: make bed, take pills, then a 30-second reverse plank.", true, "06:00", "09:00", true, null, 0),
        StarterDef(StarterKey.MORNING_WALK, "Morning Walk", "Completes on its own when your phone leaves home.", true, "06:00", "11:00", true, StarterDef.Place.HOME, 1),
        StarterDef(StarterKey.MEDITATION, "Meditation", "Ten minutes with the built-in timer or a meditation app you choose.", true, "06:00", "19:00", true, null, 2),
        StarterDef(StarterKey.JOURNALING, "Journal", "Write a short entry each evening. Past entries are kept in Journal history.", true, "18:00", "23:00", false, null, 3),
        StarterDef(StarterKey.GROCERIES, "Groceries", "A running shopping list that reminds you when you reach the store.", false, null, null, false, StarterDef.Place.STORE, 4),
        StarterDef(StarterKey.FIVE_REMEMBRANCES, "The Five Remembrances", "Short reflections delivered a few times a day. Tap \"I remember\" to acknowledge one.", false, null, null, false, null, 5),
    )

    fun starter(key: StarterKey): StarterDef = STARTERS.first { it.key == key }

    val MORNING_ROUTINE_STEPS: List<StarterStep>
        get() = listOf(
            StarterStep("Make Bed", "checkbox", JSONObject()),
            StarterStep("Take Pills", "checkbox", JSONObject()),
            StarterStep("Reverse Plank", "timed", JSONObject().put("duration_seconds", 30)),
        )

    /** Android and Windows have a home location; the Mac doesn't. */
    fun canLocate(platform: String): Boolean = platform != "macos"
}

// ── Routing ────────────────────────────────────────────────────────────────

enum class OnboardingRoute(val key: String) {
    WAIT("wait"), GUIDE("guide"), DEVICE_SETUP("device_setup"), AUTO_DISMISS("auto_dismiss"), NONE("none")
}

data class DeferredStep(val key: StarterKey, val availableFrom: String? = null, val dueAt: String? = null) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key.key)
        availableFrom?.let { put("available_from", it) }
        dueAt?.let { put("due_at", it) }
    }

    companion object {
        /** Parses `deferred_steps`, skipping entries this version doesn't know. */
        fun parseList(json: String?): List<DeferredStep> {
            val array = runCatching { JSONArray(json ?: "[]") }.getOrDefault(JSONArray())
            return (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val key = StarterKey.fromKey(o.optString("key")) ?: return@mapNotNull null
                DeferredStep(key, o.nullableString("available_from"), o.nullableString("due_at"))
            }
        }

        fun encodeList(steps: List<DeferredStep>): String = JSONArray(steps.map { it.toJson() }).toString()
    }
}

/** The synced `onboarding` row (one per user, id = user_id). */
data class OnboardingRow(
    val status: String,
    val deferredSteps: List<DeferredStep>,
    val blockingRuleId: String?,
    val meditationTaskId: String?,
    /** `<platform>_setup_at`, by platform. */
    val setupAt: Map<String, String>,
) {
    companion object {
        val PLATFORMS = listOf("android", "windows", "macos")
        fun setupColumn(platform: String) = "${platform}_setup_at"
    }
}

fun onboardingRoute(platform: String, hasSynced: Boolean, onboarding: OnboardingRow?, taskCount: Int): OnboardingRoute = when {
    !hasSynced -> OnboardingRoute.WAIT
    onboarding == null -> if (taskCount > 0) OnboardingRoute.AUTO_DISMISS else OnboardingRoute.GUIDE
    onboarding.status != "completed" -> OnboardingRoute.NONE
    Onboarding.canLocate(platform) && onboarding.deferredSteps.isNotEmpty() -> OnboardingRoute.DEVICE_SETUP
    onboarding.setupAt[platform] == null -> OnboardingRoute.DEVICE_SETUP
    else -> OnboardingRoute.NONE
}

// ── Plan ───────────────────────────────────────────────────────────────────

data class StarterChoice(val availableFrom: String? = null, val dueAt: String? = null)

data class PlaceChoice(val latitude: Double, val longitude: Double, val radiusMeters: Int = 100)

data class OnboardingChoices(
    /** Present = turned on; values override the default window. */
    val starters: Map<StarterKey, StarterChoice>,
    val home: PlaceChoice? = null,
    val store: PlaceChoice? = null,
    /** This platform's companion apps for the meditation task, as JSON entries. */
    val companionApps: List<JSONObject> = emptyList(),
    /** This platform's apps for the rule, as JSON entries. */
    val blockedApps: List<JSONObject> = emptyList(),
    val blockedDomains: List<String> = emptyList(),
) {
    companion object {
        /** Choices that re-create deferred starters, once their locations are known. */
        fun deferred(steps: List<DeferredStep>, home: PlaceChoice?, store: PlaceChoice?) = OnboardingChoices(
            starters = steps.associate { it.key to StarterChoice(it.availableFrom, it.dueAt) },
            home = home,
            store = store,
        )
    }
}

data class PlannedTask(
    val key: StarterKey,
    val title: String,
    val taskType: String,
    val verificationType: String?,
    val verificationConfig: JSONObject,
    val recurrenceType: String?,
    val recurrenceConfig: JSONObject,
    val isBlockingCondition: Boolean,
    val availableFrom: String?,
    val dueAt: String?,
    val sortOrder: Int,
    val steps: List<StarterStep>,
)

data class PlannedRule(
    val name: String,
    val blockedApps: List<JSONObject>,
    val blockedDomains: List<String>,
    val conditionKeys: List<StarterKey>,
    val conditionLogic: String,
)

data class OnboardingPlan(
    val tasks: List<PlannedTask>,
    val deferredSteps: List<DeferredStep>,
    val addRemembrances: Boolean,
    val rule: PlannedRule?,
)

fun buildOnboardingPlan(choices: OnboardingChoices): OnboardingPlan {
    val tasks = mutableListOf<PlannedTask>()
    val deferred = mutableListOf<DeferredStep>()
    for (def in Onboarding.STARTERS) {
        if (def.key == StarterKey.FIVE_REMEMBRANCES) continue
        val choice = choices.starters[def.key] ?: continue
        val from = if (def.hasWindow) choice.availableFrom ?: def.availableFrom else null
        val due = if (def.hasWindow) choice.dueAt ?: def.dueAt else null
        val place = when (def.needs) {
            StarterDef.Place.HOME -> choices.home
            StarterDef.Place.STORE -> choices.store
            null -> null
        }
        if (def.needs != null && place == null) {
            deferred += DeferredStep(def.key, from, due)
            continue
        }
        tasks += starterTask(def, choices, place, from, due)
    }

    val domains = choices.blockedDomains.mapNotNull(RuleContracts::normalizeDomain).distinct()
    val conditions = tasks.filter { it.isBlockingCondition }.map { it.key }
    val blocksSomething = choices.blockedApps.isNotEmpty() || domains.isNotEmpty()
    return OnboardingPlan(
        tasks = tasks,
        deferredSteps = deferred,
        addRemembrances = StarterKey.FIVE_REMEMBRANCES in choices.starters,
        rule = if (blocksSomething && conditions.isNotEmpty()) {
            PlannedRule(Onboarding.RULE_NAME, choices.blockedApps, domains, conditions, "all")
        } else {
            null
        },
    )
}

private fun starterTask(def: StarterDef, choices: OnboardingChoices, place: PlaceChoice?, from: String?, due: String?): PlannedTask {
    var taskType = "simple"
    var verificationType: String? = null
    val config = JSONObject()
    var recurrence: String? = "daily"
    var steps = emptyList<StarterStep>()
    when (def.key) {
        StarterKey.MORNING_ROUTINE -> {
            taskType = "routine"
            steps = Onboarding.MORNING_ROUTINE_STEPS
        }
        StarterKey.MORNING_WALK -> {
            verificationType = "gps"
            config.put("latitude", place!!.latitude)
            config.put("longitude", place.longitude)
            config.put("radius_meters", place.radiusMeters)
            config.put("mode", "leave")
            config.put("min_duration_m", 0)
        }
        StarterKey.MEDITATION -> {
            verificationType = "meditation"
            config.put("duration_seconds", Onboarding.MEDITATION_SECONDS)
            config.put("short_sit_seconds", Onboarding.MEDITATION_SHORT_SIT_SECONDS)
            config.put("companion_apps", JSONArray(choices.companionApps))
        }
        StarterKey.JOURNALING -> {
            taskType = "journal"
            verificationType = "journal"
        }
        StarterKey.GROCERIES -> {
            taskType = "shopping"
            verificationType = "gps"
            recurrence = null
            config.put("latitude", place!!.latitude)
            config.put("longitude", place.longitude)
            config.put("radius_meters", Onboarding.GROCERIES_RADIUS_M)
            config.put("mode", "notify")
            config.put(
                "notify_window",
                JSONObject().put("days", JSONArray(Onboarding.EVERY_DAY))
                    .put("start", "08:00").put("end", "21:00"),
            )
        }
        StarterKey.FIVE_REMEMBRANCES -> Unit
    }
    return PlannedTask(
        key = def.key, title = def.title, taskType = taskType, verificationType = verificationType,
        verificationConfig = config, recurrenceType = recurrence, recurrenceConfig = JSONObject(),
        isBlockingCondition = def.blocks, availableFrom = from, dueAt = due, sortOrder = def.sortOrder, steps = steps,
    )
}

private fun JSONObject.nullableString(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
