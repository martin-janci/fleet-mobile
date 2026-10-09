package dev.claudefleet.mobile.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.OrbitTokens

/**
 * The few waits that get the whole screen (MobileFullscreenLoaders), each
 * with its own loader. Full screen only when nothing else useful can show
 * yet, and never inside a wizard or a chat: those keep their loader inline.
 */
enum class FullscreenWait(val exitLabel: String) {
    /** After pairing: the first look at the fleet from this phone. Hex field. */
    FleetCheck("Skip, open Inbox"),

    /** Repair, recreate or worktree setup: building and checking work. Hex field; leaving is safe. */
    Repair("Back to the session"),

    /** Adding a host: the sweep runs while hosts that answer appear below it. Radar. */
    FindHosts("Cancel"),

    /** The first import of a fleet, once in its life. Galaxy. */
    FirstImport("Continue in the background"),
}

/** Where one real step of the wait stands. */
enum class StepState { Done, Running, Pending }

/** One line of the checklist on top of the loader: a real step, never "Loading…". [detail] sits at the right ("42 ms"). */
data class LoaderStep(val label: String, val state: StepState, val detail: String? = null)

/** Real progress under the Galaxy: "7 of 14 imported". */
data class LoaderProgress(val done: Int, val total: Int, val noun: String) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    val label: String get() = "$done of $total $noun"
}

/**
 * A full-screen loader. [title] and [meta] say what it is really doing;
 * [onExit] is the way out every one of them has, labelled from [wait] unless
 * [exitLabel] says otherwise. Nothing draws until the wait passes
 * `loader-delay`.
 *
 * - [steps]: the checklist (fleet check, repair).
 * - [note]: one line above the way out ("You can leave; the result lands in the conversation.").
 * - [blips] and [found]: the hosts the Radar has heard from, on the dish and as rows with Add.
 * - [progress]: the Galaxy's real count.
 * - [secondary]: a second quiet way on ("Enter an address by hand").
 */
@Composable
fun FullscreenLoader(
    wait: FullscreenWait,
    title: String,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    meta: String? = null,
    exitLabel: String = wait.exitLabel,
    steps: List<LoaderStep> = emptyList(),
    note: String? = null,
    blips: List<RadarBlip> = emptyList(),
    progress: LoaderProgress? = null,
    secondary: Pair<String, () -> Unit>? = null,
    waiting: Boolean = true,
    /** FindHosts only: whether the Radar still sweeps; false once the scan is over (r13 P19). */
    scanning: Boolean = true,
    found: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val o = Fleet.colors
    val shown = rememberLoaderVisible(waiting)
    Box(modifier = modifier.fillMaxSize().background(o.bg)) {
        if (!shown) return@Box
        when (wait) {
            FullscreenWait.FleetCheck, FullscreenWait.Repair -> {
                HexField(Modifier.fillMaxSize())
                // The ripple fades into the ground towards the edges, so the checklist reads.
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.radialGradient(0f to o.bg.copy(alpha = 0f), 0.3f to o.bg.copy(alpha = 0f), 0.78f to o.bg),
                    ),
                )
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    OrbitMarkLoader(MarkMotion.Still, size = 64.dp)
                    Spacer(Modifier.height(14.dp))
                    Heading(title, meta)
                    Spacer(Modifier.height(18.dp))
                    if (steps.isNotEmpty()) StepCard(steps)
                    if (note != null) {
                        Text(
                            note,
                            modifier = Modifier.padding(top = 14.dp, start = 8.dp, end = 8.dp),
                            color = o.fgMuted,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    if (wait == FullscreenWait.Repair) OutlineExit(exitLabel, onExit) else QuietButton(exitLabel, onExit)
                }
            }
            FullscreenWait.FindHosts -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(top = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Radar(blips, size = 220.dp, sweeping = scanning)
                    Spacer(Modifier.height(20.dp))
                    Box(Modifier.padding(horizontal = 24.dp)) { Heading(title, meta, size = 18) }
                    Spacer(Modifier.height(12.dp))
                    if (found != null) Column(Modifier.fillMaxWidth(), content = found)
                    Spacer(Modifier.weight(1f))
                    if (secondary != null) QuietButton(secondary.first, secondary.second)
                    OutlineExit(exitLabel, onExit, Modifier.padding(bottom = 24.dp))
                }
            }
            FullscreenWait.FirstImport -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    Galaxy(size = 240.dp)
                    Heading(title, meta)
                    if (progress != null) {
                        Box(Modifier.fillMaxWidth().height(4.dp).background(o.track, RoundedCornerShape(2.dp))) {
                            Box(
                                Modifier
                                    .fillMaxWidth(progress.fraction)
                                    .height(4.dp)
                                    .background(o.accent, RoundedCornerShape(2.dp)),
                            )
                        }
                        Text(progress.label, color = o.fgMuted, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                    QuietButton(exitLabel, onExit)
                }
            }
        }
    }
}

@Composable
private fun Heading(title: String, meta: String?, size: Int = 20) {
    val o = Fleet.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            color = o.fg,
            fontSize = size.sp,
            lineHeight = (size + 6).sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        if (meta != null) {
            Text(meta, color = o.fgMuted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
        }
    }
}

/** The checklist card: done steps ticked in Done green, the running one with the 16 px Orbit, the rest hollow and muted. */
@Composable
private fun StepCard(steps: List<LoaderStep>) {
    val o = Fleet.colors
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(o.bgPane.copy(alpha = 0.82f), shape)
            .border(1.dp, o.controlBorder, shape)
            .padding(18.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (step in steps) StepLine(step)
    }
}

@Composable
private fun StepLine(step: LoaderStep) {
    val o = Fleet.colors
    val muted = step.state == StepState.Pending
    // With reduced motion only the current step moves, and only as a fade.
    val fade = if (step.state == StepState.Running) rememberLoaderClock(1_600).alpha else 1f
    Row(
        modifier = Modifier.fillMaxWidth().alpha(fade),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.CenterStart) {
            when (step.state) {
                StepState.Done -> Text("✓", color = o.statusDone, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                StepState.Running -> OrbitMarkLoader(MarkMotion.Orbit, size = 16.dp)
                StepState.Pending -> Text("○", color = o.fgMuted, fontSize = 15.sp)
            }
        }
        Text(
            step.label,
            modifier = Modifier.weight(1f),
            color = if (muted) o.fgMuted else o.fg,
            fontSize = 15.sp,
            lineHeight = 21.sp,
        )
        if (step.detail != null) Text(step.detail, color = o.fgMuted, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun OutlineExit(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val o = Fleet.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = OrbitTokens.spacing("touch-min").dp),
        border = BorderStroke(1.dp, o.controlBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = o.fg2),
        shape = RoundedCornerShape(OrbitTokens.radius("radius-md").dp),
    ) { Text(label, fontSize = 15.sp) }
}

/** A host the Radar has heard from, as a row under the dish: name, what it has, and Add or Details. */
@Composable
fun FoundHostRow(name: String, detail: String, action: String, onAction: () -> Unit) {
    val o = Fleet.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = OrbitTokens.spacing("phone-gutter").dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = o.fg, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 1)
            Text(detail, color = o.fgMuted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1)
        }
        OutlineExit(action, onAction)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(o.border))
}
