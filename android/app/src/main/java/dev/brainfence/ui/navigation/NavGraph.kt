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
import dev.brainfence.ui.tasks.TaskListScreen
import dev.brainfence.ui.tasks.TaskListViewModel

private object Routes {
    const val SIGN_IN           = "auth/sign-in"
    const val SIGN_UP           = "auth/sign-up"
    const val ACCESSIBILITY_SETUP = "setup/accessibility"
    const val HOME              = "home"
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
            val tasks       by taskViewModel.tasks.collectAsStateWithLifecycle()
            val pendingTask by taskViewModel.pendingTask.collectAsStateWithLifecycle()
            TaskListScreen(
                tasks                  = tasks,
                pendingTask            = pendingTask,
                isAccessibilityEnabled = isAccessibilityEnabled,
                onTaskTap              = taskViewModel::requestComplete,
                onConfirmComplete      = taskViewModel::confirmComplete,
                onDismissComplete      = taskViewModel::dismissComplete,
                onSignOut              = taskViewModel::signOut,
            )
        }
    }
}
