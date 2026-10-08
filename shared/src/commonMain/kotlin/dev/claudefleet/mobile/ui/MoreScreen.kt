package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.kit.PhoneRow
import dev.claudefleet.mobile.ui.theme.Fleet

/** One row on More or Control: where it goes, its live line, and whether it is there at all. */
data class MoreEntry(val title: String, val line: String, val onOpen: () -> Unit)

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
 * The New layout's More tab: everything that left the bottom bar, each with a
 * live line so nothing hides behind a menu (MobileNav, MobileMore). Hosts,
 * Files and Settings open over this screen and back returns here.
 */
@Composable
fun MoreScreen(entries: List<MoreEntry>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(title = "More")
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            EntryRows(entries)
        }
    }
}

@Composable
internal fun EntryRows(entries: List<MoreEntry>) {
    entries.forEachIndexed { i, entry ->
        PhoneRow(
            title = entry.title,
            line = entry.line,
            lead = null,
            leadColor = Fleet.colors.fgMuted,
            divider = i < entries.lastIndex,
            dot = false,
            onClick = entry.onOpen,
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
