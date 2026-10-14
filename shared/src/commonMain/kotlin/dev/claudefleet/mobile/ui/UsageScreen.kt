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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.AccountRow
import dev.claudefleet.mobile.model.AutoSwitchView
import dev.claudefleet.mobile.model.UsageReport
import dev.claudefleet.mobile.model.relativeTime
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.components.formatUsd
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.kit.InlineLoading

data class UsageHandlers(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onSelect: (UsageWindow) -> Unit = {},
    val onOpenSession: (Long) -> Unit = {},
    val onDismissError: () -> Unit = {},
    /** Name an account (uuid, name; blank clears). Offered where [UsageUiState.canRename]. */
    val onRename: (String, String) -> Unit = { _, _ -> },
    /** Turn automatic switching on or off for my sessions. */
    val onAutoSwitch: (Boolean) -> Unit = {},
    /** …and whether a switched session is told to carry on. */
    val onAutoSwitchContinue: (Boolean) -> Unit = {},
)

/** The longest nickname the hub keeps (`Store::set_account_nickname`). */
internal const val NICKNAME_MAX = 32

/**
 * Name [account]: the label the desktop and every device show for it. Blank
 * clears it, so the email shows again.
 */
@Composable
internal fun AccountNameDialog(account: AccountRow, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(account.uuid) { mutableStateOf(account.nickname.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this account") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(NICKNAME_MAX) },
                    singleLine = true,
                    label = { Text("Name") },
                    placeholder = { Text(account.email ?: account.uuid.take(8)) },
                )
                Text(
                    "Shown on every device. Empty shows the email again.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Estimated usage over a window — total, by host, by day, the costliest
 * sessions — and the Claude accounts on the fleet's hosts. Every figure is
 * an estimate from the hub's price table, which the screen says up front.
 */
@Composable
fun UsageScreen(state: UsageUiState, handlers: UsageHandlers, nowSeconds: Long, modifier: Modifier = Modifier) {
    var naming by remember { mutableStateOf<AccountRow?>(null) }
    naming?.let { a -> AccountNameDialog(a, onSave = { handlers.onRename(a.uuid, it) }, onDismiss = { naming = null }) }
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Usage",
            subtitle = "Estimated from a price table — not a bill",
            navigation = { IconButton(onClick = handlers.onBack) { Icon(FleetIcons.ArrowBack, contentDescription = "Back") } },
            actions = {
                IconButton(onClick = handlers.onRefresh, enabled = !state.loading) { Icon(FleetIcons.Refresh, contentDescription = "Refresh") }
            },
        )
        InlineLoading(waiting = state.loading)
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
            state.autoSwitch?.let { view ->
                item { Heading("When an account runs out") }
                item { AutoSwitchRows(view, state, handlers) }
            }
            if (state.accountsAvailable && state.accounts.isNotEmpty()) {
                item { Heading("Claude accounts") }
                items(state.accounts, key = { "account:${it.uuid}" }) { a ->
                    AccountLine(a, nowSeconds, onClick = { naming = a }.takeIf { state.canRename })
                }
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

/**
 * The person's own answer to automatic account switching, as on the
 * desktop's Accounts page: switch my sessions, and tell them to carry on.
 * The fleet's own switch is the owner's, in Settings; the line says so when
 * it is off.
 */
@Composable
internal fun AutoSwitchRows(view: AutoSwitchView, state: UsageUiState, handlers: UsageHandlers) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        ToggleRow(
            title = "Switch my sessions automatically",
            sub = when {
                !view.fleetEnabled -> "Off for this fleet: the owner turns it on in Settings › Limits."
                view.enabled -> "A paused session resumes under the login on its host with the most room."
                else -> "Paused sessions wait for you to switch them."
            },
            checked = view.enabled,
            enabled = state.canChangeAutoSwitch,
            onChange = handlers.onAutoSwitch,
        )
        if (state.autoSwitchContinueAvailable) {
            ToggleRow(
                title = "Then tell it to continue",
                sub = "Only a session whose turn the limit cut off; a finished one is just resumed.",
                checked = view.continueAfter,
                enabled = state.canChangeAutoSwitch && view.enabled,
                onChange = handlers.onAutoSwitchContinue,
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = if (enabled) onChange else null, enabled = enabled)
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
private fun AccountLine(account: AccountRow, nowSeconds: Long, onClick: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Name this account", onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
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
