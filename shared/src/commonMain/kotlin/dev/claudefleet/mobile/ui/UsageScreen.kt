package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.relativeAgo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.AccountRow
import dev.claudefleet.mobile.model.UsageReport
import dev.claudefleet.mobile.model.relativeTime
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.components.formatUsd
import dev.claudefleet.mobile.ui.theme.FleetIcons

data class UsageHandlers(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onSelect: (UsageWindow) -> Unit = {},
    val onOpenSession: (Long) -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/**
 * Estimated usage over a window — total, by host, by day, the costliest
 * sessions — and the Claude accounts on the fleet's hosts. Every figure is
 * an estimate from the hub's price table, which the screen says up front.
 */
@Composable
fun UsageScreen(state: UsageUiState, handlers: UsageHandlers, nowSeconds: Long, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Usage",
            subtitle = "Estimated from a price table — not a bill",
            navigation = { IconButton(onClick = handlers.onBack) { Icon(FleetIcons.ArrowBack, contentDescription = "Back") } },
            actions = {
                IconButton(onClick = handlers.onRefresh, enabled = !state.loading) { Icon(FleetIcons.Refresh, contentDescription = "Refresh") }
            },
        )
        if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        ErrorBanner(state.error, onDismiss = handlers.onDismissError)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (state.available) {
                item {
                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (w in UsageWindow.entries) {
                            FilterChip(selected = w == state.window, onClick = { handlers.onSelect(w) }, label = { Text(w.label) })
                        }
                    }
                }
                state.report?.let { report -> usageSections(report, handlers) }
            } else {
                item { Note("This hub does not report usage.") }
            }
            if (state.accountsAvailable && state.accounts.isNotEmpty()) {
                item { Heading("Claude accounts") }
                items(state.accounts, key = { "account:${it.uuid}" }) { AccountLine(it, nowSeconds) }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.usageSections(report: UsageReport, handlers: UsageHandlers) {
    item {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(formatUsd(report.total.costMicros), style = MaterialTheme.typography.displaySmall)
            Text(
                "${compactCount(report.total.tokens)} tokens · ${compactCount(report.total.outputTokens)} out",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (report.byHost.isNotEmpty()) {
        item { Heading("By host") }
        val top = report.byHost.values.maxOf { it.costMicros }.coerceAtLeast(1)
        items(report.byHost.entries.sortedByDescending { it.value.costMicros }.map { it.key to it.value }, key = { "host:${it.first}" }) { (host, t) ->
            Bar(host, formatUsd(t.costMicros), t.costMicros.toFloat() / top)
        }
    }
    if (report.byDay.isNotEmpty()) {
        item { Heading("By day") }
        val days = report.byDay.sortedByDescending { it.day }
        val top = days.maxOf { it.costMicros }.coerceAtLeast(1)
        items(days, key = { "day:${it.day}" }) { d ->
            val backfill = if (d.backfillCostMicros > 0) " (+${formatUsd(d.backfillCostMicros)} history)" else ""
            Bar(d.day, formatUsd(d.costMicros) + backfill, d.costMicros.toFloat() / top)
        }
    }
    if (report.sessions.isNotEmpty()) {
        item { Heading(if (report.sessionsTruncated) "Costliest sessions (the first ${report.sessions.size})" else "Sessions") }
        items(report.sessions, key = { "session:${it.sessionId}" }) { s ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { handlers.onOpenSession(s.sessionId) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(s.hostAlias.takeIf { it.isNotBlank() }, s.model).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(formatUsd(s.costMicros), style = MaterialTheme.typography.labelLarge)
            }
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

/** A label, a figure, and a bar of [fraction] of the widest one. */
@Composable
private fun Bar(label: String, figure: String, fraction: Float) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(figure, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        }
        Box(modifier = Modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Box(modifier = Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable
private fun AccountLine(account: AccountRow, nowSeconds: Long) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(account.label, style = MaterialTheme.typography.bodyMedium)
        Text(
            listOfNotNull(
                account.email?.takeIf { it != account.label },
                account.organizationName,
                account.seatTier,
                if (account.hasExtraUsage) "extra usage on" else null,
                relativeAgo(account.lastSeenAt, nowSeconds)?.let { "seen $it" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
