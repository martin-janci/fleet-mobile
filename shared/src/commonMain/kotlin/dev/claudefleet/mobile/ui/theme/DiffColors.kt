package dev.claudefleet.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * The one set of diff colours: a tool card's Edit, the worktree's diff and its
 * status letters all draw from here, so an added line is the same green
 * everywhere. The greens are the fleet's own "completed" tone; the reds are
 * the theme's `error`. Each foreground holds 4.5:1 against the surface it is
 * drawn on, lit or dark — the old worktree green (#2E7D32) did not on dark.
 */
@Immutable
data class DiffColors(val addedBg: Color, val addedFg: Color, val removedBg: Color, val removedFg: Color)

@Composable
fun diffColors(): DiffColors {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    return if (dark) {
        DiffColors(Color(0xFF1E4D2E).copy(alpha = 0.55f), Color(0xFF7CD292), colors.errorContainer.copy(alpha = 0.4f), colors.error)
    } else {
        DiffColors(Color(0xFFD6F0DD), Color(0xFF0F5A2A), colors.errorContainer.copy(alpha = 0.4f), colors.error)
    }
}
