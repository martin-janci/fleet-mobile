package dev.claudefleet.mobile.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.ConvContext
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.relativeTime
import dev.claudefleet.mobile.ui.theme.LocalStatusColors
import dev.claudefleet.mobile.ui.theme.StatusTone
import kotlin.math.roundToInt

/**
 * The one-line summary under a session's title, led by its [StatusDot]: not just
 * a status word, but how long the agent has been at it (or idle since when),
 * how full its context window is, and what the turn has cost so far — the
 * things a person reopening a session actually wants to know before reading
 * a word of the transcript.
 *
 * A separate composable from the pure text ([statusStripText]) so a device
 * screenshot is never the only way to check the wording: every case that
 * matters is a `StatusStripTest`, and this composable only draws whatever
 * that function already decided.
 */
@Composable
fun StatusStrip(
    row: SessionRow?,
    context: ConvContext?,
    nowSeconds: Long,
    modifier: Modifier = Modifier,
) {
    val text = statusStripText(row, context, nowSeconds)
    if (text.isBlank()) return
    val amber = contextIsTight(row, context)
    val colors = LocalStatusColors.current(StatusTone.BLOCKED)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        // A real dot in the status's own colour rather than a `●` glyph in
        // the text: the glyph was always grey, so "working" and "blocked"
        // led with the same mark.
        StatusDot(row?.claudeStatus, row?.stuckKind)
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (amber) colors.onContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The `/compact` offer, drawn in the strip's amber. Its own composable, not
 * part of [StatusStrip]'s row: beside the strip it pushed the text to nothing
 * on a phone, so the header gives it a line of its own under the strip — and
 * only while [contextIsTight], since a `/compact` offered at any other time
 * is a chip nobody asked for.
 */
@Composable
fun CompactChip(onCompact: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalStatusColors.current(StatusTone.BLOCKED)
    AssistChip(
        onClick = onCompact,
        label = { Text("/compact", maxLines = 1) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = colors.container,
            labelColor = colors.onContainer,
        ),
        border = null,
        modifier = modifier,
    )
}

/** Whether the context window is past [CONTEXT_WARNING_PCT]: the strip turns amber and `/compact` is offered. */
fun contextIsTight(row: SessionRow?, context: ConvContext?): Boolean {
    val pct = context?.pct ?: row?.contextPct
    return pct != null && pct >= CONTEXT_WARNING_PCT
}

/** The context percentage past which the strip turns amber and offers `/compact`. */
internal const val CONTEXT_WARNING_PCT: Double = 80.0

/**
 * Builds the strip's text from what is already on the screen — no hub call of
 * its own. [row] carries the turn's own cost/model
 * ([SessionRow.usageCostMicros] / [SessionRow.usageModel], `usage_report` has
 * no per-session shape to fetch instead), and [context] is the freshest
 * conversation read's context view, falling back to [SessionRow.contextPct]
 * when the transcript tail carried none.
 *
 * Cost, model and context are only shown while the agent is actually
 * [working][ConvContext] — that is what makes them belong to *this* turn
 * rather than reading as leftover numbers on a session that has gone idle.
 * `idle` says only how long ago it stopped; any other status is shown as the
 * bare word, since nothing else here has a caption for it yet.
 */
fun statusStripText(row: SessionRow?, context: ConvContext?, nowSeconds: Long): String {
    if (row == null) return ""
    return when (row.claudeStatus) {
        "working" -> workingStrip(row, context, nowSeconds)
        "idle" -> {
            val elapsed = relativeTime(row.lastStopAt, nowSeconds)
            if (elapsed != null) "idle since $elapsed" else "idle"
        }
        null -> ""
        else -> row.claudeStatus
    }
}

private fun workingStrip(row: SessionRow, context: ConvContext?, nowSeconds: Long): String {
    val elapsed = relativeTime(row.lastTurnAt ?: row.startedAt, nowSeconds)
    val pieces = mutableListOf(if (elapsed != null) "working $elapsed" else "working")
    val pct = context?.pct ?: row.contextPct
    if (pct != null) {
        val stale = context?.stale == true
        pieces += "ctx ${pct.roundToInt()} %" + if (stale) " (stale)" else ""
    }
    row.usageCostMicros?.let { pieces += formatUsd(it) }
    row.usageModel?.let { pieces += it }
    return pieces.joinToString(" · ")
}

/** `$1.84` from micro-USD, without `String.format`/`java.util` — this runs on every KMP target. */
private fun formatUsd(micros: Long): String {
    val cents = (micros / 10_000.0).roundToInt()
    val dollars = cents / 100
    val remainder = (cents % 100).let { if (it < 0) -it else it }
    return "$$dollars.${remainder.toString().padStart(2, '0')}"
}
