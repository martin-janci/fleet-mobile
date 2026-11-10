package dev.claudefleet.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long a Copy button says "Copied" before it reads Copy again. */
const val COPIED_RESET_MS: Long = 1_500

/**
 * The one "Copied" mark every Copy button shows (the label answers the tap:
 * the clipboard alone says nothing on iOS). [mark] sets it for
 * [COPIED_RESET_MS], then it resets, so a second tap later still answers.
 * [key] tells apart several buttons sharing one state (a list of commands).
 */
@Stable
class CopiedState internal constructor(private val scope: CoroutineScope) {
    private var key by mutableStateOf<Any?>(null)
    private var reset: Job? = null

    /** Any button of this state shows Copied now. */
    val copied: Boolean get() = key != null

    /** The button for [k] shows Copied now. */
    fun isCopied(k: Any): Boolean = key == k

    fun mark(k: Any = Unit) {
        key = k
        reset?.cancel()
        reset = scope.launch {
            delay(COPIED_RESET_MS)
            key = null
        }
    }
}

/** A [CopiedState], forgotten when any of [keys] changes so a mark never carries over to other text. */
@Composable
fun rememberCopied(vararg keys: Any?): CopiedState {
    val scope = rememberCoroutineScope()
    return remember(*keys) { CopiedState(scope) }
}
