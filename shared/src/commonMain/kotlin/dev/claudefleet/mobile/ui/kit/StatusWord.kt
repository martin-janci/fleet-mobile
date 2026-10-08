package dev.claudefleet.mobile.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.OrbitColors
import dev.claudefleet.mobile.ui.theme.StatusTone

/**
 * The manual's six status words, the only words a row, chip, banner or
 * notification uses for a session's state. Each has one colour; red is Failed
 * only and amber is Needs you only.
 */
enum class StatusWord(val label: String) {
    NEEDS_YOU("Needs you"),
    WORKING("Working"),
    FAILED("Failed"),
    DONE("Done"),
    PAUSED("Paused"),
    IDLE("Idle");

    /** The colour of the dot and of the word when it leads a row's second line. */
    fun color(o: OrbitColors): Color = when (this) {
        NEEDS_YOU -> o.statusWaiting
        WORKING -> o.statusWorking
        FAILED -> o.statusFailed
        DONE -> o.statusDone
        PAUSED, IDLE -> o.statusIdle
    }

    companion object {
        /**
         * The word for one of the phone's tones. Blocked reads Needs you (its
         * reason goes on the line after it); stuck is Failed, because both are
         * a terminal someone has to go and fix; a stopped session is Idle, as
         * the manual files paused, stopped and queued under the idle colour.
         * Paused is for a session the fleet paused (an account at its limit,
         * automation off), which the hub says from contract 11 on, so no tone
         * maps to it yet. Unknown has no word: the row shows a dash.
         */
        fun of(tone: StatusTone): StatusWord? = when (tone) {
            StatusTone.BLOCKED -> NEEDS_YOU
            StatusTone.WORKING -> WORKING
            StatusTone.STUCK, StatusTone.FAILED -> FAILED
            StatusTone.COMPLETED -> DONE
            StatusTone.IDLE, StatusTone.STOPPED -> IDLE
            StatusTone.UNKNOWN -> null
        }
    }
}

/**
 * The status dot. Never the only carrier of the state: it always has a
 * label for screen readers, and the row beside it says the word.
 */
@Composable
fun OrbitDot(word: StatusWord?, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val color = word?.color(Fleet.colors) ?: Fleet.colors.statusIdle
    Box(
        modifier = modifier
            .size(size)
            .background(color, CircleShape)
            .semantics { contentDescription = word?.label ?: "Unknown" },
    )
}
