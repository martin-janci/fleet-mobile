package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ReviewNoteActions
import dev.claudefleet.mobile.model.NoteAnchor
import dev.claudefleet.mobile.model.NoteDraft
import dev.claudefleet.mobile.model.NoteSide
import dev.claudefleet.mobile.model.ReviewNote
import dev.claudefleet.mobile.model.ReviewNotesSent
import dev.claudefleet.mobile.model.noteQuote
import dev.claudefleet.mobile.model.reviewNotesSendArgs
import dev.claudefleet.mobile.model.sendNotesLabel
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Review notes on a session's diff, on the phone (claude-fleet migration 223). */
class ReviewNotesTest {
    private class Fake(
        var sent: List<ReviewNote> = emptyList(),
        var failSend: Throwable? = null,
        val delivered: Boolean = true,
    ) : ReviewNoteActions {
        val sendIds = mutableListOf<String>()
        val sends = mutableListOf<List<NoteDraft>>()
        override suspend fun list(sessionId: Long): List<ReviewNote> = sent
        override suspend fun send(sessionId: Long, clientMsgId: String, drafts: List<NoteDraft>): ReviewNotesSent {
            sendIds += clientMsgId
            failSend?.let { throw it }
            sends += drafts
            val notes = drafts.mapIndexed { i, d ->
                ReviewNote(id = 100L + i, sessionId = sessionId, batch = clientMsgId, path = d.anchor.path, line = d.anchor.line, side = d.anchor.side, quote = d.quote, body = d.body, author = "Ada · phone")
            }
            return ReviewNotesSent(sessionId, notes, delivered = delivered)
        }
        override suspend fun resolve(sessionId: Long, noteId: Long, resolved: Boolean): ReviewNote =
            sent.first { it.id == noteId }.copy(resolvedAt = if (resolved) 9 else null, resolvedBy = "Ada · phone")
    }

    private val a = NoteAnchor("src/main.rs", 12, NoteSide.NEW)
    private val b = NoteAnchor("src/lib.rs", 3, NoteSide.OLD)

    @Test
    fun a_tapped_line_becomes_a_draft_and_a_blank_save_deletes_it() = runTest {
        val vm = ReviewNotesViewModel(7, Fake(), backgroundScope, now = { 1000 })
        vm.openLine(a, "let x = 1;")
        vm.saveDraft("why 1?")
        assertEquals(NoteDraft(a, "let x = 1;", "why 1?"), vm.state.value.drafts[a])
        assertNull(vm.state.value.open)
        vm.openLine(a, "let x = 1;")
        vm.saveDraft("  ")
        assertTrue(vm.state.value.drafts.isEmpty())
    }

    @Test
    fun send_types_every_draft_once_and_keeps_them_as_sent_notes() = runTest {
        val fake = Fake()
        val vm = ReviewNotesViewModel(7, fake, backgroundScope, now = { 1000 })
        vm.openLine(a, "x"); vm.saveDraft("one")
        vm.openLine(b, "y"); vm.saveDraft("two")
        vm.send()?.join()
        assertEquals(2, fake.sends.single().size)
        assertTrue(vm.state.value.drafts.isEmpty())
        assertEquals(setOf(a, b), vm.state.value.sentByAnchor.keys)
        assertEquals("●1", noteBadge(a, "x", vm.state.value))
        assertNull(noteBadge(a, "x changed", vm.state.value), "a note sits only where the line still says what it quoted")
    }

    @Test
    fun drafts_and_the_pending_id_outlive_the_visit_and_the_process() = runTest {
        // Review notes 7: tapping back dropped the drafts and the retry id.
        val prefs = dev.claudefleet.mobile.store.FakePrefs()
        val memory = NoteDraftMemory(prefs).apply { forHub("https://hub") }
        val fake = Fake(failSend = RuntimeException("timeout"))
        val first = ReviewNotesViewModel(7, fake, backgroundScope, now = { 1000 }, memory = memory)
        first.openLine(a, "x"); first.saveDraft("one")
        first.send()?.join()

        // A new visit, in a new process: the same drafts, and a retry reuses the id.
        val reloaded = NoteDraftMemory(prefs).apply { forHub("https://hub") }
        val second = ReviewNotesViewModel(7, fake, backgroundScope, now = { 2000 }, memory = reloaded)
        assertEquals(NoteDraft(a, "x", "one"), second.state.value.drafts[a])
        fake.failSend = null
        second.send()?.join()
        assertEquals(fake.sendIds[0], fake.sendIds[1], "same notes, same id: the hub types nothing twice")
        assertTrue(second.state.value.drafts.isEmpty())
        assertTrue(NoteDraftMemory(prefs).apply { forHub("https://hub") }.drafts(7).isEmpty())

        // Another hub's session 7 is not this one's.
        memory.keepDrafts(7, mapOf(a to NoteDraft(a, "x", "kept")))
        memory.forHub("https://other")
        assertTrue(memory.drafts(7).isEmpty())
    }

    @Test
    fun a_retry_reuses_the_id_only_for_the_same_notes_and_drops_only_what_it_carried() = runTest {
        // Review notes 3: an id reused for edited notes came back `repeat` and
        // took every draft, the new one too.
        val fake = Fake(failSend = RuntimeException("timeout"))
        val vm = ReviewNotesViewModel(7, fake, backgroundScope, now = { 1000 }, memory = NoteDraftMemory())
        vm.openLine(a, "x"); vm.saveDraft("one")
        vm.send()?.join()
        vm.openLine(b, "y"); vm.saveDraft("two")
        fake.failSend = null
        vm.send()?.join()
        assertTrue(fake.sendIds[0] != fake.sendIds[1], "other notes are a new send")

        val repeating = object : ReviewNoteActions by Fake() {
            override suspend fun send(sessionId: Long, clientMsgId: String, drafts: List<NoteDraft>): ReviewNotesSent =
                ReviewNotesSent(sessionId, emptyList(), delivered = true, repeat = true)
        }
        val memory = NoteDraftMemory()
        memory.keepDrafts(7, mapOf(a to NoteDraft(a, "x", "one")))
        val vm2 = ReviewNotesViewModel(7, repeating, backgroundScope, now = { 1000 }, memory = memory)
        val job = vm2.send()
        vm2.openLine(b, "y"); vm2.saveDraft("written meanwhile")
        job?.join()
        assertEquals(setOf(b), vm2.state.value.drafts.keys, "only the drafts that were in that send go")
    }

    @Test
    fun the_hubs_in_flight_and_conflict_refusals_keep_the_drafts_in_plain_words() = runTest {
        val memory = NoteDraftMemory()
        val fake = Fake(failSend = HubError.Tool("E_IN_FLIGHT", "client_msg_id rn-1 is still being typed"))
        val vm = ReviewNotesViewModel(7, fake, backgroundScope, now = { 1000 }, memory = memory)
        vm.openLine(a, "x"); vm.saveDraft("one")
        vm.send()?.join()
        assertEquals("Still being typed", vm.state.value.error?.title)
        assertEquals(1, vm.state.value.drafts.size)
        assertNotNull(memory.pending(7), "the hub still types this send: its id stays")

        fake.failSend = HubError.Tool("E_CONFLICT", "client_msg_id rn-1 carried other notes")
        vm.send()?.join()
        assertEquals("Notes changed since the last try", vm.state.value.error?.title)
        assertEquals(1, vm.state.value.drafts.size)
        assertNull(memory.pending(7), "the next send is a new one")
    }

    @Test
    fun a_failed_resolve_is_said_in_the_sheet() = runTest {
        val note = ReviewNote(id = 5, path = a.path, line = a.line, side = a.side, quote = "x", body = "b")
        val failing = object : ReviewNoteActions by Fake(sent = listOf(note)) {
            override suspend fun resolve(sessionId: Long, noteId: Long, resolved: Boolean): ReviewNote = throw HubError.Tool("E_FORBIDDEN", "no")
        }
        val vm = ReviewNotesViewModel(7, failing, backgroundScope, now = { 1000 })
        vm.refresh().join()
        vm.resolve(note)?.join()
        assertNotNull(vm.state.value.resolveError)
        assertNull(vm.state.value.error)
    }

    @Test
    fun notes_whose_line_changed_or_that_sit_elsewhere_are_listed_above_the_diff() {
        val lines = diffLines("@@ -1,2 +1,2 @@\n-old\n+let x = 2;\n same")
        val on = ReviewNote(id = 1, path = "f", line = 1, side = NoteSide.NEW, quote = "let x = 2;", body = "on")
        val moved = ReviewNote(id = 2, path = "f", line = 2, side = NoteSide.NEW, quote = "let y = 1;", body = "changed since")
        val whole = ReviewNote(id = 3, path = "f", line = null, body = "whole file")
        val other = ReviewNote(id = 4, path = "g", line = 1, quote = "let x = 2;", body = "other file")
        assertEquals(listOf(2L, 3L, 4L), notesElsewhere("f", lines, listOf(on, moved, whole, other)).map { it.id })
    }

    @Test
    fun a_send_without_an_answer_retries_with_the_same_id() = runTest {
        val fake = Fake(failSend = RuntimeException("timeout"))
        val vm = ReviewNotesViewModel(7, fake, backgroundScope, now = { 1000 })
        vm.openLine(a, "x"); vm.saveDraft("one")
        vm.send()?.join()
        assertNotNull(vm.state.value.error)
        assertEquals(1, vm.state.value.drafts.size)
        fake.failSend = null
        vm.send()?.join()
        assertEquals(2, fake.sendIds.size)
        assertEquals(fake.sendIds[0], fake.sendIds[1])
    }

    @Test
    fun a_refused_send_is_answered_so_the_next_one_is_new() = runTest {
        val fake = Fake(failSend = HubError.Tool("E_FORBIDDEN", "a viewer does not send"))
        val vm = ReviewNotesViewModel(7, fake, backgroundScope, now = { 1000 })
        vm.openLine(a, "x"); vm.saveDraft("one")
        vm.send()?.join()
        fake.failSend = null
        vm.send()?.join()
        assertTrue(fake.sendIds[0] != fake.sendIds[1])
    }

    @Test
    fun a_queued_send_says_so_and_resolve_marks_the_line() = runTest {
        val note = ReviewNote(id = 5, path = a.path, line = a.line, side = a.side, body = "b", author = "Martin · desktop")
        val vm = ReviewNotesViewModel(7, Fake(sent = listOf(note), delivered = false), backgroundScope, now = { 1000 })
        vm.refresh().join()
        vm.resolve(note)?.join()
        assertEquals("✓", noteBadge(a, "", vm.state.value))
        vm.openLine(b, "y"); vm.saveDraft("two")
        vm.send()?.join()
        assertEquals("Queued: it goes in when the agent's turn ends.", vm.state.value.notice)
    }

    @Test
    fun the_wire_args_and_labels() {
        val args = reviewNotesSendArgs(7, "rn-1", listOf(NoteDraft(a, "x", " one "), NoteDraft(b, "", "two")))
        val notes = args["notes"]!!.jsonArray
        // By file: src/lib.rs before src/main.rs.
        assertEquals("src/lib.rs", notes[0].jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals("old", notes[0].jsonObject["side"]!!.jsonPrimitive.content)
        assertNull(notes[0].jsonObject["quote"])
        assertEquals("one", notes[1].jsonObject["body"]!!.jsonPrimitive.content)
        assertEquals("send", args["action"]!!.jsonPrimitive.content)
        assertEquals("Send 1 note to Claude", sendNotesLabel(1, "Claude"))
        assertEquals("Send 3 notes to Codex", sendNotesLabel(3, "Codex"))
        assertEquals("let x = 1;", noteQuote("+let x = 1;"))
        val wire = json.decodeFromString<ReviewNote>(
            """{"id":1,"session_id":7,"batch":"rn-1","seq":1,"path":"a.rs","line":4,"side":"old","body":"b","author":"Ada · desktop","created_at":5,"resolved_at":6,"resolved_by":"Ada · desktop"}""",
        )
        assertTrue(wire.resolved)
    }

    @Test
    fun a_diff_line_takes_the_side_the_desktop_gives_it() {
        assertEquals(NoteAnchor("f", 4, NoteSide.OLD), lineAnchor("f", CodeLine("-x", LineKind.Removed, old = 4)))
        assertEquals(NoteAnchor("f", 5, NoteSide.NEW), lineAnchor("f", CodeLine("+x", LineKind.Added, new = 5)))
        assertEquals(NoteAnchor("f", 6, NoteSide.NEW), lineAnchor("f", CodeLine(" x", LineKind.Plain, old = 5, new = 6)))
        assertNull(lineAnchor("f", CodeLine("@@ -1 +1 @@", LineKind.Hunk)))
    }
}
