package dev.claudefleet.mobile.ui

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.SessionFacetId
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
    /** One group back to *Any* (its facet cleared) — the State, Status and Organisation groups' reset. */
    val onClearFacet: (SessionFacetId) -> Unit = {},
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
 * to move: **Last active**, **State**, **Host** name the question once and let
 * the chips be the answer. Every single-choice group starts with *Any*, its
 * reset, and the labels are the desktop's (claude-fleet's sidebar filter
 * panel), in sentence case. Filters that hide rows live here — *Show
 * archived* and *Background agents* included, as switches that read
 * "include"; how the list is grouped is a view, and stays on the screen.
 *
 * The footer is the other half of it. [SessionsUiState.shown] updates live as
 * chips are tapped, so the button reads *Show 14 sessions* before it is
 * pressed — the count that tells a person whether the filter they are building
 * is the one they meant, while they can still change it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionFiltersSheet(state: SessionsUiState, handlers: SessionFiltersHandlers) {
    ModalBottomSheet(onDismissRequest = handlers.onClose) {
        val filters = state.filters
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Filters", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                // Enabled while anything the sheet can clear is on — showing
                // archived sessions included, which *Clear all* hides again.
                TextButton(onClick = handlers.onClearAll, enabled = filters.any || (state.workAvailable && filters.showArchived)) { Text("Clear all") }
            }

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
            ) {
                FilterGroup("Last active") {
                    // The direction first: it is what the windows under it
                    // mean, and reading "8 hours" before knowing which side of
                    // it is kept is reading the answer before the question.
                    ChipFlow {
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
                            ChoiceChip(w.label, filters.window == w, { handlers.onSetWindow(w) })
                        }
                    }
                }

                // Several at once, OR-ed; *Any* clears them.
                FilterGroup("State") {
                    ChipFlow {
                        ChoiceChip("Any", filters.statuses.isEmpty(), { handlers.onClearFacet(SessionFacetId.STATE) })
                        for (s in StatusFilter.entries) {
                            ChoiceChip(s.label, s in filters.statuses, { handlers.onToggleStatus(s) }, leadingIcon = { StatusSwatch(s) })
                        }
                    }
                }

                // One host is nothing to choose between — unless the list is
                // already narrowed to one, which a fleet that has shrunk to a
                // single host can be. Hiding the group then would leave the
                // filter set with no control on screen that can clear it.
                if (state.hostChoices.size > 1 || filters.hostFilter != null) {
                    FilterGroup("Host") {
                        ChipFlow {
                            ChoiceChip("Any", filters.hostFilter == null, { handlers.onSetHost(null) })
                            for (h in state.hostChoices) {
                                ChoiceChip(
                                    h.alias,
                                    filters.hostFilter == h.alias,
                                    { handlers.onSetHost(h.alias) },
                                    // Unknown reachability says nothing rather
                                    // than calling a host down: `HostFilterChoice`.
                                    leadingIcon = h.reachable?.let { up -> { ReachableDot(up) } },
                                )
                            }
                        }
                    }
                }

                // The host rule again: one project is nothing to choose
                // between, unless the list is already narrowed to it.
                if (state.projectChoices.size > 1 || filters.projectFilter != null) {
                    FilterGroup("Project") {
                        ChipFlow {
                            ChoiceChip("Any", filters.projectFilter == null, { handlers.onSetProject(null) })
                            for (p in state.projectChoices) {
                                ChoiceChip(p.label, filters.projectFilter == p.id, { handlers.onSetProject(p.id) })
                            }
                        }
                    }
                }

                if (state.orgChoices.isNotEmpty()) {
                    FilterGroup("Organisation") {
                        ChipFlow {
                            ChoiceChip("Any", filters.orgFilter == null, { handlers.onClearFacet(SessionFacetId.ORG) })
                            for (org in state.orgChoices) {
                                ChoiceChip(org.name, org.id == filters.orgFilter, { handlers.onToggleOrg(org.id) })
                            }
                        }
                    }
                }

                // The desktop's work filters: the ticket's status, asked of
                // the session's primary link. Only on a hub with the work
                // graph, which is the only one whose rows carry it.
                if (state.workAvailable) {
                    FilterGroup("Status") {
                        ChipFlow {
                            ChoiceChip(
                                "Any",
                                filters.workStatuses.isEmpty() && filters.workStatusNames.isEmpty(),
                                { handlers.onClearFacet(SessionFacetId.WORK_STATUS) },
                            )
                            for (w in WorkStatusFilter.entries) {
                                ChoiceChip(w.label, w in filters.workStatuses, { handlers.onToggleWorkStatus(w) })
                            }
                        }
                    }
                    // The tracker's own columns, read off the sessions'
                    // work: whatever the team's workflow has, nothing to set up.
                    if (state.workStatusNameChoices.isNotEmpty()) {
                        FilterGroup("Tracker column") {
                            ChipFlow {
                                for (name in state.workStatusNameChoices) {
                                    ChoiceChip(
                                        name,
                                        filters.workStatusNames.any { it.equals(name, ignoreCase = true) },
                                        { handlers.onToggleWorkStatusName(name) },
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.myWorkAvailable) {
                    FilterGroup("Assignee") {
                        SwitchRow(
                            label = "Assigned to me",
                            help = "Sessions on the tickets your tracker calls yours",
                            checked = filters.myWorkOnly,
                            onToggle = handlers.onToggleMyWork,
                        )
                    }
                }

                FilterGroup("Include") {
                    if (state.workAvailable) {
                        SwitchRow(
                            label = "Show archived",
                            help = "Sessions put away with the desktop's Tidy-up — still running, hidden by default",
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
                        !filters.any && state.shown == state.total -> "Show all ${state.total}"
                        state.shown == 1 -> "Show 1 session"
                        else -> "Show ${state.shown} sessions"
                    },
                )
            }
        }
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
