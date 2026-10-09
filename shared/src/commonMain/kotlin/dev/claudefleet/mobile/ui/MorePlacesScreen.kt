package dev.claudefleet.mobile.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.AccountUsageWindows as AccountLimits
import dev.claudefleet.mobile.model.AccountRow
import dev.claudefleet.mobile.model.AccountUsageSnapshot
import dev.claudefleet.mobile.model.UsageWindow as LimitWindow
import dev.claudefleet.mobile.model.UsageReport
import dev.claudefleet.mobile.model.relativeAgo
import dev.claudefleet.mobile.model.relativeTime
import dev.claudefleet.mobile.model.relativeWithin
import dev.claudefleet.mobile.ui.components.DangerTextButton
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.components.formatUsd
import dev.claudefleet.mobile.ui.kit.HubBanner
import dev.claudefleet.mobile.ui.kit.OrbitChip
import dev.claudefleet.mobile.ui.kit.OrbitPullToRefresh
import dev.claudefleet.mobile.ui.kit.PhoneRow
import dev.claudefleet.mobile.ui.kit.ProgressRing
import dev.claudefleet.mobile.ui.kit.StatusWord
import dev.claudefleet.mobile.ui.kit.megabytes
import dev.claudefleet.mobile.ui.kit.rememberPhoneConnection
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.theme.OrbitTokens
import dev.claudefleet.mobile.update.AppVersion
import dev.claudefleet.mobile.ui.kit.LoadFailed

// The New layout's places under More (redesign 14.10, MobileMore): Hosts,
// Accounts and usage, Files. The Classic screens stay as they were; these
// read the same view models and offer every action those do.

// ── Hosts ──

/** Never reached and never probed: the row says "Not checked yet", not "Signal lost". */
internal fun neverChecked(h: HostLine): Boolean =
    h.lastPingedAt == null && h.claudeVersion == null && h.tmuxVersion == null

/** Lost: the hub had it once and cannot reach it now. */
internal fun signalLost(h: HostLine): Boolean = !h.reachable && !neverChecked(h)

/** Exceptions first: lost hosts (longest gone first), then those never checked. Healthy rows stay quiet below. */
internal data class HostGroups(val attention: List<HostLine>, val connected: List<HostLine>)

internal fun hostGroups(hosts: List<HostLine>): HostGroups {
    val (attention, connected) = hosts.partition { !it.reachable || neverChecked(it) }
    return HostGroups(
        attention = attention.sortedWith(compareBy<HostLine>({ !signalLost(it) }, { it.lastPingedAt ?: Long.MAX_VALUE })),
        connected = connected,
    )
}

/** "5 hosts · 1 lost": the header's count. Hidden hosts count; they are listed here. */
internal fun hostsHeadline(hosts: List<HostLine>): String {
    val n = if (hosts.size == 1) "1 host" else "${hosts.size} hosts"
    val lost = hosts.count(::signalLost)
    return if (lost == 0) n else "$n · $lost lost"
}

/**
 * The hosts whose Claude is older than the newest one on the fleet: version
 * drift is a chip ("claude 2.0.31 · behind"), measured against the fleet
 * itself, because the hub does not say what the latest release is.
 */
internal fun behindHosts(hosts: List<HostLine>): Set<String> {
    val versions = hosts.mapNotNull { h -> claudeVersionOf(h)?.let { h.alias to it } }
    val newest = versions.maxOfOrNull { it.second } ?: return emptySet()
    return versions.filter { it.second < newest }.map { it.first }.toSet()
}

/** "2.0.31 (Claude Code)" → 2.0.31; anything that is not a version reads as unknown, never as behind. */
private fun claudeVersionOf(h: HostLine): AppVersion? =
    h.claudeVersion?.trim()?.substringBefore(' ')?.let(AppVersion::parse)

private fun sessionsCount(n: Int): String = if (n == 1) "1 session" else "$n sessions"

/** What a host's row says after its lead word. */
internal fun hostRowLine(h: HostLine, nowSeconds: Long): String {
    val hidden = if (h.hidden) " · hidden from Sessions" else ""
    return when {
        signalLost(h) -> listOfNotNull(relativeAgo(h.lastPingedAt, nowSeconds)?.let { "last seen $it" }, sessionsCount(h.sessions))
            .joinToString(" · ") + hidden
        h.needsYou + h.working > 0 -> {
            val parts = listOfNotNull(
                h.needsYou.takeIf { it > 0 }?.let { "$it needs you" },
                h.working.takeIf { it > 0 }?.let { "$it working" },
            )
            "${sessionsCount(h.sessions)}: ${parts.joinToString(" · ")}$hidden"
        }
        else -> sessionsCount(h.sessions) + hidden
    }
}

/** What the Hosts screen can do. Null handlers are actions this hub or token does not offer. */
data class OrbitHostsHandlers(
    val onRefresh: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    /** The host's sheet, or its sessions where the hub offers no sheet. */
    val onOpen: (String) -> Unit = {},
    /** Probe now: Try again on a lost host, Check now on one never checked. */
    val onCheck: ((String) -> Unit)? = null,
    /** The reboot plan and the conversations the host has no row for (the host's sheet). */
    val onRecovery: ((String) -> Unit)? = null,
    /**
     * Install the agent on a host the hub reaches over SSH (14.19): opens the
     * review, never installs. Null where the hub does not list the job to
     * this pairing.
     */
    val onInstallAgent: ((String) -> Unit)? = null,
    /** Add a host from the hub's SSH config (the Radar); null where the hub does not list `add_host` to this pairing. */
    val onAddHost: (() -> Unit)? = null,
)

/**
 * Hosts under More: exceptions first. Signal lost is static, with Try again
 * and the recovery plan; healthy rows stay quiet; version drift is a chip.
 * Adding a host is offered to the hub owner's phone (contract 13);
 * removing and hiding one stay on the desktop.
 */
@Composable
fun OrbitHostsScreen(
    state: HostsUiState,
    nowSeconds: Long,
    handlers: OrbitHostsHandlers,
    modifier: Modifier = Modifier,
) {
    val groups = remember(state.hosts) { hostGroups(state.hosts) }
    val behind = remember(state.hosts) { behindHosts(state.hosts) }
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(title = "Hosts", subtitle = state.hosts.takeIf { it.isNotEmpty() }?.let(::hostsHeadline))
        HubBanner(rememberPhoneConnection(state.status), onRetry = handlers.onRefresh)
        ErrorBanner(state.error, onDismiss = handlers.onDismissError)
        OrbitPullToRefresh(isRefreshing = state.refreshing, onRefresh = handlers.onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (state.isEmpty) {
                    item(key = "empty") {
                        Quiet(
                            when {
                                state.connecting -> "Connecting to the hub…"
                                handlers.onAddHost != null -> "No hosts yet."
                                else -> "No hosts. Add one from the desktop app or the terminal."
                            },
                        )
                    }
                }
                handlers.onAddHost?.let { add ->
                    item(key = "add") {
                        PhoneRow(title = "Add a host", line = "From the hub's SSH config", lead = null, dot = false, onClick = add)
                    }
                }
                if (groups.attention.isNotEmpty()) {
                    item(key = "h:attention") { GroupHeading("Needs attention", groups.attention.size) }
                    items(groups.attention, key = { "a:${it.alias}" }) { host ->
                        HostRowItem(host, nowSeconds, host.alias in behind, host.alias in state.checking, handlers)
                    }
                }
                if (groups.connected.isNotEmpty()) {
                    item(key = "h:connected") { GroupHeading("Connected", groups.connected.size) }
                    items(groups.connected, key = { "c:${it.alias}" }) { host ->
                        HostRowItem(host, nowSeconds, host.alias in behind, host.alias in state.checking, handlers)
                    }
                }
            }
        }
    }
}

@Composable
private fun HostRowItem(host: HostLine, nowSeconds: Long, behind: Boolean, checking: Boolean, handlers: OrbitHostsHandlers) {
    val o = Fleet.colors
    val lost = signalLost(host)
    val never = neverChecked(host)
    PhoneRow(
        title = host.alias,
        line = hostRowLine(host, nowSeconds),
        lead = when {
            checking -> "Checking…"
            lost -> "Signal lost"
            never -> "Not checked yet"
            else -> null
        },
        leadColor = if (lost && !checking) o.fg else o.fgMuted,
        age = if (lost || never) null else relativeTime(host.lastPingedAt, nowSeconds),
        word = null,
        onClick = { handlers.onOpen(host.alias) },
        chips = {
            when {
                lost -> {
                    handlers.onCheck?.let { probe -> RowAction("Try again", enabled = !checking) { probe(host.alias) } }
                    handlers.onRecovery?.let { plan -> RowAction("Recovery plan") { plan(host.alias) } }
                }
                never -> handlers.onCheck?.let { probe -> RowAction("Check now", enabled = !checking) { probe(host.alias) } }
                else -> {
                    OrbitChip(host.transport)
                    if (behind) OrbitChip("claude ${host.claudeVersion?.substringBefore(' ')} · behind")
                    if (host.transport == "ssh") handlers.onInstallAgent?.let { add -> RowAction("Install agent") { add(host.alias) } }
                }
            }
        },
    )
}

// ── Accounts and usage ──

/**
 * Accounts and usage under More: the Claude accounts first, then the
 * estimated spend for a window, by host, by day and by session. Each
 * account shows its 5-hour and weekly limits as meters where the hub serves
 * them (`account_usage`); an older hub keeps them on the desktop's machine,
 * so the screen says where they are rather than drawing an empty meter.
 */
@Composable
fun OrbitUsageScreen(state: UsageUiState, handlers: UsageHandlers, nowSeconds: Long, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Accounts and usage",
            subtitle = "Estimated from a price table, not a bill",
            navigation = { IconButton(onClick = handlers.onBack) { Icon(FleetIcons.ArrowBack, contentDescription = "Back") } },
        )
        ErrorBanner(state.error, onDismiss = handlers.onDismissError)
        OrbitPullToRefresh(isRefreshing = state.loading, onRefresh = handlers.onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (state.accountsAvailable && state.accounts.isNotEmpty()) {
                    items(state.accounts, key = { "account:${it.uuid}" }) {
                        Column {
                            AccountItem(it, nowSeconds)
                            if (state.limitsAvailable) AccountLimitsBlock(state.limits[it.uuid], nowSeconds)
                        }
                    }
                    if (!state.limitsAvailable) item(key = "limits") { Quiet(LIMITS_ON_DESKTOP, small = true) }
                }
                if (state.available) {
                    item(key = "windows") {
                        Row(
                            modifier = Modifier.padding(horizontal = gutter(), vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (w in UsageWindow.entries) WindowChip(w.label, w == state.window) { handlers.onSelect(w) }
                        }
                    }
                    state.report?.let { orbitUsage(it, handlers) }
                } else {
                    item(key = "no-usage") { Quiet("This hub does not report usage.") }
                }
            }
        }
    }
}

/** Where the 5-hour and weekly limits are, while the hub keeps them on the desktop's machine. */
internal const val LIMITS_ON_DESKTOP =
    "Session and weekly limits are read on the desktop's machine; this hub does not send them to phones yet."

/** The windows an account has, in the board's order, each with its name. */
internal fun limitRows(usage: AccountLimits): List<Pair<String, LimitWindow>> = listOfNotNull(
    usage.fiveHour?.let { "5-hour" to it },
    usage.sevenDay?.let { "Weekly" to it },
    usage.sevenDayOpus?.let { "Weekly Opus" to it },
    usage.sevenDaySonnet?.let { "Weekly Sonnet" to it },
)

/**
 * "62% used · resets in 2 h"; the figure always beside the bar, never the
 * colour alone. A window past its reset says so instead of its old figure
 * (the desktop's `freshness` calls it expired): the reading is from before
 * the reset, and the next one has not come in.
 */
internal fun limitFigure(w: LimitWindow, nowSeconds: Long): String {
    if (hasReset(w, nowSeconds)) return "Reset · not re-read yet"
    val pct = "${kotlin.math.round(w.utilization.coerceIn(0.0, 100.0)).toInt()}% used"
    val resets = relativeWithin(w.resetsAt, nowSeconds)?.let { "resets in $it" }
    return listOfNotNull(pct, resets).joinToString(" · ")
}

/** A window whose reset time has passed since it was read. */
internal fun hasReset(w: LimitWindow, nowSeconds: Long): Boolean = w.resetsAt?.let { it <= nowSeconds } ?: false

/** A window close to its limit: its meter turns to the failed colour and says so in words too. */
internal fun nearLimit(w: LimitWindow, nowSeconds: Long): Boolean = !hasReset(w, nowSeconds) && w.utilization >= 90.0

/**
 * What a read that did not come back says, in words, and how old the meters
 * above it are; null when the latest read is good.
 */
internal fun limitsNote(snap: AccountUsageSnapshot?, nowSeconds: Long): String? {
    if (snap == null) return "Not read yet."
    val why = when (snap.status) {
        "ok" -> return null
        "never_fetched" -> "Not read yet"
        "no_credentials" -> "No Claude login on its host"
        // The hub refreshes an expired access token by itself (attention.rs).
        "access_token_expired" -> "Refreshing its login"
        "login_expired" -> "Its login expired; sign in again on the host"
        "token_rejected" -> "Claude refused its login; sign in again on the host"
        "rate_limited" -> "Claude asked to wait before reading again"
        "host_unsupported" -> "Its host cannot read limits"
        "no_online_host" -> "No host with this account is online"
        else -> "Could not read the limits"
    }
    val age = if (snap.usage != null) relativeAgo(snap.fetchedAt, nowSeconds)?.let { "meters from $it" } else null
    return listOfNotNull(why, age).joinToString(" · ") + "."
}

@Composable
private fun AccountLimitsBlock(snap: AccountUsageSnapshot?, nowSeconds: Long) {
    val o = Fleet.colors
    Column(modifier = Modifier.fillMaxWidth().padding(start = gutter(), end = gutter(), bottom = 10.dp)) {
        for ((name, w) in snap?.usage?.let(::limitRows).orEmpty()) {
            val near = nearLimit(w, nowSeconds)
            val used = if (hasReset(w, nowSeconds)) 0.0 else w.utilization
            Row(modifier = Modifier.padding(top = 6.dp)) {
                Text(name, color = o.fg, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
                Text(
                    (if (near) "Near the limit · " else "") + limitFigure(w, nowSeconds),
                    color = if (near) o.statusFailed else o.fgMuted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
            }
            Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(4.dp).background(o.track, RoundedCornerShape(2.dp))) {
                Box(
                    Modifier.fillMaxWidth((used / 100.0).toFloat().coerceIn(0f, 1f)).fillMaxHeight()
                        .background(if (near) o.statusFailed else o.accent, RoundedCornerShape(2.dp)),
                )
            }
        }
        limitsNote(snap, nowSeconds)?.let { Text(it, color = o.fgMuted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp)) }
    }
}

@Composable
private fun AccountItem(account: AccountRow, nowSeconds: Long) {
    PhoneRow(
        title = account.label,
        line = listOfNotNull(
            account.email?.takeIf { it != account.label },
            account.organizationName,
            account.seatTier,
            if (account.hasExtraUsage) "extra usage on" else null,
        ).joinToString(" · "),
        lead = null,
        dot = false,
        age = relativeAgo(account.lastSeenAt, nowSeconds)?.let { "seen $it" },
    )
}

private fun LazyListScope.orbitUsage(report: UsageReport, handlers: UsageHandlers) {
    item(key = "total") {
        val o = Fleet.colors
        Column(modifier = Modifier.padding(horizontal = gutter(), vertical = 6.dp)) {
            Text(formatUsd(report.total.costMicros), color = o.fg, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${compactCount(report.total.tokens)} tokens · ${compactCount(report.total.outputTokens)} out",
                color = o.fgMuted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
    }
    if (report.byHost.isNotEmpty()) {
        item(key = "by-host") { GroupHeading("By host", null) }
        val top = report.byHost.values.maxOf { it.costMicros }.coerceAtLeast(1)
        items(report.byHost.entries.sortedByDescending { it.value.costMicros }.map { it.key to it.value }, key = { "host:${it.first}" }) { (host, t) ->
            SpendBar(host, formatUsd(t.costMicros), t.costMicros.toFloat() / top)
        }
    }
    if (report.byDay.isNotEmpty()) {
        item(key = "by-day") { GroupHeading("By day", null) }
        val days = report.byDay.sortedByDescending { it.day }
        val top = days.maxOf { it.costMicros }.coerceAtLeast(1)
        items(days, key = { "day:${it.day}" }) { d ->
            val backfill = if (d.backfillCostMicros > 0) " (+${formatUsd(d.backfillCostMicros)} history)" else ""
            SpendBar(d.day, formatUsd(d.costMicros) + backfill, d.costMicros.toFloat() / top)
        }
    }
    if (report.sessions.isNotEmpty()) {
        item(key = "by-session") {
            GroupHeading(if (report.sessionsTruncated) "Costliest sessions" else "By session", null)
        }
        items(report.sessions, key = { "session:${it.sessionId}" }) { s ->
            PhoneRow(
                title = s.name,
                line = listOfNotNull(s.hostAlias.takeIf { it.isNotBlank() }, s.model).joinToString(" · "),
                lead = null,
                dot = false,
                age = formatUsd(s.costMicros),
                onClick = { handlers.onOpenSession(s.sessionId) },
            )
        }
    }
}

/** A label, a figure, and a bar of [fraction] of the widest one, in the accent. */
@Composable
private fun SpendBar(label: String, figure: String, fraction: Float) {
    val o = Fleet.colors
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = gutter(), vertical = 6.dp)) {
        Row {
            Text(label, color = o.fg, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(figure, color = o.fg, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
        }
        Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(4.dp).background(o.track, RoundedCornerShape(2.dp))) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(o.accent, RoundedCornerShape(2.dp)))
        }
    }
}

// ── Files ──

/** What a file row leads with. A failed copy off a host that is gone now says Signal lost, not Failed. */
internal fun fileLead(file: FileLine, transferring: Boolean, hostReachable: (String) -> Boolean): String? = when {
    transferring -> null
    file.state == FileState.Fetching -> "Copying to the hub"
    file.state == FileState.Failed && file.host.isNotBlank() && !hostReachable(file.host) -> "Signal lost"
    file.state == FileState.Failed -> StatusWord.FAILED.label
    file.state == FileState.Other -> "Not ready"
    else -> null
}

/** Line two of a file row: size, host and note; a failure in a plain sentence; a transfer's real amount. */
internal fun fileLine(file: FileLine, transfer: Transfer?, hostReachable: (String) -> Boolean): String = when {
    transfer != null -> listOf(megabytes(transfer.received, transfer.total ?: 0), file.host).filter { it.isNotBlank() }.joinToString(" · ")
    file.state == FileState.Failed && file.host.isNotBlank() && !hostReachable(file.host) -> "${file.host} is offline"
    file.state == FileState.Failed -> fileFailure(file.error, file.host)
    else -> listOfNotNull(file.size, file.host.takeIf { it.isNotBlank() }, file.note).joinToString(" · ")
}

/** "5 files · 1.2 GB of 2 GB on the hub". */
internal fun filesHeadline(state: FilesUiState): String? {
    if (!state.loaded) return null
    val n = if (state.files.size == 1) "1 file" else "${state.files.size} files"
    return listOfNotNull(n, state.usage).joinToString(" · ")
}

/**
 * Files under More: the hub's copies of files sessions sent, newest first.
 * A transfer to this phone shows the Progress ring with the real size; a
 * failed copy off a host that went away says Signal lost and offers Retry
 * once the host is back. Session names open the session. Remove lives in
 * each file's menu and says it removes the hub's copy for every device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrbitFilesScreen(
    state: FilesUiState,
    status: ConnectionStatus,
    handlers: FilesHandlers,
    hostReachable: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(title = "Files", subtitle = filesHeadline(state))
        HubBanner(rememberPhoneConnection(status), onRetry = handlers.onRefresh)
        ErrorBanner(state.error, onDismiss = handlers.onDismissError)
        state.notice?.let { notice ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = gutter(), vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(notice, color = Fleet.colors.fg, fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = handlers.onDismissNotice) { Text("OK") }
            }
        }
        OrbitPullToRefresh(isRefreshing = state.refreshing, onRefresh = handlers.onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (state.isEmpty) {
                    item(key = "empty") {
                        Quiet("No files yet. When Claude sends you one, or you send one from the desktop's file viewer, it appears here.")
                    }
                } else if (state.listFailed && state.files.isEmpty() && !state.refreshing) {
                    item(key = "failed") { LoadFailed("files", state.error?.body, handlers.onRefresh) }
                }
                items(state.files, key = { it.id }) { file ->
                    FileRowItem(file, state, handlers, hostReachable)
                }
            }
        }
    }

    state.opened?.let { opened ->
        ModalBottomSheet(onDismissRequest = handlers.onCloseOpened) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = gutter(), vertical = 8.dp)) {
                Text(opened.name, color = Fleet.colors.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${opened.size} · on this phone", color = Fleet.colors.fgMuted, fontSize = 13.sp)
                if (state.handoffs.isEmpty()) {
                    Text("This device has nowhere to hand the file on to.", color = Fleet.colors.fg, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)) {
                    state.handoffs.forEachIndexed { index, action ->
                        if (index == 0) Button(onClick = { handlers.onHandOff(action) }) { Text(action.label) }
                        else OutlinedButton(onClick = { handlers.onHandOff(action) }) { Text(action.label) }
                    }
                }
            }
        }
    }

    state.confirmRemove?.let { file ->
        AlertDialog(
            onDismissRequest = handlers.onCancelRemove,
            title = { Text("Remove ${file.name}?") },
            text = { Text("The hub forgets its copy, for every device. The file on its host is not touched.") },
            confirmButton = { DangerTextButton(onClick = handlers.onConfirmRemove) { Text("Remove") } },
            dismissButton = { TextButton(onClick = handlers.onCancelRemove) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FileRowItem(file: FileLine, state: FilesUiState, handlers: FilesHandlers, hostReachable: (String) -> Boolean) {
    val transfer = state.transfer?.takeIf { it.id == file.id }
    val tappable = file.state == FileState.Ready && state.transfer == null
    val lead = fileLead(file, transfer != null, hostReachable)
    val failed = file.state == FileState.Failed
    val hostBack = file.host.isBlank() || hostReachable(file.host)
    Row(verticalAlignment = Alignment.CenterVertically) {
        PhoneRow(
            title = file.name,
            line = fileLine(file, transfer, hostReachable),
            modifier = Modifier.weight(1f),
            word = if (failed && lead == StatusWord.FAILED.label) StatusWord.FAILED else null,
            lead = lead,
            leadColor = if (failed) Fleet.colors.fg else Fleet.colors.fgMuted,
            age = if (transfer != null) null else file.age.takeIf { it.isNotBlank() },
            dot = false,
            divider = false,
            onClick = if (tappable) ({ handlers.onTap(file.id) }) else null,
            chips = {
                when {
                    transfer != null -> RowAction("Cancel download", onClick = handlers.onCancelTransfer)
                    failed && handlers.onRetry != null && file.sessionId != null ->
                        RowAction(if (hostBack) "Retry" else "Retry when ${file.host} is back", enabled = hostBack) { handlers.onRetry.invoke(file.id) }
                    else -> {
                        val session = file.sessionName ?: file.sessionId?.let { "session $it" }
                        val open = handlers.onOpenSession
                        if (session != null) {
                            val id = file.sessionId
                            OrbitChip(
                                session,
                                modifier = if (id != null && open != null) Modifier.clickable(onClickLabel = "Open $session") { open(id) } else Modifier,
                            )
                        }
                    }
                }
            },
        )
        if (transfer != null) {
            val f = transfer.fraction
            ProgressRing(
                fraction = f ?: 0f,
                label = f?.let { "${(it * 100).toInt()}%" } ?: "…",
                size = 56.dp,
                stroke = 5.dp,
                modifier = Modifier.padding(end = gutter()),
            )
        } else if (handlers.onRemove != null) {
            FileMenu(file.name) { handlers.onRemove.invoke(file.id) }
        }
    }
    androidx.compose.material3.HorizontalDivider(color = Fleet.colors.border)
}

/** The ⋮ on a file row: Remove, which then asks, naming every device. */
@Composable
private fun FileMenu(name: String, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(FleetIcons.MoreVert, contentDescription = "More for $name") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Remove from the hub…") }, onClick = { open = false; onRemove() })
        }
    }
}

// ── Shared pieces ──

@Composable
private fun gutter() = OrbitTokens.spacing("phone-gutter").dp

/** A group's heading with its count: "Needs attention 1". */
@Composable
internal fun GroupHeading(text: String, count: Int?) {
    val o = Fleet.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = gutter(), end = gutter(), top = 16.dp, bottom = 4.dp).semantics { heading() },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = o.fgMuted, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
        if (count != null) Text("$count", color = o.fgMuted, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

/** A row's own button: Try again, Recovery plan, Cancel download. Small, outlined, at the touch minimum. */
@Composable
private fun RowAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(OrbitTokens.radius("radius-md").dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
    ) { Text(label, fontSize = 13.sp) }
}

@Composable
private fun WindowChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val o = Fleet.colors
    Box(
        modifier = Modifier
            .background(if (selected) o.accentSoft else o.chipBg, RoundedCornerShape(OrbitTokens.radius("radius-sm").dp))
            .clickable(onClickLabel = "Show $label", onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = if (selected) o.fg else o.fg2, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun Quiet(text: String, small: Boolean = false) {
    Text(
        text,
        color = Fleet.colors.fgMuted,
        fontSize = if (small) 13.sp else 14.sp,
        lineHeight = if (small) 18.sp else 20.sp,
        modifier = Modifier.padding(horizontal = gutter(), vertical = if (small) 8.dp else 24.dp),
    )
}
