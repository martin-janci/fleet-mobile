package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.askedAt
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.help.TourAnchor
import dev.claudefleet.mobile.ui.help.TourAnchors
import dev.claudefleet.mobile.ui.help.tourAnchor
import dev.claudefleet.mobile.ui.kit.OrbitPullToRefresh
import dev.claudefleet.mobile.ui.kit.StatusWord
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.StatusTone

/**
 * What needs a person, fleet-wide, oldest ask first: the New layout's first
 * tab. Not the Sessions list's *Needs you* filter, which follows that list's
 * other filters; the Inbox is every session that needs you, whatever the
 * Sessions tab is narrowed to, and its count is the bar's one badge.
 *
 * Sorted by when each one asked ([askedAt]: the hub's attention stamp, else
 * the nearest the phone has), so the one waiting longest is on top, then by
 * id so the list does not reshuffle on every event frame.
 */
fun inboxRows(sessions: List<SessionRow>): List<SessionRow> =
    sessions.filter { it.needsAttention }
        .sortedWith(compareBy<SessionRow, Long?>(nullsLast()) { it.askedAt }.thenBy { it.id })

/** The word a needs-you row leads with: Failed for stuck and failed, else Needs you. */
internal fun inboxWord(row: SessionRow): StatusWord =
    when (StatusWord.of(StatusTone.of(row.claudeStatus, row.stuckKind))) {
        StatusWord.FAILED -> StatusWord.FAILED
        else -> StatusWord.NEEDS_YOU
    }

/** "4 need you · 6 running": the Inbox header's line. */
internal fun inboxSubtitle(needYou: Int, running: Int): String = "$needYou need${if (needYou == 1) "s" else ""} you · $running running"

@Composable
fun InboxScreen(
    rows: List<SessionRow>,
    running: Int,
    nowSeconds: Long,
    onOpenSession: (Long) -> Unit,
    onOpenToday: (() -> Unit)?,
    modifier: Modifier = Modifier,
    live: Boolean = true,
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    /** Above the list: the hub-version banner and the Update ready line (14.18), when there are any. */
    top: @Composable () -> Unit = {},
    /** Where the tour's stops are (14.22); null records nothing. */
    anchors: TourAnchors? = null,
    /** Account uuid → label, for a row's account and a paused row's line (step 4.10). */
    accountNames: Map<String, String> = emptyMap(),
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Inbox",
            subtitle = inboxSubtitle(rows.size, running),
            modifier = Modifier.tourAnchor(anchors, TourAnchor.Header),
            actions = {
                onOpenToday?.let { TextButton(onClick = it, modifier = Modifier.tourAnchor(anchors, TourAnchor.Today)) { Text("Today") } }
            },
        )
        top()
        OrbitPullToRefresh(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (rows.isEmpty()) {
                    item(key = "empty") {
                        Box(modifier = Modifier.fillParentMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("Nothing needs you.", color = Fleet.colors.fgMuted, fontSize = 15.sp)
                        }
                    }
                } else {
                    item(key = "heading") {
                        Text(
                            "Needs you · oldest first",
                            color = Fleet.colors.fgMuted,
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                }
                items(rows, key = { it.id }) { row ->
                    PhoneSessionRow(
                        modifier = if (row.id == rows.first().id) Modifier.tourAnchor(anchors, TourAnchor.FirstRow) else Modifier,
                        row = row,
                        nowSeconds = nowSeconds,
                        live = live,
                        showHost = true,
                        since = row.askedAt,
                        accountName = row.accountUuid?.let(accountNames::get),
                        onClick = { onOpenSession(row.id) },
                    )
                }
            }
        }
    }
}
