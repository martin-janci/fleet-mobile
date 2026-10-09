package dev.claudefleet.mobile.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import dev.claudefleet.mobile.ui.kit.BottomSheet
import dev.claudefleet.mobile.ui.kit.SheetAction

/**
 * Closing a wizard asks only if something was typed (board MobileWizards'
 * rules): Keep editing is the safe way out, Discard closes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscardSheet(onKeep: () -> Unit, onDiscard: () -> Unit) {
    BottomSheet(
        title = "Discard what you typed?",
        meta = "Closing drops the answers in this wizard.",
        onDismiss = onKeep,
        cancelLabel = "Keep editing",
        primary = SheetAction("Discard") { onDiscard() },
    ) {}
}

/** Whether leaving New session from its first step would drop something typed. */
internal fun newSessionTyped(s: NewSessionUiState): Boolean =
    s.branch.isNotBlank() || s.baseBranch.isNotBlank() || s.friendlyName.isNotBlank()

/** Whether closing Add a project would drop something typed. */
internal fun addProjectTyped(url: String, folder: String, owner: String, repo: String): Boolean =
    listOf(url, folder, owner, repo).any { it.isNotBlank() }

/** Whether closing Connect a tracker would drop something typed. */
internal fun trackerTyped(w: TrackerWizard): Boolean =
    w.secret.isNotBlank() || (w.created == null && (w.siteUrl.isNotBlank() || w.email.isNotBlank()))
