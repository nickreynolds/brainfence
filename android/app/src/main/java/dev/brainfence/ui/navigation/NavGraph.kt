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

private object Routes {
    const val SIGN_IN = "auth/sign-in"
    const val SIGN_UP = "auth/sign-up"
    const val HOME    = "home"
}

@Composable
fun BrainfenceNavGraph(
    navController: NavHostController = rememberNavController(),
) {
    val viewModel: AuthViewModel = hiltViewModel()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val uiState   by viewModel.uiState.collectAsStateWithLifecycle()

    // Redirect based on auth state
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
                uiState           = uiState,
                onSignIn          = viewModel::signIn,
                onNavigateToSignUp = { navController.navigate(Routes.SIGN_UP) },
            )
        }
        composable(Routes.SIGN_UP) {
            SignUpScreen(
                uiState            = uiState,
                onSignUp           = viewModel::signUp,
                onNavigateToSignIn = { navController.popBackStack() },
            )
        }
        composable(Routes.HOME) {
            // Task list added in ANDROID-004
        }
    }
}
