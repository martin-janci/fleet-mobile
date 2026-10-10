package dev.claudefleet.mobile.ui.help

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.model.Page
import dev.claudefleet.mobile.model.PageItem
import dev.claudefleet.mobile.model.Section
import dev.claudefleet.mobile.model.holds
import dev.claudefleet.mobile.model.AccountUsageSnapshot
import dev.claudefleet.mobile.ui.AccountMeter
import dev.claudefleet.mobile.ui.accountMeter
import dev.claudefleet.mobile.ui.FieldRow
import dev.claudefleet.mobile.ui.FleetSettingsUiState
import dev.claudefleet.mobile.ui.PhoneSessionRow
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.kit.OrbitMark
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.theme.OrbitTokens

// ---- the tour's anchors ----

/** Where the parts of the real Inbox the tour lights are, in root coordinates. */
class TourAnchors {
    val rects = mutableStateMapOf<TourAnchor, Rect>()
}

/** Record where this part of the screen is, for the tour to light it. */
fun Modifier.tourAnchor(anchors: TourAnchors?, anchor: TourAnchor): Modifier =
    if (anchors == null) this else onGloballyPositioned { anchors.rects[anchor] = it.boundsInRoot() }

private val gutter get() = OrbitTokens.spacing("phone-gutter").dp

// ---- tips ----

/**
 * A tip in place: one dashed card under the thing it explains. Got it hides
 * it; No more tips stops all of them. It explains; it never acts.
 */
@Composable
fun TipCard(text: String, onGotIt: () -> Unit, onNoMore: () -> Unit, modifier: Modifier = Modifier) {
    val o = Fleet.colors
    val radius = OrbitTokens.radius("radius-md")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = gutter, vertical = 6.dp)
            .drawBehind {
                drawRoundRect(
                    color = o.accent,
                    cornerRadius = CornerRadius(radius.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .padding(12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Tip · $text", color = o.fg2, fontSize = 14.sp, lineHeight = 20.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onGotIt) { Text("Got it", color = o.accent, fontSize = 14.sp) }
            TextButton(onClick = onNoMore) { Text("No more tips", color = o.fgMuted, fontSize = 14.sp) }
        }
    }
}

/** [tip] as a [TipCard] when [help] says it shows now; nothing otherwise. */
@Composable
fun TipFor(tip: Tip, help: HelpState, settings: HelpSettings) {
    if (help.shows(tip)) TipCard(help.words(tip), onGotIt = { settings.gotIt(tip) }, onNoMore = settings::noMoreTips)
}

// ---- the tour ----

/**
 * The tour over the real Inbox: everything dims but the part this stop is
 * about, and one sentence says what it is. Taps on the dimmed screen do
 * nothing, so the tour can never press anything for the person. Skip is
 * always there.
 */
@Composable
fun TourOverlay(stop: Int, anchors: TourAnchors, onNext: () -> Unit, onSkip: () -> Unit) {
    val o = Fleet.colors
    val current = TOUR_STOPS.getOrNull(stop) ?: return
    var area by remember { mutableStateOf(Rect.Zero) }
    val hole = anchors.rects[current.anchor]?.translate(-area.left, -area.top)
    val scrim = o.scrimStrong
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { area = it.boundsInRoot() }
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val path = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                if (hole != null) {
                    val pad = 6.dp.toPx()
                    addRoundRect(RoundRect(hole.inflate(pad), CornerRadius(12.dp.toPx())))
                }
            }
            drawPath(path, scrim)
            if (hole != null) {
                drawRoundRect(
                    o.accent,
                    topLeft = hole.inflate(6.dp.toPx()).topLeft,
                    size = hole.inflate(6.dp.toPx()).size,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(2.dp.toPx()),
                )
            }
        }
        // The card sits in whichever half the lit part is not in.
        val below = hole == null || hole.center.y < area.height / 2
        val shape = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp)
        Column(
            modifier = Modifier
                .align(if (below) Alignment.BottomCenter else Alignment.TopCenter)
                .fillMaxWidth()
                .padding(gutter)
                .padding(bottom = if (below) OrbitTokens.spacing("tab-bar-h").dp else 0.dp)
                .background(o.bgRaise, shape)
                .border(1.dp, o.accent, shape)
                .padding(16.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Tour · ${stop + 1} of ${TOUR_STOPS.size}", color = o.fgMuted, fontSize = 12.sp)
            Text(current.text, color = o.fg, fontSize = 16.sp, lineHeight = 22.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSkip) { Text("Skip tour", color = o.fgMuted, fontSize = 15.sp) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onNext,
                    colors = ButtonDefaults.buttonColors(containerColor = o.accent, contentColor = o.bg),
                ) { Text(if (stop + 1 == TOUR_STOPS.size) "Done" else "Next", fontSize = 15.sp) }
            }
        }
    }
}

// ---- the picker ----

/**
 * After pairing, once: how much help. Tips only is the usual choice and the
 * one already picked; the practice fleet is one tap away.
 */
@Composable
fun HelpPicker(onPick: (HelpMode) -> Unit, onPracticeFirst: (HelpMode) -> Unit) {
    val o = Fleet.colors
    var chosen by remember { mutableStateOf(HelpMode.TIPS) }
    Column(
        modifier = Modifier.fillMaxSize().background(o.bg).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OrbitMark(48.dp)
        Text("How much help do you want?", color = o.fg, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
        Text("You can change it any time in More › Settings › This phone › Help.", color = o.fgMuted, fontSize = 14.sp, lineHeight = 20.sp)
        for (mode in HelpMode.entries) {
            val selected = mode == chosen
            val shape = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (selected) o.accentSoft else o.bgPane, shape)
                    .border(1.dp, if (selected) o.accent else o.controlBorder, shape)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { chosen = mode })
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(mode.label, color = o.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    if (mode == HelpMode.TIPS) Text("usual", color = o.fgMuted, fontSize = 12.sp)
                }
                Text(mode.line, color = o.fg2, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onPick(chosen) },
            modifier = Modifier.fillMaxWidth().heightIn(min = OrbitTokens.spacing("touch-min").dp),
            colors = ButtonDefaults.buttonColors(containerColor = o.accent, contentColor = o.bg),
        ) { Text("Continue", fontSize = 15.sp) }
        TextButton(onClick = { onPracticeFirst(chosen) }, modifier = Modifier.fillMaxWidth()) {
            Text("Try the practice fleet first", color = o.accent, fontSize = 15.sp)
        }
    }
}

// ---- Help under This phone ----

/** Help under This phone (MobileTutorialModes · Help settings). */
@Composable
fun ColumnScope.HelpSettingsSection(
    help: HelpState,
    settings: HelpSettings,
    tourAvailable: Boolean,
    onTour: () -> Unit,
    onPractice: () -> Unit,
) {
    val o = Fleet.colors
    Text(
        "HELP",
        style = Fleet.type.text2xs,
        color = o.fgMuted,
        modifier = Modifier.padding(start = gutter, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
    val shape = RoundedCornerShape(OrbitTokens.radius("radius-md").dp)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (mode in HelpMode.entries) {
            val selected = mode == help.mode
            Text(
                mode.label,
                style = Fleet.type.textSm,
                color = if (selected) o.fg else o.fg2,
                modifier = Modifier.weight(1f)
                    .background(if (selected) o.accentSoft else o.bgPane, shape)
                    .border(1.dp, if (selected) o.accent else o.controlBorder, shape)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { settings.pick(mode, tourHere = false) })
                    .heightIn(min = OrbitTokens.spacing("touch-min").dp)
                    .padding(vertical = 13.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
    HelpRow(
        "Show tips again",
        "${help.tipsSeen.size} seen · ${Tip.entries.size - help.tipsSeen.size} left" + if (help.noMoreTips) " · stopped" else "",
        "Reset",
        settings::resetTips,
    )
    HelpRow("Practice fleet", "Sample sessions on a pretend host. Nothing reaches the hub.", "Open", onPractice)
    if (tourAvailable) HelpRow("Take the tour again", "5 stops on the Inbox · about a minute", "Start", onTour)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = OrbitTokens.spacing("touch-min").dp).padding(horizontal = gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Explain more on shared hubs", color = o.fg, fontSize = 15.sp)
            Text("Longer tips, for someone new to a shared fleet", color = o.fgMuted, fontSize = 13.sp)
        }
        Switch(checked = help.explainMore, onCheckedChange = settings::setExplainMore)
    }
}

@Composable
private fun HelpRow(title: String, line: String, action: String, onAction: () -> Unit) {
    val o = Fleet.colors
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = OrbitTokens.spacing("touch-min").dp).padding(horizontal = gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = o.fg, fontSize = 15.sp)
            Text(line, color = o.fgMuted, fontSize = 13.sp)
        }
        TextButton(onClick = onAction) { Text(action, color = o.accent, fontSize = 15.sp) }
    }
}

// ---- Learn ----

/** The approved guides the hub serves: its pages with the guide layout. */
fun guidePages(pages: List<Page>): List<Page> = pages.filter { it.layout == "guide" }

/**
 * More › Learn: the practice fleet, the lessons with progress, and the
 * desktop's approved guides, which change real settings with Undo.
 */
@Composable
fun LearnScreen(
    help: HelpState,
    guides: List<Page>,
    onBack: () -> Unit,
    onPractice: () -> Unit,
    onLesson: (Lesson) -> Unit,
    onGuide: (String) -> Unit,
    tip: @Composable () -> Unit = {},
) {
    val o = Fleet.colors
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Learn",
            subtitle = "${help.lessonsDone.count { id -> LESSONS.any { it.id == id } }} of ${LESSONS.size} done",
            navigation = { IconButton(onClick = onBack) { Icon(FleetIcons.ArrowBack, contentDescription = "Back") } },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            tip()
            val shape = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = gutter, vertical = 8.dp)
                    .background(o.bgPane, shape)
                    .border(1.dp, o.controlBorder, shape)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Practice fleet", color = o.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Sample sessions on a pretend host. Answer, start and break things safely.", color = o.fgMuted, fontSize = 13.sp, lineHeight = 18.sp)
                }
                TextButton(onClick = onPractice) { Text("Open", color = o.accent, fontSize = 15.sp) }
            }
            Label("Lessons ${LESSONS.size} · 1–2 min each")
            for (lesson in LESSONS) {
                val done = lesson.id in help.lessonsDone
                val running = help.lesson?.first?.id == lesson.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLesson(lesson) }
                        .heightIn(min = OrbitTokens.spacing("touch-min").dp)
                        .padding(horizontal = gutter, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        when { done -> "✓"; running -> "▶"; else -> "○" },
                        color = if (done) o.statusDone else if (running) o.accent else o.fgMuted,
                        fontSize = 15.sp,
                        modifier = Modifier.width(18.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(lesson.title, color = o.fg, fontSize = 15.sp)
                        Text(lesson.line, color = o.fgMuted, fontSize = 13.sp)
                    }
                }
            }
            if (guides.isNotEmpty()) {
                Label("Guides ${guides.size} · change real settings")
                for (g in guides) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onGuide(g.id) }
                            .heightIn(min = OrbitTokens.spacing("touch-min").dp)
                            .padding(horizontal = gutter, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(g.title, color = o.fg, fontSize = 15.sp)
                            Text(
                                listOfNotNull("${g.allSections.size} steps", g.intro).joinToString(" · "),
                                color = o.fgMuted,
                                fontSize = 13.sp,
                                maxLines = 2,
                            )
                        }
                        Text("›", color = o.fgMuted, fontSize = 18.sp)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text.uppercase(),
        style = Fleet.type.text2xs,
        color = Fleet.colors.fgMuted,
        modifier = Modifier.padding(start = gutter, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

// ---- a lesson on the real screens ----

/**
 * The bar a running lesson puts over the real screen: which lesson, which
 * step, one sentence saying where to look. Next and End lesson are its only
 * buttons; a lesson never presses anything on the screen under it. With
 * [onPrompt], a step's suggested prompts are offered too, and a tap only
 * puts one in the coordinator's composer (14.22).
 */
@Composable
fun LessonBar(
    lesson: Lesson,
    step: Int,
    onNext: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    onPrompt: ((String) -> Unit)? = null,
) {
    val o = Fleet.colors
    val index = LESSONS.indexOfFirst { it.id == lesson.id } + 1
    val current = lesson.steps.getOrNull(step) ?: return
    val text = current.text
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(o.accentSoft)
            .border(1.dp, o.accent)
            .padding(horizontal = gutter, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lesson $index of ${LESSONS.size} · ${lesson.title}", color = o.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onEnd) { Text("End lesson", color = o.fgMuted, fontSize = 13.sp) }
        }
        Text("Step ${step + 1} · $text", color = o.fg2, fontSize = 14.sp, lineHeight = 20.sp)
        if (onPrompt != null && current.prompts.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (prompt in current.prompts) {
                    OutlinedButton(onClick = { onPrompt(prompt) }) { Text(prompt, fontSize = 14.sp) }
                }
            }
        }
        Row {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onNext) {
                // fg, not accent: accent on accent-soft is 4.44:1 in light (review r11).
                Text(if (step + 1 == lesson.steps.size) "Done" else "Next", color = o.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ---- the practice fleet ----

/**
 * The practice fleet: its own accent banner (never the amber of Needs you),
 * sample rows, and the demo session with a question card. Nothing here talks
 * to the hub; see [PracticeFleet].
 */
@Composable
fun PracticeScreen(
    practice: PracticeFleet,
    state: PracticeState,
    onLeave: () -> Unit,
    tip: @Composable () -> Unit = {},
) {
    val o = Fleet.colors
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(o.accentSoft).padding(horizontal = gutter, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Practice fleet", color = o.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Sample sessions · nothing real runs", color = o.fg2, fontSize = 13.sp)
            }
            TextButton(onClick = onLeave) { Text("Leave", color = o.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
        val open = state.open
        if (open == null) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                // The demo's notification, as a real one would arrive, marked
                // Practice; a tap opens the demo, the way a real one opens its card.
                if (state.answered == null) PracticeNotificationCard(PracticeFixtures.notification()) { practice.open(PracticeFixtures.demo.id) }
                for (row in practice.sessions) {
                    PhoneSessionRow(row = row, nowSeconds = PracticeFixtures.NOW, showHost = true, onClick = { practice.open(row.id) })
                }
            }
        } else {
            val row = practice.sessions.firstOrNull { it.id == open } ?: return@Column
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = gutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { practice.back() }) { Text("‹ Practice fleet", color = o.accent, fontSize = 14.sp) }
                Text(row.friendlyName ?: row.tmuxName, color = o.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("${row.hostAlias} · sample-repo", color = o.fgMuted, fontSize = 13.sp)
                if (row.id == PracticeFixtures.demo.id) {
                    for ((who, said) in PracticeFixtures.conversation) {
                        Column {
                            Text(who, color = o.fgMuted, fontSize = 12.sp)
                            Text(said, color = o.fg, fontSize = 15.sp, lineHeight = 21.sp)
                        }
                    }
                    PracticeQuestion(state.answered, onAnswer = practice::answer)
                    tip()
                } else {
                    Text(row.currentActivity.orEmpty(), color = o.fg2, fontSize = 15.sp)
                }
            }
        }
    }
}

/** The practice notification, drawn as a notification: its accent edge, never the amber of Needs you. */
@Composable
private fun PracticeNotificationCard(n: PracticeNotification, onOpen: () -> Unit) {
    val o = Fleet.colors
    val shape = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = gutter, vertical = 8.dp)
            .background(o.bgRaise, shape)
            .border(1.dp, o.accent, shape)
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(n.title, color = o.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(n.body, color = o.fg2, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

/** The demo's question card. Nothing is pre-selected; each answer is a tap. */
@Composable
private fun PracticeQuestion(answered: Int?, onAnswer: (Int) -> Unit) {
    val o = Fleet.colors
    val shape = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp)
    Column(
        modifier = Modifier.fillMaxWidth().background(o.waitingFaint, shape).border(1.dp, o.waitingLine, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(PracticeFixtures.QUESTION, color = o.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (answered == null) {
            PracticeFixtures.choices.forEachIndexed { i, label ->
                OutlinedButton(
                    onClick = { onAnswer(i + 1) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = OrbitTokens.spacing("touch-min").dp),
                ) { Text("${i + 1}  $label", color = o.fg, fontSize = 15.sp, modifier = Modifier.fillMaxWidth()) }
            }
        } else {
            Text("You answered ${answered}: ${PracticeFixtures.choices[answered - 1]}", color = o.fg2, fontSize = 14.sp)
            Text(PracticeFixtures.outcome(answered), color = o.statusDone, fontSize = 14.sp)
        }
    }
}

// ---- a guide ----

/** One setting a guide changed: what it was, so Undo can put it back. */
data class GuideChange(val key: String, val label: String, val before: String, val after: String)

/**
 * The changes a guide has made so far, one per setting: a second change to
 * the same setting keeps the first "before", and a change back to it drops
 * the line.
 */
fun List<GuideChange>.record(change: GuideChange): List<GuideChange> {
    val earlier = firstOrNull { it.key == change.key }
    val before = earlier?.before ?: change.before
    val rest = filterNot { it.key == change.key }
    return if (change.after == before) rest else rest + change.copy(before = before)
}

/**
 * Whether a guide step is about usage limits, so it shows the real meters
 * above its fields (MobileTutorialModes · Guide: "real meters, one setting
 * per step"): it edits an `accounts.` setting, the fleet's limits and pauses.
 */
internal fun guideStepShowsUsage(section: Section): Boolean =
    section.items.any { raw -> (PageItem.of(raw) as? PageItem.Field)?.key?.startsWith("accounts.") == true }

/**
 * Every account the hub has a usage reading for, as a guide step's meters,
 * named as `list_accounts` names them and in name order. An account with no
 * reading is left out: a meter with nothing in it says nothing.
 */
fun guideMeters(names: Map<String, String>, usage: Map<String, AccountUsageSnapshot>, now: Long): List<AccountMeter> =
    usage.values.mapNotNull { u -> accountMeter(u.accountUuid, names[u.accountUuid], u, now)?.takeIf { it.line != null } }
        .sortedBy { it.name.lowercase() }

/** One account's meter in a guide step: its name, what is left in words, and the bar of what is used. */
@Composable
private fun UsageMeterRow(m: AccountMeter) {
    val o = Fleet.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.name, color = o.fg, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(m.line ?: "no reading yet", color = if (m.low) o.statusFailed else o.fgMuted, fontSize = 13.sp)
        }
        m.leftFraction?.let { left ->
            // The words say it; the bar, filled by what is used, is for the eye.
            Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp).background(o.chipBg).clearAndSetSemantics {}) {
                Box(
                    Modifier.fillMaxWidth((1f - left).coerceIn(0f, 1f)).height(4.dp)
                        .background(if (m.low) o.statusFailed else o.accent),
                )
            }
        }
    }
}

/**
 * A guide from the desktop (MobileTutorialModes · Guide): the hub's approved
 * guide page, one section per step, with the person's own settings controls.
 * Every change it makes is listed with Undo. Only an approved guide reaches
 * the phone; the hub serves no other.
 */
@Composable
fun GuideScreen(
    page: Page,
    state: FleetSettingsUiState,
    onBack: () -> Unit,
    onSet: (String, String) -> Unit,
    onRefuse: (String, String) -> Unit,
    onDecide: (Long, Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancelConfirm: () -> Unit,
    /** Every account's meter from the hub's last usage reading, for a step about usage limits. */
    usage: List<AccountMeter> = emptyList(),
) {
    val o = Fleet.colors
    val steps = page.allSections.filter { it.condition.holds(state.values) }
    var step by remember(page.id) { mutableStateOf(0) }
    var changes by remember(page.id) { mutableStateOf(emptyList<GuideChange>()) }
    val at = step.coerceIn(0, (steps.size - 1).coerceAtLeast(0))
    val tracked: (String, String) -> Unit = { key, value ->
        val d = state.descriptors[key]
        val before = state.values[key] ?: d?.value.orEmpty()
        changes = changes.record(GuideChange(key, d?.label ?: key, before, value))
        onSet(key, value)
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = page.title,
            subtitle = if (steps.isEmpty()) "Guide" else "Guide · step ${at + 1} of ${steps.size}",
            navigation = { IconButton(onClick = onBack) { Icon(FleetIcons.ArrowBack, contentDescription = "Back") } },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            page.intro?.let { Text(it, color = o.fgMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = gutter, vertical = 6.dp)) }
            steps.getOrNull(at)?.let { section ->
                Text(section.title, color = o.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = gutter, vertical = 8.dp))
                section.intro?.let { Text(it, color = o.fg2, fontSize = 14.sp, modifier = Modifier.padding(horizontal = gutter)) }
                if (guideStepShowsUsage(section)) {
                    for (m in usage) UsageMeterRow(m)
                }
                for (raw in section.items) {
                    when (val item = PageItem.of(raw)) {
                        is PageItem.Field -> {
                            val d = state.descriptors[item.key]
                            if (d != null && item.condition.holds(state.values)) {
                                FieldRow(state, d, item.hint, item.readOnly, tracked, onRefuse, onDecide, {})
                            }
                        }
                        is PageItem.Notice -> Text(item.text, color = o.fg2, fontSize = 14.sp, modifier = Modifier.padding(horizontal = gutter, vertical = 4.dp))
                        else -> Unit
                    }
                }
            }
            if (changes.isNotEmpty()) {
                Label("Changed so far")
                for (c in changes) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${c.label} · ${c.before.ifBlank { "—" }} → ${c.after}", color = o.fg, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            changes = changes.filterNot { it.key == c.key }
                            onSet(c.key, c.before)
                        }) { Text("Undo", color = o.accent, fontSize = 14.sp) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 8.dp)) {
            if (at > 0) TextButton(onClick = { step = at - 1 }) { Text("‹ Back", color = o.fg2, fontSize = 15.sp) }
            Spacer(Modifier.weight(1f))
            if (at + 1 < steps.size) {
                TextButton(onClick = { step = at + 1 }) { Text("Next ›", color = o.accent, fontSize = 15.sp) }
            } else {
                TextButton(onClick = onBack) { Text("Done", color = o.accent, fontSize = 15.sp) }
            }
        }
    }
    state.confirm?.let { c ->
        AlertDialog(
            onDismissRequest = onCancelConfirm,
            title = { Text(c.label) },
            text = { Text(c.message) },
            confirmButton = { TextButton(onClick = onConfirm) { Text("Change it") } },
            dismissButton = { TextButton(onClick = onCancelConfirm) { Text("Cancel") } },
        )
    }
}
