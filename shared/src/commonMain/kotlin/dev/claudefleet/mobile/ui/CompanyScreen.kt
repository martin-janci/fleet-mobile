package dev.claudefleet.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.OrgDetail
import dev.claudefleet.mobile.model.hasSpend
import dev.claudefleet.mobile.model.orgColorArgb
import dev.claudefleet.mobile.model.relativeAgo
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.theme.FleetIcons

data class CompanyHandlers(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onOpen: (Long) -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/**
 * The company's organisations, read-only: the list, and one org's overview —
 * hosts, sessions, spend against its budgets, devices and members, as far as
 * the hub sends them to this phone. Nothing here changes an org; the screen
 * says where that is done.
 */
@Composable
fun CompanyScreen(state: CompanyUiState, handlers: CompanyHandlers, nowSeconds: Long, modifier: Modifier = Modifier) {
    val open = state.open
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = open?.name?.ifBlank { null } ?: if (open != null) "Org ${open.id}" else "Company",
            subtitle = open?.myRole?.let { "You are ${roleWord(it).lowercase()}" } ?: "Read only — change orgs on the desktop",
            navigation = { IconButton(onClick = handlers.onBack) { Icon(FleetIcons.ArrowBack, contentDescription = "Back") } },
            actions = {
                IconButton(onClick = handlers.onRefresh, enabled = !state.loading) { Icon(FleetIcons.Refresh, contentDescription = "Refresh") }
            },
        )
        if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        ErrorBanner(state.error, onDismiss = handlers.onDismissError)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            when {
                !state.available -> item { Note("This hub does not list organisations.") }
                open != null -> orgSections(open, nowSeconds)
                state.orgs.isEmpty() && !state.loading -> item { Note("No organisations this device can see.") }
                else -> items(state.orgs, key = { "org:${it.id}" }) { OrgLine(it, onClick = { handlers.onOpen(it.id) }) }
            }
        }
    }
}

@Composable
private fun OrgLine(org: OrgDetail, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Swatch(org.color)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(org.name.ifBlank { "Org ${org.id}" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                orgSummary(org),
                style = MaterialTheme.typography.bodySmall,
                color = if (org.overBudget.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(FleetIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun LazyListScope.orgSections(org: OrgDetail, nowSeconds: Long) {
    item {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(sessionsLine(org), style = MaterialTheme.typography.bodyMedium)
            if (org.ownsHub) {
                Text("Owns this hub", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (org.hasSpend) {
        item { Heading("Spend") }
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                spendAgainst(org.spentTodayMicros, org.budgetDailyUsd)?.let { Figure("Today", it, over = "daily" in org.overBudget) }
                spendAgainst(org.spentWeekMicros, null)?.let { Figure("Last 7 days", it, over = false) }
                spendAgainst(org.spentMonthMicros, org.budgetMonthlyUsd)?.let { Figure("This month", it, over = "monthly" in org.overBudget) }
                Text(
                    "Estimated from a price table — not a bill",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    if (org.hosts.isNotEmpty()) {
        item { Heading("Hosts") }
        items(org.hosts, key = { "host:$it" }) { Line(it) }
    }
    org.members?.let { members ->
        item { Heading("Members") }
        if (members.isEmpty()) item { Note("No members yet.") }
        items(members, key = { "member:${it.personId}" }) { m -> Line(m.label, roleWord(m.role)) }
    }
    org.devices?.let { devices ->
        item { Heading("Devices") }
        if (devices.isEmpty()) item { Note("No devices are bound to this org.") }
        items(devices, key = { "device:${it.name}" }) { d ->
            Line(
                d.name,
                listOfNotNull(
                    if (d.mode == "readonly") "read only" else null,
                    if (d.trusted) "trusted" else null,
                    relativeAgo(d.lastSeenAt, nowSeconds)?.let { "seen $it" },
                ).joinToString(" · "),
            )
        }
    }
    if (org.trackers.isNotEmpty()) {
        item { Heading("Trackers") }
        items(org.trackers, key = { "tracker:${it.id}" }) { Line(it.name) }
    }
    item { Note("Members, roles, devices and budgets are changed on the desktop, in Settings → Organisations.") }
}

/** The list line under an org's name: its sessions, and a budget it has reached. */
internal fun orgSummary(org: OrgDetail): String = listOfNotNull(
    sessionsLine(org),
    org.myRole?.let { roleWord(it) },
    when {
        "daily" in org.overBudget && "monthly" in org.overBudget -> "over its daily and monthly budget"
        "daily" in org.overBudget -> "over its daily budget"
        "monthly" in org.overBudget -> "over its monthly budget"
        else -> null
    },
).joinToString(" · ")

internal fun sessionsLine(org: OrgDetail): String {
    val sessions = if (org.sessionCount == 1) "1 session" else "${org.sessionCount} sessions"
    return if (org.needsYou > 0) "$sessions, ${org.needsYou} need${if (org.needsYou == 1) "s" else ""} you" else sessions
}

@Composable
private fun Swatch(color: String?) {
    val argb = orgColorArgb(color)
    Box(
        modifier = Modifier.size(12.dp).clip(CircleShape)
            .background(argb?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

@Composable
private fun Figure(label: String, figure: String, over: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            if (over) "$figure · over" else figure,
            style = MaterialTheme.typography.labelLarge,
            color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Line(text: String, detail: String = "") {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Heading(text: String) {
    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
}
