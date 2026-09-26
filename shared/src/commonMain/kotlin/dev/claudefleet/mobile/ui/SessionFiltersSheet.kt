package dev.claudefleet.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.StatusFilter
import dev.claudefleet.mobile.model.TimeDirection
import dev.claudefleet.mobile.model.TimeWindow
import dev.claudefleet.mobile.model.WorkStatusFilter
import dev.claudefleet.mobile.ui.theme.LocalStatusColors
import dev.claudefleet.mobile.ui.theme.StatusTone

/** Everything the filter sheet reports. */
data class SessionFiltersHandlers(
    val onClose: () -> Unit = {},
    val onSetWindow: (TimeWindow) -> Unit = {},
    val onSetDirection: (TimeDirection) -> Unit = {},
    val onToggleStatus: (StatusFilter) -> Unit = {},
    val onSetHost: (String?) -> Unit = {},
    val onSetProject: (Long?) -> Unit = {},
    val onToggleWorkStatus: (WorkStatusFilter) -> Unit = {},
    /** A tracker status name ("QA Review") chip, beside the three buckets. */
    val onToggleWorkStatusName: (String) -> Unit = {},
    val onToggleArchived: () -> Unit = {},
    val onToggleOrg: (Long) -> Unit = {},
    val onToggleMyWork: () -> Unit = {},
    val onToggleBackground: () -> Unit = {},
    val onClearAll: () -> Unit = {},
)

/**
 * Every filter this screen has, in a sheet.
 *
 * They were chips in the header — all of them, in one `FlowRow`, styled alike
 * whether they narrowed the list or only regrouped it. That works at three and
 * stops working at six: on a 320 dp screen the row wrapped to four lines and
 * ate the list it was filtering, and a bare chip reading `8h` cannot say
 * whether it means *within* eight hours or *beyond* them, because a chip has
 * nowhere to put the question. A sheet has headings, which is the whole reason
 * to move: **Activity**, **Status**, **Host** name the question once and let
 * the chips be the answer.
 *
 * The footer is the other half of it. [SessionsUiState.shown] updates live as
 * chips are tapped, so the button reads *Show 14 sessions* before it is
 * pressed — the count that tells a person whether the filter they are building
 * is the one they meant, while they can still change it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionFiltersSheet(state: SessionsUiState, handlers: SessionFiltersHandlers) {
    ModalBottomSheet(onDismissRequest = handlers.onClose) {
        val filters = state.filters
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Filters", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = handlers.onClearAll, enabled = filters.any) { Text("Clear all") }
            }

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
            ) {
                Section("Activity") {
                    // The direction first: it is what the windows under it
                    // mean, and reading "8 hours" before knowing which side of
                    // it is kept is reading the answer before the question.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (d in TimeDirection.entries) {
                            FilterChip(
                                selected = filters.direction == d,
                                onClick = { handlers.onSetDirection(d) },
                                enabled = filters.window != TimeWindow.ANY,
                                label = { Text(d.label) },
                            )
                        }
                    }
                    ChipFlow {
                        for (w in TimeWindow.entries) {
                            FilterChip(
                                selected = filters.window == w,
                                onClick = { handlers.onSetWindow(w) },
                                label = { Text(w.label) },
                            )
                        }
                    }
                }

                Section("Status") {
                    ChipFlow {
                        for (s in StatusFilter.entries) {
                            FilterChip(
                                selected = s in filters.statuses,
                                onClick = { handlers.onToggleStatus(s) },
                                label = { Text(s.label) },
                                leadingIcon = { StatusSwatch(s) },
                            )
                        }
                    }
                }

                // One host is nothing to choose between — unless the list is
                // already narrowed to one, which a fleet that has shrunk to a
                // single host can be. Hiding the section then would leave the
                // filter set with no control on screen that can clear it, and
                // *Clear all* is a blunt way out of one chip.
                if (state.hostChoices.size > 1 || filters.hostFilter != null) {
                    Section("Host") {
                        ChipFlow {
                            FilterChip(
                                selected = filters.hostFilter == null,
                                onClick = { handlers.onSetHost(null) },
                                label = { Text("Any host") },
                            )
                            for (h in state.hostChoices) {
                                FilterChip(
                                    selected = filters.hostFilter == h.alias,
                                    onClick = { handlers.onSetHost(h.alias) },
                                    label = {
                                        Text(h.alias, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    // Unknown reachability says nothing rather
                                    // than calling a host down: `HostFilterChoice`.
                                    leadingIcon = h.reachable?.let { up -> { ReachableDot(up) } },
                                    modifier = Modifier.widthIn(max = 200.dp),
                                )
                            }
                        }
                    }
                }

                // The host rule again: one project is nothing to choose
                // between, unless the list is already narrowed to it.
                if (state.projectChoices.size > 1 || filters.projectFilter != null) {
                    Section("Project") {
                        ChipFlow {
                            FilterChip(
                                selected = filters.projectFilter == null,
                                onClick = { handlers.onSetProject(null) },
                                label = { Text("Any project") },
                            )
                            for (p in state.projectChoices) {
                                FilterChip(
                                    selected = filters.projectFilter == p.id,
                                    onClick = { handlers.onSetProject(p.id) },
                                    label = { Text(p.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.widthIn(max = 240.dp),
                                )
                            }
                        }
                    }
                }

                // The desktop's work filters (its "⚑ work" pill): the ticket's
                // status, asked of the session's primary link. Only on a hub
                // with the work graph, which is the only one whose rows carry it.
                if (state.workAvailable) {
                    Section("Ticket status") {
                        ChipFlow {
                            for (w in WorkStatusFilter.entries) {
                                FilterChip(
                                    selected = w in filters.workStatuses,
                                    onClick = { handlers.onToggleWorkStatus(w) },
                                    label = { Text(w.label) },
                                )
                            }
                        }
                        // The tracker's own columns, read off the sessions'
                        // work: whatever the team's workflow has, nothing to set up.
                        if (state.workStatusNameChoices.isNotEmpty()) {
                            ChipFlow {
                                for (name in state.workStatusNameChoices) {
                                    FilterChip(
                                        selected = filters.workStatusNames.any { it.equals(name, ignoreCase = true) },
                                        onClick = { handlers.onToggleWorkStatusName(name) },
                                        label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        modifier = Modifier.widthIn(max = 200.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.orgChoices.isNotEmpty()) {
                    Section("Organisation") {
                        ChipFlow {
                            for (org in state.orgChoices) {
                                FilterChip(
                                    selected = org.id == filters.orgFilter,
                                    onClick = { handlers.onToggleOrg(org.id) },
                                    label = { Text(org.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.widthIn(max = 200.dp),
                                )
                            }
                        }
                    }
                }

                Section("Include") {
                    if (state.myWorkAvailable) {
                        SwitchRow(
                            label = "Only my work",
                            help = "Sessions on the tickets your tracker calls yours",
                            checked = filters.myWorkOnly,
                            onToggle = handlers.onToggleMyWork,
                        )
                    }
                    if (state.workAvailable) {
                        SwitchRow(
                            label = "Archived sessions",
                            help = "Sessions put away with the desktop's Tidy-up — still running",
                            checked = filters.showArchived,
                            onToggle = handlers.onToggleArchived,
                        )
                    }
                    SwitchRow(
                        label = "Background agents",
                        help = "Sessions started as `bg:` agents rather than at a terminal",
                        checked = filters.showBackground,
                        onToggle = handlers.onToggleBackground,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Button(
                onClick = handlers.onClose,
                modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = TOUCH_TARGET),
            ) {
                // "0 sessions" is a result, not an error: a person who has
                // filtered everything away should be told so here, with the
                // chips still in front of them, rather than after the sheet
                // closes over an empty list.
                Text(
                    when {
                        !filters.any -> "Show all ${state.total}"
                        state.shown == 1 -> "Show 1 session"
                        else -> "Show ${state.shown} sessions"
                    },
                )
            }
        }
    }
}

/** A titled block of controls: the heading a chip cannot carry on its own. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        content()
    }
}

/**
 * A wrapping row of chips with 8 dp between them in both directions.
 *
 * The vertical gap is 8 dp and not the header's 4 dp on purpose: a 32 dp chip
 * is already under Material's 48 dp touch target, and in a sheet where a
 * mistap silently reshapes the list behind it the rows need the clearance more
 * than they need the density.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** A labelled switch with a line of help under it. */
@Composable
private fun SwitchRow(label: String, help: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = TOUCH_TARGET).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                help,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

/** The colour the list draws this status in, so the chip and the row agree. */
@Composable
private fun StatusSwatch(status: StatusFilter) {
    val tone = when (status) {
        StatusFilter.WORKING -> StatusTone.WORKING
        StatusFilter.BLOCKED -> StatusTone.BLOCKED
        StatusFilter.STUCK -> StatusTone.STUCK
        StatusFilter.FAILED -> StatusTone.FAILED
        StatusFilter.COMPLETED -> StatusTone.COMPLETED
        StatusFilter.IDLE, StatusFilter.STOPPED -> StatusTone.IDLE
    }
    Box(Modifier.size(10.dp).clip(CircleShape).background(LocalStatusColors.current(tone).dot))
}

@Composable
private fun ReachableDot(up: Boolean) {
    val tone = if (up) StatusTone.COMPLETED else StatusTone.FAILED
    Box(Modifier.size(8.dp).clip(CircleShape).background(LocalStatusColors.current(tone).dot))
}

/** Material's minimum touch target, which a 32 dp chip does not reach on its own. */
private val TOUCH_TARGET = 48.dp
