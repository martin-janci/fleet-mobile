package dev.claudefleet.mobile.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
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
    val amber = contextIsTight(row, context)
    val colors = LocalStatusColors.current(StatusTone.BLOCKED)
    // The strip is measured before it is written: on the narrowest phone the
    // four-fact line does not fit, and a fact clipped mid-word ("$13.2…") is
    // worse than one that is honestly absent. See [stripFitsModel].
    BoxWithConstraints(modifier = modifier) {
        val text = statusStripText(row, context, nowSeconds, includeModel = stripFitsModel(maxWidth.value.toDouble()))
        if (text.isBlank()) return@BoxWithConstraints
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                // A backstop, not the plan: a friendly name long enough to
                // matter is not in this text at all, so what reaches here is
                // the bounded line [statusStripText] builds.
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Whether a strip [availableDp] wide can carry the model as well.
 *
 * The full four-fact line — `working 2m · ctx 91% · $13.22 · opus-5` — is
 * about 38 characters of `labelLarge`, and the status dot and its gap take
 * 18 dp before a word of it is drawn. That clears a 360 dp phone's content
 * width and does not clear a 320 dp one, which is where the threshold sits.
 *
 * Its own function, and a pure one, so the rule is stated once and tested
 * without a device.
 */
internal fun stripFitsModel(availableDp: Double): Boolean = availableDp >= 300.0

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
fun statusStripText(
    row: SessionRow?,
    context: ConvContext?,
    nowSeconds: Long,
    includeModel: Boolean = true,
): String {
    if (row == null) return ""
    return when (row.claudeStatus) {
        "working" -> workingStrip(row, context, nowSeconds, includeModel)
        "idle" -> {
            val elapsed = compactElapsed(row.lastStopAt, nowSeconds)
            if (elapsed != null) "idle since $elapsed" else "idle"
        }
        null -> ""
        else -> row.claudeStatus
    }
}

private fun workingStrip(
    row: SessionRow,
    context: ConvContext?,
    nowSeconds: Long,
    includeModel: Boolean,
): String {
    val elapsed = compactElapsed(row.lastTurnAt ?: row.startedAt, nowSeconds)
    val pieces = mutableListOf(if (elapsed != null) "working $elapsed" else "working")
    val pct = context?.pct ?: row.contextPct
    if (pct != null) {
        val stale = context?.stale == true
        pieces += "ctx ${pct.roundToInt()}%" + if (stale) " (stale)" else ""
    }
    row.usageCostMicros?.let { pieces += formatUsd(it) }
    if (includeModel) row.usageModel?.let { pieces += shortModel(it) }
    return pieces.joinToString(" · ")
}

/**
 * `relativeTime`'s vocabulary with the spaces squeezed out: `now`, `2m`, `3h`,
 * `4d`.
 *
 * Not a change to `relativeTime` itself, which reads well in the places that
 * have room for it (a session row, the Today sheet). This strip does not: four
 * facts share one line on a 320 dp screen, and "2 min" against "2m" is three
 * characters that buy nothing here.
 */
private fun compactElapsed(epochSeconds: Long?, nowSeconds: Long): String? {
    if (epochSeconds == null) return null
    val delta = (nowSeconds - epochSeconds).coerceAtLeast(0)
    return when {
        delta < 60 -> "now"
        delta < 3600 -> "${delta / 60}m"
        delta < 86_400 -> "${delta / 3600}h"
        else -> "${delta / 86_400}d"
    }
}

/**
 * `claude-opus-5` as `opus-5`. Every model the hub reports carries the prefix,
 * so it is seven characters that tell two of them apart not at all — the
 * desktop's conversation header drops it for the same reason.
 */
private fun shortModel(model: String): String = model.removePrefix("claude-")

/** `$1.84` from micro-USD, without `String.format`/`java.util` — this runs on every KMP target. */
private fun formatUsd(micros: Long): String {
    val cents = (micros / 10_000.0).roundToInt()
    val dollars = cents / 100
    val remainder = (cents % 100).let { if (it < 0) -it else it }
    return "$$dollars.${remainder.toString().padStart(2, '0')}"
}
