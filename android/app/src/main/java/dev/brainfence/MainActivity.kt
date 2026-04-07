package dev.brainfence

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.brainfence.ui.navigation.BrainfenceNavGraph
import dev.brainfence.ui.theme.BrainfenceTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BrainfenceTheme {
                BrainfenceNavGraph()
            }
        }
    }
}
