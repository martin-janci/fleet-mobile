@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.RepoActions
import dev.claudefleet.mobile.model.ChangedFile
import dev.claudefleet.mobile.model.Commit
import dev.claudefleet.mobile.model.CommitDetail
import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.model.ConvTurn
import dev.claudefleet.mobile.model.FileContent
import dev.claudefleet.mobile.model.FileDiff
import dev.claudefleet.mobile.model.PlanSaid
import dev.claudefleet.mobile.model.RepoTree
import dev.claudefleet.mobile.model.epochDayOf
import dev.claudefleet.mobile.model.planApprovedPrompt
import dev.claudefleet.mobile.model.planChangesDraft
import dev.claudefleet.mobile.model.planChangesPrefix
import dev.claudefleet.mobile.model.planDate
import dev.claudefleet.mobile.model.planFile
import dev.claudefleet.mobile.model.planFirstBrief
import dev.claudefleet.mobile.model.planSaid
import dev.claudefleet.mobile.model.withPlanFirst
import dev.claudefleet.mobile.store.FakePrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plan first on the phone (claude-fleet `docs/plan-first.md`): the words, the signal, and the card's state. */
class PlanFirstTest {
    private val plan = "docs/plans/2026-11-10-retry.md"

    private fun turn(prompt: String?, at: String, vararg said: String) =
        ConvTurn(prompt = prompt, at = at, items = said.map { ConvItem.Text(it) })

    private fun file(path: String, status: String = "untracked") = ChangedFile(path, status)

    // ---- the words ----

    @Test
    fun the_agent_is_told_where_the_plan_goes_what_to_say_and_to_wait() {
        val brief = planFirstBrief("2026-11-10")
        assertTrue("docs/plans/2026-11-10-<short-slug>.md" in brief)
        assertTrue("do not commit it" in brief)
        assertTrue("Say \"Plan ready\"" in brief)
        assertTrue("Stop." in brief)
        assertEquals("Fix the login\n\n$brief", withPlanFirst("Fix the login\n", "2026-11-10"))
        assertEquals(brief, withPlanFirst("  ", "2026-11-10"), "nothing typed: the instructions go alone")
        assertTrue("$plan is approved" in planApprovedPrompt(plan))
        assertTrue("say \"Plan ready\" again" in planChangesPrefix(plan))
    }

    @Test
    fun ask_for_changes_names_the_plan_once_and_keeps_what_was_typed() {
        val once = planChangesDraft("drop step 3", plan)
        assertEquals(planChangesPrefix(plan) + "drop step 3", once)
        assertEquals(once, planChangesDraft(once, plan), "a second tap adds nothing")
    }

    @Test
    fun the_plan_files_date_is_the_phones_own_day() {
        val lateEvening = epochDayOf("2026-11-10")!! * 86_400L + 23 * 3_600L + 30 * 60L
        assertEquals("2026-11-10", planDate(lateEvening, 0))
        assertEquals("2026-11-11", planDate(lateEvening, 3_600), "half past midnight in Bratislava")
        assertEquals("2026-01-05", planDate(epochDayOf("2026-01-05")!! * 86_400L, 0))
    }

    @Test
    fun under_the_first_message_the_hint_says_what_goes_with_it() {
        assertTrue("Plan first" in firstMessageHint(planFirst = true))
        assertFalse("Plan first" in firstMessageHint(planFirst = false))
    }

    // ---- the signal: what the agent said last ----

    @Test
    fun plan_ready_is_heard_in_what_the_agent_said_since_the_last_prompt() {
        assertNull(planSaid(emptyList()))
        assertNull(planSaid(listOf(turn("Add retries", "t1", "Reading the code."))))
        assertEquals(
            PlanSaid("I read the loop.\n**Plan ready**: `$plan`", "t1"),
            planSaid(listOf(turn("Add retries", "t1", "I read the loop.", "**Plan ready**: `$plan`"))),
        )
        assertEquals("t1", planSaid(listOf(turn("x", "t1", "The plan is ready in docs/plans/a.md.")))?.at)
        // A turn no prompt opened (a notification) belongs to the prompt before it.
        assertEquals("t1", planSaid(listOf(turn("x", "t1", "Plan ready: docs/plans/a.md"), turn(null, "t2", "The build finished.")))?.at)
        // The next prompt, whoever sent it, ends the announcement.
        assertNull(planSaid(listOf(turn("x", "t1", "Plan ready: docs/plans/a.md"), turn("Looks good, go ahead", "t2", "Starting step 1."))))
        assertNull(planSaid(listOf(turn("x", "t1", "Plan ready: docs/plans/a.md"), turn("go", "t2"))))
    }

    @Test
    fun only_what_the_agent_said_counts_not_what_a_command_printed() {
        val searched = ConvTurn(
            prompt = "x",
            at = "t1",
            items = listOf(ConvItem.Text("Looking at the last plan."), ConvItem.Tool("grep -r \"Plan ready\" docs/plans/a.md")),
        )
        assertNull(planSaid(listOf(searched)), "a search for the words is not the agent saying so")
        val wrote = searched.copy(
            items = listOf(ConvItem.Text("Wrote it."), ConvItem.Tool("Write docs/plans/a.md"), ConvItem.Text("Plan ready: docs/plans/a.md")),
        )
        assertEquals(PlanSaid("Wrote it.\nPlan ready: docs/plans/a.md", "t1"), planSaid(listOf(wrote)), "what it said around a tool call, one line each")
    }

    @Test
    fun the_plan_is_the_file_the_reply_names_among_the_changes() {
        val reply = "Plan ready: $plan"
        assertNull(planFile(listOf(file("src/a.kt", "modified")), reply))
        assertEquals(plan, planFile(listOf(file(plan), file("src/a.kt", "modified")), reply))
        assertEquals("web/docs/superpowers/plans/x.md", planFile(listOf(file("web/docs/superpowers/plans/x.md", "added")), "Plan ready, see x.md"))
        // A plan the reply does not name is somebody else's, or an older one.
        assertNull(planFile(listOf(file("docs/plans/2026-11-01-other.md")), reply))
        // A deleted plan, a nested file and another kind of file are not one.
        assertNull(planFile(listOf(file("docs/plans/x.md", "deleted"), file("docs/plans/img/x.md"), file("docs/plans/x.txt")), "Plan ready: x.md x.txt"))
        // Several named: a new plan before an edited one; among new ones the last by name.
        assertEquals(
            "docs/plans/2026-11-10-b.md",
            planFile(
                listOf(file("docs/plans/2026-11-01-old.md", "modified"), file("docs/plans/2026-11-09-a.md"), file("docs/plans/2026-11-10-b.md")),
                "Plan ready. I split it: 2026-11-01-old.md, 2026-11-09-a.md and 2026-11-10-b.md",
            ),
        )
        // The edited one sorts last by name here, and the new one still wins.
        assertEquals(
            "docs/plans/2026-11-10-b.md",
            planFile(
                listOf(file("docs/plans/2026-11-12-later.md", "modified"), file("docs/plans/2026-11-10-b.md")),
                "Plan ready: 2026-11-10-b.md. I also fixed a link in 2026-11-12-later.md",
            ),
        )
        // A plan that was committed and then changed is announced as an edited file.
        assertEquals(plan, planFile(listOf(file(plan, "modified"), file("src/a.kt", "modified")), reply))
        assertEquals("docs/plans/b.md", planFile(listOf(file("docs/plans/a.md", "modified"), file("docs/plans/b.md", "modified")), "Plan ready: a.md and b.md"))
    }

    // ---- answered on this phone ----

    @Test
    fun an_answered_announcement_stays_answered_and_the_next_one_asks_again() {
        val prefs = FakePrefs()
        val first = PlanSettled(prefs)
        assertFalse(first.isSettled(7, PlanReady(plan, "t1")))
        first.settle(7, PlanReady(plan, "t1"))

        val next = PlanSettled(prefs)
        assertTrue(next.isSettled(7, PlanReady(plan, "t1")), "across a killed process")
        assertFalse(next.isSettled(7, PlanReady(plan, "t2")), "the same file, said again after another prompt")
        assertFalse(next.isSettled(8, PlanReady(plan, "t1")), "another session")
        assertFalse(next.isSettled(7, PlanReady("docs/plans/other.md", "t1")), "another plan, announced before any other prompt")
    }

    @Test
    fun another_hubs_sessions_are_not_this_ones_and_only_the_newest_are_kept() {
        val prefs = FakePrefs()
        val settled = PlanSettled(prefs)
        settled.forHub("https://a.example")
        settled.settle(7, PlanReady(plan, "t1"))
        settled.forHub("https://a.example")
        assertTrue(settled.isSettled(7, PlanReady(plan, "t1")), "the same hub again keeps it")
        settled.forHub("https://b.example")
        assertFalse(settled.isSettled(7, PlanReady(plan, "t1")))
        assertFalse(PlanSettled(prefs).isSettled(7, PlanReady(plan, "t1")), "and the store forgets it too")

        for (id in 1L..(PlanSettled.MAX_SESSIONS + 5L)) settled.settle(id, PlanReady(plan, "t1"))
        assertFalse(settled.isSettled(1, PlanReady(plan, "t1")), "the oldest fell off")
        assertTrue(settled.isSettled(PlanSettled.MAX_SESSIONS + 5L, PlanReady(plan, "t1")))
        assertEquals(PlanSettled.MAX_SESSIONS * 2, prefs.getStringList(PlanSettled.KEY).size)
    }

    @Test
    fun the_hub_an_answer_was_given_on_is_remembered_across_a_killed_process() {
        val prefs = FakePrefs()
        PlanSettled(prefs).apply {
            forHub("https://a.example")
            settle(7, PlanReady(plan, "t1"))
        }
        assertTrue(PlanSettled(prefs).apply { forHub("https://a.example") }.isSettled(7, PlanReady(plan, "t1")), "the same hub keeps it")
        assertFalse(PlanSettled(prefs).apply { forHub("https://b.example") }.isSettled(7, PlanReady(plan, "t1")), "another hub's session 7 is not this one")
    }

    // ---- whether a card is due ----

    private class Changes(var files: List<ChangedFile>) : RepoActions {
        var reads = 0
        var fail: Throwable? = null

        /** Set: a read waits here until it is completed. */
        var slow: CompletableDeferred<Unit>? = null
        override suspend fun changes(sessionId: Long): List<ChangedFile> {
            reads++
            slow?.await()
            fail?.let { throw it }
            return files
        }
        override suspend fun diff(sessionId: Long, path: String): FileDiff = error("unused")
        override suspend fun log(sessionId: Long, skip: Int): List<Commit> = error("unused")
        override suspend fun commit(sessionId: Long, hash: String): CommitDetail = error("unused")
        override suspend fun commitDiff(sessionId: Long, hash: String, path: String): FileDiff = error("unused")
        override suspend fun tree(sessionId: Long): RepoTree = error("unused")
        override suspend fun file(sessionId: Long, path: String): FileContent = error("unused")
    }

    private fun said(at: String = "t1") = PlanSaid("Plan ready: `$plan`. Two open questions at the end.", at)

    @Test
    fun the_changes_are_read_only_once_the_agent_has_said_so_and_stopped() = runTest {
        val repo = Changes(listOf(file(plan)))
        val vm = PlanReadyViewModel(7, repo, PlanSettled(), backgroundScope)

        vm.heard(null, working = false)
        vm.heard(said(), working = true)
        runCurrent()
        assertEquals(0, repo.reads, "nothing said, or still working: nothing read")
        assertNull(vm.plan.value)

        vm.heard(said(), working = false)
        runCurrent()
        assertEquals(1, repo.reads)
        assertEquals(PlanReady(plan, "t1"), vm.plan.value)
    }

    @Test
    fun a_plan_the_agent_did_not_name_gets_no_card() = runTest {
        val vm = PlanReadyViewModel(7, Changes(listOf(file("docs/plans/2026-11-01-other.md"))), PlanSettled(), backgroundScope)
        vm.heard(said(), working = false)
        runCurrent()
        assertNull(vm.plan.value)
    }

    @Test
    fun the_next_prompt_takes_the_card_away_whoever_sent_it() = runTest {
        val vm = PlanReadyViewModel(7, Changes(listOf(file(plan))), PlanSettled(), backgroundScope)
        vm.heard(said(), working = false)
        runCurrent()
        assertEquals(plan, vm.plan.value?.path)

        // "Looks good, go ahead", typed by hand or on the desktop: a new turn.
        vm.heard(null, working = true)
        assertNull(vm.plan.value)
    }

    @Test
    fun a_slow_read_is_dropped_once_the_conversation_has_moved_on() = runTest {
        val repo = Changes(listOf(file(plan)))
        val slow = CompletableDeferred<Unit>().also { repo.slow = it }
        val vm = PlanReadyViewModel(7, repo, PlanSettled(), backgroundScope)
        vm.heard(said(), working = false)
        runCurrent()
        assertEquals(1, repo.reads)

        // The next prompt went out while the changes were still being read.
        vm.heard(null, working = true)
        slow.complete(Unit)
        runCurrent()
        assertNull(vm.plan.value)
    }

    @Test
    fun an_answered_card_stays_away_and_the_next_announcement_brings_one_back() = runTest {
        val settled = PlanSettled()
        val vm = PlanReadyViewModel(7, Changes(listOf(file(plan))), settled, backgroundScope)
        vm.heard(said("t1"), working = false)
        runCurrent()
        val first = vm.plan.value!!

        vm.settle(first)
        assertNull(vm.plan.value)
        // The approval waits in the hub's queue: the same words are heard again.
        vm.heard(said("t1"), working = false)
        runCurrent()
        assertNull(vm.plan.value)

        // Asked for changes, and the agent says it again after that prompt.
        vm.heard(said("t2"), working = false)
        runCurrent()
        assertEquals(PlanReady(plan, "t2"), vm.plan.value)
    }

    @Test
    fun a_read_that_fails_keeps_the_card_that_was_shown() = runTest {
        val repo = Changes(listOf(file(plan)))
        val vm = PlanReadyViewModel(7, repo, PlanSettled(), backgroundScope)
        vm.heard(said(), working = false)
        runCurrent()
        assertEquals(plan, vm.plan.value?.path)

        repo.fail = RuntimeException("connection reset")
        vm.heard(said().copy(text = said().text + "\nOne more thing."), working = false)
        runCurrent()
        assertEquals(plan, vm.plan.value?.path, "a dropped connection is not \"no plan\"")
    }
}
