package dev.claudefleet.mobile.ui.help

import dev.claudefleet.mobile.model.AccountUsageSnapshot
import dev.claudefleet.mobile.model.AccountUsageWindows
import dev.claudefleet.mobile.model.Section
import dev.claudefleet.mobile.model.UsageWindow
import dev.claudefleet.mobile.store.FakePrefs
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.claudefleet.mobile.ui.Navigator
import dev.claudefleet.mobile.ui.PhoneLayout
import dev.claudefleet.mobile.ui.Screen
import dev.claudefleet.mobile.ui.Tab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tutorials and help modes (redesign 14.22, MobileTutorials and MobileTutorialModes). */
class HelpTest {

    @Test
    fun the_picker_shows_until_a_mode_is_picked_and_the_pick_is_remembered() {
        val prefs = FakePrefs()
        val help = HelpSettings(prefs)
        assertNull(help.state.value.mode, "no mode yet: the picker shows")
        help.pick(HelpMode.TIPS, tourHere = true)
        assertEquals(HelpMode.TIPS, HelpSettings(prefs).state.value.mode)
        assertNull(help.state.value.tourStop, "Tips only has no tour")
    }

    @Test
    fun show_me_around_starts_the_tour_where_it_can_run() {
        val help = HelpSettings(FakePrefs())
        help.pick(HelpMode.TOUR, tourHere = true)
        assertEquals(0, help.state.value.tourStop)
        val elsewhere = HelpSettings(FakePrefs())
        elsewhere.pick(HelpMode.TOUR, tourHere = false)
        assertNull(elsewhere.state.value.tourStop)
    }

    @Test
    fun the_tour_has_five_stops_at_most_and_ends_after_the_last() {
        assertTrue(TOUR_STOPS.size <= 5)
        val help = HelpSettings(FakePrefs())
        help.startTour()
        repeat(TOUR_STOPS.size - 1) { help.nextStop() }
        assertEquals(TOUR_STOPS.size - 1, help.state.value.tourStop)
        help.nextStop()
        assertNull(help.state.value.tourStop)
    }

    /** The step's acceptance test: Skip and No help leave nothing behind. */
    @Test
    fun skip_and_no_help_leave_nothing_behind() {
        val prefs = FakePrefs()
        val help = HelpSettings(prefs)
        help.pick(HelpMode.TOUR, tourHere = true)
        help.skipTour()
        assertNull(help.state.value.tourStop, "Skip ends the tour")
        assertNull(HelpSettings(prefs).state.value.tourStop, "and it does not come back on the next start")

        help.pick(HelpMode.NONE, tourHere = true)
        val s = help.state.value
        assertNull(s.tourStop, "No help starts no tour")
        assertTrue(Tip.entries.none { s.shows(it) }, "No help shows no tip")
        assertNull(s.lesson)
    }

    /**
     * 14.22's Verified by, the stored half: Skip and No help write nothing a
     * later start would act on — only the mode the person picked.
     */
    @Test
    fun skip_and_no_help_store_nothing_but_the_choice() {
        val skipped = FakePrefs()
        HelpSettings(skipped).apply {
            pick(HelpMode.TOUR, tourHere = true)
            skipTour()
        }
        assertEquals(setOf("help.mode"), skipped.written, "Skip leaves no tour, tip or lesson state")
        val fresh = HelpSettings(skipped).state.value
        assertNull(fresh.tourStop)
        assertNull(fresh.lesson)
        assertTrue(fresh.lessonsDone.isEmpty())

        val none = FakePrefs()
        HelpSettings(none).pick(HelpMode.NONE, tourHere = true)
        assertEquals(setOf("help.mode"), none.written, "No help leaves nothing but the choice")
        val again = HelpSettings(none).state.value
        assertNull(again.tourStop, "and starts no tour on the next launch")
        assertTrue(Tip.entries.none { again.shows(it) })
    }

    /** Leaving the practice fleet leaves nothing behind: it opens fresh, its demo waiting again. */
    @Test
    fun the_practice_fleet_starts_fresh_after_it_is_left() {
        val practice = PracticeFleet()
        practice.open(PracticeFixtures.demo.id)
        practice.answer(1)
        practice.reset()
        assertEquals(PracticeState(), practice.state.value)
        assertEquals("blocked", practice.sessions.first { it.id == PracticeFixtures.demo.id }.claudeStatus)
    }

    /** 14.22: practice notifications are marked Practice, offer no button, and name no real session. */
    @Test
    fun practice_notifications_are_marked_practice() {
        val n = PracticeFixtures.notification()
        assertTrue(n.title.startsWith("Practice · "), n.title)
        assertTrue(n.publicBody.startsWith("Practice · "), "the lock screen line says Practice too")
        assertTrue(PracticeFixtures.QUESTION !in n.publicBody, "a locked phone never shows the question")
        assertTrue(n.actions.isEmpty(), "no Approve, no button at all")
        assertTrue(n.sessionId < 0, "a practice id no hub session has")
    }

    @Test
    fun a_tip_shows_once_and_no_more_tips_stops_them_all_until_reset() {
        val prefs = FakePrefs()
        val help = HelpSettings(prefs)
        help.pick(HelpMode.TIPS, tourHere = false)
        assertTrue(help.state.value.shows(Tip.INBOX))
        help.gotIt(Tip.INBOX)
        assertFalse(help.state.value.shows(Tip.INBOX))
        assertTrue(help.state.value.shows(Tip.CONTROL))
        assertFalse(HelpSettings(prefs).state.value.shows(Tip.INBOX), "remembered")
        help.noMoreTips()
        assertTrue(Tip.entries.none { help.state.value.shows(it) })
        help.resetTips()
        assertTrue(Tip.entries.all { help.state.value.shows(it) }, "every tip comes back once")
    }

    @Test
    fun no_tip_shows_over_the_tour() {
        val help = HelpSettings(FakePrefs())
        help.pick(HelpMode.TOUR, tourHere = true)
        assertFalse(help.state.value.shows(Tip.INBOX))
        help.skipTour()
        assertTrue(help.state.value.shows(Tip.INBOX))
    }

    @Test
    fun explain_more_uses_the_longer_words() {
        val help = HelpSettings(FakePrefs())
        assertEquals(Tip.QUESTION.text, help.state.value.words(Tip.QUESTION))
        help.setExplainMore(true)
        assertEquals(Tip.QUESTION.more, help.state.value.words(Tip.QUESTION))
    }

    @Test
    fun a_lesson_is_done_after_its_last_step_and_end_lesson_does_not_count() {
        val prefs = FakePrefs()
        val help = HelpSettings(prefs)
        val lesson = LESSONS.first { it.id == "find" }
        help.startLesson(lesson)
        help.endLesson()
        assertTrue(help.state.value.lessonsDone.isEmpty())
        help.startLesson(lesson)
        repeat(lesson.steps.size) { help.nextStep() }
        assertNull(help.state.value.lesson)
        assertEquals(setOf("find"), HelpSettings(prefs).state.value.lessonsDone)
    }

    @Test
    fun there_are_six_lessons_with_unique_ids() {
        assertEquals(6, LESSONS.size)
        assertEquals(LESSONS.size, LESSONS.map { it.id }.toSet().size)
        assertTrue(LESSONS.all { it.steps.isNotEmpty() })
    }

    @Test
    fun the_practice_question_waits_for_a_tap_and_takes_only_one_to_three() {
        val practice = PracticeFleet()
        assertNull(practice.state.value.answered, "nothing is pre-selected")
        assertEquals("blocked", practice.sessions.first { it.id == PracticeFixtures.demo.id }.claudeStatus)
        practice.open(PracticeFixtures.demo.id)
        assertFailsWith<IllegalArgumentException> { practice.answer(4) }
        practice.answer(1)
        assertEquals(1, practice.state.value.answered)
        assertEquals("working", practice.sessions.first { it.id == PracticeFixtures.demo.id }.claudeStatus)
        assertTrue(practice.back())
        assertFalse(practice.back())
        practice.reset()
        assertNull(practice.state.value.answered, "every visit starts fresh")
    }

    @Test
    fun practice_rows_cannot_collide_with_real_sessions() {
        assertTrue(PracticeFixtures.sessions.all { it.id < 0 }, "real session ids are positive")
    }

    @Test
    fun a_guide_lists_each_setting_once_and_forgets_a_change_put_back() {
        val one = emptyList<GuideChange>().record(GuideChange("accounts.pause_at", "Stop starting at", "90", "80"))
        val two = one.record(GuideChange("accounts.pause_at", "Stop starting at", "80", "95"))
        assertEquals(listOf(GuideChange("accounts.pause_at", "Stop starting at", "90", "95")), two)
        val back = two.record(GuideChange("accounts.pause_at", "Stop starting at", "95", "90"))
        assertTrue(back.isEmpty())
    }

    @Test
    fun learn_practice_and_guides_sit_over_more_and_back_returns_there() {
        val nav = Navigator(PhoneLayout.New)
        nav.select(Tab.More)
        nav.openLearn()
        assertEquals(Screen.Learn, nav.screen.value)
        assertEquals(Tab.More, nav.tab.value)
        nav.openGuide("guide.usage")
        assertEquals(Screen.Guide("guide.usage"), nav.screen.value)
        assertTrue(nav.back())
        nav.openPractice()
        assertEquals(Screen.Practice, nav.screen.value)
        assertTrue(nav.back())
        assertEquals(Screen.Learn, nav.screen.value)
        assertTrue(nav.back())
        assertEquals(Screen.More, nav.screen.value)
    }

    /** G7.18 (MobileTutorialModes · Guide): a step about usage limits shows the real meters. */
    @Test
    fun a_guide_step_about_account_limits_shows_every_accounts_meter() {
        fun field(key: String) = buildJsonObject {
            put("type", "field")
            put("key", key)
        }
        assertTrue(guideStepShowsUsage(Section("Pause new work", items = listOf(field("accounts.pause_at")))))
        assertFalse(guideStepShowsUsage(Section("Tidy up", items = listOf(field("gc.enabled")))))
        assertFalse(guideStepShowsUsage(Section("What it does", items = listOf(buildJsonObject { put("type", "notice"); put("text", "accounts.x") }))))

        val now = 1_000L
        val usage = mapOf(
            "b" to AccountUsageSnapshot("b", AccountUsageWindows(sevenDay = UsageWindow(15.0))),
            "a" to AccountUsageSnapshot("a", AccountUsageWindows(sevenDay = UsageWindow(100.0, resetsAt = now + 3600))),
            "c" to AccountUsageSnapshot("c"),
        )
        val meters = guideMeters(mapOf("a" to "tech.silvester", "b" to "m.janci"), usage, now)
        assertEquals(listOf("m.janci", "tech.silvester"), meters.map { it.name }, "no reading, no meter; in name order")
        assertEquals("week 85% left", meters[0].line)
        assertTrue(meters[1].low)
    }
}
