package dev.brainfence.ui.onboarding

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.brainfence.data.apps.InstalledApp
import dev.brainfence.data.apps.InstalledAppsProvider
import dev.brainfence.data.blocking.BlockingRepository
import dev.brainfence.data.contemplation.ContemplationRepository
import dev.brainfence.data.contemplation.ContemplationSettingsRepository
import dev.brainfence.data.onboarding.OnboardingRepository
import dev.brainfence.data.settings.HomeLocation
import dev.brainfence.data.settings.HomeLocationRepository
import dev.brainfence.domain.blocking.RuleContracts
import dev.brainfence.domain.onboarding.DeferredStep
import dev.brainfence.domain.onboarding.Onboarding
import dev.brainfence.domain.onboarding.OnboardingChoices
import dev.brainfence.domain.onboarding.OnboardingPlan
import dev.brainfence.domain.onboarding.OnboardingRoute
import dev.brainfence.domain.onboarding.PlaceChoice
import dev.brainfence.domain.onboarding.StarterChoice
import dev.brainfence.domain.onboarding.StarterKey
import dev.brainfence.domain.onboarding.buildOnboardingPlan
import dev.brainfence.domain.onboarding.onboardingRoute
import dev.brainfence.service.ContemplationScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Onboarding"

/**
 * Decides once per process, after the first sync, whether to open the setup
 * guide or this device's setup (shared/CONTRACTS.md, Onboarding).
 */
@Singleton
class OnboardingGate @Inject constructor(
    private val repository: OnboardingRepository,
) {
    private val mutex = Mutex()
    private var decided = false

    /** The screen to open, or null. Only the first call after sign-in decides. */
    suspend fun decide(): OnboardingRoute? = mutex.withLock {
        if (decided) return null
        try {
            repository.waitForFirstSync()
            val route = onboardingRoute(
                platform = Onboarding.PLATFORM,
                hasSynced = true,
                onboarding = repository.getRow(),
                taskCount = repository.activeTaskCount(),
            )
            decided = true
            when (route) {
                OnboardingRoute.AUTO_DISMISS -> { repository.dismiss(); null }
                OnboardingRoute.GUIDE, OnboardingRoute.DEVICE_SETUP -> route
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Onboarding check failed", e)
            null
        }
    }

    /** A different account may sign in next: decide again for it. */
    fun reset() {
        decided = false
    }
}

@HiltViewModel
class OnboardingGateViewModel @Inject constructor(private val gate: OnboardingGate) : ViewModel() {
    suspend fun decide(): OnboardingRoute? = gate.decide()
    fun reset() = gate.reset()
}

/** Latitude / longitude as typed or picked; null until both parse. */
data class PlaceInput(val latitude: String = "", val longitude: String = "") {
    fun toChoice(radiusMeters: Int = 100): PlaceChoice? {
        val lat = latitude.toDoubleOrNull() ?: return null
        val lng = longitude.toDoubleOrNull() ?: return null
        if (kotlin.math.abs(lat) > 90 || kotlin.math.abs(lng) > 180) return null
        return PlaceChoice(lat, lng, radiusMeters)
    }
}

private fun androidEntry(app: InstalledApp?, pkg: String): JSONObject =
    JSONObject().put("platform", Onboarding.PLATFORM).put("package", pkg).apply { app?.let { put("name", it.label) } }

enum class GuideStep { WELCOME, STARTERS, PLACES, MEDITATION, BLOCKING, REVIEW, DONE }

data class GuideState(
    val step: GuideStep = GuideStep.WELCOME,
    val starters: Map<StarterKey, StarterChoice> = StarterKey.entries.associateWith { StarterChoice() },
    val home: PlaceInput = PlaceInput(),
    val homeRadiusMeters: Int = HomeLocationRepository.DEFAULT_RADIUS_M,
    val store: PlaceInput = PlaceInput(),
    val companionApps: Set<String> = emptySet(),
    val blockedApps: Set<String> = emptySet(),
    val domains: List<String> = emptyList(),
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    val steps: List<GuideStep>
        get() = buildList {
            add(GuideStep.WELCOME)
            add(GuideStep.STARTERS)
            if (StarterKey.MORNING_WALK in starters || StarterKey.GROCERIES in starters) add(GuideStep.PLACES)
            if (StarterKey.MEDITATION in starters) add(GuideStep.MEDITATION)
            add(GuideStep.BLOCKING)
            add(GuideStep.REVIEW)
            add(GuideStep.DONE)
        }
}

@HiltViewModel
class GuideViewModel @Inject constructor(
    private val repository: OnboardingRepository,
    private val homeLocationRepository: HomeLocationRepository,
    private val contemplationSettings: ContemplationSettingsRepository,
    private val contemplationScheduler: ContemplationScheduler,
    installedAppsProvider: InstalledAppsProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(
        homeLocationRepository.get()?.let {
            GuideState(home = PlaceInput(it.latitude.toString(), it.longitude.toString()), homeRadiusMeters = it.radiusMeters)
        } ?: GuideState(),
    )
    val state: StateFlow<GuideState> = _state.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    init {
        viewModelScope.launch { _installedApps.value = installedAppsProvider.load() }
    }

    fun plan(s: GuideState = _state.value): OnboardingPlan {
        val apps = _installedApps.value.associateBy { it.packageName }
        return buildOnboardingPlan(
            OnboardingChoices(
                starters = s.starters,
                home = s.home.toChoice(s.homeRadiusMeters),
                store = s.store.toChoice(),
                companionApps = s.companionApps.map { androidEntry(apps[it], it) },
                blockedApps = s.blockedApps.map { androidEntry(apps[it], it) },
                blockedDomains = s.domains,
            ),
        )
    }

    fun next() = move(+1)
    fun back() = move(-1)

    private fun move(by: Int) {
        val s = _state.value
        val steps = s.steps
        val i = steps.indexOf(s.step) + by
        if (i in steps.indices) _state.value = s.copy(step = steps[i], error = null)
    }

    fun setStarter(key: StarterKey, enabled: Boolean) {
        val s = _state.value
        _state.value = s.copy(starters = if (enabled) s.starters + (key to StarterChoice()) else s.starters - key)
    }

    fun setWindow(key: StarterKey, availableFrom: String?, dueAt: String?) {
        val s = _state.value
        val current = s.starters[key] ?: return
        _state.value = s.copy(
            starters = s.starters + (key to current.copy(
                availableFrom = availableFrom ?: current.availableFrom,
                dueAt = dueAt ?: current.dueAt,
            )),
        )
    }

    fun setHome(place: PlaceInput) { _state.value = _state.value.copy(home = place) }
    fun setStore(place: PlaceInput) { _state.value = _state.value.copy(store = place) }

    fun toggleCompanionApp(pkg: String) {
        val s = _state.value
        _state.value = s.copy(companionApps = if (pkg in s.companionApps) s.companionApps - pkg else s.companionApps + pkg)
    }

    fun toggleBlockedApp(pkg: String) {
        val s = _state.value
        _state.value = s.copy(blockedApps = if (pkg in s.blockedApps) s.blockedApps - pkg else s.blockedApps + pkg)
    }

    fun toggleDomain(domain: String) {
        val s = _state.value
        _state.value = s.copy(domains = if (domain in s.domains) s.domains - domain else s.domains + domain)
    }

    /** Returns false when [raw] isn't a usable domain. */
    fun addDomain(raw: String): Boolean {
        val d = RuleContracts.normalizeDomain(raw) ?: return false
        val s = _state.value
        if (d !in s.domains) _state.value = s.copy(domains = s.domains + d)
        return true
    }

    fun create() {
        val s = _state.value
        val plan = plan(s)
        _state.value = s.copy(isSaving = true, error = null)
        viewModelScope.launch {
            try {
                repository.applyGuide(plan)
                val home = s.home.toChoice(s.homeRadiusMeters)
                if (home != null && StarterKey.MORNING_WALK in s.starters) {
                    homeLocationRepository.set(HomeLocation(home.latitude, home.longitude, home.radiusMeters))
                }
                if (plan.addRemembrances) enableContemplationsHere()
                _state.value = _state.value.copy(isSaving = false, step = GuideStep.DONE)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isSaving = false, error = e.message)
            }
        }
    }

    fun skip(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { repository.dismiss() }.onFailure { Log.e(TAG, "Dismiss failed", it) }
            onDone()
        }
    }

    private fun enableContemplationsHere() {
        val current = contemplationSettings.get()
        if (current.enabled) return
        contemplationSettings.set(current.copy(enabled = true))
        contemplationScheduler.reschedule()
    }
}

data class DeviceSetupState(
    val isLoading: Boolean = true,
    val ruleName: String? = null,
    /** Android packages the guide's rule already blocks. */
    val alreadyBlocked: List<String> = emptyList(),
    val blockedApps: Set<String> = emptySet(),
    /** Null when the guide made no meditation task. */
    val companionApps: Set<String>? = null,
    val deferred: List<DeferredStep> = emptyList(),
    val keep: Set<StarterKey> = emptySet(),
    val savedHome: HomeLocation? = null,
    val home: PlaceInput = PlaceInput(),
    val store: PlaceInput = PlaceInput(),
    val askContemplations: Boolean = false,
    val deliverContemplations: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class DeviceSetupViewModel @Inject constructor(
    private val repository: OnboardingRepository,
    private val blockingRepository: BlockingRepository,
    private val contemplationRepository: ContemplationRepository,
    private val homeLocationRepository: HomeLocationRepository,
    private val contemplationSettings: ContemplationSettingsRepository,
    private val contemplationScheduler: ContemplationScheduler,
    private val database: com.powersync.PowerSyncDatabase,
    installedAppsProvider: InstalledAppsProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(DeviceSetupState())
    val state: StateFlow<DeviceSetupState> = _state.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    init {
        viewModelScope.launch { _installedApps.value = installedAppsProvider.load() }
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        try {
            val row = repository.getRow()
            val rule = row?.blockingRuleId?.let { blockingRepository.getRuleById(it) }
            val companions = row?.meditationTaskId?.let { id ->
                database.getOptional("SELECT verification_config FROM tasks WHERE id = ?", listOf(id)) { it.getString(0) ?: "{}" }
            }?.let { raw ->
                val apps = runCatching { JSONObject(raw).optJSONArray("companion_apps") }.getOrNull()
                buildSet {
                    for (i in 0 until (apps?.length() ?: 0)) {
                        val entry = apps!!.opt(i)
                        if (entry is String) add(entry)
                        else if (entry is JSONObject && entry.optString("platform") == Onboarding.PLATFORM) add(entry.optString("package"))
                    }
                }
            }
            val decks = contemplationRepository.watchDecks().first()
            val home = homeLocationRepository.get()
            _state.value = DeviceSetupState(
                isLoading = false,
                ruleName = rule?.name,
                alreadyBlocked = rule?.blockedApps.orEmpty(),
                companionApps = companions,
                deferred = row?.deferredSteps.orEmpty(),
                keep = row?.deferredSteps.orEmpty().map { it.key }.toSet(),
                savedHome = home,
                home = home?.let { PlaceInput(it.latitude.toString(), it.longitude.toString()) } ?: PlaceInput(),
                askContemplations = decks.any { it.isEnabled } && !contemplationSettings.get().enabled,
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(isLoading = false, error = e.message)
        }
    }

    fun toggleBlockedApp(pkg: String) {
        val s = _state.value
        _state.value = s.copy(blockedApps = if (pkg in s.blockedApps) s.blockedApps - pkg else s.blockedApps + pkg)
    }

    fun toggleCompanionApp(pkg: String) {
        val s = _state.value
        val current = s.companionApps ?: return
        _state.value = s.copy(companionApps = if (pkg in current) current - pkg else current + pkg)
    }

    fun setKeep(key: StarterKey, keep: Boolean) {
        val s = _state.value
        _state.value = s.copy(keep = if (keep) s.keep + key else s.keep - key)
    }

    fun setHome(place: PlaceInput) { _state.value = _state.value.copy(home = place) }
    fun setStore(place: PlaceInput) { _state.value = _state.value.copy(store = place) }
    fun setDeliverContemplations(on: Boolean) { _state.value = _state.value.copy(deliverContemplations = on) }

    /** Deferred starters the user kept, planned with the locations entered. */
    fun deferredPlan(s: DeviceSetupState = _state.value): OnboardingPlan = buildOnboardingPlan(
        OnboardingChoices.deferred(
            s.deferred.filter { it.key in s.keep },
            home = s.home.toChoice(s.savedHome?.radiusMeters ?: HomeLocationRepository.DEFAULT_RADIUS_M),
            store = s.store.toChoice(),
        ),
    )

    fun finish(skip: Boolean, onDone: () -> Unit) {
        val s = _state.value
        _state.value = s.copy(isSaving = true, error = null)
        viewModelScope.launch {
            try {
                if (skip) {
                    repository.finishDeviceSetup(emptyList(), null, null)
                } else {
                    val apps = _installedApps.value.associateBy { it.packageName }
                    repository.finishDeviceSetup(
                        blockedApps = s.blockedApps.map { androidEntry(apps[it], it) },
                        companionApps = s.companionApps?.map { androidEntry(apps[it], it) },
                        deferredPlan = deferredPlan(s),
                    )
                    val home = s.home.toChoice(s.savedHome?.radiusMeters ?: HomeLocationRepository.DEFAULT_RADIUS_M)
                    if (home != null && (home.latitude != s.savedHome?.latitude || home.longitude != s.savedHome?.longitude)) {
                        homeLocationRepository.set(HomeLocation(home.latitude, home.longitude, home.radiusMeters))
                    }
                    if (s.askContemplations && s.deliverContemplations) {
                        contemplationSettings.set(contemplationSettings.get().copy(enabled = true))
                        contemplationScheduler.reschedule()
                    }
                }
                _state.value = _state.value.copy(isSaving = false)
                onDone()
            } catch (e: Exception) {
                _state.value = _state.value.copy(isSaving = false, error = e.message)
            }
        }
    }
}
