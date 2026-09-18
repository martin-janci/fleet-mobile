package dev.claudefleet.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * The shared Compose entry point. Every platform host renders exactly this;
 * later tasks grow it into the Pair / Sessions / Session / Hosts / Settings
 * screens. For now it only proves the shared UI toolchain end to end.
 */
@Composable
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = greeting(), style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

/** Placeholder copy, replaced once the app has real screens. */
fun greeting(): String = "fleet-mobile"
