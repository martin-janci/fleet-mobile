package dev.claudefleet.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import dev.claudefleet.mobile.model.PrFilter
import dev.claudefleet.mobile.model.PullRequest
import dev.claudefleet.mobile.model.prChecksLabel
import dev.claudefleet.mobile.model.prRef
import dev.claudefleet.mobile.model.prStateLabel
import dev.claudefleet.mobile.model.relativeAgo
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.kit.BottomSheet
import dev.claudefleet.mobile.ui.kit.DotWave
import dev.claudefleet.mobile.ui.kit.PhoneRow
import dev.claudefleet.mobile.ui.kit.rememberLoaderVisible
import dev.claudefleet.mobile.ui.theme.Fleet

/** What the Pull requests sheet reports. */
data class PullRequestsHandlers(
    val onClose: () -> Unit = {},
    val onSetFilter: (PrFilter) -> Unit = {},
    val onReload: () -> Unit = {},
    /** Open the session that opened a PR. Only offered while the phone has its row. */
    val onOpenSession: (Long) -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/**
 * A PR row's second line: where it is, its branch, CI and review, when it
 * merged, and the session that opened it ("by fix-login · mac", or
 * "by fix-login (gone)" once that session is no longer on the fleet).
 */
internal fun prLine(pr: PullRequest, nowSeconds: Long, sessionLive: Boolean): String = buildList {
    add(prRef(pr))
    pr.headRef?.takeIf { it.isNotBlank() }?.let { add(it) }
    prChecksLabel(pr).takeIf { it.isNotBlank() }?.let { add(it) }
    if (pr.state.equals("MERGED", ignoreCase = true)) relativeAgo(pr.mergedAt, nowSeconds)?.let { add("merged $it") }
    pr.sessionName?.takeIf { it.isNotBlank() }?.let { name ->
        add(
            when {
                !sessionLive -> "by $name (gone)"
                !pr.hostAlias.isNullOrBlank() -> "by $name · ${pr.hostAlias}"
                else -> "by $name"
            },
        )
    }
}.joinToString(" · ")

/**
 * **Pull requests** (redesign 6.7): every PR the fleet's sessions opened,
 * Open first, with Merged, Closed and All a chip away. A row opens the PR on
 * GitHub; *Open session* opens the session that opened it while that session
 * is still on the fleet. Readonly: nothing here changes a PR.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhonePullRequestsSheet(
    state: PullRequestsUiState,
    handlers: PullRequestsHandlers,
    nowSeconds: Long,
    /** Whether the phone still has the session's row: a gone one is named, not opened. */
    sessionLive: (Long) -> Boolean,
) {
    val o = Fleet.colors
    val uri = LocalUriHandler.current
    BottomSheet(
        title = "Pull requests" + if (state.loaded && state.total > 0) " · ${state.total}" else "",
        onDismiss = handlers.onClose,
        cancelLabel = "Close",
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (f in PrFilter.entries) {
                FilterChip(selected = f == state.filter, onClick = { handlers.onSetFilter(f) }, label = { Text(f.label) })
            }
        }
        Column(modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            if (!state.connected) {
                Text((state.stale ?: "Offline") + ".", color = o.fgMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
            }
            ErrorBanner(state.error, onDismiss = handlers.onDismissError)
            if (state.loading && rememberLoaderVisible(true)) DotWave()
            if (state.loaded && state.items.isEmpty()) {
                Text(emptyPrSentence(state.filter), color = o.fgMuted, fontSize = 15.sp, modifier = Modifier.padding(vertical = 8.dp))
            }
            for (pr in state.items) {
                val live = pr.sessionId?.let(sessionLive) == true
                val merged = pr.state.equals("MERGED", ignoreCase = true)
                val sessionId = pr.sessionId
                val openPr: (() -> Unit)? = if (pr.url.isBlank()) null else {
                    { runCatching { uri.openUri(pr.url) } }
                }
                val openSession: (@Composable RowScope.() -> Unit)? = if (sessionId == null || !live) null else {
                    {
                        Text(
                            "Open session",
                            color = o.accent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .heightIn(min = 32.dp)
                                .clickable { handlers.onOpenSession(sessionId) }
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                        )
                    }
                }
                PhoneRow(
                    title = pr.title?.takeIf { it.isNotBlank() } ?: prRef(pr),
                    line = prLine(pr, nowSeconds, live),
                    lead = prStateLabel(pr),
                    leadColor = if (merged) o.statusDone else o.fgMuted,
                    separator = " · ",
                    dot = false,
                    onClick = openPr,
                    chips = openSession,
                )
            }
            if (state.loaded && state.total > state.items.size) {
                Text(
                    "Showing the newest ${state.items.size} of ${state.total}. The desktop lists them all.",
                    color = o.fgMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

/** What an empty list says, for the filter it was read with. */
internal fun emptyPrSentence(filter: PrFilter): String = when (filter) {
    PrFilter.OPEN -> "No open pull requests."
    PrFilter.MERGED -> "No merged pull requests yet."
    PrFilter.CLOSED -> "No closed pull requests."
    PrFilter.ALL -> "No pull requests yet. They appear here when a session opens one."
}
