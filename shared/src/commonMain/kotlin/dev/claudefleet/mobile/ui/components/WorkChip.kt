package dev.claudefleet.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.StatusCategory
import dev.claudefleet.mobile.model.WorkSummary
import dev.claudefleet.mobile.ui.theme.LocalStatusColors
import dev.claudefleet.mobile.ui.theme.StatusTone

/**
 * A session's work as a small chip: the key, and — when there is room — the
 * ticket's status and title.
 *
 * The desktop's vocabulary (claude-fleet design §0.3.1): a **suggestion** is
 * dashed with a `?`, because nobody has said yes to it; a link the hub made
 * by itself carries a small **ring**; an **unavailable** ticket (the tracker
 * stopped answering for it) is struck through. None of that is left to the
 * eye alone: the chip is read aloud as [workChipDescription], so a screen
 * reader tells a guess from a link. The title is third-party text and is
 * drawn as plain text, never as markup.
 */
@Composable
fun WorkChip(
    work: WorkSummary,
    suggested: Boolean,
    modifier: Modifier = Modifier,
    showTitle: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val outline = MaterialTheme.colorScheme.outline
    val shape = RoundedCornerShape(6.dp)
    val framed = if (suggested) {
        modifier.drawBehind {
            drawRoundRect(
                color = outline,
                cornerRadius = CornerRadius(6.dp.toPx()),
                style = Stroke(
                    width = 1.dp.toPx(),
                    // In dp, like the stroke: 4 px dashes all but vanish on a
                    // high-density screen, and the dash is the whole signal.
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                ),
            )
        }
    } else {
        modifier.border(1.dp, outline, shape)
    }
    val spoken = workChipDescription(work, suggested, showTitle)
    Row(
        modifier = framed
            .clip(shape)
            // One node, read as one sentence; the tap (when there is one)
            // stays on it, since the children below say nothing of their own.
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        work.statusCategory?.let { WorkStatusDot(it) }
        if (!suggested && isAutoLinked(work)) {
            Box(
                Modifier
                    .padding(end = 3.dp)
                    .size(6.dp)
                    .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
            )
        }
        Text(
            text = workChipText(work, suggested, showTitle),
            style = MaterialTheme.typography.labelSmall,
            textDecoration = if (work.unavailable) TextDecoration.LineThrough else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = if (showTitle) 240.dp else 120.dp)
                .clearAndSetSemantics { },
        )
    }
}

/**
 * A link the hub made by itself — from a branch, a PR, a prompt — rather than
 * one a person or the agent set, or one started from the ticket. `explicit`
 * strength is a decision whatever its source.
 */
fun isAutoLinked(work: WorkSummary): Boolean =
    work.strength != "explicit" && work.source !in DECIDED_SOURCES && work.source.isNotBlank()

private val DECIDED_SOURCES = setOf("manual", "agent", "started")

/** What the chip draws: the key (with its title when asked), and `?` on a guess. */
fun workChipText(work: WorkSummary, suggested: Boolean, showTitle: Boolean = false): String {
    val base = if (showTitle && work.title.isNotBlank() && work.key != null) "${work.label} · ${work.title}" else work.label
    return if (suggested) "$base?" else base
}

/**
 * What a screen reader says for the chip: everything the drawing says with a
 * dash, a ring, a dot and a strike-through, in words.
 */
fun workChipDescription(work: WorkSummary, suggested: Boolean, showTitle: Boolean = false): String = buildList {
    add(if (suggested) "Suggested work ${work.label}, not confirmed" else "Work ${work.label}")
    if (showTitle && work.key != null && work.title.isNotBlank()) add(work.title)
    if (!suggested && isAutoLinked(work)) add("linked automatically")
    (work.statusName ?: work.statusCategory?.spoken())?.let { add(it) }
    if (work.unavailable) add("ticket unavailable")
}.joinToString(", ")

private fun StatusCategory.spoken(): String? = when (this) {
    StatusCategory.Todo -> "to do"
    StatusCategory.InProgress -> "in progress"
    StatusCategory.Done -> "done"
    StatusCategory.Unknown -> null
}

/** A ticket's status bucket as a dot, in the session palette: to do idle, in progress working, done completed. */
@Composable
fun WorkStatusDot(category: StatusCategory, modifier: Modifier = Modifier) {
    val tone = when (category) {
        StatusCategory.Todo -> StatusTone.IDLE
        StatusCategory.InProgress -> StatusTone.WORKING
        StatusCategory.Done -> StatusTone.COMPLETED
        StatusCategory.Unknown -> StatusTone.UNKNOWN
    }
    Box(
        modifier
            .padding(end = 4.dp)
            .size(6.dp)
            .clip(CircleShape)
            .background(LocalStatusColors.current(tone).dot),
    )
}
