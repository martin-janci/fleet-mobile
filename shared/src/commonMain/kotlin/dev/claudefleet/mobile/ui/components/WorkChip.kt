package dev.claudefleet.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.StatusCategory
import dev.claudefleet.mobile.ui.theme.StatusTone

/**
 * How a row's work chip is drawn — the desktop's vocabulary (claude-fleet
 * design §0.3.1): **solid** for a link a person or an agent made, solid with a
 * small **ring** for one the hub linked automatically (a branch key under
 * R3 / R5), **dashed with `?`** for a suggestion nobody has decided.
 */
enum class WorkChipStyle { Solid, Auto, Suggested }

/** What the chip says and how. [struck] is an unavailable ticket (C25). */
data class WorkChipLook(val text: String, val style: WorkChipStyle, val struck: Boolean) {
    /** Read aloud instead of the glyphs. */
    val spoken: String
        get() = buildString {
            append("work ").append(text.removeSuffix("?"))
            when (style) {
                WorkChipStyle.Solid -> Unit
                WorkChipStyle.Auto -> append(", linked automatically")
                WorkChipStyle.Suggested -> append(", suggested")
            }
            if (struck) append(", unavailable")
        }
}

/**
 * The chip a session row carries, or null for none. The confirmed link wins;
 * a suggestion shows only on a row without one. Pure, so the vocabulary is
 * tested without a device.
 */
fun workChipLook(row: SessionRow): WorkChipLook? {
    val work = row.work?.takeUnless { it.isSuggestion || it.label.isBlank() }
    if (work != null) {
        val decided = work.source == "manual" || work.source == "agent" || work.strength == "explicit"
        return WorkChipLook(work.label, if (decided) WorkChipStyle.Solid else WorkChipStyle.Auto, work.unavailable)
    }
    val guess = row.workSuggested?.takeUnless { it.label.isBlank() } ?: return null
    return WorkChipLook("${guess.label}?", WorkChipStyle.Suggested, guess.unavailable)
}

/** A ticket status as the list's status dot draws it. */
fun StatusCategory?.tone(): StatusTone = when (this) {
    StatusCategory.Todo -> StatusTone.IDLE
    StatusCategory.InProgress -> StatusTone.WORKING
    StatusCategory.Done -> StatusTone.COMPLETED
    StatusCategory.Unknown, null -> StatusTone.UNKNOWN
}

@Composable
fun WorkChip(look: WorkChipLook, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    val colors = MaterialTheme.colorScheme
    val outline = colors.outline
    val base = when (look.style) {
        WorkChipStyle.Suggested -> modifier.drawBehind {
            drawRoundRect(
                color = outline,
                style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                cornerRadius = CornerRadius(6.dp.toPx()),
            )
        }
        else -> modifier.clip(shape).background(colors.secondaryContainer)
    }
    Row(
        modifier = base
            .widthIn(max = 140.dp)
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .semantics { contentDescription = look.spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (look.style == WorkChipStyle.Auto) {
            Box(Modifier.size(6.dp).border(1.dp, colors.onSecondaryContainer, CircleShape))
        }
        Text(
            text = look.text,
            style = MaterialTheme.typography.labelSmall,
            color = if (look.style == WorkChipStyle.Suggested) colors.onSurfaceVariant else colors.onSecondaryContainer,
            textDecoration = if (look.struck) TextDecoration.LineThrough else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
