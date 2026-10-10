package dev.claudefleet.mobile.ui.kit

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.ui.theme.Fleet
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The phone's loaders, ported from the manual's Loader card and the
 * MobileStates / MobileFullscreenLoaders boards. Each is a single Canvas
 * (the phone's version of "CSS and SVG only, under 100 nodes"), draws from
 * the brand and status tokens only, and follows [rememberLoaderClock]: with
 * reduced motion it holds its still pose and fades.
 *
 * None of them decides when to appear. A screen shows one only once
 * [rememberLoaderVisible] says the wait has passed `loader-delay`, and only
 * one per screen.
 */

/** What the Orbit mark is doing. Each is one wait from the manual's Motion section. */
enum class MarkMotion(val description: String) {
    /** The mark at rest: empty and error screens. */
    Still("Orbit Fleet"),

    /** The default loader: the three hosts turn round the hub. */
    Orbit("Loading"),

    /** Connecting to the hub: the amber host looks for the others. Also pull to refresh once released. */
    Chase("Connecting"),

    /** Reconnecting after the network dropped: the hosts fall in and come back out. */
    GravityWell("Reconnecting"),

    /** The hub or a host is offline: a broken ring and drifting hosts, never a spin. */
    SignalLost("Signal lost"),

    /** The splash and pairing: the ring draws itself, then the hub and the hosts pop in. Reduced motion shows the finished mark. */
    DrawOn("Starting"),

    /** Waiting on a person, or a pairing that landed: a ring pulses out from the orbit. */
    Halo("Waiting on you"),
}

/** The mark's geometry on its 108-unit grid (manual: OrbitMark), shared by every motion. */
private object Mark {
    const val GRID = 108f
    const val CENTRE = 54f
    const val ORBIT = 24f
    const val HUB = 10f
    const val HOST = 7.5f

    /** The hosts' angles: top, the amber one at lower right, lower left. */
    const val TOP = -90f
    const val AMBER = 30f
    const val LEFT = 150f

    val orbitEasing = CubicBezierEasing(0.65f, 0.05f, 0.35f, 0.95f)
}

private fun hostAt(angleDeg: Float, radius: Float = Mark.ORBIT): Offset {
    val a = angleDeg * PI.toFloat() / 180f
    return Offset(Mark.CENTRE + radius * cos(a), Mark.CENTRE + radius * sin(a))
}

/**
 * The Orbit mark, still or in one of its motions. [pull] (0 to 1) draws the
 * ring as far as a pull to refresh has gone, with the hosts turning under the
 * finger; it wins over [motion].
 */
@Composable
fun OrbitMarkLoader(
    motion: MarkMotion,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    pull: Float? = null,
    /** Draw [motion]'s first frame and run no animation (the offline banner after a while, r16 M8). */
    frozen: Boolean = false,
) {
    val o = Fleet.colors
    val ink = o.brandInk
    val light = o.brandLight
    val amber = o.brandAmber
    val failed = o.statusFailed
    val period = when (motion) {
        MarkMotion.Still -> 1_600
        MarkMotion.Orbit -> 1_600
        MarkMotion.Chase -> 1_200
        MarkMotion.GravityWell -> 1_800
        MarkMotion.SignalLost -> 2_400
        MarkMotion.DrawOn -> 3_000
        MarkMotion.Halo -> 2_000
    }
    val clock = if (motion == MarkMotion.Still || pull != null || frozen) null else rememberLoaderClock(period)
    val t = clock?.phase ?: 0f
    val alpha = clock?.alpha ?: 1f
    Canvas(modifier.size(size).semantics { contentDescription = motion.description }) {
        val k = this.size.minDimension / Mark.GRID
        scale(k, pivot = Offset.Zero) {
            drawRoundRect(ink, size = Size(Mark.GRID, Mark.GRID), cornerRadius = CornerRadius(24f), alpha = alpha)
            val c = Offset(Mark.CENTRE, Mark.CENTRE)
            when {
                pull != null -> {
                    val p = pull.coerceIn(0f, 1f)
                    drawArc(
                        light.copy(alpha = 0.5f),
                        startAngle = -90f,
                        sweepAngle = 360f * p,
                        useCenter = false,
                        topLeft = Offset(Mark.CENTRE - Mark.ORBIT, Mark.CENTRE - Mark.ORBIT),
                        size = Size(Mark.ORBIT * 2, Mark.ORBIT * 2),
                        style = Stroke(5f),
                    )
                    drawCircle(light, Mark.HUB, c)
                    val turn = 120f * p
                    drawCircle(light, Mark.HOST, hostAt(Mark.TOP + turn), alpha = p)
                    drawCircle(amber, Mark.HOST, hostAt(Mark.AMBER + turn), alpha = p)
                    drawCircle(light, Mark.HOST, hostAt(Mark.LEFT + turn), alpha = p)
                }
                motion == MarkMotion.Chase -> {
                    drawCircle(light.copy(alpha = 0.35f * alpha), Mark.ORBIT, c, style = Stroke(5f))
                    drawCircle(light, Mark.HUB, c, alpha = alpha)
                    drawCircle(light, Mark.HOST, hostAt(Mark.TOP), alpha = alpha * dim(t))
                    drawCircle(light, Mark.HOST, hostAt(Mark.LEFT), alpha = alpha * dim(t - 0.33f))
                    drawCircle(amber, Mark.HOST, hostAt(Mark.AMBER + 360f * t), alpha = alpha)
                }
                motion == MarkMotion.DrawOn -> {
                    // The finished mark when reduced: Draw-on never fades a half-drawn ring.
                    val u = if (clock == null || clock.reduced) DRAW_ON_DONE else t
                    val fade = alpha * if (u > 0.85f) 1f - (u - 0.85f) / 0.15f else 1f
                    drawArc(
                        light.copy(alpha = 0.5f * fade),
                        startAngle = -90f,
                        sweepAngle = 360f * (u / 0.25f).coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = Offset(Mark.CENTRE - Mark.ORBIT, Mark.CENTRE - Mark.ORBIT),
                        size = Size(Mark.ORBIT * 2, Mark.ORBIT * 2),
                        style = Stroke(5f),
                    )
                    val hub = pop((u - 0.08f) / 0.14f)
                    val hosts = pop((u - 0.25f) / 0.13f)
                    drawCircle(light, Mark.HUB * hub, c, alpha = fade)
                    drawCircle(light, Mark.HOST * hosts, hostAt(Mark.TOP), alpha = fade)
                    drawCircle(amber, Mark.HOST * hosts, hostAt(Mark.AMBER), alpha = fade)
                    drawCircle(light, Mark.HOST * hosts, hostAt(Mark.LEFT), alpha = fade)
                }
                motion == MarkMotion.Halo -> {
                    drawCircle(light.copy(alpha = 0.5f * alpha), Mark.ORBIT, c, style = Stroke(5f))
                    drawCircle(light, Mark.HUB, c, alpha = alpha)
                    drawCircle(light, Mark.HOST, hostAt(Mark.TOP), alpha = alpha)
                    drawCircle(amber, Mark.HOST, hostAt(Mark.AMBER), alpha = alpha)
                    drawCircle(light, Mark.HOST, hostAt(Mark.LEFT), alpha = alpha)
                    // Out from just past the hosts to the tile's edge, fading; held half way when reduced.
                    val u = if (clock == null || clock.reduced) 0.5f else t
                    val inner = Mark.ORBIT + Mark.HOST + 2f
                    drawCircle(
                        amber,
                        inner + (Mark.CENTRE - 4f - inner) * u,
                        c,
                        alpha = alpha * 0.9f * (1f - u),
                        style = Stroke(3f),
                    )
                }
                motion == MarkMotion.SignalLost -> {
                    rotate(360f * t / 7.5f, pivot = c) {
                        drawCircle(
                            light.copy(alpha = 0.4f * alpha),
                            Mark.ORBIT,
                            c,
                            style = Stroke(5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 7f))),
                        )
                    }
                    drawCircle(light, Mark.HUB, c, alpha = alpha)
                    // A drift out and back, never round: half a sine over the period.
                    val d = sin(PI.toFloat() * t)
                    drawCircle(light, Mark.HOST, hostAt(Mark.TOP) + Offset(0f, -3f * d), alpha = alpha * (1f - 0.5f * d))
                    drawCircle(failed, Mark.HOST, hostAt(Mark.AMBER) + Offset(3f * d, 2f * d), alpha = alpha)
                    drawCircle(light, Mark.HOST, hostAt(Mark.LEFT) + Offset(-3f * d, 2f * d), alpha = alpha * (1f - 0.5f * d))
                }
                else -> {
                    drawCircle(light.copy(alpha = 0.5f * alpha), Mark.ORBIT, c, style = Stroke(5f))
                    drawCircle(light, Mark.HUB, c, alpha = alpha)
                    val (turn, pullIn) = when (motion) {
                        MarkMotion.Orbit -> 360f * Mark.orbitEasing.transform(t) to 1f
                        MarkMotion.GravityWell -> {
                            val e = FastOutSlowInEasing.transform(t)
                            // In to 0.45 at half way, back out by the end.
                            360f * e to (1f - 0.55f * sin(PI.toFloat() * e))
                        }
                        else -> 0f to 1f
                    }
                    val r = Mark.ORBIT * pullIn
                    val hostR = Mark.HOST * pullIn
                    drawCircle(light, hostR, hostAt(Mark.TOP + turn, r), alpha = alpha)
                    drawCircle(amber, hostR, hostAt(Mark.AMBER + turn, r), alpha = alpha)
                    drawCircle(light, hostR, hostAt(Mark.LEFT + turn, r), alpha = alpha)
                }
            }
        }
    }
}

/** Where a reduced-motion Draw-on rests: drawn in full, before the fade. */
private const val DRAW_ON_DONE = 0.5f

/** Draw-on's pop: 0 to full with a small overshoot, as the manual's `cubic-bezier(.3,1.6,.5,1)`. */
private val popEasing = CubicBezierEasing(0.3f, 1.6f, 0.5f, 1f)

private fun pop(x: Float): Float = popEasing.transform(x.coerceIn(0f, 1f))

/** Chase's dimming hosts: full, down to 0.35 half way through, full again. */
private fun dim(t: Float): Float {
    val u = ((t % 1f) + 1f) % 1f
    return 1f - 0.65f * sin(PI.toFloat() * u)
}

/**
 * The Hex field: a ripple from the centre across a grid of hexagons, for
 * building and checking work (the fleet check after pairing, a repair). With
 * reduced motion the grid is still.
 */
@Composable
fun HexField(modifier: Modifier = Modifier, cell: Dp = 14.dp) {
    val accent = Fleet.colors.loaderAccent
    val clock = rememberLoaderClock(1_800)
    // Clipped: the edge rows start half a cell outside, and must not spill onto whatever sits above.
    Canvas(modifier.clipToBounds().semantics { contentDescription = "Checking" }) {
        val r = cell.toPx()
        val w = sqrt(3f) * r
        val rowStep = r * 1.5f + 1.5f
        val colStep = w + 2f
        val centre = Offset(this.size.width / 2f, this.size.height * 0.43f)
        val far = hypot(max(centre.x, this.size.width - centre.x), max(centre.y, this.size.height - centre.y))
        val hex = Path()
        var row = 0
        var y = -r
        while (y < this.size.height + r) {
            var x = if (row % 2 == 0) 0f else colStep / 2f
            while (x < this.size.width + w) {
                val p = Offset(x, y)
                // Each cell lights 1.3 s after the centre at the far corner, then fades.
                val delay = (p - centre).getDistance() / far * 0.72f
                val local = if (clock.reduced) 1f else (((clock.phase - delay) % 1f) + 1f) % 1f
                val glow = if (local < 0.15f) local / 0.15f else 1f - (local - 0.15f) / 0.85f
                hexagon(hex, p, r)
                drawPath(hex, accent, alpha = clock.alpha * (0.06f + 0.49f * glow))
                drawPath(hex, accent, alpha = clock.alpha * (0.25f + 0.65f * glow), style = Stroke(1f))
                x += colStep
            }
            y += rowStep
            row++
        }
    }
}

/** A pointy-top hexagon of circumradius [r] round [c], into [path]. */
private fun hexagon(path: Path, c: Offset, r: Float) {
    path.reset()
    for (i in 0 until 6) {
        val a = (60f * i - 90f) * PI.toFloat() / 180f
        val p = Offset(c.x + r * cos(a), c.y + r * sin(a))
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
}

/** One host the Radar has found, where it sits on the dish (0 to 1 each way), and whether it needs anything. */
data class RadarBlip(val x: Float, val y: Float, val ready: Boolean = true)

/**
 * The Radar: a sweep that finds hosts while adding one. One blip per host
 * that answered; a blip lights as the sweep passes it and fades until the
 * next pass. Ready hosts are Done green, a host missing something is amber.
 */
@Composable
fun Radar(blips: List<RadarBlip>, modifier: Modifier = Modifier, size: Dp = 200.dp, sweeping: Boolean = true) {
    val o = Fleet.colors
    val accent = o.loaderAccent
    val done = o.statusDone
    val waiting = o.statusWaiting
    // Once the scan is over the dish stands still with every blip lit, and
    // runs no animation at all (r13 P19).
    val clock = if (sweeping) rememberLoaderClock(2_400) else null
    val fade = clock?.alpha ?: 1f
    Canvas(modifier.size(size).semantics { contentDescription = if (sweeping) "Looking for hosts" else "Hosts found" }) {
        val c = center
        val radius = this.size.minDimension / 2f
        drawCircle(accent, radius - 1f, c, alpha = 0.35f * fade, style = Stroke(1.5f))
        drawCircle(accent, radius * 0.63f, c, alpha = 0.2f * fade, style = Stroke(1f))
        drawCircle(accent, radius * 0.23f, c, alpha = 0.2f * fade, style = Stroke(1f))
        val sweep = 360f * (clock?.phase ?: 0f)
        if (clock != null && !clock.reduced) {
            rotate(sweep, pivot = c) {
                drawCircle(
                    Brush.sweepGradient(
                        0f to accent.copy(alpha = 0f),
                        0.78f to accent.copy(alpha = 0f),
                        1f to accent.copy(alpha = 0.45f),
                        center = c,
                    ),
                    radius - 1f,
                    c,
                )
            }
        }
        for (b in blips) {
            val p = Offset(b.x * this.size.width, b.y * this.size.height)
            val angle = (atan2(p.y - c.y, p.x - c.x) * 180f / PI.toFloat() + 360f) % 360f
            // How long since the sweep passed it, as a share of a turn.
            val since = when {
                clock == null -> 0f
                clock.reduced -> 0.5f
                else -> (((sweep - angle) % 360f) + 360f) % 360f / 360f
            }
            val glow = (1f - since * 1.5f).coerceIn(0.25f, 1f)
            val color = if (b.ready) done else waiting
            drawCircle(color, 3.5.dp.toPx() * (1f + 0.4f * (glow - 0.25f) / 0.75f), p, alpha = glow * fade)
        }
    }
}

/** Where the Radar puts host [index] of a sweep: spread over the dish, the same place every frame. */
fun radarSpot(index: Int): Pair<Float, Float> {
    val a = index * 2.399963f + 0.6f
    val r = 0.18f + 0.26f * ((index * 0.618034f) % 1f)
    return (0.5f + r * cos(a)) to (0.5f + r * sin(a))
}

/**
 * The Galaxy: a slow two-armed spiral of particles, for the first import of
 * a fleet, once in its life. Particle loaders sit on a dark stage in both
 * themes, so it brings its own `brand-ink` ground.
 */
@Composable
fun Galaxy(modifier: Modifier = Modifier, size: Dp = 220.dp) {
    val o = Fleet.colors
    val colors = listOf(o.brandLight, o.brandAmber, o.loaderAccent)
    val ink = o.brandInk
    val clock = rememberLoaderClock(12_000)
    val stars = remember { galaxyStars() }
    Canvas(modifier.size(size).semantics { contentDescription = "Building your fleet" }) {
        val c = center
        val reach = this.size.minDimension / 2f
        drawCircle(
            Brush.radialGradient(0f to ink, 0.7f to ink.copy(alpha = 0.6f), 1f to ink.copy(alpha = 0f), center = c, radius = reach),
            reach,
            c,
        )
        withTransform({ rotate(360f * clock.phase, pivot = c) }) {
            for (s in stars) {
                val p = Offset(c.x + s.r * reach * cos(s.angle), c.y + s.r * reach * 0.62f * sin(s.angle))
                // A 3 s twinkle, each star at its own offset; four twinkles to a turn of the galaxy.
                val tw = if (clock.reduced) 1f else 0.625f + 0.375f * cos(2f * PI.toFloat() * (clock.phase * 4f + s.offset))
                drawCircle(colors[s.color], s.dot * density, p, alpha = tw * clock.alpha)
            }
        }
        drawCircle(o.brandLight, 4.dp.toPx(), c, alpha = clock.alpha)
    }
}

private class Star(val angle: Float, val r: Float, val dot: Float, val color: Int, val offset: Float)

/** Sixty stars on two arms, a fixed pattern so every frame and every screenshot agree. */
private fun galaxyStars(): List<Star> = List(60) { i ->
    val arm = i % 2
    val t = i / 60f
    val jitter = ((i * 0.754877f) % 1f) - 0.5f
    Star(
        angle = arm * PI.toFloat() + t * 3f * PI.toFloat() + jitter * 0.5f,
        r = 0.1f + t * 0.85f + jitter * 0.05f,
        dot = 0.75f + 0.75f * ((i * 0.381966f) % 1f),
        color = when { i % 7 == 0 -> 1; i % 3 == 0 -> 2; else -> 0 },
        offset = (i * 0.618034f) % 1f,
    )
}

/**
 * The Dot wave: a diagonal wave through a small grid of dots, for a list or
 * a conversation that is still arriving (beside its skeleton).
 */
@Composable
fun DotWave(modifier: Modifier = Modifier, columns: Int = 7, rows: Int = 2, dot: Dp = 4.dp, gap: Dp = 4.dp) {
    val accent = Fleet.colors.accent
    val clock = rememberLoaderClock(1_600)
    Canvas(
        modifier
            .size(width = (dot + gap) * columns, height = (dot + gap) * rows)
            .semantics { contentDescription = "Loading" },
    ) {
        val step = (dot + gap).toPx()
        val r = dot.toPx() / 2f
        for (row in 0 until rows) {
            for (col in 0 until columns) {
                val local = if (clock.reduced) 0.35f else (((clock.phase - (row + col) * 0.07f / 1.6f) % 1f) + 1f) % 1f
                val peak = if (local < 0.35f) local / 0.35f else 1f - (local - 0.35f) / 0.65f
                drawCircle(
                    accent,
                    r * (0.7f + 0.55f * peak),
                    Offset(step * col + step / 2f, step * row + step / 2f),
                    alpha = clock.alpha * (0.15f + 0.85f * peak),
                )
            }
        }
    }
}

/**
 * One streak of the Data rain, from the manual's markup (16 streaks, 12 px
 * apart): its height in px, how many times it falls in [RAIN_PERIOD_MS], where
 * in its fall it starts, and its opacity. The manual's 1.15–2.53 s falls are
 * rounded to whole laps of the period so the loop has no seam.
 */
internal class RainStreak(val height: Float, val laps: Int, val start: Float, val alpha: Float)

internal const val RAIN_PERIOD_MS = 5_000

internal val RAIN = listOf(
    RainStreak(19f, 4, 0.03f, 0.49f), RainStreak(21f, 4, 0.81f, 0.67f), RainStreak(38f, 2, 0.27f, 1.00f), RainStreak(39f, 2, 0.81f, 0.79f),
    RainStreak(47f, 3, 0.97f, 0.80f), RainStreak(41f, 3, 0.02f, 0.67f), RainStreak(23f, 4, 0.72f, 0.61f), RainStreak(25f, 2, 0.44f, 0.86f),
    RainStreak(37f, 3, 0.18f, 0.66f), RainStreak(48f, 4, 0.80f, 0.72f), RainStreak(38f, 3, 0.64f, 0.94f), RainStreak(44f, 4, 0.76f, 0.78f),
    RainStreak(42f, 3, 0.96f, 0.44f), RainStreak(34f, 2, 0.81f, 0.44f), RainStreak(41f, 2, 0.73f, 0.97f), RainStreak(34f, 2, 0.26f, 0.81f),
)

/**
 * The Data rain: a steady stream with no promise of an end, for a download or
 * an import whose size is not known (a clone the hub answers only once it is
 * done). The real counts, when there are any, go under it in words.
 */
@Composable
fun DataRain(modifier: Modifier = Modifier, width: Dp = 196.dp, height: Dp = 150.dp) {
    val o = Fleet.colors
    val clock = rememberLoaderClock(RAIN_PERIOD_MS)
    Canvas(
        modifier
            .size(width = width, height = height)
            .clipToBounds()
            .semantics { contentDescription = "Receiving" },
    ) {
        val u = size.width / 196f
        RAIN.forEachIndexed { i, s ->
            val through = if (clock.reduced) 0.45f else (clock.phase * s.laps + s.start) % 1f
            val top = (-60f + 230f * through) * u
            val h = s.height * u
            val alpha = s.alpha * rainEdge((top + h / 2f) / size.height) * clock.alpha
            if (alpha > 0f) {
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(o.accent.copy(alpha = 0f), o.accent, o.brandLight), startY = top, endY = top + h),
                    topLeft = Offset((4f + 12f * i) * u, top),
                    size = Size(2f * u, h),
                    cornerRadius = CornerRadius(2f * u),
                    alpha = alpha,
                )
            }
        }
    }
}

/** How much of a streak shows at [mid] (0 top, 1 bottom): the manual's mask, clear above 25 % and below 70 %. */
internal fun rainEdge(mid: Float): Float = when {
    mid < 0.25f -> (mid / 0.25f).coerceIn(0f, 1f)
    mid > 0.7f -> ((1f - mid) / 0.3f).coerceIn(0f, 1f)
    else -> 1f
}

/**
 * The Atom: three electron rings round a nucleus, each with its electron,
 * for something being put together (a chat form Control is building). Small,
 * inline beside what it builds. With reduced motion the electrons hold still.
 */
@Composable
fun Atom(modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val accent = Fleet.colors.accent
    val clock = rememberLoaderClock(2_400)
    Canvas(modifier.size(size).semantics { contentDescription = "Building" }) {
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val rx = this.size.width * 0.46f
        val ry = this.size.height * 0.17f
        val stroke = this.size.width * 0.05f
        drawCircle(accent, this.size.width * 0.09f, c, alpha = clock.alpha)
        for (i in 0 until 3) {
            val tilt = i * 60f
            rotate(tilt, c) {
                drawOval(
                    accent,
                    topLeft = Offset(c.x - rx, c.y - ry),
                    size = Size(rx * 2f, ry * 2f),
                    style = Stroke(stroke),
                    alpha = clock.alpha * 0.45f,
                )
                val a = (clock.phase + i / 3f) * 2f * PI.toFloat()
                drawCircle(accent, stroke * 1.4f, Offset(c.x + rx * cos(a), c.y + ry * sin(a)), alpha = clock.alpha)
            }
        }
    }
}

/**
 * The Comet: a head on a small orbit trailing a fading tail, for work that
 * is running (a mission's step, a tool call). The one particle loader small
 * enough for a row. With reduced motion the head holds still.
 */
@Composable
fun Comet(modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val head = Fleet.colors.cometHead
    val tail = Fleet.colors.accent
    val clock = rememberLoaderClock(1_400)
    Canvas(modifier.size(size).semantics { contentDescription = "Working" }) {
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val r = this.size.width * 0.36f
        val dot = this.size.width * 0.1f
        for (i in 6 downTo 1) {
            val a = (clock.phase - i * 0.035f) * 2f * PI.toFloat()
            drawCircle(tail, dot * (1f - i / 8f), Offset(c.x + r * cos(a), c.y + r * sin(a)), alpha = clock.alpha * (1f - i / 7f) * 0.7f)
        }
        val a = clock.phase * 2f * PI.toFloat()
        drawCircle(head, dot, Offset(c.x + r * cos(a), c.y + r * sin(a)), alpha = clock.alpha)
    }
}

/**
 * Assemble, the startup loader (manual: Startup at 1.3 s): one particle per
 * session flies in from the edge to its place on the orbit, holds, and
 * streams on. The first [needYou] particles are amber. Drawn on the mark's
 * dark tile in both themes; with reduced motion the particles rest on the
 * ring and fade.
 */
@Composable
fun Assemble(count: Int, modifier: Modifier = Modifier, size: Dp = 160.dp, needYou: Int = 0) {
    val o = Fleet.colors
    val ink = o.brandInk
    val light = o.brandLight
    val amber = o.brandAmber
    val clock = rememberLoaderClock(3_600)
    val n = count.coerceIn(ASSEMBLE_MIN, ASSEMBLE_MAX)
    Canvas(modifier.size(size).semantics { contentDescription = "Loading the fleet" }) {
        val w = this.size.minDimension
        val c = Offset(w / 2f, w / 2f)
        drawRoundRect(ink, size = Size(w, w), cornerRadius = CornerRadius(w * 0.22f))
        val ring = w * 0.3f
        val local = if (clock.reduced) 0.55f else clock.phase
        // The ring fades in as the particles arrive and out as they leave.
        val ringIn = when {
            local < 0.3f -> 0f
            local < 0.5f -> (local - 0.3f) / 0.2f
            local < 0.72f -> 1f
            local < 0.9f -> 1f - (local - 0.72f) / 0.18f
            else -> 0f
        }
        drawCircle(light, ring, c, alpha = clock.alpha * 0.45f * ringIn, style = Stroke(w * 0.012f))
        for (i in 0 until n) {
            val p = assembleAt(i, n, local)
            val from = 0.5f * w
            val home = 2f * PI.toFloat() * i / n - PI.toFloat() / 2f
            val start = i * GOLDEN
            val end = start + 0.9f
            fun at(angle: Float, r: Float) = Offset(c.x + r * cos(angle), c.y + r * sin(angle))
            val pos = when {
                p.leg == 0 -> lerp(at(start, from), at(home, ring), p.k)
                p.leg == 1 -> at(home, ring)
                else -> lerp(at(home, ring), at(end, from), p.k)
            }
            drawCircle(if (i < needYou) amber else light, w * 0.014f, pos, alpha = clock.alpha * p.alpha)
        }
    }
}

private const val ASSEMBLE_MIN = 6
private const val ASSEMBLE_MAX = 60

/** The golden angle, so the particles start spread round the edge whatever their count. */
private const val GOLDEN = 2.399963f

/** Where one particle is in Assemble's loop: flying in (leg 0), held on the ring (1), or streaming on (2). */
internal data class AssembleStep(val leg: Int, val k: Float, val alpha: Float)

/**
 * Assemble's timeline for particle [i] of [n] at [phase] (0 to 1 over 3.6 s,
 * the manual's `ofl-conv`): in by 45 %, held to 72 %, out by the end; each
 * particle a little after the one before, so they arrive as a stream.
 */
internal fun assembleAt(i: Int, n: Int, phase: Float): AssembleStep {
    val delay = 0.1f * i / n.coerceAtLeast(1)
    val t = (phase - delay).coerceIn(0f, 1f)
    return when {
        t < 0.45f -> AssembleStep(0, FastOutSlowInEasing.transform(t / 0.45f), (t / 0.12f).coerceAtMost(1f) * 0.9f)
        t < 0.72f -> AssembleStep(1, 1f, 1f)
        else -> {
            val k = (t - 0.72f) / 0.28f
            AssembleStep(2, k, 1f - k)
        }
    }
}

private fun lerp(a: Offset, b: Offset, k: Float): Offset = Offset(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k)

/**
 * A halo pulsing out round whatever it sits behind: the paired mark (manual:
 * Halo "on success"), anything that waits on a person. Held half way and
 * fading with reduced motion.
 */
@Composable
fun HaloRing(modifier: Modifier = Modifier) {
    val accent = Fleet.colors.accent
    val clock = rememberLoaderClock(2_000)
    Canvas(modifier.semantics { contentDescription = "Paired" }) {
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val r = this.size.minDimension / 2f
        val u = if (clock.reduced) 0.5f else clock.phase
        // The manual's `ofl-halo`: scale .6 to 1, stroke from .9 to nothing.
        drawCircle(accent, r * (0.6f + 0.4f * u), c, alpha = clock.alpha * 0.9f * (1f - u), style = Stroke(r * 0.04f))
    }
}
