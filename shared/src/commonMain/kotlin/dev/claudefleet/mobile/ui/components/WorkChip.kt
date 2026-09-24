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
 * The desktop's vocabulary (M4): a **suggestion** is drawn with a dotted
 * outline, because nobody has said yes to it; an **unavailable** ticket (the
 * tracker stopped answering for it) is struck through. The title is
 * third-party text and is drawn as plain text, never as markup.
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
                style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))),
            )
        }
    } else {
        modifier.border(1.dp, outline, shape)
    }
    Row(
        modifier = framed
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        work.statusCategory?.let { WorkStatusDot(it) }
        val text = if (showTitle && work.title.isNotBlank() && work.key != null) "${work.label} · ${work.title}" else work.label
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            textDecoration = if (work.unavailable) TextDecoration.LineThrough else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = if (showTitle) 240.dp else 120.dp),
        )
    }
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
