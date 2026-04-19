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
import dev.brainfence.ui.setup.AccessibilitySetupScreen
import dev.brainfence.ui.debug.DebugScreen
import dev.brainfence.ui.debug.DebugViewModel
import dev.brainfence.ui.tasks.DurationTaskScreen
import dev.brainfence.ui.tasks.DurationTaskViewModel
import dev.brainfence.ui.tasks.GpsTaskScreen
import dev.brainfence.ui.tasks.GpsTaskViewModel
import dev.brainfence.ui.tasks.MeditationTaskScreen
import dev.brainfence.ui.tasks.MeditationTaskViewModel
import dev.brainfence.ui.tasks.RoutineTaskScreen
import dev.brainfence.ui.tasks.RoutineTaskViewModel
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
    const val TASK_EDITOR       = "task/editor"
    const val DEBUG             = "debug"
    const val BLOCKING_RULES    = "blocking/rules"
    const val BLOCKING_RULE_EDITOR = "blocking/editor/{ruleId}"
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

    LaunchedEffect(authState, isAccessibilityEnabled, setupSkipped) {
        when (authState) {
            is AuthState.SignedIn -> {
                if (!isAccessibilityEnabled && !setupSkipped) {
                    navController.navigate(Routes.ACCESSIBILITY_SETUP) {
                        popUpTo(Routes.SIGN_IN) { inclusive = true }
                    }
                } else {
                    navController.navigate(Routes.HOME) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
            is AuthState.SignedOut -> navController.navigate(Routes.SIGN_IN) {
                popUpTo(0) { inclusive = true }
            }
            is AuthState.Loading -> Unit
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
            TaskListScreen(
                activeTasks              = activeTasks,
                completedTasks           = completedTasks,
                upcomingTasks            = upcomingTasks,
                selectedTab              = selectedTab,
                activeRules              = activeRules,
                pendingTask              = pendingTask,
                blockingStatus           = blockingStatus,
                isAccessibilityEnabled   = isAccessibilityEnabled,
                hasLocationPermission    = hasLocationPerm,
                onLocationPermissionResult = taskViewModel::onLocationPermissionResult,
                onSelectTab              = taskViewModel::selectTab,
                onTaskTap                = { task ->
                    when {
                        task.verificationType == "gps" -> navController.navigate("task/${task.id}")
                        task.verificationType == "duration" -> navController.navigate("duration-task/${task.id}")
                        task.verificationType == "meditation" -> navController.navigate("meditation-task/${task.id}")
                        task.taskType == "routine" || task.taskType == "workout" ->
                            navController.navigate("routine-task/${task.id}")
                        else -> taskViewModel.requestComplete(task)
                    }
                },
                onConfirmComplete        = taskViewModel::confirmComplete,
                onDismissComplete        = taskViewModel::dismissComplete,
                onSignOut                = taskViewModel::signOut,
                onNavigateToDebug        = { navController.navigate(Routes.DEBUG) },
                onNavigateToRules        = { navController.navigate(Routes.BLOCKING_RULES) },
                onCreateTask             = { navController.navigate(Routes.TASK_EDITOR) },
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

            val currentTask = task
            val currentConfig = meditationConfig
            if (currentTask != null && currentConfig != null) {
                MeditationTaskScreen(
                    task = currentTask,
                    meditationConfig = currentConfig,
                    timerState = timerState,
                    onStartInApp = meditationViewModel::startInAppTimer,
                    onStartCompanion = meditationViewModel::startCompanionTracking,
                    onPause = meditationViewModel::pauseTimer,
                    onResume = meditationViewModel::resumeTimer,
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

            val currentTask = task
            if (currentTask != null) {
                RoutineTaskScreen(
                    task = currentTask,
                    steps = steps,
                    stepStates = stepStates,
                    supersetRounds = supersetRounds,
                    isCompleting = isCompleting,
                    allStepsCompleted = allStepsCompleted,
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
        composable(Routes.TASK_EDITOR) {
            val viewModel: TaskEditorViewModel = hiltViewModel()
            val editorState by viewModel.state.collectAsStateWithLifecycle()
            TaskEditorScreen(
                state = editorState,
                onUpdateTitle = viewModel::updateTitle,
                onUpdateDescription = viewModel::updateDescription,
                onSetTaskType = viewModel::setTaskType,
                onSetVerificationType = viewModel::setVerificationType,
                onSetDurationSeconds = viewModel::setDurationSeconds,
                onSetLatitude = viewModel::setLatitude,
                onSetLongitude = viewModel::setLongitude,
                onSetRadiusMeters = viewModel::setRadiusMeters,
                onSetMeditationSeconds = viewModel::setMeditationSeconds,
                onSetAllowCompanion = viewModel::setAllowCompanion,
                onAddRoutineStep = viewModel::addRoutineStep,
                onRemoveRoutineStep = viewModel::removeRoutineStep,
                onUpdateRoutineStep = viewModel::updateRoutineStep,
                onCreateSupersetGroup = viewModel::createSupersetGroup,
                onRemoveSupersetGroup = viewModel::removeSupersetGroup,
                onSetRecurrenceType = viewModel::setRecurrenceType,
                onToggleWeeklyDay = viewModel::toggleWeeklyDay,
                onSetBlockingCondition = viewModel::setBlockingCondition,
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
