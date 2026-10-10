package dev.claudefleet.mobile.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * The system's recogniser activity: it listens, shows its own UI, asks for
 * the microphone itself, and hands back what it heard. No audio permission
 * here — the app never touches the microphone. Whether a recogniser exists
 * is asked through the `<queries>` entry in the app's manifest; without it
 * Android 11+ hides every recogniser from this check.
 */
@Composable
internal actual fun rememberDictation(onText: (String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onText)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!heard.isNullOrBlank()) latest(heard)
    }
    val available = remember(context) {
        runCatching {
            context.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
        }.getOrDefault(false)
    }
    if (!available) return null
    return remember<() -> Unit>(launcher) {
        {
            val listen = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your message")
            // A recogniser uninstalled since the check is not worth a crash:
            // the field is still there to type in.
            runCatching { launcher.launch(listen) }
        }
    }
}
