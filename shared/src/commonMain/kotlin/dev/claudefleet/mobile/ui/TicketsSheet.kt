package dev.claudefleet.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.ResumeCandidate
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.model.TicketFacetId
import dev.claudefleet.mobile.model.TicketList
import dev.claudefleet.mobile.model.TicketSessionFilter
import dev.claudefleet.mobile.model.TicketSort
import dev.claudefleet.mobile.model.WorkStatusFilter
import dev.claudefleet.mobile.model.facetSentence
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.TicketCardBody
import dev.claudefleet.mobile.ui.components.WorkStatusDot
import dev.claudefleet.mobile.ui.theme.FleetIcons

/** Everything the Tickets sheet reports. */
data class TicketsHandlers(
    val onClose: () -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onSearch: () -> Unit = {},
    val onSelect: (Ticket?) -> Unit = {},
    val onOpenLive: () -> Unit = {},
    val onStartHere: () -> Unit = {},
    val onResumeHost: (String) -> Unit = {},
    /** Asks first: puts up [TicketsUiState.confirmResume]. */
    val onResume: () -> Unit = {},
    val onConfirmResume: () -> Unit = {},
    val onCancelResume: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onOpenFilters: () -> Unit = {},
    val onCloseFilters: () -> Unit = {},
    val onToggleList: (TicketList) -> Unit = {},
    val onToggleStatus: (WorkStatusFilter) -> Unit = {},
    val onToggleStatusName: (String) -> Unit = {},
    val onSetOrg: (Long?) -> Unit = {},
    val onSetTracker: (Long?) -> Unit = {},
    val onSetSession: (TicketSessionFilter?) -> Unit = {},
    /** One chip's ×, or one group back to *Any*. */
    val onClearFacet: (TicketFacetId) -> Unit = {},
    val onClearAll: () -> Unit = {},
    val onCycleSort: () -> Unit = {},
)

/**
 * The Tickets sheet: *My work*, *Current sprint*, *Recent*, and a search by
 * key or pasted URL. Tapping a ticket shows what can be done with it.
 * Ticket text is the tracker's and is drawn as plain text only.
 *
 * Filtered the way the Sessions list is: the search field narrows the lists
 * as it is typed (and still looks a key or URL up on Search), **Filters (n)**
 * opens the filter page, **Sort** is a view beside it, and a strip of
 * removable chips — "5 of 23" first — says what is on. The filter page
 * replaces the lists inside this sheet rather than stacking a second sheet
 * over it, and its button counts the result before it is pressed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketsSheet(state: TicketsUiState, handlers: TicketsHandlers) {
    state.confirmResume?.let { ResumeConfirmDialog(it, handlers) }
    ModalBottomSheet(onDismissRequest = handlers.onClose) {
        if (state.filtersOpen) {
            TicketFiltersPage(state, handlers)
            return@ModalBottomSheet
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("Tickets", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = handlers.onQuery,
                label = { Text("Filter, or look up a key or URL") },
                singleLine = true,
                enabled = !state.busy,
                trailingIcon = if (state.query.isNotEmpty()) {
                    {
                        IconButton(onClick = { handlers.onClearFacet(TicketFacetId.SEARCH) }) {
                            Icon(FleetIcons.Close, contentDescription = "Clear search")
                        }
                    }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { handlers.onSearch() }),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
            TicketFilterRow(state, handlers)
            FilterStrip(
                facets = state.stripFacets,
                onClear = handlers.onClearFacet,
                onClearAll = handlers.onClearAll,
                lead = if (state.shown != state.total) "${state.shown} of ${state.total}" else null,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            ErrorBanner(state.error, onDismiss = handlers.onDismissError)
            if (state.loading || state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            state.selected?.let { TicketActions(it, state.busy, handlers) }
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                state.found?.let { found ->
                    item(key = "found") { SectionTitle("Found") }
                    item(key = "found-${found.id}") {
                        TicketRow(found, state.selected?.ticket?.id == found.id, state.ticketOrgs[found.id], handlers)
                    }
                }
                if (state.allFiltered) {
                    item(key = "all-filtered") { AllFiltered(state, handlers.onClearAll) }
                }
                for (section in state.sections) {
                    if (state.allFiltered) break
                    item(key = "section-${section.view}") { SectionTitle(sectionTitle(section)) }
                    if (section.tickets.isEmpty()) {
                        item(key = "empty-${section.view}") {
                            Text(
                                emptySectionText(section),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                            )
                        }
                    }
                    items(section.tickets, key = { "${section.view}-${it.id}" }) { ticket ->
                        TicketRow(ticket, state.selected?.ticket?.id == ticket.id, state.ticketOrgs[ticket.id], handlers)
                    }
                }
            }
        }
    }
}

/**
 * **Filters (n)** and **Sort**: the one control that narrows, carrying its
 * count, and the one that only reorders, apart — the Sessions list's
 * Filters / Group pair. Sort's label names the order you are *in*.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TicketFilterRow(state: TicketsUiState, handlers: TicketsHandlers) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FiltersButton(count = state.stripFacets.size, onClick = handlers.onOpenFilters)
        FilterChip(
            selected = state.sort != TicketSort.TRACKER,
            onClick = handlers.onCycleSort,
            label = { Text("Sort: ${state.sort.label}") },
        )
    }
}

/** Every ticket the lists hold is filtered away: say which filters, and offer them all back. */
@Composable
private fun AllFiltered(state: TicketsUiState, onClearAll: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(emptyTicketsSentence(facetSentence(state.facets)), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onClearAll) { Text("Clear filters") }
    }
}

/**
 * The filter page, in place of the lists: labelled chip groups, each
 * single-choice one led by *Any*, and a button that says how many tickets
 * it will show before it is pressed — the Sessions filter sheet's shape.
 * A group with nothing to choose between is left out, unless its filter is
 * on, so an active filter always has a chip that turns it off.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TicketFiltersPage(state: TicketsUiState, handlers: TicketsHandlers) {
    val f = state.filters
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = handlers.onCloseFilters) {
                Icon(FleetIcons.ArrowBack, contentDescription = "Back to tickets")
            }
            Text("Filters", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = handlers.onClearAll, enabled = state.facets.isNotEmpty()) { Text("Clear all") }
        }
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
        ) {
            // Several at once; *All* is the reset.
            FilterGroup("Lists") {
                ChipFlow {
                    ChoiceChip("All", f.lists.isEmpty(), { handlers.onClearFacet(TicketFacetId.LIST) })
                    for (list in TicketList.entries) {
                        ChoiceChip(list.label, list in f.lists, { handlers.onToggleList(list) })
                    }
                }
            }
            FilterGroup("Status") {
                ChipFlow {
                    ChoiceChip(
                        "Any",
                        f.statuses.isEmpty() && f.statusNames.isEmpty(),
                        { handlers.onClearFacet(TicketFacetId.STATUS) },
                    )
                    for (w in WorkStatusFilter.entries) {
                        ChoiceChip(w.label, w in f.statuses, { handlers.onToggleStatus(w) })
                    }
                }
            }
            // The tracker's own columns, read off the tickets listed — and a
            // remembered one the lists no longer hold, so it can be turned off.
            val names = state.statusNameChoices +
                f.statusNames.filter { n -> state.statusNameChoices.none { it.equals(n, ignoreCase = true) } }
            if (names.isNotEmpty()) {
                FilterGroup("Tracker column") {
                    ChipFlow {
                        for (name in names) {
                            ChoiceChip(
                                name,
                                f.statusNames.any { it.equals(name, ignoreCase = true) },
                                { handlers.onToggleStatusName(name) },
                            )
                        }
                    }
                }
            }
            FilterGroup("Sessions") {
                ChipFlow {
                    ChoiceChip("Any", f.session == null, { handlers.onSetSession(null) })
                    for (s in TicketSessionFilter.entries) {
                        ChoiceChip(s.label, f.session == s, { handlers.onSetSession(s) })
                    }
                }
            }
            if (state.orgChoices.isNotEmpty()) {
                FilterGroup("Organisation") {
                    ChipFlow {
                        ChoiceChip("Any", f.org == null, { handlers.onSetOrg(null) })
                        for (org in state.orgChoices) {
                            ChoiceChip(org.name, f.org == org.id, { handlers.onSetOrg(org.id) })
                        }
                    }
                }
            }
            if (state.trackerChoices.isNotEmpty()) {
                FilterGroup("Tracker") {
                    ChipFlow {
                        ChoiceChip("Any", f.tracker == null, { handlers.onSetTracker(null) })
                        for (t in state.trackerChoices) {
                            ChoiceChip(trackerName(t), f.tracker == t.id, { handlers.onSetTracker(t.id) })
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Button(
            onClick = handlers.onCloseFilters,
            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = TOUCH_TARGET),
        ) {
            Text(showTicketsLabel(state.shown, state.total, state.facets.isNotEmpty()))
        }
    }
}

/** The filter page's button: "Show all 23", "Show 1 ticket", "Show 5 tickets" — "Show 0 tickets" is an answer too. */
internal fun showTicketsLabel(shown: Int, total: Int, filtered: Boolean): String = when {
    !filtered && shown == total -> "Show all $total"
    shown == 1 -> "Show 1 ticket"
    else -> "Show $shown tickets"
}

/** "No tickets match Status: In progress, Org: Acme." */
internal fun emptyTicketsSentence(facets: String): String = "No tickets match $facets."

/** "My work · 12", or "My work · 3 of 12" while filters hide some. */
internal fun sectionTitle(section: TicketSection): String = when {
    section.total == 0 -> section.title
    section.tickets.size == section.total -> "${section.title} · ${section.total}"
    else -> "${section.title} · ${section.tickets.size} of ${section.total}"
}

/** A section with nothing to draw: empty at the hub, or emptied by the filters. */
internal fun emptySectionText(section: TicketSection): String = when (section.total) {
    0 -> "Nothing here."
    1 -> "1 hidden by filters."
    else -> "${section.total} hidden by filters."
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun TicketRow(ticket: Ticket, selected: Boolean, org: String?, handlers: TicketsHandlers) {
    ListItem(
        modifier = Modifier.clickable { handlers.onSelect(if (selected) null else ticket) },
        leadingContent = { ticket.statusCategory?.let { WorkStatusDot(it) } },
        headlineContent = {
            Text(
                ticket.label,
                style = MaterialTheme.typography.titleSmall,
                textDecoration = if (ticket.unavailable) TextDecoration.LineThrough else null,
            )
        },
        supportingContent = {
            val line = listOfNotNull(ticket.title.takeIf { it.isNotBlank() }, org).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            val status = ticket.statusName ?: if (ticket.liveSessionIds.isNotEmpty()) "live" else null
            status?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        },
    )
}

/** Open, Start here, or Resume with a host picker — only what the hub and the token allow. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TicketActions(detail: TicketDetail, busy: Boolean, handlers: TicketsHandlers) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(detail.ticket.label, style = MaterialTheme.typography.titleSmall)
                if (detail.ticket.title.isNotBlank()) {
                    Text(
                        " · ${detail.ticket.title}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // The hub's reasons, as plain text: why the ticket is struck
            // through, and a tracker whose statuses may be stale.
            for (line in detail.trouble) {
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
            val card = detail.card
            if (card != null && card.hasBody) {
                TicketCardBody(card)
            } else {
                detail.ticket.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
            if (detail.pastWork.isNotEmpty()) {
                Text("Past work", style = MaterialTheme.typography.labelMedium)
                for (past in detail.pastWork.take(PAST_WORK_SHOWN)) {
                    Text(
                        pastWorkLine(past),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (detail.canResume && detail.resumeHosts.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (host in detail.resumeHosts) {
                        FilterChip(
                            selected = host == detail.resumeHost,
                            onClick = { handlers.onResumeHost(host) },
                            enabled = !busy,
                            label = { Text(host) },
                        )
                    }
                }
            }
            detail.resumeWhyNot?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (detail.liveSessionId != null) Button(onClick = handlers.onOpenLive) { Text("Open") }
                if (detail.canResume) {
                    Button(onClick = handlers.onResume, enabled = !busy) {
                        Text(detail.resumeHost?.let { "Resume on $it" } ?: "Resume")
                    }
                }
                if (detail.canStart) OutlinedButton(onClick = handlers.onStartHere, enabled = !busy) { Text("Start here") }
            }
        }
    }
}

/**
 * **Resume**'s question: it starts a session, so it is asked like Restart.
 * The ticket's title is the tracker's text, drawn as plain text.
 */
@Composable
private fun ResumeConfirmDialog(ask: ResumeConfirm, handlers: TicketsHandlers) {
    AlertDialog(
        onDismissRequest = handlers.onCancelResume,
        title = { Text("Resume ${ask.key}?") },
        text = { Text(resumeQuestion(ask)) },
        confirmButton = { TextButton(onClick = handlers.onConfirmResume) { Text("Resume") } },
        dismissButton = { TextButton(onClick = handlers.onCancelResume) { Text("Cancel") } },
    )
}

/** What Resume will do, in a sentence. */
internal fun resumeQuestion(ask: ResumeConfirm): String {
    val what = if (ask.title.isNotBlank()) "${ask.key} · ${ask.title}" else ask.key
    val where = ask.host?.takeIf { it.isNotBlank() }?.let { " on $it" } ?: ""
    return "This starts a session$where that picks up the last conversation on $what."
}

/** How many past sessions the detail lists; the desktop has the rest. */
private const val PAST_WORK_SHOWN = 5

/**
 * One past session on the key, in a line: its name, where it ran, its branch
 * and PR, and how many conversations it held. The branch and PR are the
 * hub's snapshots, drawn as text.
 */
internal fun pastWorkLine(past: ResumeCandidate): String = buildList {
    add(past.name?.takeIf { it.isNotBlank() } ?: "Session")
    past.hostAlias?.takeIf { it.isNotBlank() }?.let { add("on $it") }
    past.branch?.takeIf { it.isNotBlank() }?.let { add(it) }
    past.prUrl?.takeIf { it.isNotBlank() }?.let { add("PR $it") }
    when (past.conversations) {
        0 -> Unit
        1 -> add("1 conversation")
        else -> add("${past.conversations} conversations")
    }
}.joinToString(" · ")
