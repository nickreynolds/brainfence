package dev.brainfence.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.brainfence.data.auth.AuthState
import dev.brainfence.ui.auth.AuthViewModel
import dev.brainfence.ui.auth.SignInScreen
import dev.brainfence.ui.auth.SignUpScreen
import dev.brainfence.ui.tasks.TaskListScreen
import dev.brainfence.ui.tasks.TaskListViewModel

private object Routes {
    const val SIGN_IN = "auth/sign-in"
    const val SIGN_UP = "auth/sign-up"
    const val HOME    = "home"
}

@Composable
fun BrainfenceNavGraph(
    navController: NavHostController = rememberNavController(),
) {
    val authViewModel: AuthViewModel = hiltViewModel()
    val authState by authViewModel.authState.collectAsStateWithLifecycle()
    val authUiState by authViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(authState) {
        when (authState) {
            is AuthState.SignedIn  -> navController.navigate(Routes.HOME) {
                popUpTo(Routes.SIGN_IN) { inclusive = true }
            }
            is AuthState.SignedOut -> navController.navigate(Routes.SIGN_IN) {
                popUpTo(Routes.HOME) { inclusive = true }
            }
            is AuthState.Loading   -> Unit
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
        composable(Routes.HOME) {
            val taskViewModel: TaskListViewModel = hiltViewModel()
            val tasks       by taskViewModel.tasks.collectAsStateWithLifecycle()
            val pendingTask by taskViewModel.pendingTask.collectAsStateWithLifecycle()
            TaskListScreen(
                tasks             = tasks,
                pendingTask       = pendingTask,
                onTaskTap         = taskViewModel::requestComplete,
                onConfirmComplete = taskViewModel::confirmComplete,
                onDismissComplete = taskViewModel::dismissComplete,
                onSignOut         = taskViewModel::signOut,
            )
        }
    }
}
