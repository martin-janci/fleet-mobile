package dev.claudefleet.mobile.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.ui.theme.Fleet

/**
 * The Progress ring (manual: loaders): a real fraction on the track, never a
 * spinner, with the real amount inside it ("12.4 of 31 MB"). For a wait the
 * phone can measure: a download, an upload.
 */
@Composable
fun ProgressRing(
    fraction: Float,
    label: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    size: Dp = 168.dp,
    stroke: Dp = 10.dp,
) {
    val o = Fleet.colors
    val f = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier.size(size).semantics { progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val w = stroke.toPx()
            val inset = w / 2
            val arc = Size(this.size.width - w, this.size.height - w)
            drawArc(o.track, 0f, 360f, useCenter = false, topLeft = Offset(inset, inset), size = arc, style = Stroke(w))
            if (f > 0f) {
                drawArc(o.accent, -90f, 360f * f, useCenter = false, topLeft = Offset(inset, inset), size = arc, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = o.fg, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
            if (detail != null) Text(detail, color = o.fgMuted, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

/**
 * The small Progress ring: the same track and accent arc, without the amount
 * inside, for a row or a sheet that already says what is moving. Replaces a
 * determinate `LinearProgressIndicator` (manual: download/sync of known size
 * uses a Progress ring). Read out as "N percent".
 */
@Composable
fun ProgressRing(
    fraction: Float,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    color: Color = Fleet.colors.accent,
) {
    val o = Fleet.colors
    val f = fraction.coerceIn(0f, 1f)
    val percent = (f * 100).toInt()
    Canvas(
        modifier.size(size).semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f)
            contentDescription = "$percent percent"
        },
    ) {
        val w = (this.size.minDimension * 0.12f).coerceAtLeast(2f)
        val inset = w / 2
        val arc = Size(this.size.width - w, this.size.height - w)
        drawArc(o.track, 0f, 360f, useCenter = false, topLeft = Offset(inset, inset), size = arc, style = Stroke(w))
        if (f > 0f) {
            drawArc(color, -90f, 360f * f, useCenter = false, topLeft = Offset(inset, inset), size = arc, style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}

/** "12.4 of 31 MB": one decimal under 100 MB, whole above, and never a trailing ".0". */
fun megabytes(done: Long, total: Long): String {
    fun mb(b: Long): String {
        val v = b / 1_000_000.0
        return if (v < 100) ((v * 10).toLong() / 10.0).let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() } else v.toLong().toString()
    }
    return if (total > 0) "${mb(done)} of ${mb(total)} MB" else "${mb(done)} MB"
}
