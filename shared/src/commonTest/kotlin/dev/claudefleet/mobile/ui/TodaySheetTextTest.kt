package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.TodayGroup
import dev.claudefleet.mobile.model.TodaySection
import dev.claudefleet.mobile.model.TodaySession
import dev.claudefleet.mobile.model.TodayShipped
import dev.claudefleet.mobile.model.TodayView
import dev.claudefleet.mobile.ui.theme.StatusTone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Today sheet's pure functions, each a `@Composable`-free `internal` that
 * decides what a person reads or which colour they see — and each exercised by
 * nothing. A composed test cannot reach them (the sheet draws into a window of
 * its own) and no unit test named them, so the header line, the triage colours
 * and the section colours were all drawn on trust.
 */
class TodaySheetTextTest {
    private fun s(id: Long, attention: String? = null) =
        TodaySession(id = id, name = "s$id", hostAlias = "trn", attention = attention)

    private fun view(groups: List<TodayGroup>, shipped: List<TodayShipped> = emptyList()) =
        TodayView(waiting = groups, shipped = shipped)

    /**
     * The header counts LIVE sessions, and says "today" only of the thing that
     * is about the day.
     *
     * It used to open with "Since midnight", which claimed the day for a number
     * that is not about it: `v.sessions` is what is running now, not what ran
     * since midnight.
     */
    @Test
    fun the_header_line_counts_live_sessions_and_claims_the_day_only_for_shipped() {
        assertEquals("1 live session", todaySummary(view(listOf(TodayGroup(key = "A", sessions = listOf(s(1)))))))
        assertEquals(
            "2 live sessions · 1 needs you",
            todaySummary(view(listOf(TodayGroup(key = "A", sessions = listOf(s(1, "waiting"), s(2)))))),
        )
        assertEquals(
            "2 live sessions · 2 need you · 1 shipped today",
            todaySummary(
                view(
                    listOf(TodayGroup(key = "A", sessions = listOf(s(1, "waiting"), s(2, "stuck")))),
                    listOf(TodayShipped(how = "done", key = "B", title = "t")),
                ),
            ),
        )
        // A session counts once however many groups list it.
        val twice = TodayView(
            waiting = listOf(TodayGroup(key = "A", sessions = listOf(s(1)))),
            inProgress = listOf(TodayGroup(key = "B", sessions = listOf(s(1)))),
        )
        assertEquals("1 live session", todaySummary(twice))
        // Nothing at all still says something rather than an empty line.
        assertEquals("0 live sessions", todaySummary(TodayView()))
    }

    /** A colour means one thing across the app: amber answers, red is broken, grey is nothing to do. */
    @Test
    fun the_triage_colours_are_the_sessions_lists() {
        assertEquals(StatusTone.BLOCKED, attentionTone("waiting"))
        assertEquals(StatusTone.STUCK, attentionTone("stuck"))
        assertEquals(StatusTone.FAILED, attentionTone("failed"))
        assertEquals(StatusTone.FAILED, attentionTone("stop_failed"))
        assertEquals(StatusTone.FAILED, attentionTone("ci_failing"))
        assertEquals(StatusTone.IDLE, attentionTone("lifecycle"))
        // A reason this build has never heard of needs a person, so it reads
        // amber rather than disappearing into grey.
        assertEquals(StatusTone.BLOCKED, attentionTone("something_new"))
    }

    @Test
    fun each_section_keeps_its_own_tone() {
        assertEquals(
            listOf(StatusTone.BLOCKED, StatusTone.WORKING, StatusTone.COMPLETED, StatusTone.IDLE),
            TodaySection.entries.map { sectionTone(it) },
        )
    }
}
