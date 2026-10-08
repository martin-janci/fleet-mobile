package dev.claudefleet.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.Page
import dev.claudefleet.mobile.notify.BackgroundNotifier
import dev.claudefleet.mobile.notify.NoBackgroundNotifier
import dev.claudefleet.mobile.notify.NotifyKind
import dev.claudefleet.mobile.notify.NotifyKinds
import dev.claudefleet.mobile.ui.components.DangerTextButton
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.theme.OrbitTokens

/**
 * Everything the New layout's Settings draws besides the hub's own pages,
 * which stay [FleetSettingsSection]'s.
 */
data class OrbitSettingsInput(
    val settings: SettingsUiState,
    /** The hub's settings pages; null where the hub serves none to this device. */
    val fleet: FleetSettingsUiState?,
    val theme: ThemeChoice,
    val notifyKinds: NotifyKinds,
)

/** Every tap the New layout's Settings reports. */
class OrbitSettingsHandlers(
    val onOpen: (SettingsPlace) -> Unit = {},
    val onBack: () -> Unit = {},
    val onOpenPage: (String) -> Unit = {},
    val onSetTheme: (ThemeChoice) -> Unit = {},
    val onSetNotify: (NotifyKind, Boolean) -> Unit = { _, _ -> },
    val onForget: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    /** The Usage screen; null where the hub reports neither usage nor accounts. */
    val onOpenUsage: (() -> Unit)? = null,
    /** The Company screen; null where the hub lists no organisation to this device. */
    val onOpenCompany: (() -> Unit)? = null,
)

/**
 * Settings in the New layout (redesign 14.11, board MobileSettings): one home
 * with This phone and the desktop's five groups, and a page per group listing
 * the hub's pages under it. Nothing the Classic screen had is gone:
 *
 * - hub, client name, access and both versions: the summary at the top, and
 *   in full under General;
 * - Usage under General, Company under Organisations;
 * - every hub page under its group ([groupPages]), proposed changes also as
 *   a row at the top while any wait;
 * - background notifications under This phone;
 * - Forget this hub at the foot, still asked first.
 *
 * A hub page opened from here takes the screen ([fleetPage]) and back returns
 * to where it was opened. Stateless: [place] says which page is up.
 */
@Composable
fun OrbitSettingsScreen(
    place: SettingsPlace,
    input: OrbitSettingsInput,
    handlers: OrbitSettingsHandlers,
    modifier: Modifier = Modifier,
    /** A hub page is open: it takes the screen, under [place]'s header. */
    fleetPageOpen: Boolean = false,
    fleetPage: @Composable () -> Unit = {},
    notifier: BackgroundNotifier = NoBackgroundNotifier,
    /** Extra rows at the foot of the home page (the layout switch, 14.2). */
    homeExtras: @Composable ColumnScope.() -> Unit = {},
) {
    val o = Fleet.colors
    Column(modifier = modifier.fillMaxSize().background(o.bg)) {
        val title = when (place) {
            SettingsPlace.Home -> "Settings"
            SettingsPlace.ThisPhone -> "This phone"
            is SettingsPlace.Group -> place.group.title
        }
        val subtitle = if (place == SettingsPlace.ThisPhone) "Only this device" else null
        if (place != SettingsPlace.Home || fleetPageOpen) {
            ScreenHeader(
                title = title,
                subtitle = subtitle,
                navigation = { IconButton(onClick = handlers.onBack) { Icon(FleetIcons.ArrowBack, contentDescription = "Back") } },
            )
        } else {
            ScreenHeader(title = title, subtitle = subtitle)
        }
        ErrorBanner(input.settings.error?.asGenericFriendly(), onDismiss = handlers.onDismissError)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (fleetPageOpen) {
                fleetPage()
                return@Column
            }
            when (place) {
                SettingsPlace.Home -> SettingsHome(input, handlers, homeExtras)
                SettingsPlace.ThisPhone -> ThisPhone(input, handlers, notifier)
                is SettingsPlace.Group -> SettingsGroupPage(place.group, input, handlers)
            }
        }
    }
}

@Composable
private fun ColumnScope.SettingsHome(
    input: OrbitSettingsInput,
    handlers: OrbitSettingsHandlers,
    extras: @Composable ColumnScope.() -> Unit,
) {
    val o = Fleet.colors
    val s = input.settings
    val gutter = OrbitTokens.spacing("phone-gutter").dp
    HubSummary(s)

    // An action item, not a setting buried in a list (analysis 90).
    val waiting = input.fleet?.proposals?.size ?: 0
    if (waiting > 0 && input.fleet?.pages.orEmpty().any { it.id == REVIEW_PAGE }) {
        Surface(
            color = o.waitingFaint,
            shape = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp),
            border = BorderStroke(1.dp, o.waitingLine),
            modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 8.dp),
        ) {
            SettingsRow(
                title = if (waiting == 1) "1 proposed change" else "$waiting proposed changes",
                line = "Waits for a decision · Review",
                onClick = { handlers.onOpenPage(REVIEW_PAGE) },
                divider = false,
            )
        }
    }

    SectionLabel("Settings")
    val groups = input.fleet?.pages?.let(::groupPages).orEmpty()
    SettingsRow("This phone", "Notifications, theme", onClick = { handlers.onOpen(SettingsPlace.ThisPhone) })
    for (group in SettingsGroup.entries) {
        // General holds the hub's details and Organisations the Company
        // screen, so those two are there even when the hub serves no pages.
        val shown = when (group) {
            SettingsGroup.GENERAL -> true
            SettingsGroup.ORGANISATIONS -> handlers.onOpenCompany != null || groups[group].orEmpty().isNotEmpty()
            else -> groups[group].orEmpty().isNotEmpty()
        }
        if (shown) {
            SettingsRow(group.title, group.line, onClick = { handlers.onOpen(SettingsPlace.Group(group)) })
        }
    }
    extras()

    Spacer(Modifier.height(16.dp))
    Text(
        "Orbit Fleet ${s.appVersion.ifBlank { "—" }}" + if (s.hubVersion.isNotBlank()) " · hub ${s.hubVersion}" else "",
        style = Fleet.type.textXs,
        color = o.fgMuted,
        modifier = Modifier.padding(horizontal = gutter),
    )
    ForgetHub(s, handlers.onForget)
}

/** "fleet.example.com", then what this device may do and its name: the More header of the board. */
@Composable
private fun HubSummary(s: SettingsUiState) {
    val o = Fleet.colors
    val gutter = OrbitTokens.spacing("phone-gutter").dp
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 12.dp)) {
        Text(hubLabel(s.hub).ifBlank { "—" }, style = Fleet.type.textLg, color = o.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${accessWords(s)} · “${s.clientName.ifBlank { "—" }}”",
            style = Fleet.type.textSm,
            color = if (s.readOnly) o.statusWaiting else o.fgMuted,
        )
    }
}

private fun accessWords(s: SettingsUiState): String =
    if (s.readOnly) "Read only, this device cannot send prompts" else "Full access"

@Composable
private fun ColumnScope.ThisPhone(input: OrbitSettingsInput, handlers: OrbitSettingsHandlers, notifier: BackgroundNotifier) {
    val o = Fleet.colors
    val gutter = OrbitTokens.spacing("phone-gutter").dp
    SectionLabel("Notifications")
    // The always-on watcher, as Classic has it: the person's to turn on, with
    // the system's permission asked right then.
    if (notifier.supported) NotifyRow(notifier)
    val master by notifier.enabled.collectAsState()
    val kindsLive = !notifier.supported || master
    for (kind in NotifyKind.entries) {
        SwitchRow(
            title = kind.label,
            line = kind.line,
            on = input.notifyKinds.allows(kind),
            enabled = kindsLive,
            onChange = { handlers.onSetNotify(kind, it) },
        )
    }

    SectionLabel("Theme")
    ThemePicker(input.theme, handlers.onSetTheme)

    Spacer(Modifier.height(16.dp))
    Text(
        "Saved as you change it, on this phone only.",
        style = Fleet.type.textXs,
        color = o.fgMuted,
        modifier = Modifier.padding(horizontal = gutter),
    )
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun ColumnScope.SettingsGroupPage(group: SettingsGroup, input: OrbitSettingsInput, handlers: OrbitSettingsHandlers) {
    val s = input.settings
    val fleet = input.fleet
    val pages = fleet?.pages?.let(::groupPages)?.get(group).orEmpty()
    when (group) {
        SettingsGroup.GENERAL -> {
            KeyValue("Hub", s.hub)
            KeyValue("Client name", s.clientName)
            KeyValue("Access", if (s.readOnly) "read only — this device cannot send prompts" else s.mode)
            // Two programs, two versions, named apart.
            KeyValue("App version", s.appVersion)
            KeyValue("Hub version", s.hubVersion)
            handlers.onOpenUsage?.let {
                SettingsRow("Usage", "Estimated cost by host, day and session; the fleet's Claude accounts", onClick = it)
            }
        }
        SettingsGroup.ORGANISATIONS -> handlers.onOpenCompany?.let {
            SettingsRow("Company", "Organisations, their spend, members and devices — read only", onClick = it)
        }
        else -> Unit
    }
    if (pages.isNotEmpty()) {
        SectionLabel(if (fleet?.canWrite == true) "The hub's settings" else "The hub's settings, read only")
        for ((i, page) in pages.withIndex()) {
            PageRow(page, fleet, divider = i < pages.lastIndex, onOpen = handlers.onOpenPage)
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun PageRow(page: Page, fleet: FleetSettingsUiState?, divider: Boolean, onOpen: (String) -> Unit) {
    val waiting = if (page.layout == "review_apply") fleet?.proposals?.size ?: 0 else 0
    SettingsRow(
        title = page.title,
        line = when {
            waiting > 0 -> "$waiting waiting"
            else -> page.intro.orEmpty()
        },
        onClick = { onOpen(page.id) },
        divider = divider,
        lineIsNews = waiting > 0,
    )
}

/**
 * One row that opens somewhere: title, a line under it, a chevron. At least
 * `touch-min` tall; the whole row is the target.
 */
@Composable
internal fun SettingsRow(
    title: String,
    line: String,
    onClick: () -> Unit,
    divider: Boolean = true,
    lineIsNews: Boolean = false,
) {
    val o = Fleet.colors
    val gutter = OrbitTokens.spacing("phone-gutter").dp
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = OrbitTokens.spacing("touch-min").dp + 12.dp)
                .padding(horizontal = gutter, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = Fleet.type.textLg, color = o.fg)
                if (line.isNotBlank()) {
                    Text(
                        line,
                        style = Fleet.type.textSm,
                        color = if (lineIsNews) o.statusWaiting else o.fgMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(FleetIcons.ChevronRight, contentDescription = null, tint = o.fgMuted)
        }
        if (divider) HorizontalDivider(color = o.border, modifier = Modifier.padding(start = gutter))
    }
}

@Composable
private fun SwitchRow(title: String, line: String, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val o = Fleet.colors
    val gutter = OrbitTokens.spacing("phone-gutter").dp
    // The whole row is the switch: a tap on its words flips it, and a screen
    // reader reads the words as the switch's own.
    Row(
        modifier = Modifier.fillMaxWidth()
            .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .alpha(if (enabled) 1f else 0.5f)
            .heightIn(min = OrbitTokens.spacing("touch-min").dp)
            .padding(horizontal = gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = Fleet.type.textLg, color = o.fg)
            Text(line, style = Fleet.type.textSm, color = o.fgMuted)
        }
        Switch(checked = on, onCheckedChange = null, enabled = enabled)
    }
}

/** Dark, Light, System as one segmented control; dark first, as the manual is. */
@Composable
private fun ThemePicker(current: ThemeChoice, onPick: (ThemeChoice) -> Unit) {
    val o = Fleet.colors
    val gutter = OrbitTokens.spacing("phone-gutter").dp
    val shape = RoundedCornerShape(OrbitTokens.radius("radius-md").dp)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (choice in ThemeChoice.entries) {
            val selected = choice == current
            Text(
                choice.label,
                style = Fleet.type.textMd,
                color = if (selected) o.fg else o.fg2,
                modifier = Modifier.weight(1f)
                    .background(if (selected) o.accentSoft else o.bgPane, shape)
                    .border(1.dp, if (selected) o.accent else o.controlBorder, shape)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onPick(choice) })
                    .heightIn(min = OrbitTokens.spacing("touch-min").dp)
                    .padding(vertical = 13.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = Fleet.type.text2xs,
        color = Fleet.colors.fgMuted,
        modifier = Modifier.padding(start = OrbitTokens.spacing("phone-gutter").dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun KeyValue(label: String, value: String) {
    val o = Fleet.colors
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = OrbitTokens.spacing("phone-gutter").dp, vertical = 8.dp)) {
        Text(label, style = Fleet.type.textSm, color = o.fgMuted)
        Text(value.ifBlank { "—" }, style = Fleet.type.textMd, color = o.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Forget this hub, asked first: getting back means a new pairing code. The
 * words keep "forgotten" apart from "cancelled", which differ by exactly the
 * thing that matters when a phone is lost.
 */
@Composable
private fun ForgetHub(s: SettingsUiState, onForget: () -> Unit) {
    val o = Fleet.colors
    val gutter = OrbitTokens.spacing("phone-gutter").dp
    var asking by remember { mutableStateOf(false) }
    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        DangerTextButton(onClick = { asking = true }, enabled = s.canForget) {
            Text(if (s.forgetting) "Forgetting…" else "Forget this hub…")
        }
    }
    Text(
        "Removes the credential from this phone. It does not cancel it: the token stays good on the hub " +
            "until the operator cancels it there, which is what to do if this phone is lost.",
        style = Fleet.type.textXs,
        color = o.fgMuted,
        modifier = Modifier.padding(start = gutter, end = gutter, bottom = 24.dp),
    )
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Forget ${hubLabel(s.hub)}?") },
            text = { Text("This phone stops seeing the fleet. To come back you need a new pairing code from the hub's operator.") },
            confirmButton = { DangerTextButton(onClick = { asking = false; onForget() }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
}
