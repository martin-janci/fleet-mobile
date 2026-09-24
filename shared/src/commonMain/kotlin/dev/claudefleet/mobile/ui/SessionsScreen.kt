package dev.claudefleet.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.relativeTime
import dev.claudefleet.mobile.ui.components.ConnectionBanner
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.StatusChip
import dev.claudefleet.mobile.ui.components.StatusDot
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.theme.LocalStatusColors
import dev.claudefleet.mobile.ui.theme.StatusTone

/**
 * The home screen: the fleet, under a lens that says how its sessions are
 * being used, grouped by host and then by project — or, while something is
 * typed in the search box, one flat answer about the whole fleet.
 *
 * Stateless by design — it draws a [SessionsUiState] and reports taps. The
 * rules behind what it is given are a pure function in `SessionsTriage.kt` and
 * are tested there; this is what only a device can show.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(
    state: SessionsUiState,
    onOpenSession: (Long) -> Unit,
    onLensChange: (Lens) -> Unit,
    onQueryChange: (String) -> Unit,
    onToggleHideNoise: () -> Unit,
    onToggleHost: (String) -> Unit,
    onToggleDormant: () -> Unit,
    onClearHostFilter: () -> Unit,
    onRefresh: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        SessionsBar(status = state.status)
        SearchField(query = state.query, onQueryChange = onQueryChange)
        FilterRow(
            state = state,
            onLensChange = onLensChange,
            onToggleHideNoise = onToggleHideNoise,
            onClearHostFilter = onClearHostFilter,
        )
        ConnectionBanner(state.status)
        ErrorBanner(state.error, onDismiss = onDismissError)

        // The empty state is INSIDE the pull-to-refresh, and inside the
        // `LazyColumn` at that. It used to return early, so the one screen a
        // person would most want to pull on — no sessions yet, is the hub
        // really up? — was the one screen that did not respond to the
        // gesture. `PullToRefreshBox` needs a scrollable child to receive the
        // drag, which a bare `Box` is not, so the message rides as a single
        // item filling the viewport.
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (state.isEmpty) {
                    item(key = "empty") {
                        EmptyFleet(state = state, modifier = Modifier.fillParentMaxSize())
                    }
                }

                val results = state.results
                if (results != null) {
                    // A search is one flat answer, deliberately: the question
                    // was about the fleet, not about a host, and re-grouping
                    // four results under four headings would put more chrome
                    // on the screen than content.
                    items(results, key = { it.id }) { row ->
                        SessionRowItem(
                            row = row,
                            nowSeconds = state.nowSeconds,
                            context = "${row.hostAlias}",
                            onClick = { onOpenSession(row.id) },
                        )
                    }
                } else {
                    for (host in state.groups) {
                        stickyHeader(key = "host-${host.alias}") {
                            HostHeader(
                                alias = host.alias,
                                reachable = host.reachable,
                                sessions = host.sessionCount,
                                collapsed = host.collapsed,
                                onClick = { onToggleHost(host.alias) },
                            )
                        }
                        for (project in host.projects) {
                            item(key = "project-${host.alias}-${project.projectId ?: "none"}") {
                                ProjectHeader(project.label)
                            }
                            items(project.sessions, key = { it.id }) { row ->
                                SessionRowItem(
                                    row = row,
                                    nowSeconds = state.nowSeconds,
                                    onClick = { onOpenSession(row.id) },
                                )
                            }
                        }
                    }

                    if (state.dormant.isNotEmpty()) {
                        item(key = "dormant-header") {
                            DormantHeader(
                                count = state.dormant.size,
                                expanded = state.dormantExpanded,
                                onClick = onToggleDormant,
                            )
                        }
                        if (state.dormantExpanded) {
                            items(state.dormant, key = { it.id }) { row ->
                                SessionRowItem(
                                    row = row,
                                    nowSeconds = state.nowSeconds,
                                    context = row.hostAlias,
                                    onClick = { onOpenSession(row.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsBar(status: ConnectionStatus) {
    TopAppBar(
        title = {
            Column {
                Text("Sessions", style = MaterialTheme.typography.titleLarge)
                val live = when (status) {
                    is ConnectionStatus.Connected -> "live"
                    is ConnectionStatus.Reconnecting -> "reconnecting…"
                    is ConnectionStatus.Offline -> "offline"
                    // Not "offline": the hub is up and answering. The banner
                    // under this bar says which side is behind.
                    is ConnectionStatus.Refused -> "refused"
                }
                Text(live, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/**
 * The one way to find a session by what it is *doing*.
 *
 * Everything the row carries is searched — the name, the tmux name, the host,
 * the project, the last prompt, the sanitised activity, the branch and the
 * tags — and the search looks past the lens and the noise switch, because a
 * search that only looked inside the current lens would answer "no such
 * session" about a session that is plainly there. See `triageSessions`.
 *
 * The keyboard is told what this is: no autocorrect and no leading capital,
 * because what gets typed here is a branch, a host alias or half a path, and
 * a dictionary that rewrites `violet-mars` into `violet mars` turns a search
 * that would have worked into one that finds nothing.
 */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        placeholder = { Text("Search name, prompt, branch, tag…") },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Search,
        ),
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(FleetIcons.Close, contentDescription = "Clear the search")
                }
            }
        } else {
            null
        },
    )
}

/**
 * The lenses and the switches, on their own line under the bar.
 *
 * They used to ride in the `TopAppBar`'s `actions`, which is a plain `Row`
 * that neither wraps nor scrolls and is measured before the title: on a phone,
 * the moment a host filter joined the "Needs attention" chip the two
 * overflowed the bar and pushed the title clean off the screen. Here they own
 * the full width and *wrap* — a `FlowRow`, not a `Row`, because on a 320dp
 * screen the chips genuinely do not fit side by side and the next one belongs
 * on a second line rather than half past the right edge. A long alias is
 * capped so one chip can never be the whole line.
 *
 * Four chips rather than a `SegmentedButton` row: the lenses are one choice of
 * four, which is what a segmented control is for, but a segmented row does not
 * wrap either, and on 320dp four labels in one non-wrapping row is the bug
 * this layout was changed to fix.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterRow(
    state: SessionsUiState,
    onLensChange: (Lens) -> Unit,
    onToggleHideNoise: () -> Unit,
    onClearHostFilter: () -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (lens in Lens.entries) {
            FilterChip(
                selected = state.lens == lens,
                onClick = { onLensChange(lens) },
                label = { Text(lens.label) },
                leadingIcon = if (lens == Lens.NeedsYou) {
                    {
                        Icon(
                            FleetIcons.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
                trailingIcon = if (lens == Lens.NeedsYou && state.attentionCount > 0) {
                    ({ Badge { Text("${state.attentionCount}") } })
                } else {
                    null
                },
            )
        }

        // Selected means *shown*, so the chip reads as "background and shell
        // are in the list" when it is on. The count is what turning it on
        // would bring back, which is the only number that makes the tap worth
        // considering.
        FilterChip(
            selected = !state.hideNoise,
            onClick = onToggleHideNoise,
            label = {
                Text(
                    if (state.hideNoise && state.hiddenNoise > 0) {
                        "bg + shell (${state.hiddenNoise})"
                    } else {
                        "bg + shell"
                    },
                )
            },
        )

        if (state.hostFilter != null) {
            InputChip(
                selected = true,
                onClick = onClearHostFilter,
                label = { Text("host: ${state.hostFilter}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = {
                    Icon(
                        FleetIcons.Close,
                        contentDescription = "Clear host filter",
                        modifier = Modifier.size(InputChipDefaults.IconSize),
                    )
                },
                modifier = Modifier.widthIn(max = 220.dp),
            )
        }
    }
}

/** What a lens chip says. Short, because four of them share one phone's width. */
private val Lens.label: String
    get() = when (this) {
        Lens.NeedsYou -> "Needs you"
        Lens.Active -> "Active"
        Lens.Today -> "Today"
        Lens.All -> "All"
    }

@Composable
private fun HostHeader(
    alias: String,
    reachable: Boolean?,
    sessions: Int,
    collapsed: Boolean,
    onClick: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chevron(collapsed = collapsed, description = if (collapsed) "Expand $alias" else "Collapse $alias")
                Text(
                    text = alias,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            // A host that is not in `list_hosts` is unknown, not unreachable,
            // and says nothing rather than accusing it of being down.
            Text(
                text = when (reachable) {
                    true -> "$sessions session${if (sessions == 1) "" else "s"}"
                    false -> "unreachable · $sessions"
                    null -> "$sessions"
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * The tail's one heading.
 *
 * Collapsed by default, and that is the point of it: on the fleet this screen
 * was written against, most rows have not moved in days, and they were the
 * reason the ones that moved this morning were three screens down.
 */
@Composable
private fun DormantHeader(count: Int, expanded: Boolean, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chevron(
                    collapsed = !expanded,
                    description = if (expanded) "Hide the dormant sessions" else "Show the dormant sessions",
                )
                Text(
                    text = "Dormant",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Text(
                text = "$count · quiet over a day",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The fold marker, drawn from the one arrow this app has.
 *
 * `FleetIcons.ArrowBack` points left, and the rotation convention is the one
 * `SessionScreen`'s turn stepper already uses: positive is clockwise, so 90°
 * is up, -90° is down, and 180° is the right-pointing arrow a collapsed
 * section wants. Hand-drawn icons are this repository's rule (see
 * `FleetIcons`'s own comment), so a chevron is a rotation rather than a
 * thirteenth path.
 */
@Composable
private fun Chevron(collapsed: Boolean, description: String) {
    Icon(
        FleetIcons.ArrowBack,
        contentDescription = description,
        modifier = Modifier.size(18.dp).rotate(if (collapsed) 180f else -90f),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ProjectHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * One session.
 *
 * [context] is the host (and only ever the host) for the two lists that have
 * no heading above them to say where a row lives — a search result and the
 * dormant tail. Inside a host group it is null, because the heading two rows
 * up already said it and a row that repeats its own heading is noise.
 */
@Composable
private fun SessionRowItem(
    row: SessionRow,
    nowSeconds: Long,
    onClick: () -> Unit,
    context: String? = null,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { StatusDot(row.claudeStatus, row.stuckKind) },
        headlineContent = {
            Column {
                Text(row.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val pct = row.contextPct
                if (pct != null) {
                    LinearProgressIndicator(
                        progress = { (pct / 100.0).toFloat().coerceIn(0f, 1f) },
                        // Padding FIRST. `Modifier` applies left to right, so
                        // `.height(2.dp).padding(top = 2.dp)` sized the bar to
                        // 2 dp and then spent both of them on padding: a
                        // context meter that measured to zero and drew
                        // nothing. Padding first pads a 2 dp bar instead.
                        modifier = Modifier.padding(top = 2.dp).width(60.dp).height(2.dp),
                        color = if (pct >= 80) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        supportingContent = {
            val line = listOfNotNull(context, row.supportingLine).joinToString(" · ").ifBlank { null }
            if (line != null) Text(line, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                StatusChip(claudeStatus = row.claudeStatus, stuckKind = row.stuckKind)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    relativeTime(row.lastActivityAt, nowSeconds)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    row.ciStatus?.let { ci ->
                        val tone = when (ci) { "passing" -> StatusTone.COMPLETED; "failing" -> StatusTone.FAILED; else -> StatusTone.IDLE }
                        Box(Modifier.padding(start = 4.dp).size(6.dp).clip(CircleShape).background(LocalStatusColors.current(tone).dot))
                    }
                }
            }
        },
    )
    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
}

/**
 * What the list says when it has nothing to draw.
 *
 * The message names whichever choice is actually responsible, because there
 * are now four of them and "No sessions" over a fleet of fifty would read as a
 * broken app rather than as a narrow lens. The order is narrowest first: a
 * search is the most recent thing the person did, then the lens, then the
 * host.
 */
@Composable
private fun EmptyFleet(state: SessionsUiState, modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = when {
                state.searching && state.hostFilter != null ->
                    "Nothing on ${state.hostFilter} matches “${state.query.trim()}”"
                state.searching -> "Nothing in the fleet matches “${state.query.trim()}”"
                state.lens == Lens.NeedsYou && state.hostFilter != null -> "Nothing on ${state.hostFilter} needs you"
                state.lens == Lens.NeedsYou -> "Nothing needs you right now."
                state.hostFilter != null -> "No ${state.lens.emptyWord} sessions on ${state.hostFilter}"
                state.lens != Lens.All -> "Nothing ${state.lens.emptyWord}. Try All."
                else -> "No sessions. Start one from the desktop app or the terminal."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        )
    }
}

/** How a lens names itself inside a sentence about finding nothing. */
private val Lens.emptyWord: String
    get() = when (this) {
        Lens.NeedsYou -> "waiting on you"
        Lens.Active -> "active"
        Lens.Today -> "from today"
        Lens.All -> "matching"
    }
