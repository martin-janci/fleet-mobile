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
import dev.claudefleet.mobile.model.reasonLabel
import dev.claudefleet.mobile.model.relativeTime
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.kit.PhoneRow
import dev.claudefleet.mobile.ui.kit.StatusWord
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.StatusTone

/**
 * What needs a person, fleet-wide, oldest ask first: the New layout's first
 * tab. Not the Sessions list's *Needs you* filter, which follows that list's
 * other filters; the Inbox is every session that needs you, whatever the
 * Sessions tab is narrowed to, and its count is the bar's one badge.
 */
fun inboxRows(sessions: List<SessionRow>): List<SessionRow> =
    sessions.filter { it.needsAttention }.sortedWith(compareBy(nullsLast()) { it.lastActivityAt })

/** The word a needs-you row leads with: Failed for stuck and failed, else Needs you. */
internal fun inboxWord(row: SessionRow): StatusWord =
    when (StatusWord.of(StatusTone.of(row.claudeStatus, row.stuckKind))) {
        StatusWord.FAILED -> StatusWord.FAILED
        else -> StatusWord.NEEDS_YOU
    }

@Composable
fun InboxScreen(
    rows: List<SessionRow>,
    running: Int,
    nowSeconds: Long,
    onOpenSession: (Long) -> Unit,
    onOpenToday: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Inbox",
            subtitle = "${rows.size} need you · $running running",
            actions = { onOpenToday?.let { TextButton(onClick = it) { Text("Today") } } },
        )
        if (rows.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("Nothing needs you.", color = Fleet.colors.fgMuted, fontSize = 15.sp)
            }
            return@Column
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(rows, key = { it.id }) { row ->
                val word = inboxWord(row)
                PhoneRow(
                    title = row.displayName,
                    line = row.attentionReason?.let(::reasonLabel).orEmpty() + (row.hostAlias.takeIf { it.isNotBlank() }?.let { " · on $it" } ?: ""),
                    word = word,
                    lead = if (word == StatusWord.NEEDS_YOU) "Waiting for you" else word.label,
                    age = relativeTime(row.lastActivityAt, nowSeconds),
                    onClick = { onOpenSession(row.id) },
                )
            }
        }
    }
}
