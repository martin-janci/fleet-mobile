package dev.claudefleet.mobile.ui.help

import dev.claudefleet.mobile.store.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** How much help the phone gives (MobileTutorialModes), picked once after pairing. */
enum class HelpMode(val label: String, val line: String) {
    TOUR("Show me around", "A 5-stop tour now, tips the first time each screen needs one, and the practice fleet."),
    TIPS("Tips only", "No tour. A short tip appears the first time a screen needs explaining."),
    NONE("No help", "I know Orbit Fleet from the desktop. Learn stays in More."),
}

/**
 * One tip in place: a dashed card under the thing it explains, the first time
 * a screen needs it. [more] is the longer wording for someone new to a shared
 * hub ("Explain more on shared hubs").
 */
enum class Tip(val text: String, val more: String) {
    INBOX(
        "Each row waits on you. The amber line says what for; tap it to answer.",
        "Each row is a session on one of the fleet's hosts that stopped to ask you something. The amber line says what it asks; tap the row to see the question and answer it there.",
    ),
    QUESTION(
        "1 allows this once. 2 allows this command for the rest of the session. 3 lets you say what to do instead.",
        "Claude Code asks before anything risky. 1 allows this one command, 2 allows it for the rest of this session, 3 lets you tell it what to do instead. Nothing is chosen for you.",
    ),
    CONTROL(
        "Ask Control in plain words. Anything that would run shows a form first.",
        "Control is the fleet's coordinator, the same one as on the desktop. Ask it in plain words; when an answer would start or change something, it shows a form and nothing runs until you press its last button.",
    ),
    LEARN(
        "Lessons and the practice fleet live here, whenever you want them.",
        "Lessons take a minute or two and only point at the real screens. The practice fleet is a pretend fleet on this phone; nothing in it reaches the hub.",
    ),
}

private const val MODE_PREF = "help.mode"
private const val TIPS_SEEN_PREF = "help.tipsSeen"
private const val NO_MORE_TIPS_PREF = "help.noMoreTips"
private const val EXPLAIN_MORE_PREF = "help.explainMore"
private const val LESSONS_DONE_PREF = "help.lessonsDone"

/** The help this phone gives, as one value. */
data class HelpState(
    /** Null until the person has picked, which is when the picker shows. */
    val mode: HelpMode? = null,
    val tipsSeen: Set<Tip> = emptySet(),
    val noMoreTips: Boolean = false,
    val explainMore: Boolean = false,
    /** The tour's current stop, 0-based; null when no tour is running. */
    val tourStop: Int? = null,
    val lessonsDone: Set<String> = emptySet(),
    /** The lesson running now and its step, or null. */
    val lesson: Pair<Lesson, Int>? = null,
) {
    /** Whether [tip] shows now: help is on, tips are not stopped, and it has not been seen. */
    fun shows(tip: Tip): Boolean =
        mode != null && mode != HelpMode.NONE && !noMoreTips && tip !in tipsSeen && tourStop == null

    fun words(tip: Tip): String = if (explainMore) tip.more else tip.text
}

/**
 * The phone's help (redesign 14.22): the mode, tips seen, the tour, lessons
 * done. On this device only, in [Prefs]; nothing here talks to the hub, and
 * nothing here answers a question for anyone: a tip explains, a tour stop
 * points, a lesson step says where to look.
 */
class HelpSettings(private val prefs: Prefs) {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<HelpState> = _state.asStateFlow()

    /**
     * The picker's answer. Show me around starts the tour when [tourHere] (the
     * Inbox of the New bar is where it runs). No help leaves nothing behind:
     * no tour, no tip.
     */
    fun pick(mode: HelpMode, tourHere: Boolean) {
        prefs.putStringList(MODE_PREF, listOf(mode.name.lowercase()))
        _state.update { it.copy(mode = mode, tourStop = if (mode == HelpMode.TOUR && tourHere) 0 else null) }
    }

    fun gotIt(tip: Tip) {
        _state.update { it.copy(tipsSeen = it.tipsSeen + tip) }
        prefs.putStringList(TIPS_SEEN_PREF, _state.value.tipsSeen.map { it.name })
    }

    fun noMoreTips() {
        prefs.putStringList(NO_MORE_TIPS_PREF, listOf("true"))
        _state.update { it.copy(noMoreTips = true) }
    }

    /** "Show tips again": every tip comes back once. */
    fun resetTips() {
        prefs.putStringList(TIPS_SEEN_PREF, emptyList())
        prefs.putStringList(NO_MORE_TIPS_PREF, emptyList())
        _state.update { it.copy(tipsSeen = emptySet(), noMoreTips = false) }
    }

    fun setExplainMore(on: Boolean) {
        prefs.putStringList(EXPLAIN_MORE_PREF, if (on) listOf("true") else emptyList())
        _state.update { it.copy(explainMore = on) }
    }

    fun startTour() = _state.update { it.copy(tourStop = 0, lesson = null) }

    /** Next stop, or the end of the tour after the last. */
    fun nextStop() = _state.update { s ->
        val at = s.tourStop ?: return@update s
        s.copy(tourStop = if (at + 1 < TOUR_STOPS.size) at + 1 else null)
    }

    /** Skip ends the tour where it is; it leaves nothing behind. */
    fun skipTour() = _state.update { it.copy(tourStop = null) }

    fun startLesson(lesson: Lesson) = _state.update { it.copy(lesson = lesson to 0, tourStop = null) }

    /** Next step; past the last, the lesson is done and remembered. */
    fun nextStep() {
        val (lesson, step) = _state.value.lesson ?: return
        if (step + 1 < lesson.steps.size) {
            _state.update { it.copy(lesson = lesson to step + 1) }
        } else {
            val done = _state.value.lessonsDone + lesson.id
            prefs.putStringList(LESSONS_DONE_PREF, done.sorted())
            _state.update { it.copy(lesson = null, lessonsDone = done) }
        }
    }

    /** End lesson: stops it without marking it done. */
    fun endLesson() = _state.update { it.copy(lesson = null) }

    private fun read(): HelpState {
        val mode = prefs.getStringList(MODE_PREF).firstOrNull()
        return HelpState(
            mode = HelpMode.entries.firstOrNull { it.name.lowercase() == mode },
            tipsSeen = prefs.getStringList(TIPS_SEEN_PREF).mapNotNull { n -> Tip.entries.firstOrNull { it.name == n } }.toSet(),
            noMoreTips = prefs.getStringList(NO_MORE_TIPS_PREF).firstOrNull() == "true",
            explainMore = prefs.getStringList(EXPLAIN_MORE_PREF).firstOrNull() == "true",
            lessonsDone = prefs.getStringList(LESSONS_DONE_PREF).toSet(),
        )
    }
}

/** Where a tour stop points: a part of the real Inbox, registered with [tourAnchor]. */
enum class TourAnchor { Header, FirstRow, Today, Bar }

/** One stop of the tour: one sentence, and what it lights. */
data class TourStop(val anchor: TourAnchor, val text: String)

/** The tour on the real Inbox: five stops at most, one sentence each. It never answers anything. */
val TOUR_STOPS: List<TourStop> = listOf(
    TourStop(TourAnchor.Header, "This is the Inbox: everything across the fleet that needs you, and how much is running."),
    TourStop(TourAnchor.FirstRow, "This session waits on you. The amber line says what for. Tap it to see the question."),
    TourStop(TourAnchor.Today, "Today gathers what happened and what is waiting, in one sheet."),
    TourStop(TourAnchor.Bar, "Sessions lists every session; Control is the coordinator you can ask in plain words."),
    TourStop(TourAnchor.Bar, "More holds hosts, accounts, files, settings, and Learn with a practice fleet."),
)

/** Where a lesson step sends the person. The step only points; the person does the thing. */
enum class LessonPlace { Practice, Inbox, Sessions, Control, Settings }

/**
 * One step: where to look and what to try. [prompts] are words a Control step
 * suggests (MobileTutorialModes · Lesson in Control); a tap puts one in the
 * coordinator's composer and sends nothing.
 */
data class LessonStep(val place: LessonPlace, val text: String, val prompts: List<String> = emptyList())

/** A short lesson on the real screens (MobileTutorials · Learn). */
data class Lesson(val id: String, val title: String, val line: String, val steps: List<LessonStep>)

/** The six lessons, a minute or two each. None of their steps can answer, start or change anything. */
val LESSONS: List<Lesson> = listOf(
    Lesson(
        "answer", "Answer a session that needs you", "Question cards, 1 2 3, own words",
        listOf(
            LessonStep(LessonPlace.Practice, "Open the practice session that waits on you. This is a pretend fleet; nothing reaches a host."),
            LessonStep(LessonPlace.Practice, "Read the question card: 1 allows once, 2 allows for the session, 3 lets you say what to do instead."),
            LessonStep(LessonPlace.Practice, "Answer it. In your real fleet the same card asks before anything risky, and nothing is pre-selected."),
        ),
    ),
    Lesson(
        "find", "Find any session", "Search, filters, grouping",
        listOf(
            LessonStep(LessonPlace.Sessions, "Sessions lists every session, grouped by host."),
            LessonStep(LessonPlace.Sessions, "Search finds a session by its name, ticket or host."),
            LessonStep(LessonPlace.Sessions, "The filters sheet keeps your filters and grouping, even after a restart."),
        ),
    ),
    Lesson(
        "control", "Ask the fleet in Control", "Plain questions, forms, missions",
        listOf(
            LessonStep(LessonPlace.Control, "Control answers from your real fleet. Try asking it \"What needs me?\".", listOf("What needs me?")),
            LessonStep(
                LessonPlace.Control,
                "Ask it to do something, like \"start a session for FLEET-150\". It shows a form before anything runs.",
                listOf("Start a session for FLEET-150", "What did I ship today?"),
            ),
            LessonStep(LessonPlace.Control, "Missions are longer jobs Control runs; Pause all stops every one."),
        ),
    ),
    Lesson(
        "start", "Start a session", "Host, project, worktree",
        listOf(
            LessonStep(LessonPlace.Sessions, "New session asks three things: where, which project, and a review before it starts."),
            LessonStep(LessonPlace.Sessions, "A worktree keeps the session's changes apart from the main checkout."),
        ),
    ),
    Lesson(
        "recover", "Recover a failed session", "Retry, repair, move",
        listOf(
            LessonStep(LessonPlace.Inbox, "A failed session shows in the Inbox in red, with what went wrong."),
            LessonStep(LessonPlace.Inbox, "Open log shows why. Retry runs it again; Move takes it to another host you pick."),
        ),
    ),
    Lesson(
        "notify", "Hear when you are needed", "Notifications on this phone",
        listOf(
            LessonStep(LessonPlace.Settings, "This phone can tell you when a session needs you, even with the app closed."),
            LessonStep(LessonPlace.Settings, "Pick which kinds reach you. A notification never answers anything: it opens the question."),
        ),
    ),
)
