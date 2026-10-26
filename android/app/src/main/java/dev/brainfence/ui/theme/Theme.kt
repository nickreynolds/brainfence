package dev.brainfence.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

// LightColorScheme / DarkColorScheme and the BlockingColors sets are generated
// into Color.kt from shared/tokens.json.

private val LocalBlockingColors = staticCompositionLocalOf { LightBlockingColors }

/** Colors for blocking state: blocked, unlocked, and a pending (time-locked) change. */
val MaterialTheme.blockingColors: BlockingColors
    @Composable
    @ReadOnlyComposable
    get() = LocalBlockingColors.current

@Composable
fun BrainfenceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    CompositionLocalProvider(LocalBlockingColors provides if (darkTheme) DarkBlockingColors else LightBlockingColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = Typography,
            content     = content,
        )
    }
}
