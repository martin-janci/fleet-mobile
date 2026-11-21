package dev.claudefleet.mobile.ui

import androidx.compose.material3.Button
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.SearchHit
import dev.claudefleet.mobile.model.SessionFacetId
import dev.claudefleet.mobile.model.facetSentence
import dev.claudefleet.mobile.ui.theme.FleetIcons
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.semantics
import dev.claudefleet.mobile.ui.kit.MarkMotion
import dev.claudefleet.mobile.ui.kit.OrbitMarkLoader
import dev.claudefleet.mobile.ui.kit.rememberLoaderVisible
import dev.claudefleet.mobile.ui.kit.DotWave
import dev.claudefleet.mobile.ui.kit.LocalHubReconnect
import dev.claudefleet.mobile.ui.kit.PhoneConnection
import dev.claudefleet.mobile.ui.kit.ReconnectingPanel
import dev.claudefleet.mobile.ui.kit.rememberPhoneConnection
import androidx.compose.ui.text.style.TextAlign

/**
 * Everything the fleet list reports.
 *
 * A data class rather than eighteen parameters, the way [TodayHandlers] and
 * [SessionFiltersHandlers] already are: the screen grew from three controls to
 * a sheet's worth, and a call site of eighteen positional lambdas is one
 * transposition away from wiring *clear all* to *refresh*.
 */
data class SessionsHandlers(
    val onOpenSession: (Long) -> Unit = {},
    val onToggleNeedsAttention: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    /** Move to the next view: project → work → urgency. Work is skipped without the work graph. */
    val onCycleGroupMode: () -> Unit = {},
    /** Show the search field, or hide it (hiding clears the query). */
    val onToggleSearch: () -> Unit = {},
    val onSetQuery: (String) -> Unit = {},
    val onOpenFilters: () -> Unit = {},
    /** Fold a host's rows away, or unfold them. The heading and its count stay. */
    val onToggleHost: (String) -> Unit = {},
    /**
     * Every filter off, the host one included — which is why this is one
     * handler and not [SessionsViewModel.clearFilters]: the host filter is the
     * navigator's (`Screen.Sessions.hostAlias`), so clearing everything means
     * calling both, and the screen must not be the place that remembers to.
     */
    val onClearAll: () -> Unit = {},
    /**
     * One chip's ✕ in the strip. The host chip goes through the navigator as
     * well, for the reason [onClearAll] does.
     */
    val onClearFacet: (SessionFacetId) -> Unit = {},
    /** The archived row at the end of the list: *Show archived* / *Hide archived*. */
    val onSetShowArchived: (Boolean) -> Unit = {},
    /**
     * Open the New session form. Null hides the button — a `readonly` pairing,
     * which the hub would refuse `new_session` anyway.
     */
    val onNewSession: (() -> Unit)? = null,
    /** Open the Tickets sheet. Null hides the action — a hub without the work graph. */
    val onOpenTickets: (() -> Unit)? = null,
    /** Open the Today sheet. Null hides the action — a hub without `work today`. */
    val onOpenToday: (() -> Unit)? = null,
    /** Open the Missions sheet. Null hides the action — a hub without missions. */
    val onOpenMissions: (() -> Unit)? = null,
    /**
     * Open the fleet's agent (the desktop's ✦). Null hides the action — a hub
     * without `ensure_operator`, or a `readonly` pairing.
     */
    val onOpenAgent: (() -> Unit)? = null,
    val onDismissAgentError: () -> Unit = {},
    /** Multi-select: pick or unpick a row, drop the selection, act on it, close its outcome. */
    val onToggleSelect: (Long) -> Unit = {},
    val onClearSelection: () -> Unit = {},
    /** *Select* in the header's overflow: select mode with nothing picked yet. */
    val onStartSelect: () -> Unit = {},
    val onBulkSend: (String) -> Unit = {},
    /** Kill the picked sessions the confirmation named, by id. */
    val onBulkKill: (Set<Long>) -> Unit = {},
    val onDismissBulkOutcome: () -> Unit = {},
    /** Search hits beyond sessions: a host's sessions, a new session in a project, a ticket lookup. */
    val onSearchHost: (String) -> Unit = {},
    val onSearchProject: (Long) -> Unit = {},
    val onSearchTicket: (String) -> Unit = {},
    /** A hit of the hub's full-text index: its task, else its session (Find on the query). */
    val onSearchFound: (SearchHit) -> Unit = {},
)

/**
 * What the filters are keeping from you, when it is something that wants a
 * person.
 *
 * This is the price of remembering filters between launches, paid in full and
 * in the open. `SessionsViewModel.setGroupMode`'s KDoc states the objection —
 * restoring a filter on launch *"can make a busy fleet look quiet"* — and this
 * is the answer to it: the fleet may look quiet, but it cannot lie about being
 * quiet, because the app says so above the list.
 *
 * Deliberately narrow. It counts only [SessionsUiState.hiddenAttention], the
 * rows that are blocked or stuck; how many rows the filters hide in general is
 * already on screen as *shown / total*. A banner that fired on every ordinary
 * narrowing would be learnt and then ignored, which is the one thing this must
 * not become.
 *
 * `errorContainer` rather than a softer tone: an agent that has stopped and is
 * waiting for an answer nobody can see is a failure of the app's whole
 * purpose, not a note about the view.
 */
@Composable
internal fun HiddenAttentionBanner(count: Int, onClearAll: () -> Unit) {
    if (count <= 0) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(FleetIcons.Warning, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(
                text = if (count == 1) {
                    "1 session is waiting on you and is hidden by these filters."
                } else {
                    "$count sessions are waiting on you and are hidden by these filters."
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClearAll) { Text("Clear") }
        }
    }
}

/** Over the routine groups: how many runs they hold. */
@Composable
internal fun RoutinesHeading(runs: Int) {
    Text(
        text = "Routines · ${routineRunsWord(runs)}",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** One routine's runs, folded to a line: its name, how many, how many need you. */
@Composable
internal fun RoutineGroupHeader(group: RoutineGroup, open: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp)
            .clearAndSetSemantics { contentDescription = routineGroupDescription(group, open) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                FleetIcons.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(18.dp).rotate(if (open) -90f else 180f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(text = group.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (group.attentionCount > 0) {
                Badge(containerColor = MaterialTheme.colorScheme.error) { Text("${group.attentionCount} need you") }
            }
            Text(
                text = routineRunsWord(group.sessions.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "1 run", "3 runs". */
internal fun routineRunsWord(n: Int): String = if (n == 1) "1 run" else "$n runs"

/** What a screen reader hears for a routine's folded line. */
internal fun routineGroupDescription(group: RoutineGroup, open: Boolean): String = buildList {
    add("Routine ${group.name}")
    add(routineRunsWord(group.sessions.size))
    if (group.attentionCount > 0) add("${group.attentionCount} need you")
    add(if (open) "tap to fold" else "tap to show its runs")
}.joinToString(", ")

/** What a list that has never loaded draws while the hub is not there yet (14.12). */
internal sealed interface ColdStart {
    /** The first attempt: a quiet loader, after `loader-delay`. */
    data object Connecting : ColdStart

    /** A later attempt, still inside `hub-lost-after`: the Gravity well. */
    data object Reconnecting : ColdStart

    /** Past `hub-lost-after`, or refused: no spinner, the reason and Retry. */
    data class Unreachable(val reason: String?) : ColdStart
}

@Composable
private fun ColdStartBody(body: ColdStart, onRetry: (() -> Unit)?) {
    when (body) {
        ColdStart.Connecting -> if (rememberLoaderVisible(true)) {
            DotWave()
            Text(
                text = "Connecting to the hub…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ColdStart.Reconnecting -> ReconnectingPanel(hub = "the hub", modifier = Modifier.heightIn(min = 240.dp))
        is ColdStart.Unreachable -> {
            val reconnect = LocalHubReconnect.current
            Text(
                text = "Can't reach the hub",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body.reason ?: "The phone keeps trying. Check the network and the hub address.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = { reconnect(); onRetry?.invoke() }) { Text("Retry") }
        }
    }
}

internal fun coldStartBody(connection: PhoneConnection): ColdStart = when (connection) {
    PhoneConnection.Live -> ColdStart.Connecting
    is PhoneConnection.Reconnecting -> if (connection.attempt <= 1) ColdStart.Connecting else ColdStart.Reconnecting
    is PhoneConnection.Offline -> ColdStart.Unreachable(connection.reason)
    is PhoneConnection.Refused -> ColdStart.Unreachable(connection.reason)
}

/**
 * What the list says when it has nothing to draw.
 *
 * It used to be a `when` over three filters, and it already lied: *My work*
 * won the branch whenever it was on, so "No session is on your tickets" was
 * printed over a list that a host filter was also emptying, and clearing the
 * one the message named left the screen just as blank. Four filters would have
 * made that `when` sixteen branches and every added filter doubles it — a
 * message that is wrong in a way a person cannot act on.
 *
 * So it says one true thing instead, names every filter that is on, and puts
 * the way out under it. The only special case left is the one that is not
 * about filters at all: an empty fleet, where the useful sentence is how to
 * start a session rather than what to clear.
 */
@Composable
internal fun EmptyFleet(
    state: SessionsUiState,
    /** Null for a readonly pairing, which can only point elsewhere. */
    onNewSession: (() -> Unit)?,
    onClearAll: () -> Unit,
    onSetShowArchived: (Boolean) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    /** The screen's refresh, run with the reconnect when "Can't reach the hub" is retried. */
    onRetry: (() -> Unit)? = null,
) {
    val facets = state.facets
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            when {
                // Not "no sessions": nothing has been listed yet — what a
                // person sees opening the app from a notification. Nothing
                // for `loader-delay`, then the wait in words; past
                // `hub-lost-after` it stops spinning and says so (14.12).
                state.connecting -> ColdStartBody(coldStartBody(rememberPhoneConnection(state.status)), onRetry)
                facets.isNotEmpty() -> {
                    Text(
                        text = emptySessionsSentence(facets.let(::facetSentence)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onClearAll) { Text("Clear filters") }
                }
                // Nothing narrows, and every session there is is archived:
                // the row under this says how many and shows them.
                state.archivedHidden > 0 -> Text(
                    text = "No sessions to show.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.status is ConnectionStatus.Refused -> Text(
                    text = "This app and the hub cannot talk to each other. Update the app or the hub.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The phone can start one itself: say so, with the button.
                onNewSession != null -> {
                    Text(
                        text = "No sessions yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onNewSession) { Text("New session") }
                }
                else -> Text(
                    text = "No sessions. Start one from the desktop app or the terminal.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Under an empty list the archived row belongs here, in view,
            // rather than below a message that fills the screen.
            if (state.archivedRow) {
                ArchivedRow(
                    hidden = state.archivedHidden,
                    showing = state.filters.showArchived,
                    noun = "sessions",
                    attention = state.archivedAttention,
                    onSetShown = onSetShowArchived,
                )
            }
        }
    }
}

/** The empty state's sentence, the desktop's words: "No sessions match Host: x, Last 1d." */
internal fun emptySessionsSentence(facets: String): String = "No sessions match $facets."

/** How a search hit's matched words are drawn: the conversation's Find colour behind them. */
@Composable
internal fun searchMark(): SpanStyle {
    val color = MaterialTheme.colorScheme.tertiaryContainer
    val on = MaterialTheme.colorScheme.onTertiaryContainer
    return remember(color, on) { SpanStyle(background = color, color = on) }
}

/** "1 session", "3 sessions". */
internal fun sessionsWord(n: Int): String = if (n == 1) "1 session" else "$n sessions"
