package dev.claudefleet.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Every icon this app draws, hand-rolled from primitives.
 *
 * `org.jetbrains.compose.material:material-icons-core` is not published past
 * 1.7.3 — CMP 1.12's own Gradle plugin does not even expose a
 * `materialIconsCore` accessor, only `materialIconsExtended`, which itself is
 * hardcoded to the same stale 1.7.3 coordinate. Rather than pull in a
 * two-year-unmaintained artifact (or the extended pack this app does not
 * need), every icon the app draws — the two tab glyphs the core set never had
 * plus the handful of `Icons.Outlined.*` the screens use — is a plain
 * [ImageVector] built from lines, arcs and a couple of filled/hollow shapes.
 * No `androidx.compose.material.icons` import anywhere in this app.
 */
object FleetIcons {
    val Sessions: ImageVector by lazy {
        ImageVector.Builder("Sessions", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                // rounded bubble
                moveTo(4f, 3f); lineTo(20f, 3f); quadTo(22f, 3f, 22f, 5f)
                lineTo(22f, 15f); quadTo(22f, 17f, 20f, 17f); lineTo(9f, 17f)
                lineTo(5f, 21f); lineTo(5f, 17f); lineTo(4f, 17f); quadTo(2f, 17f, 2f, 15f)
                lineTo(2f, 5f); quadTo(2f, 3f, 4f, 3f); close()
                // hollow inside
                moveTo(5f, 6f); lineTo(19f, 6f); lineTo(19f, 14f); lineTo(7.5f, 14f); lineTo(6f, 15.5f)
                lineTo(6f, 14f); lineTo(5f, 14f); close()
            }
        }.build()
    }

    val Hosts: ImageVector by lazy {
        ImageVector.Builder("Hosts", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                // two shelves
                moveTo(3f, 4f); lineTo(21f, 4f); lineTo(21f, 10f); lineTo(3f, 10f); close()
                moveTo(3f, 14f); lineTo(21f, 14f); lineTo(21f, 20f); lineTo(3f, 20f); close()
                // hollow shelf interiors
                moveTo(5f, 6f); lineTo(19f, 6f); lineTo(19f, 8f); lineTo(5f, 8f); close()
                moveTo(5f, 16f); lineTo(19f, 16f); lineTo(19f, 18f); lineTo(5f, 18f); close()
            }
        }.build()
    }

    /** A gear: a filled ring plus eight radial teeth. */
    val Settings: ImageVector by lazy {
        ImageVector.Builder("Settings", 24.dp, 24.dp, 24f, 24f).apply {
            // The ring: outer circle r=9 minus inner circle r=4, both centered
            // on (12,12), each drawn as two half-circle arcs.
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(21f, 12f)
                arcTo(9f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 3f, y1 = 12f)
                arcTo(9f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 21f, y1 = 12f)
                close()
                moveTo(16f, 12f)
                arcTo(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 8f, y1 = 12f)
                arcTo(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 16f, y1 = 12f)
                close()
            }
            // The teeth: eight short quads pointing straight out from the
            // ring's outer edge (r=9) to r=11, evenly spaced by 45 degrees. A
            // separate path (default NonZero fill, no hollow) so a tooth's
            // overlap with the ring's own outer edge can never double-count
            // under an EvenOdd rule and punch an accidental hole.
            path(fill = SolidColor(Color.Black)) {
                val center = 12f
                val innerR = 9f
                val outerR = 11f
                val halfWidth = 1.1f
                for (i in 0 until 8) {
                    val angle = (i * 45f) * (kotlin.math.PI.toFloat() / 180f)
                    val radialX = cos(angle)
                    val radialY = sin(angle)
                    // perpendicular to the radial direction
                    val tangentX = -radialY
                    val tangentY = radialX
                    val ix = center + radialX * innerR
                    val iy = center + radialY * innerR
                    val ox = center + radialX * outerR
                    val oy = center + radialY * outerR
                    moveTo(ix - tangentX * halfWidth, iy - tangentY * halfWidth)
                    lineTo(ix + tangentX * halfWidth, iy + tangentY * halfWidth)
                    lineTo(ox + tangentX * halfWidth, oy + tangentY * halfWidth)
                    lineTo(ox - tangentX * halfWidth, oy - tangentY * halfWidth)
                    close()
                }
            }
        }.build()
    }

    /** A filled triangle with the exclamation mark punched out as a hole. */
    val Warning: ImageVector by lazy {
        ImageVector.Builder("Warning", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(2f, 20f); lineTo(22f, 20f); lineTo(12f, 3f); close()
                // bang bar, 2 wide, y 9..14 — a hole in the triangle
                moveTo(11f, 9f); lineTo(13f, 9f); lineTo(13f, 14f); lineTo(11f, 14f); close()
                // bang dot at y ~16..18 — a hole in the triangle
                moveTo(11f, 16f); lineTo(13f, 16f); lineTo(13f, 18f); lineTo(11f, 18f); close()
            }
        }.build()
    }

    /** A stroked three-quarter arc with a chevron arrowhead at its open end. */
    val Refresh: ImageVector by lazy {
        ImageVector.Builder("Refresh", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(19f, 12f)
                arcTo(7f, 7f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 12f, y1 = 5f)
                // arrowhead at the arc's end
                moveTo(12f, 5f); lineTo(9f, 7f)
                moveTo(12f, 5f); lineTo(15f, 7f)
            }
        }.build()
    }

    /** A stroked horizontal line with a chevron for the back arrow's head. */
    val ArrowBack: ImageVector by lazy {
        ImageVector.Builder("ArrowBack", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(20f, 12f); lineTo(4f, 12f)
                moveTo(11f, 5f); lineTo(4f, 12f); lineTo(11f, 19f)
            }
        }.build()
    }

    /** A filled paper plane. */
    val Send: ImageVector by lazy {
        ImageVector.Builder("Send", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 20f); lineTo(21f, 12f); lineTo(3f, 4f)
                lineTo(3f, 10f); lineTo(15f, 12f); lineTo(3f, 14f); close()
            }
        }.build()
    }

    /** A stroked checkmark polyline. */
    val Check: ImageVector by lazy {
        ImageVector.Builder("Check", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4f, 12f); lineTo(9f, 17f); lineTo(20f, 6f)
            }
        }.build()
    }

    /** A stroked X, two crossing lines. */
    val Close: ImageVector by lazy {
        ImageVector.Builder("Close", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6f, 6f); lineTo(18f, 18f)
                moveTo(18f, 6f); lineTo(6f, 18f)
            }
        }.build()
    }

    /**
     * Two stroked overlapping rounded rectangles: the copy-to-clipboard
     * glyph. Both paths are stroke-only, like [ArrowBack]/[Check]/[Close] --
     * `Icon()` always tints through [androidx.compose.ui.graphics.ColorFilter.tint],
     * which replaces color with coverage, so a filled "front sheet" (to
     * visually occlude the back one) would buy nothing a plain outline
     * doesn't already give at this size.
     */
    val Copy: ImageVector by lazy {
        ImageVector.Builder("Copy", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // back sheet, top-right
                moveTo(9f, 3f); lineTo(19f, 3f); quadTo(21f, 3f, 21f, 5f)
                lineTo(21f, 15f); quadTo(21f, 17f, 19f, 17f); lineTo(9f, 17f)
                quadTo(7f, 17f, 7f, 15f); lineTo(7f, 5f); quadTo(7f, 3f, 9f, 3f); close()
            }
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // front sheet, bottom-left, overlapping the back one
                moveTo(5f, 7f); lineTo(15f, 7f); quadTo(17f, 7f, 17f, 9f)
                lineTo(17f, 19f); quadTo(17f, 21f, 15f, 21f); lineTo(5f, 21f)
                quadTo(3f, 21f, 3f, 19f); lineTo(3f, 9f); quadTo(3f, 7f, 5f, 7f); close()
            }
        }.build()
    }
}
