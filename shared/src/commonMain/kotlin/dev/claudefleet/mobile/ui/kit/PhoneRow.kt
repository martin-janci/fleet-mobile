package dev.claudefleet.mobile.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.OrbitTokens

/**
 * `SessionRow` at phone size (manual: PhoneRow): dot, title and age, then
 * what the row waits on, then at most two chips or row actions. Also for
 * tasks, hosts and files.
 *
 * Line two leads with the status word when there is one ("Needs you:
 * approve push to main"); [lead] overrides that word for rows that are not
 * sessions ("Signal lost"). Never put Approve here: answering a permission
 * opens the session's question card.
 */
@Composable
fun PhoneRow(
    title: String,
    line: String,
    modifier: Modifier = Modifier,
    word: StatusWord? = null,
    age: String? = null,
    lead: String? = word?.label,
    leadColor: Color = word?.color(Fleet.colors) ?: Fleet.colors.fgMuted,
    /** Between [lead] and [line]: a colon after a status word, else a dot. A task row's counts take a dot. */
    separator: String = if (word != null) ": " else " · ",
    selected: Boolean = false,
    divider: Boolean = true,
    /** False for a menu row (More, Control), which has no state to show. */
    dot: Boolean = true,
    onClick: (() -> Unit)? = null,
    chips: (@Composable RowScope.() -> Unit)? = null,
    /** A long press: starts bulk select on a session row. Needs [onClick]. */
    onLongClick: (() -> Unit)? = null,
    /** Drawn in the dot's place: the check box while rows are being picked. */
    leading: (@Composable () -> Unit)? = null,
) {
    val o = Fleet.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(o.accentSoft) else Modifier)
            .then(
                when {
                    onClick != null && onLongClick != null -> Modifier.longPressable(onClick, onLongClick)
                    onClick != null -> Modifier.clickable(onClick = onClick)
                    else -> Modifier
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = OrbitTokens.spacing("phone-row-min").dp)
                .padding(horizontal = OrbitTokens.spacing("phone-gutter").dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (leading != null) leading() else if (dot) OrbitDot(word, modifier = Modifier.padding(top = 6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        title,
                        modifier = Modifier.weight(1f),
                        color = o.fg,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (age != null) Text(age, color = o.fgMuted, fontSize = 13.sp, lineHeight = 18.sp)
                }
                Text(
                    buildAnnotatedString {
                        if (lead != null) {
                            withStyle(SpanStyle(color = leadColor)) { append(lead) }
                            if (line.isNotEmpty()) append(separator)
                        }
                        append(line)
                    },
                    color = o.fgMuted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (chips != null) {
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = chips,
                    )
                }
            }
        }
        if (divider) HorizontalDivider(color = o.border)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.longPressable(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Select")

/**
 * The neutral chip a row carries (host, PR, project), or a status chip when
 * [word] is given. One status chip per row at most; the colour is never the
 * only carrier, the word is always written.
 */
@Composable
fun OrbitChip(text: String, modifier: Modifier = Modifier, word: StatusWord? = null) {
    val o = Fleet.colors
    val (bg, fg) = when (word) {
        null -> o.chipBg to o.fg2
        StatusWord.NEEDS_YOU -> o.waitingSoft to o.statusWaiting
        StatusWord.FAILED -> o.failedSoft to o.fg
        StatusWord.DONE -> o.doneSoft to o.statusDone
        StatusWord.WORKING -> o.chipBg to o.statusWorking
        StatusWord.PAUSED, StatusWord.IDLE -> o.chipBg to o.statusIdle
    }
    Surface(
        modifier = modifier,
        color = bg,
        contentColor = fg,
        shape = RoundedCornerShape(OrbitTokens.radius("radius-sm").dp),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}
