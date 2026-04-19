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
import dev.brainfence.ui.tasks.TaskListScreen
import dev.brainfence.ui.tasks.TaskListViewModel
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
    const val DEBUG             = "debug"
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
            val tasks              by taskViewModel.tasks.collectAsStateWithLifecycle()
            val pendingTask        by taskViewModel.pendingTask.collectAsStateWithLifecycle()
            val blockingStatus     by taskViewModel.blockingStatus.collectAsStateWithLifecycle()
            val hasLocationPerm    by taskViewModel.hasLocationPermission.collectAsStateWithLifecycle()
            TaskListScreen(
                tasks                    = tasks,
                pendingTask              = pendingTask,
                blockingStatus           = blockingStatus,
                isAccessibilityEnabled   = isAccessibilityEnabled,
                hasLocationPermission    = hasLocationPerm,
                onLocationPermissionResult = taskViewModel::onLocationPermissionResult,
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
                onCreateQuickTimer       = {
                    taskViewModel.createQuickTimer { taskId ->
                        navController.navigate("duration-task/$taskId")
                    }
                },
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
            val isCompleting by routineViewModel.isCompleting.collectAsStateWithLifecycle()

            val currentTask = task
            if (currentTask != null) {
                RoutineTaskScreen(
                    task = currentTask,
                    steps = steps,
                    stepStates = stepStates,
                    isCompleting = isCompleting,
                    onToggleCheckbox = routineViewModel::toggleCheckbox,
                    onUpdateSet = routineViewModel::updateSet,
                    onAddSet = routineViewModel::addSet,
                    onRemoveSet = routineViewModel::removeSet,
                    onStartTimer = routineViewModel::startStepTimer,
                    onStopTimer = routineViewModel::stopStepTimer,
                    onFinish = {
                        routineViewModel.finishRoutine {
                            navController.popBackStack()
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
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
