package dev.claudefleet.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.StatusCategory
import dev.claudefleet.mobile.model.askedAt
import dev.claudefleet.mobile.model.TodayGroup
import dev.claudefleet.mobile.model.TodaySection
import dev.claudefleet.mobile.model.TodaySession
import dev.claudefleet.mobile.model.TodayShipped
import dev.claudefleet.mobile.model.TodayView
import dev.claudefleet.mobile.model.attentionLabel
import dev.claudefleet.mobile.model.groupLabel
import dev.claudefleet.mobile.model.hasUnlinked
import dev.claudefleet.mobile.model.sessions
import dev.claudefleet.mobile.model.staleLabel
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.StatusDot
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.theme.LocalStatusColors
import dev.claudefleet.mobile.ui.theme.StatusTone
import dev.claudefleet.mobile.ui.kit.rememberLoaderVisible

/** Everything the Today sheet reports. */
data class TodayHandlers(
    val onClose: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onOpenSession: (Long) -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onToggleSection: (TodaySection) -> Unit = {},
    /** A host chip; the selected one again clears it. */
    val onSetHost: (String?) -> Unit = {},
    val onToggleTicketsOnly: () -> Unit = {},
    val onClearFilters: () -> Unit = {},
    /** Tidy-up — what is stale, and the choice of what to do with it; null where it is not offered. */
    val onOpenTidy: (() -> Unit)? = null,
)

/**
 * The Today sheet: the desktop's four sections, **Copy standup** and
 * **Share** — the same text handed to the platform's share sheet, for a
 * standup posted from the phone. A session line opens that session. Titles
 * are the tracker's text, drawn as plain text only.
 *
 * Laid out for a glance, which is how a phone reads it:
 *
 * - **Each section is a card** on a darker sheet, with a heading in the
 *   body colour and a count in the section's tone. They used to be a
 *   lavender label above a flat list, lighter than the ticket titles under
 *   them, so the eye read the hierarchy upside down and a no-work group's
 *   "No work" passed for a fifth section.
 * - **Why a session is listed is a chip in its tone**, in words
 *   ([attentionLabel]) — amber to answer, red to go and fix. It was the
 *   hub's token in brackets (`(ci_failing)`) in the same colour as the name.
 * - **A session is a 48 dp row** with its status dot, name, host on a line
 *   of its own (it was the part the ellipsis cut) and a chevron. Every line
 *   used to be link-coloured, so nothing stood out; now only the chips do.
 * - **Filters** are the Sessions list's chips: sections with counts, a host,
 *   tickets only. *Copy standup* copies what is shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodaySheet(
    state: TodayUiState,
    handlers: TodayHandlers,
    /**
     * The New layout (redesign 14.3, MobileSessionsTools): *Waiting on me*
     * is the live fleet's needs-you rows, the Inbox's own list, so the two
     * counts can never disagree. Null on Classic, which keeps the hub's digest.
     */
    waitingNow: List<SessionRow>? = null,
    nowSeconds: Long = 0,
) {
    val clipboard = LocalClipboardManager.current
    val share = rememberShareText()
    // Keyed on the text: "Copied" is about this standup, and a re-read that
    // changes it puts the button back.
    var copied by remember(state.standup) { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = handlers.onClose,
        // The sheet one step darker than its cards: the separation is the
        // surface change, not a line.
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val f = state.filters
            val waitingLive = waitingNow
                ?.filter { f.host == null || it.hostAlias == f.host }
                ?.filter { !f.ticketsOnly || it.work != null }
                ?.takeIf { f.sections.isEmpty() || TodaySection.Waiting in f.sections }
            TodayHeader(state, handlers, needYou = waitingNow?.size)
            // Reserved whether or not it is loading, so a re-read does not
            // shift the list under a thumb about to tap it.
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (rememberLoaderVisible(state.loading)) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (state.loaded && !state.view.isEmpty) TodayFilterRow(state, handlers, waitingCount = waitingNow?.size)
            ErrorBanner(state.error, onDismiss = handlers.onDismissError)
            val v = state.shown
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    state.loaded && state.view.isEmpty -> item(key = "empty") { EmptyNote("Nothing yet today.") }
                    state.loaded && v.isEmpty -> item(key = "filtered-empty") {
                        EmptyNote("Nothing today matches these filters.", action = "Clear filters", onAction = handlers.onClearFilters)
                    }
                }
                if (waitingNow == null) {
                    groupSection(TodaySection.Waiting, v.waiting, handlers)
                } else if (!waitingLive.isNullOrEmpty()) {
                    item(key = "section-waiting-live") {
                        SectionCard(TodaySection.Waiting, waitingLive.size) {
                            waitingLive.forEachIndexed { i, row ->
                                PhoneSessionRow(
                                    row = row,
                                    nowSeconds = nowSeconds,
                                    showHost = true,
                                    since = row.askedAt,
                                    divider = i < waitingLive.lastIndex,
                                    onClick = { handlers.onOpenSession(row.id) },
                                )
                            }
                        }
                    }
                }
                groupSection(TodaySection.InProgress, v.inProgress, handlers)
                if (v.shipped.isNotEmpty()) {
                    item(key = "section-shipped") {
                        SectionCard(
                            TodaySection.Shipped,
                            v.shipped.size,
                            // Shipped entries name no host; say so rather
                            // than let them pass for the filtered host's.
                            note = if (state.filters.host != null) "all hosts" else null,
                        ) {
                            v.shipped.forEachIndexed { i, x ->
                                if (i > 0) ItemDivider()
                                ShippedRow(x)
                            }
                        }
                    }
                }
                groupSection(TodaySection.Stale, v.stale, handlers)
                handlers.onOpenTidy?.let { tidy ->
                    item(key = "tidy") {
                        TextButton(onClick = tidy, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Tidy up…") }
                    }
                }
            }
            TodayFooter(
                enabled = state.loaded,
                filtered = state.filters.any,
                copied = copied,
                onCopy = {
                    clipboard.setText(AnnotatedString(state.standup))
                    copied = true
                },
                onShare = { share(state.standup) },
            )
        }
    }
}

/** "Today", a line of what it holds, and Refresh — the only action that is not about the text. */
@Composable
private fun TodayHeader(state: TodayUiState, handlers: TodayHandlers, needYou: Int? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Today",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            if (state.loaded) {
                Text(
                    todaySummary(state.view, needYou),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = handlers.onRefresh, enabled = !state.loading) {
            Icon(FleetIcons.Refresh, contentDescription = "Refresh")
        }
    }
}

/**
 * "Since midnight · 11 sessions · 5 need you": the day in one line, over
 * the whole digest rather than the filtered slice — a filter narrows the
 * list, it does not make the day quieter.
 */
internal fun todaySummary(v: TodayView, liveNeedYou: Int? = null): String {
    val sessions = v.sessions
    val needYou = liveNeedYou ?: sessions.count { it.attention != null }
    return buildList {
        add("Since midnight")
        add(if (sessions.size == 1) "1 session" else "${sessions.size} sessions")
        if (needYou > 0) add(if (needYou == 1) "1 needs you" else "$needYou need you")
        if (v.shipped.isNotEmpty()) add("${v.shipped.size} shipped")
    }.joinToString(" · ")
}

/**
 * The Sessions list's chip row, for the digest: a chip per section with its
 * count and tone swatch (several at once, none meaning all), *Tickets only*,
 * a chip per host when there is more than one, and *Clear* while anything
 * is on. One line that scrolls sideways, like the Sessions filter strip.
 */
@Composable
private fun TodayFilterRow(state: TodayUiState, handlers: TodayHandlers, waitingCount: Int? = null) {
    val f = state.filters
    Row(verticalAlignment = Alignment.CenterVertically) {
        LazyRow(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (s in TodaySection.entries) {
                val n = (if (s == TodaySection.Waiting) waitingCount else null) ?: state.sectionCounts[s] ?: 0
                val on = s in f.sections
                if (n == 0 && !on) continue
                item(key = "section-${s.name}") {
                    FilterChip(
                        selected = on,
                        onClick = { handlers.onToggleSection(s) },
                        label = { Text("${s.label} $n") },
                        leadingIcon = { ToneSwatch(sectionTone(s)) },
                        modifier = Modifier.semantics { contentDescription = "${s.label}, $n" },
                    )
                }
            }
            // Only when it would hide something, or is on and must stay clearable.
            if (f.ticketsOnly || state.view.hasUnlinked) {
                item(key = "tickets-only") {
                    FilterChip(selected = f.ticketsOnly, onClick = handlers.onToggleTicketsOnly, label = { Text("Tickets only") })
                }
            }
            // One host is nothing to choose between — unless the sheet is
            // already narrowed to it: the chip that clears it stays.
            if (state.hostChoices.size > 1 || f.host != null) {
                for (h in state.hostChoices) {
                    item(key = "host-$h") {
                        FilterChip(
                            selected = f.host == h,
                            onClick = { handlers.onSetHost(h) },
                            label = { Text(h, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.widthIn(max = 200.dp),
                        )
                    }
                }
            }
        }
        if (f.any) {
            TextButton(onClick = handlers.onClearFilters, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Clear") }
        }
    }
}

private fun LazyListScope.groupSection(section: TodaySection, groups: List<TodayGroup>, handlers: TodayHandlers) {
    if (groups.isEmpty()) return
    item(key = "section-${section.name}") {
        SectionCard(section, groups.size) {
            groups.forEachIndexed { i, g ->
                if (i > 0) ItemDivider()
                TodayGroupBlock(g, section, handlers)
            }
        }
    }
}

/** A section: one card, its heading in the body colour and its count in the section's tone. */
@Composable
private fun SectionCard(section: TodaySection, count: Int, note: String? = null, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(bottom = 4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)
                    .clearAndSetSemantics {
                        heading()
                        contentDescription = listOfNotNull(section.label, "$count", note).joinToString(", ")
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    section.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                TonePill("$count", sectionTone(section))
                Spacer(Modifier.weight(1f))
                if (note != null) {
                    Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

/** One piece of work and its sessions — each a tap to open. */
@Composable
private fun TodayGroupBlock(g: TodayGroup, section: TodaySection, handlers: TodayHandlers) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        if (g.key.isNullOrEmpty()) {
            // Not a heading: it names what is missing, set quieter than a
            // ticket's title so it cannot read as a fifth section.
            Text(
                "Not linked to a ticket",
                style = MaterialTheme.typography.labelLarge,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.Top) {
                Text(
                    groupLabel(g.key, g.title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                g.statusName?.let {
                    Spacer(Modifier.width(8.dp))
                    TonePill(it, categoryTone(g.statusCategory))
                }
            }
        }
        for (s in g.sessions) SessionLine(s, section, onOpen = { handlers.onOpenSession(s.id) })
    }
}

/**
 * A session: status dot, name over host, the reason it is listed, chevron.
 * One node for a screen reader, said as what a tap does.
 */
@Composable
private fun SessionLine(s: TodaySession, section: TodaySection, onOpen: () -> Unit) {
    val reason: Pair<String, StatusTone>? = when {
        section == TodaySection.Waiting && s.attention != null -> attentionLabel(s.attention) to attentionTone(s.attention)
        section == TodaySection.Stale && s.stale != null -> staleLabel(s.stale) to StatusTone.IDLE
        else -> null
    }
    val spoken = listOfNotNull(s.name, "on ${s.hostAlias}".takeIf { s.hostAlias.isNotEmpty() }, reason?.first).joinToString(", ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TOUCH_TARGET)
            .clickable(onClick = onOpen)
            .clearAndSetSemantics {
                contentDescription = "$spoken. Open session"
                role = Role.Button
                onClick { onOpen(); true }
            }
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(s.claudeStatus, null)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                s.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (s.hostAlias.isNotEmpty()) {
                Text(
                    s.hostAlias,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        reason?.let { (label, tone) ->
            Spacer(Modifier.width(8.dp))
            TonePill(label, tone)
        }
        Icon(
            // `FleetIcons.ArrowBack` turned to point forward, as the Sessions
            // list's folding chevron is.
            FleetIcons.ArrowBack,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp).size(18.dp).rotate(180f),
        )
    }
}

@Composable
private fun ShippedRow(x: TodayShipped) {
    val label = if (!x.key.isNullOrEmpty()) groupLabel(x.key, x.title) else x.title.ifEmpty { "Untitled work" }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = TOUCH_TARGET).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            FleetIcons.Check,
            contentDescription = null,
            tint = LocalStatusColors.current(StatusTone.COMPLETED).dot,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TonePill(if (x.how == "done") "Done" else "PR", StatusTone.COMPLETED)
    }
}

@Composable
private fun ItemDivider() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** Copy and Share, pinned under the list so they never scroll away from a long day. */
@Composable
private fun TodayFooter(enabled: Boolean, filtered: Boolean, copied: Boolean, onCopy: () -> Unit, onShare: () -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onCopy, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = TOUCH_TARGET)) {
            // A filtered copy says so: a standup without Shipped because a
            // chip was on is a surprise worth a word.
            Text(
                when {
                    copied -> "Copied"
                    filtered -> "Copy filtered"
                    else -> "Copy standup"
                },
                maxLines = 1,
            )
        }
        Button(onClick = onShare, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = TOUCH_TARGET)) {
            Text("Share", maxLines = 1)
        }
    }
}

@Composable
private fun EmptyNote(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(action) }
    }
}

/**
 * A small label in a tone's container colours — the same pair the Sessions
 * list's status chip uses, so a colour means one thing across the app. The
 * idle pair sits close to the card, so it gets an outline to keep its edge.
 */
@Composable
private fun TonePill(text: String, tone: StatusTone) {
    val c = LocalStatusColors.current(tone)
    val container = if (c.container.alpha == 0f) MaterialTheme.colorScheme.surfaceContainerHighest else c.container
    val edge = if (tone == StatusTone.IDLE || c.container.alpha == 0f) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(container)
            .then(if (edge != null) Modifier.border(edge, shape) else Modifier)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = c.onContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ToneSwatch(tone: StatusTone) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(LocalStatusColors.current(tone).dot))
}

/** Waiting is amber (answer it), in progress blue, shipped green, stale grey — the Sessions list's tones. */
internal fun sectionTone(s: TodaySection): StatusTone = when (s) {
    TodaySection.Waiting -> StatusTone.BLOCKED
    TodaySection.InProgress -> StatusTone.WORKING
    TodaySection.Shipped -> StatusTone.COMPLETED
    TodaySection.Stale -> StatusTone.IDLE
}

/**
 * The hub's attention reasons in the app's triage colours: amber is a
 * person's answer or decision, red is something broken to go and fix, grey
 * is nothing a phone can act on.
 */
internal fun attentionTone(reason: String): StatusTone = when (reason) {
    "stuck" -> StatusTone.STUCK
    "failed", "stop_failed", "ci_failing" -> StatusTone.FAILED
    "lifecycle" -> StatusTone.IDLE
    else -> StatusTone.BLOCKED
}

private fun categoryTone(c: StatusCategory?): StatusTone = when (c) {
    StatusCategory.InProgress -> StatusTone.WORKING
    StatusCategory.Done -> StatusTone.COMPLETED
    else -> StatusTone.IDLE
}
