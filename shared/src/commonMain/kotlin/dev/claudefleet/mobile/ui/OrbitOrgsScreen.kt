package dev.claudefleet.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.model.OrgDetail
import dev.claudefleet.mobile.model.SettingDescriptor
import dev.claudefleet.mobile.model.SettingProposal
import dev.claudefleet.mobile.model.inWords
import dev.claudefleet.mobile.model.orgColorArgb
import dev.claudefleet.mobile.model.relativeAgo
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.components.formatUsd
import dev.claudefleet.mobile.ui.kit.OrbitPullToRefresh
import dev.claudefleet.mobile.ui.kit.PhoneRow
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.theme.OrbitTokens

// Organisations and AI settings in the New layout (redesign 14.17,
// MobileOrgsSettings): an organisation with its budget meter and rows that
// open filtered lists, what Decisions (Jev) may do and who opted in, and a
// proposed change arriving as an Inbox item with two equal buttons.

// ── Organisations ──

/** "Organisation · you are a member": the role in words, never "You are member" (analysis 96). */
internal fun orgRoleLine(org: OrgDetail): String = "Organisation · " + when (org.myRole) {
    null -> "you are not in it"
    "admin" -> "you are an admin"
    "member" -> "you are a member"
    "viewer" -> "you are a viewer"
    else -> "you are ${org.myRole}"
}

/** "Owner: František" from the admins the hub listed; null when it sent no members. */
internal fun orgOwners(org: OrgDetail): String? {
    val admins = org.members?.filter { it.role == "admin" }?.map { it.label }?.filter { it.isNotBlank() } ?: return null
    return when (admins.size) {
        0 -> null
        1 -> "Owner: ${admins.single()}"
        else -> "Owners: ${admins.joinToString(", ")}"
    }
}

/** One budget meter: what is spent against what may be, and whether it has reached it. */
internal data class BudgetMeter(val title: String, val figure: String, val fraction: Float, val over: Boolean) {
    /** Near the limit: the bar turns to the warning colour before it is over. */
    val near: Boolean get() = !over && fraction >= NEAR_BUDGET
}

internal const val NEAR_BUDGET = 0.8f

/**
 * The org's budget meter (analysis 97: budgets mentioned but not shown): the
 * month's when it has a monthly budget, else the day's. Null when the hub
 * sent no spend (only its administrators see it) or the org has no budget.
 */
internal fun budgetMeter(org: OrgDetail): BudgetMeter? {
    fun meter(title: String, micros: Long?, budgetUsd: Long?, period: String): BudgetMeter? {
        if (micros == null || budgetUsd == null || budgetUsd <= 0) return null
        val fraction = (micros.toDouble() / (budgetUsd * 1_000_000.0)).toFloat()
        return BudgetMeter(title, "${formatUsd(micros)} of $$budgetUsd", fraction.coerceIn(0f, 1f), period in org.overBudget || fraction >= 1f)
    }
    return meter("Budget this month", org.spentMonthMicros, org.budgetMonthlyUsd, "monthly")
        ?: meter("Budget today", org.spentTodayMicros, org.budgetDailyUsd, "daily")
}

/** "František (owner), Martin (member)". */
internal fun membersLine(org: OrgDetail): String? = org.members?.takeIf { it.isNotEmpty() }?.joinToString(", ") { m ->
    "${m.label} (${if (m.role == "admin") "owner" else roleWord(m.role).lowercase()})"
}

data class OrbitOrgsHandlers(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onOpen: (Long) -> Unit = {},
    val onDismissError: () -> Unit = {},
    /** The Sessions list filtered to one org. */
    val onOpenSessions: (Long) -> Unit = {},
    /** The Hosts list. */
    val onOpenHosts: () -> Unit = {},
    /** An org's members sheet; null where the hub does not serve `org_admin` to this phone. */
    val onOpenMembers: ((OrgDetail) -> Unit)? = null,
)

/** This phone may change [org]'s members: it administers it and the hub serves member actions. */
private fun OrbitOrgsHandlers.managesMembers(org: OrgDetail): Boolean = onOpenMembers != null && administers(org)

/**
 * Organisations under More: the list, then one organisation with its swatch,
 * your role, its owner, the budget meter, and rows that open filtered lists.
 * An org's admins change its members' roles and remove them from here
 * (redesign 11.10); the rest is read-only, and the screen says where it is
 * changed.
 */
@Composable
fun OrbitOrgsScreen(state: CompanyUiState, handlers: OrbitOrgsHandlers, nowSeconds: Long, modifier: Modifier = Modifier) {
    val open = state.open
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = open?.name?.ifBlank { null } ?: if (open != null) "Organisation ${open.id}" else "Organisations",
            subtitle = open?.let(::orgRoleLine) ?: "Read only on the phone",
            navigation = { IconButton(onClick = handlers.onBack) { Icon(FleetIcons.ArrowBack, contentDescription = "Back") } },
        )
        ErrorBanner(state.error, onDismiss = handlers.onDismissError)
        OrbitPullToRefresh(isRefreshing = state.loading, onRefresh = handlers.onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                when {
                    !state.available -> item { Quiet("This hub does not list organisations.") }
                    open != null -> orgDetail(open, handlers, nowSeconds)
                    state.orgs.isEmpty() && !state.loading -> item { Quiet("No organisations this device can see.") }
                    else -> items(state.orgs, key = { "org:${it.id}" }) { org ->
                        PhoneRow(
                            title = org.name.ifBlank { "Organisation ${org.id}" },
                            line = orgSummary(org),
                            lead = null,
                            dot = false,
                            leading = { Swatch(org.color) },
                            onClick = { handlers.onOpen(org.id) },
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.orgDetail(org: OrgDetail, handlers: OrbitOrgsHandlers, nowSeconds: Long) {
    item(key = "card") {
        val o = Fleet.colors
        val gutter = OrbitTokens.spacing("phone-gutter").dp
        Surface(
            color = o.bgPane,
            shape = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp),
            border = BorderStroke(1.dp, o.border),
            modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 8.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.padding(top = 5.dp)) { Swatch(org.color) }
                    Column {
                        Text(org.name.ifBlank { "Organisation ${org.id}" }, color = o.fg, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(orgOwners(org), if (handlers.managesMembers(org)) "members change here" else "read-only on the phone").joinToString(" · "),
                            color = o.fgMuted,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }
                budgetMeter(org)?.let { BudgetBar(it) }
            }
        }
    }
    item(key = "sessions") {
        PhoneRow(
            title = if (org.sessionCount == 1) "1 session" else "${org.sessionCount} sessions",
            line = if (org.needsYou > 0) "${org.needsYou} need${if (org.needsYou == 1) "s" else ""} you" else "None waits on you",
            lead = null,
            dot = false,
            onClick = { handlers.onOpenSessions(org.id) },
        )
    }
    if (org.hosts.isNotEmpty()) {
        item(key = "hosts") { PhoneRow(title = "Hosts", line = org.hosts.joinToString(", "), lead = null, dot = false, onClick = handlers.onOpenHosts) }
    }
    val openMembers = handlers.onOpenMembers
    val members = membersLine(org) ?: if (openMembers != null) "Who is in it" else null
    members?.let { line ->
        item(key = "members") {
            PhoneRow(title = "Members", line = line, lead = null, dot = false, onClick = openMembers?.let { open -> { open(org) } })
        }
    }
    if (org.trackers.isNotEmpty()) {
        item(key = "trackers") { PhoneRow(title = "Trackers", line = org.trackers.joinToString(", ") { it.name }, lead = null, dot = false) }
    }
    org.devices?.takeIf { it.isNotEmpty() }?.let { devices ->
        item(key = "devices") {
            PhoneRow(
                title = "Devices",
                line = devices.joinToString(", ") { d ->
                    d.name + (relativeAgo(d.lastSeenAt, nowSeconds)?.let { " (seen $it)" } ?: "")
                },
                lead = null,
                dot = false,
            )
        }
    }
    org.jevAllowed?.let { allowed ->
        item(key = "jev") {
            PhoneRow(
                title = "Decisions (Jev)",
                line = if (allowed) "Opted in: redacted prompts may be sent when Jev is on" else "Not opted in: nothing of it is sent",
                lead = null,
                dot = false,
            )
        }
    }
    item(key = "note") {
        Quiet(
            if (handlers.managesMembers(org)) {
                "Adding members, pairing devices and budgets stay on the desktop: Settings → Organisations."
            } else {
                "Members, roles, devices and budgets are changed on the desktop: Settings → Organisations."
            },
            small = true,
        )
    }
}

@Composable
private fun BudgetBar(m: BudgetMeter) {
    val o = Fleet.colors
    val bar = when {
        m.over -> o.statusFailed
        m.near -> o.statusWaiting
        else -> o.accent
    }
    Column(Modifier.padding(top = 14.dp)) {
        Row {
            Text(m.title, color = o.fgMuted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
            Text(if (m.over) "${m.figure} · over" else m.figure, color = o.fg, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).background(o.track, RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(m.fraction).fillMaxHeight().background(bar, RoundedCornerShape(3.dp)))
        }
        Text(
            "Spend counts every session on its hosts or on its tickets, so it can differ from host totals. Estimated, not a bill.",
            color = o.fgMuted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun Swatch(color: String?) {
    val argb = orgColorArgb(color)
    Box(Modifier.size(12.dp).clip(CircleShape).background(argb?.let { Color(it) } ?: Fleet.colors.chipBg))
}

// ── Decisions (Jev) ──

/** The hub page holding the Decisions (Jev) settings. */
internal const val DECISIONS_PAGE = "settings.decisions"

/**
 * The modes a Jev feature offers on the phone. `auto` is never offered,
 * whatever a hub lists: no feature has passed acceptance (D36), and Jev only
 * ever proposes.
 */
internal fun SettingDescriptor.offeredOptions(): List<String> =
    if (key.startsWith("decide.jev.")) kind.options.filterNot { it == "auto" } else kind.options

/** What Jev may and may not do, said once at the top of its page. */
internal const val JEV_MAY =
    "Jev marks one choice with ✦ and a reason; you confirm or change it. It never answers a permission, " +
        "never pre-selects Approve, and never chooses trust, roles, shares, an organisation or a host by numbers."

/**
 * The head of the Decisions (Jev) page: which organisations opted in, what
 * Jev may do, and where the key is set. The hub's own fields (the kill
 * switch, the model, each feature's mode) follow it unchanged.
 */
@Composable
fun DecisionsHead(orgs: List<OrgDetail>) {
    val o = Fleet.colors
    val gutter = OrbitTokens.spacing("phone-gutter").dp
    Column(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 8.dp)) {
        val known = orgs.filter { it.jevAllowed != null }
        if (known.isNotEmpty()) {
            val (inOrgs, out) = known.partition { it.jevAllowed == true }
            HeadLine("Opted in", inOrgs.joinToString(", ") { it.name }.ifBlank { "None" })
            HeadLine("Not opted in", out.joinToString(", ") { it.name }.ifBlank { "None" })
        }
        Text("What Jev may do", color = o.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
        Text(JEV_MAY, color = o.fg2, fontSize = 14.sp, lineHeight = 20.sp)
        Text(
            "Off: nothing is ever sent. On: only for organisations that opted in, redacted. Set or rotate the key on the desktop: Settings → Work → Decisions.",
            color = o.fgMuted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun HeadLine(title: String, value: String) {
    val o = Fleet.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(title, color = o.fgMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = o.fg, fontSize = 14.sp)
    }
}

// ── A proposed change, as an Inbox item ──

/** "From the session "Hub tuning"": who proposed it, in words. */
internal fun proposalSource(p: SettingProposal): String =
    "From " + when {
        p.sourceDetail != null && p.source == "session" -> "the session “${p.sourceDetail}”"
        p.sourceDetail != null -> "${p.source} (${p.sourceDetail})"
        p.source == "agent" -> "an agent"
        else -> p.source
    }

/**
 * A change an agent proposed for the hub, on the Inbox: the setting, its
 * value now and the proposed one, why, and two equal buttons (Reject and
 * Apply, neither pre-selected). A device that may not decide sees no
 * buttons, only where to decide.
 */
@Composable
fun ProposedChangeCard(
    p: SettingProposal,
    descriptor: SettingDescriptor?,
    canDecide: Boolean,
    busy: Boolean,
    nowSeconds: Long,
    onDecide: (Long, Boolean) -> Unit,
) {
    val o = Fleet.colors
    val gutter = OrbitTokens.spacing("phone-gutter").dp
    Surface(
        color = o.bgPane,
        shape = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp),
        border = BorderStroke(1.dp, o.border),
        modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Proposed change", color = o.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(proposalSource(p), relativeAgo(p.at.takeIf { it > 0 }, nowSeconds)).joinToString(" · "),
                color = o.fgMuted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Text(descriptor?.label ?: p.key, color = o.fg2, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
            val now = descriptor?.inWords(p.current) ?: p.current
            val next = descriptor?.inWords(p.value) ?: p.value
            Text("$now → $next", color = o.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (p.current != p.before) {
                Text("Changed since it was proposed (it was ${descriptor?.inWords(p.before) ?: p.before}).", color = o.fgMuted, fontSize = 13.sp)
            }
            p.why?.let { Text("Why: $it", color = o.fg2, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp)) }
            Text(
                "Applies to the whole fleet. You can change it back in Settings.",
                color = o.fgMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (canDecide) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                    // Two equal buttons: the same shape and weight, neither filled.
                    for ((label, apply) in listOf("Reject" to false, "Apply" to true)) {
                        OutlinedButton(
                            onClick = { onDecide(p.id, apply) },
                            enabled = !busy,
                            modifier = Modifier.weight(1f).heightIn(min = OrbitTokens.spacing("touch-min").dp),
                            shape = RoundedCornerShape(OrbitTokens.radius("radius-md").dp),
                        ) { Text(label) }
                    }
                }
            } else {
                Text("Decide on a device the hub trusts.", color = o.fgMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun Quiet(text: String, small: Boolean = false) {
    Text(
        text,
        color = Fleet.colors.fgMuted,
        fontSize = if (small) 13.sp else 14.sp,
        lineHeight = if (small) 18.sp else 20.sp,
        modifier = Modifier.padding(horizontal = OrbitTokens.spacing("phone-gutter").dp, vertical = if (small) 12.dp else 24.dp),
    )
}
