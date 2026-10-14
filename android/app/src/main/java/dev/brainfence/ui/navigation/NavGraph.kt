package dev.brainfence.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.brainfence.data.auth.AuthState
import dev.brainfence.service.AccessibilityServiceChecker
import dev.brainfence.ui.auth.AuthViewModel
import dev.brainfence.ui.auth.SignInScreen
import dev.brainfence.ui.auth.SignUpScreen
import dev.brainfence.ui.contemplation.ContemplationDeckScreen
import dev.brainfence.ui.report.MetricEditorScreen
import dev.brainfence.ui.report.MetricEditorViewModel
import dev.brainfence.ui.report.ReportEditorScreen
import dev.brainfence.ui.report.ReportEditorViewModel
import dev.brainfence.ui.report.ReportTaskScreen
import dev.brainfence.ui.report.ReportTaskViewModel
import dev.brainfence.ui.report.ReportsScreen
import dev.brainfence.ui.report.ReportsViewModel
import dev.brainfence.ui.contemplation.ContemplationDeckViewModel
import dev.brainfence.ui.contemplation.ContemplationSettingsScreen
import dev.brainfence.ui.contemplation.ContemplationSettingsViewModel
import dev.brainfence.ui.settings.HomeLocationScreen
import dev.brainfence.ui.settings.HomeLocationViewModel
import dev.brainfence.ui.setup.AccessibilitySetupScreen
import dev.brainfence.ui.debug.DebugScreen
import dev.brainfence.ui.debug.DebugViewModel
import dev.brainfence.ui.journal.JournalHistoryScreen
import dev.brainfence.ui.journal.JournalHistoryViewModel
import dev.brainfence.ui.tasks.DurationTaskScreen
import dev.brainfence.ui.tasks.DurationTaskViewModel
import dev.brainfence.ui.tasks.GpsTaskScreen
import dev.brainfence.ui.tasks.GpsTaskViewModel
import dev.brainfence.ui.tasks.JournalTaskScreen
import dev.brainfence.ui.tasks.JournalTaskViewModel
import dev.brainfence.ui.tasks.MeditationTaskScreen
import dev.brainfence.ui.tasks.MeditationTaskViewModel
import dev.brainfence.ui.tasks.AutoRoutineProgress
import dev.brainfence.ui.tasks.RoutineTaskScreen
import dev.brainfence.ui.tasks.RoutineTaskViewModel
import dev.brainfence.ui.tasks.ShoppingListScreen
import dev.brainfence.ui.tasks.ShoppingListViewModel
import dev.brainfence.ui.tasks.TaskEditorScreen
import dev.brainfence.ui.tasks.TaskEditorViewModel
import dev.brainfence.ui.tasks.TaskListScreen
import dev.brainfence.ui.tasks.TaskListViewModel
import dev.brainfence.ui.blocking.BlockingRuleEditorScreen
import dev.brainfence.ui.blocking.BlockingRuleEditorViewModel
import dev.brainfence.ui.blocking.BlockingRuleListScreen
import dev.brainfence.ui.blocking.BlockingRuleListViewModel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.navArgument

private object Routes {
    const val SIGN_IN           = "auth/sign-in"
    const val SIGN_UP           = "auth/sign-up"
    const val ACCESSIBILITY_SETUP = "setup/accessibility"
    const val HOME              = "home"
    const val GPS_TASK          = "task/{taskId}"
    const val DURATION_TASK     = "duration-task/{taskId}"
    const val MEDITATION_TASK   = "meditation-task/{taskId}"
    const val ROUTINE_TASK      = "routine-task/{taskId}"
    const val JOURNAL_TASK      = "journal-task/{taskId}"
    const val SHOPPING_TASK     = "shopping-task/{taskId}"
    const val REPORT_TASK       = "report-task/{taskId}"
    const val REPORTS           = "reports"
    const val REPORT_EDITOR     = "reports/{reportId}"
    const val METRIC_EDITOR     = "reports/metric/{metricId}"
    const val JOURNAL_HISTORY   = "journal/history"
    const val TASK_EDITOR       = "task/editor?taskId={taskId}"
    const val DEBUG             = "debug"
    const val BLOCKING_RULES    = "blocking/rules"
    const val BLOCKING_RULE_EDITOR = "blocking/editor/{ruleId}"
    const val HOME_LOCATION     = "settings/home-location"
    const val CONTEMPLATIONS    = "settings/contemplations"
    const val CONTEMPLATION_DECK = "settings/contemplations/deck/{deckId}"
}

@Composable
fun BrainfenceNavGraph(
    navController: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val authViewModel: AuthViewModel = hiltViewModel()
    val authState by authViewModel.authState.collectAsStateWithLifecycle()
    val authUiState by authViewModel.uiState.collectAsStateWithLifecycle()

    // Track accessibility service state; re-check every time the app resumes
    var isAccessibilityEnabled by rememberSaveable {
        mutableStateOf(AccessibilityServiceChecker.isEnabled(context))
    }
    // Whether the user has explicitly skipped the setup screen this session
    var setupSkipped by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            isAccessibilityEnabled = AccessibilityServiceChecker.isEnabled(context)
        }
    }

    // Key on the coarse signed-in/out state, not the full AuthState object, so
    // that token refreshes (which emit a new SignedIn value) don't re-trigger
    // navigation and pop the user out of whatever screen they're on.
    val isSignedIn = authState is AuthState.SignedIn
    val isSignedOut = authState is AuthState.SignedOut
    LaunchedEffect(isSignedIn, isSignedOut, isAccessibilityEnabled, setupSkipped) {
        val current = navController.currentDestination?.route
        when {
            isSignedIn && !isAccessibilityEnabled && !setupSkipped -> {
                if (current != Routes.ACCESSIBILITY_SETUP) {
                    navController.navigate(Routes.ACCESSIBILITY_SETUP) {
                        popUpTo(Routes.SIGN_IN) { inclusive = true }
                    }
                }
            }
            isSignedIn -> {
                // Only force-route to HOME from the auth/setup screens. Once
                // the user is past those, never blow away the back stack on
                // an auth-state re-emission.
                if (current == Routes.SIGN_IN ||
                    current == Routes.SIGN_UP ||
                    current == Routes.ACCESSIBILITY_SETUP
                ) {
                    navController.navigate(Routes.HOME) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
            isSignedOut -> {
                if (current != Routes.SIGN_IN) {
                    navController.navigate(Routes.SIGN_IN) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
        }
    }

    NavHost(navController = navController, startDestination = Routes.SIGN_IN) {
        composable(Routes.SIGN_IN) {
            SignInScreen(
                uiState            = authUiState,
                onSignIn           = authViewModel::signIn,
                onNavigateToSignUp = { navController.navigate(Routes.SIGN_UP) },
            )
        }
        composable(Routes.SIGN_UP) {
            SignUpScreen(
                uiState            = authUiState,
                onSignUp           = authViewModel::signUp,
                onNavigateToSignIn = { navController.popBackStack() },
            )
        }
        composable(Routes.ACCESSIBILITY_SETUP) {
            // If they enabled it while in settings, auto-advance
            LaunchedEffect(isAccessibilityEnabled) {
                if (isAccessibilityEnabled) {
                    navController.navigate(Routes.HOME) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
            AccessibilitySetupScreen(
                onSkip = {
                    setupSkipped = true
                    navController.navigate(Routes.HOME) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.HOME) {
            val taskViewModel: TaskListViewModel = hiltViewModel()
            val activeTasks        by taskViewModel.activeTasks.collectAsStateWithLifecycle()
            val completedTasks     by taskViewModel.completedTasks.collectAsStateWithLifecycle()
            val upcomingTasks      by taskViewModel.upcomingTasks.collectAsStateWithLifecycle()
            val selectedTab        by taskViewModel.selectedTab.collectAsStateWithLifecycle()
            val pendingTask        by taskViewModel.pendingTask.collectAsStateWithLifecycle()
            val blockingStatus     by taskViewModel.blockingStatus.collectAsStateWithLifecycle()
            val activeRules        by taskViewModel.activeRules.collectAsStateWithLifecycle()
            val hasLocationPerm    by taskViewModel.hasLocationPermission.collectAsStateWithLifecycle()
            val needsUsageStats   by taskViewModel.needsUsageStatsPermission.collectAsStateWithLifecycle()
            val needsExactAlarm   by taskViewModel.needsExactAlarmPermission.collectAsStateWithLifecycle()
            val meditationTimerStates by taskViewModel.meditationTimerStates.collectAsStateWithLifecycle()
            val shoppingItemCounts by taskViewModel.shoppingItemCounts.collectAsStateWithLifecycle()
            TaskListScreen(
                activeTasks              = activeTasks,
                completedTasks           = completedTasks,
                upcomingTasks            = upcomingTasks,
                selectedTab              = selectedTab,
                activeRules              = activeRules,
                pendingTask              = pendingTask,
                blockingStatus           = blockingStatus,
                meditationTimerStates    = meditationTimerStates,
                shoppingItemCounts       = shoppingItemCounts,
                isAccessibilityEnabled   = isAccessibilityEnabled,
                hasLocationPermission    = hasLocationPerm,
                needsUsageStatsPermission = needsUsageStats,
                needsExactAlarmPermission = needsExactAlarm,
                onLocationPermissionResult = taskViewModel::onLocationPermissionResult,
                onUsageStatsPermissionResult = taskViewModel::onUsageStatsPermissionResult,
                onExactAlarmPermissionResult = taskViewModel::onExactAlarmPermissionResult,
                onSelectTab              = taskViewModel::selectTab,
                onTaskTap                = { task ->
                    when {
                        // Before the gps check: shopping tasks also carry verificationType "gps"
                        task.taskType == "shopping" -> navController.navigate("shopping-task/${task.id}")
                        task.verificationType == "gps" -> navController.navigate("task/${task.id}")
                        task.verificationType == "duration" -> navController.navigate("duration-task/${task.id}")
                        task.verificationType == "meditation" -> navController.navigate("meditation-task/${task.id}")
                        task.taskType == "routine" || task.taskType == "workout" ->
                            navController.navigate("routine-task/${task.id}")
                        task.taskType == "journal" -> navController.navigate("journal-task/${task.id}")
                        task.taskType == "report" -> navController.navigate("report-task/${task.id}")
                        else -> taskViewModel.requestComplete(task)
                    }
                },
                onConfirmComplete        = taskViewModel::confirmComplete,
                onDismissComplete        = taskViewModel::dismissComplete,
                onSignOut                = taskViewModel::signOut,
                onNavigateToDebug        = { navController.navigate(Routes.DEBUG) },
                onNavigateToRules        = { navController.navigate(Routes.BLOCKING_RULES) },
                onNavigateToHomeLocation = { navController.navigate(Routes.HOME_LOCATION) },
                onNavigateToJournalHistory = { navController.navigate(Routes.JOURNAL_HISTORY) },
                onNavigateToContemplations = { navController.navigate(Routes.CONTEMPLATIONS) },
                onNavigateToReports = { navController.navigate(Routes.REPORTS) },
                onCreateTask             = { navController.navigate("task/editor") },
                onEditTask               = { task -> navController.navigate("task/editor?taskId=${task.id}") },
            )
        }
        composable(
            route = Routes.GPS_TASK,
            arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
        ) {
            val gpsViewModel: GpsTaskViewModel = hiltViewModel()
            val task       by gpsViewModel.task.collectAsStateWithLifecycle()
            val gpsConfig  by gpsViewModel.gpsConfig.collectAsStateWithLifecycle()
            val isTracking by gpsViewModel.isTracking.collectAsStateWithLifecycle()

            val currentTask = task
            val currentConfig = gpsConfig
            if (currentTask != null && currentConfig != null) {
                GpsTaskScreen(
                    task = currentTask,
                    gpsConfig = currentConfig,
                    isTracking = isTracking,
                    onBack = { navController.popBackStack() },
                )
            }
        }
        composable(
            route = Routes.DURATION_TASK,
            arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
        ) {
            val durationViewModel: DurationTaskViewModel = hiltViewModel()
            val task           by durationViewModel.task.collectAsStateWithLifecycle()
            val durationConfig by durationViewModel.durationConfig.collectAsStateWithLifecycle()
            val timerState     by durationViewModel.timerState.collectAsStateWithLifecycle()

            val currentTask = task
            val currentConfig = durationConfig
            if (currentTask != null && currentConfig != null) {
                DurationTaskScreen(
                    task = currentTask,
                    durationConfig = currentConfig,
                    timerState = timerState,
                    onStart = durationViewModel::startTimer,
                    onPause = durationViewModel::pauseTimer,
                    onResume = durationViewModel::resumeTimer,
                    onCancel = {
                        durationViewModel.cancelTimer()
                        navController.popBackStack()
                    },
                    onBack = { navController.popBackStack() },
                )
            } else {
                LoadingScreen(onBack = { navController.popBackStack() })
            }
        }
        composable(
            route = Routes.MEDITATION_TASK,
            arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
        ) {
            val meditationViewModel: MeditationTaskViewModel = hiltViewModel()
            val task             by meditationViewModel.task.collectAsStateWithLifecycle()
            val meditationConfig by meditationViewModel.meditationConfig.collectAsStateWithLifecycle()
            val timerState       by meditationViewModel.timerState.collectAsStateWithLifecycle()
            val todaysSession    by meditationViewModel.todaysSession.collectAsStateWithLifecycle()

            val currentTask = task
            val currentConfig = meditationConfig
            if (currentTask != null && currentConfig != null) {
                MeditationTaskScreen(
                    task = currentTask,
                    meditationConfig = currentConfig,
                    timerState = timerState,
                    todaysSession = todaysSession,
                    onStartInApp = meditationViewModel::startInAppTimer,
                    onStartShortSit = meditationViewModel::startShortSit,
                    onStartCompanion = meditationViewModel::startCompanionTracking,
                    onPause = meditationViewModel::pauseTimer,
                    onResume = meditationViewModel::resumeTimer,
                    onFinishEarly = meditationViewModel::finishEarly,
                    onCancel = {
                        meditationViewModel.cancelTimer()
                        navController.popBackStack()
                    },
                    onBack = { navController.popBackStack() },
                )
            } else {
                LoadingScreen(onBack = { navController.popBackStack() })
            }
        }
        composable(
            route = Routes.ROUTINE_TASK,
            arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
        ) {
            val routineViewModel: RoutineTaskViewModel = hiltViewModel()
            val task       by routineViewModel.task.collectAsStateWithLifecycle()
            val steps      by routineViewModel.steps.collectAsStateWithLifecycle()
            val stepStates by routineViewModel.stepStates.collectAsStateWithLifecycle()
            val supersetRounds by routineViewModel.supersetRounds.collectAsStateWithLifecycle()
            val isCompleting by routineViewModel.isCompleting.collectAsStateWithLifecycle()
            val allStepsCompleted by routineViewModel.allStepsCompleted.collectAsStateWithLifecycle()
            val isAutoRoutine by routineViewModel.isAutoRoutine.collectAsStateWithLifecycle()
            val autoProgress by routineViewModel.autoProgress.collectAsStateWithLifecycle()
            val reportSteps by routineViewModel.reportSteps.collectAsStateWithLifecycle()
            val reportSubmissions by routineViewModel.reportSubmissions.collectAsStateWithLifecycle()
            val submittingReportSteps by routineViewModel.submittingReportSteps.collectAsStateWithLifecycle()

            val currentTask = task
            if (currentTask != null) {
                RoutineTaskScreen(
                    task = currentTask,
                    steps = steps,
                    stepStates = stepStates,
                    supersetRounds = supersetRounds,
                    isCompleting = isCompleting,
                    allStepsCompleted = allStepsCompleted,
                    isAutoRoutine = isAutoRoutine,
                    autoProgress = autoProgress,
                    onToggleCheckbox = routineViewModel::toggleCheckbox,
                    onUpdateSet = routineViewModel::updateSet,
                    onCompleteCurrentSet = routineViewModel::completeCurrentSet,
                    onGoToSet = routineViewModel::goToSet,
                    onAddSet = routineViewModel::addSet,
                    onRemoveSet = routineViewModel::removeSet,
                    onStartTimer = routineViewModel::startStepTimer,
                    onStopTimer = routineViewModel::stopStepTimer,
                    onAdvanceRound = routineViewModel::advanceRound,
                    onGoToRound = routineViewModel::goToRound,
                    onAddStep = routineViewModel::addStep,
                    onRemoveStep = routineViewModel::removeStep,
                    onStartAutoRoutine = routineViewModel::startAutoRoutine,
                    onStopAutoRoutine = routineViewModel::stopAutoRoutine,
                    reportSteps = reportSteps,
                    reportSubmissions = reportSubmissions,
                    submittingReportSteps = submittingReportSteps,
                    onSubmitReport = routineViewModel::submitReportStep,
                    onFinish = {
                        routineViewModel.finishRoutine {
                            navController.popBackStack()
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            } else {
                LoadingScreen(onBack = { navController.popBackStack() })
            }
        }
        composable(
            route = Routes.JOURNAL_TASK,
            arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
        ) {
            val journalViewModel: JournalTaskViewModel = hiltViewModel()
            val task        by journalViewModel.task.collectAsStateWithLifecycle()
            val text        by journalViewModel.text.collectAsStateWithLifecycle()
            val isSubmitting by journalViewModel.isSubmitting.collectAsStateWithLifecycle()

            val currentTask = task
            if (currentTask != null) {
                JournalTaskScreen(
                    task = currentTask,
                    text = text,
                    isSubmitting = isSubmitting,
                    onTextChange = journalViewModel::updateText,
                    onSubmit = { journalViewModel.submit { navController.popBackStack() } },
                    onBack = { navController.popBackStack() },
                )
            } else {
                LoadingScreen(onBack = { navController.popBackStack() })
            }
        }
        composable(
            route = Routes.SHOPPING_TASK,
            arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
        ) {
            val shoppingViewModel: ShoppingListViewModel = hiltViewModel()
            val task      by shoppingViewModel.task.collectAsStateWithLifecycle()
            val openItems by shoppingViewModel.openItems.collectAsStateWithLifecycle()

            val currentTask = task
            if (currentTask != null) {
                ShoppingListScreen(
                    task = currentTask,
                    openItems = openItems,
                    onAddItem = shoppingViewModel::addItem,
                    onCheckOff = shoppingViewModel::checkOff,
                    onUndoCheckOff = shoppingViewModel::undoCheckOff,
                    onDeleteItem = shoppingViewModel::deleteItem,
                    onEditTask = { navController.navigate("task/editor?taskId=${currentTask.id}") },
                    onBack = { navController.popBackStack() },
                )
            } else {
                LoadingScreen(onBack = { navController.popBackStack() })
            }
        }
        composable(
            route = Routes.REPORT_TASK,
            arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
        ) {
            val reportViewModel: ReportTaskViewModel = hiltViewModel()
            val task             by reportViewModel.task.collectAsStateWithLifecycle()
            val report           by reportViewModel.report.collectAsStateWithLifecycle()
            val todaysSubmission by reportViewModel.todaysSubmission.collectAsStateWithLifecycle()
            val isSubmitting     by reportViewModel.isSubmitting.collectAsStateWithLifecycle()

            val currentTask = task
            if (currentTask != null) {
                ReportTaskScreen(
                    task = currentTask,
                    report = report,
                    todaysSubmission = todaysSubmission,
                    isSubmitting = isSubmitting,
                    onSubmit = { answers, note ->
                        reportViewModel.submit(answers, note) { navController.popBackStack() }
                    },
                    onBack = { navController.popBackStack() },
                )
            } else {
                LoadingScreen(onBack = { navController.popBackStack() })
            }
        }
        composable(Routes.REPORTS) {
            val viewModel: ReportsViewModel = hiltViewModel()
            val reports  by viewModel.reports.collectAsStateWithLifecycle()
            val metrics  by viewModel.metrics.collectAsStateWithLifecycle()
            val starters by viewModel.availableStarters.collectAsStateWithLifecycle()
            ReportsScreen(
                reports = reports,
                metrics = metrics,
                starters = starters,
                onOpenReport = { id -> navController.navigate("reports/$id") },
                onOpenMetric = { id -> navController.navigate("reports/metric/$id") },
                onAddStarter = viewModel::addStarter,
                onCreateReport = { viewModel.createReport { id -> navController.navigate("reports/$id") } },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.REPORT_EDITOR,
            arguments = listOf(navArgument("reportId") { type = NavType.StringType }),
        ) {
            val viewModel: ReportEditorViewModel = hiltViewModel()
            val form           by viewModel.form.collectAsStateWithLifecycle()
            val report         by viewModel.report.collectAsStateWithLifecycle()
            val addableMetrics by viewModel.addableMetrics.collectAsStateWithLifecycle()
            ReportEditorScreen(
                form = form,
                report = report,
                addableMetrics = addableMetrics,
                onSetName = viewModel::setName,
                onSetIncludeNote = viewModel::setIncludeNote,
                onSave = { viewModel.save { navController.popBackStack() } },
                onAddMetric = viewModel::addMetric,
                onCreateMetric = { viewModel.createMetric { id -> navController.navigate("reports/metric/$id") } },
                onOpenMetric = { id -> navController.navigate("reports/metric/$id") },
                onRemoveMetric = viewModel::removeMetric,
                onMoveMetric = viewModel::moveMetric,
                onDelete = { viewModel.deleteReport { navController.popBackStack() } },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.METRIC_EDITOR,
            arguments = listOf(navArgument("metricId") { type = NavType.StringType }),
        ) {
            val viewModel: MetricEditorViewModel = hiltViewModel()
            val form   by viewModel.form.collectAsStateWithLifecycle()
            val usedIn by viewModel.usedIn.collectAsStateWithLifecycle()
            MetricEditorScreen(
                form = form,
                usedIn = usedIn,
                onUpdate = viewModel::update,
                onSave = { viewModel.save { navController.popBackStack() } },
                onDelete = { viewModel.deleteMetric { navController.popBackStack() } },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.JOURNAL_HISTORY) {
            val journalHistoryViewModel: JournalHistoryViewModel = hiltViewModel()
            val entries by journalHistoryViewModel.entries.collectAsStateWithLifecycle()
            JournalHistoryScreen(
                entries = entries,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.TASK_EDITOR,
            arguments = listOf(
                navArgument("taskId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            val viewModel: TaskEditorViewModel = hiltViewModel()
            val editorState by viewModel.state.collectAsStateWithLifecycle()
            val editorInstalledApps by viewModel.installedApps.collectAsStateWithLifecycle()
            TaskEditorScreen(
                state = editorState,
                installedApps = editorInstalledApps,
                onUpdateTitle = viewModel::updateTitle,
                onUpdateDescription = viewModel::updateDescription,
                onSetTaskType = viewModel::setTaskType,
                onSetVerificationType = viewModel::setVerificationType,
                onSetDurationSeconds = viewModel::setDurationSeconds,
                onSetLatitude = viewModel::setLatitude,
                onSetLongitude = viewModel::setLongitude,
                onSetLocation = viewModel::setLocation,
                onSetRadiusMeters = viewModel::setRadiusMeters,
                onSetGpsMode = viewModel::setGpsMode,
                onSetGpsMinDurationM = viewModel::setGpsMinDurationM,
                onSetMeditationSeconds = viewModel::setMeditationSeconds,
                onSetShortSitSeconds = viewModel::setShortSitSeconds,
                onToggleCompanionApp = viewModel::toggleCompanionApp,
                onToggleNotifyDay = viewModel::toggleNotifyDay,
                onSetNotifyStart = viewModel::setNotifyStart,
                onSetNotifyEnd = viewModel::setNotifyEnd,
                onAddRoutineStep = viewModel::addRoutineStep,
                onRemoveRoutineStep = viewModel::removeRoutineStep,
                onUpdateRoutineStep = viewModel::updateRoutineStep,
                onCreateSupersetGroup = viewModel::createSupersetGroup,
                onRemoveSupersetGroup = viewModel::removeSupersetGroup,
                onSetReportId = viewModel::setReportId,
                onSetRecurrenceType = viewModel::setRecurrenceType,
                onToggleWeeklyDay = viewModel::toggleWeeklyDay,
                onSetBlockingCondition = viewModel::setBlockingCondition,
                onSetHomeOnlyBlocking = viewModel::setHomeOnlyBlocking,
                onToggleBlockingDay = viewModel::toggleBlockingDay,
                onSetAvailableFrom = viewModel::setAvailableFrom,
                onSetDueAt = viewModel::setDueAt,
                onNextStep = viewModel::nextStep,
                onPrevStep = viewModel::prevStep,
                onSave = { viewModel.save { navController.popBackStack() } },
                onClearError = viewModel::clearError,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.BLOCKING_RULES) {
            val viewModel: BlockingRuleListViewModel = hiltViewModel()
            val rules by viewModel.rules.collectAsStateWithLifecycle()
            BlockingRuleListScreen(
                rules = rules,
                onRuleTap = { ruleId ->
                    navController.navigate("blocking/editor/$ruleId")
                },
                onCreateRule = {
                    navController.navigate("blocking/editor/new")
                },
                onDeleteRule = viewModel::deleteRule,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.BLOCKING_RULE_EDITOR,
            arguments = listOf(navArgument("ruleId") { type = NavType.StringType }),
        ) {
            val viewModel: BlockingRuleEditorViewModel = hiltViewModel()
            val editorState by viewModel.state.collectAsStateWithLifecycle()
            val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
            val tasks by viewModel.tasks.collectAsStateWithLifecycle()
            BlockingRuleEditorScreen(
                state = editorState,
                installedApps = installedApps,
                tasks = tasks,
                onUpdateName = viewModel::updateName,
                onToggleApp = viewModel::toggleApp,
                onAddDomain = viewModel::addDomain,
                onRemoveDomain = viewModel::removeDomain,
                onToggleConditionTask = viewModel::toggleConditionTask,
                onSetConditionLogic = viewModel::setConditionLogic,
                onSave = { viewModel.save { navController.popBackStack() } },
                onCancelPendingChanges = viewModel::cancelPendingChanges,
                onClearError = viewModel::clearError,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.HOME_LOCATION) {
            val viewModel: HomeLocationViewModel = hiltViewModel()
            val homeState by viewModel.state.collectAsStateWithLifecycle()
            HomeLocationScreen(
                state = homeState,
                onSetLatitude = viewModel::setLatitude,
                onSetLongitude = viewModel::setLongitude,
                onSetRadius = viewModel::setRadius,
                onUseCurrentLocation = viewModel::useCurrentLocation,
                onSave = viewModel::save,
                onClear = viewModel::clear,
                onClearError = viewModel::clearError,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.CONTEMPLATIONS) {
            val viewModel: ContemplationSettingsViewModel = hiltViewModel()
            val form                  by viewModel.form.collectAsStateWithLifecycle()
            val decks                 by viewModel.decks.collectAsStateWithLifecycle()
            val availableLibraryDecks by viewModel.availableLibraryDecks.collectAsStateWithLifecycle()
            val recentDeliveries      by viewModel.recentDeliveries.collectAsStateWithLifecycle()
            ContemplationSettingsScreen(
                form = form,
                decks = decks,
                availableLibraryDecks = availableLibraryDecks,
                recentDeliveries = recentDeliveries,
                onSetEnabled = viewModel::setEnabled,
                onSetPerDay = viewModel::setPerDay,
                onSetWindowStart = viewModel::setWindowStart,
                onSetWindowEnd = viewModel::setWindowEnd,
                onSaveSchedule = viewModel::saveSchedule,
                onClearError = viewModel::clearError,
                onSendNow = viewModel::sendNow,
                onAddFromLibrary = viewModel::addFromLibrary,
                onSetDeckEnabled = viewModel::setDeckEnabled,
                onOpenDeck = { deckId -> navController.navigate("settings/contemplations/deck/$deckId") },
                onCreateDeck = {
                    viewModel.createDeck { deckId ->
                        navController.navigate("settings/contemplations/deck/$deckId")
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.CONTEMPLATION_DECK,
            arguments = listOf(navArgument("deckId") { type = NavType.StringType }),
        ) {
            val viewModel: ContemplationDeckViewModel = hiltViewModel()
            val form  by viewModel.form.collectAsStateWithLifecycle()
            val items by viewModel.items.collectAsStateWithLifecycle()
            ContemplationDeckScreen(
                form = form,
                items = items,
                onUpdateForm = viewModel::update,
                onSave = { viewModel.save { navController.popBackStack() } },
                onDeleteDeck = { viewModel.deleteDeck { navController.popBackStack() } },
                onSaveItem = viewModel::saveItem,
                onDeleteItem = viewModel::deleteItem,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.DEBUG) {
            val debugViewModel: DebugViewModel = hiltViewModel()
            val logs by debugViewModel.logs.collectAsStateWithLifecycle()
            val selectedCategory by debugViewModel.selectedCategory.collectAsStateWithLifecycle()
            DebugScreen(
                logs = logs,
                selectedCategory = selectedCategory,
                onCategorySelected = debugViewModel::setCategory,
                onClearLogs = debugViewModel::clearLogs,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoadingScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}
