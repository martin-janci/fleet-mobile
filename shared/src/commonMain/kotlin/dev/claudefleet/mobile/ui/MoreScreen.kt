package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.model.AccountUsageSnapshot
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.limitAt
import kotlin.math.roundToInt
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.kit.PhoneRow
import dev.claudefleet.mobile.ui.theme.Fleet

/**
 * One row on More or Control: where it goes, its live line, and whether it
 * is there at all. [action] is a button of the row's own beside the tap that
 * opens it (Automation's Pause all, MobileNav); null draws none.
 */
data class MoreEntry(val title: String, val line: String, val action: MoreAction? = null, val onOpen: () -> Unit)

/** A row's inline button: its word and what it does — a separate tap from opening the row. */
data class MoreAction(val label: String, val onClick: () -> Unit)

internal const val MORE_ACTION_TAG = "more.action."

/** "5 hosts · oci-arm offline", or "2 offline" when more than one is: the Hosts row's live line. */
fun hostsLine(hosts: List<HostRow>): String {
    val shown = hosts.filterNot { it.hidden }
    val down = shown.filterNot { it.reachable }
    val count = if (shown.size == 1) "1 host" else "${shown.size} hosts"
    return when (down.size) {
        0 -> count
        1 -> "$count · ${down.single().alias} offline"
        else -> "$count · ${down.size} offline"
    }
}

/**
 * Accounts and usage's live line (MobileSettings): the accounts at a limit
 * first, else the busiest weekly window; null with no reading, and the row
 * keeps its plain line.
 */
fun accountsLine(usage: Collection<AccountUsageSnapshot>, now: Long): String? {
    val limited = usage.mapNotNull { it.limitAt(now) }
    if (limited.isNotEmpty()) {
        val window = if (limited.any { it.weekly }) "weekly" else "5-hour"
        return if (limited.size == 1) "An account is at its $window limit" else "${limited.size} accounts at a limit"
    }
    val weekly = usage.mapNotNull { it.usage?.sevenDay?.utilization }.maxOrNull() ?: return null
    return "Weekly ${weekly.roundToInt()}% on the busiest account"
}

/** Files' live line (MobileNav): the count, and the transfer in flight with how far it got. */
fun filesLine(files: Int, transfer: Transfer?): String {
    val count = if (files == 1) "1 file" else "$files files"
    transfer ?: return count
    val pct = transfer.fraction?.let { " · ${(it * 100).roundToInt()}%" }.orEmpty()
    return "$count · ${transfer.name}$pct"
}

/** More's header line (MobileSettings): "fleet.example.com · full access". */
fun moreSubtitle(hub: String, canWrite: Boolean): String = "$hub · ${if (canWrite) "full access" else "read-only"}"

/** More's footer (MobileNav): both versions, and whether every host answers. */
fun moreFooter(appVersion: String, hubVersion: String?, hosts: List<HostRow>, connected: Boolean): String {
    val down = hosts.filterNot { it.hidden }.count { !it.reachable }
    val health = when {
        !connected -> "hub offline"
        down == 0 -> "all systems OK"
        down == 1 -> "1 host offline"
        else -> "$down hosts offline"
    }
    return listOfNotNull("Orbit Fleet $appVersion", hubVersion?.let { "hub $it" }, health).joinToString(" · ")
}

/**
 * The New layout's More tab: everything that left the bottom bar, each with a
 * live line so nothing hides behind a menu (MobileNav, MobileMore). Hosts,
 * Files and Settings open over this screen and back returns here. The header
 * names the hub and this phone's access; the footer, the versions and health.
 */
@Composable
fun MoreScreen(
    entries: List<MoreEntry>,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    footer: String? = null,
    top: @Composable () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(title = "More", subtitle = subtitle)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            // Update ready (14.18) sits above the rows, as a card.
            top()
            EntryRows(entries)
            footer?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Fleet.colors.fgMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
internal fun EntryRows(entries: List<MoreEntry>) {
    entries.forEachIndexed { i, entry ->
        val action = entry.action
        PhoneRow(
            title = entry.title,
            line = entry.line,
            lead = null,
            leadColor = Fleet.colors.fgMuted,
            divider = i < entries.lastIndex,
            dot = false,
            onClick = entry.onOpen,
            chips = if (action != null) ({
                OutlinedButton(
                    onClick = action.onClick,
                    modifier = Modifier.heightIn(min = 32.dp).testTag(MORE_ACTION_TAG + entry.title),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) { Text(action.label, fontSize = 13.sp) }
            }) else null,
        )
    }
}

/**
 * The New layout's Control tab: the fleet's coordinator (the desktop's agent,
 * a session on the hub, which the phone shows on its ordinary Session
 * screen) and the missions it runs. It replaces the agent button the Sessions
 * list used to float; 14.7 grows it into the coordinator chat with forms.
 */
@Composable
fun ControlScreen(subtitle: String?, entries: List<MoreEntry>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(title = "Control", subtitle = subtitle)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            EntryRows(entries)
        }
    }
}
