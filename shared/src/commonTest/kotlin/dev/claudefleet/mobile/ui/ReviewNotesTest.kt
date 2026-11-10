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
        assertEquals("●1", noteBadge(a, vm.state.value))
    }

    @Test
    fun a_send_without_an_answer_retries_with_the_same_id() = runTest {
        val fake = Fake(failSend = RuntimeException("timeout"))
        val vm = ReviewNotesViewModel(7, fake, backgroundScope, now = { 1000 })
        vm.openLine(a, "x"); vm.saveDraft("one")
        vm.send()?.join()
        assertEquals("timeout", vm.state.value.error)
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
        assertEquals("✓", noteBadge(a, vm.state.value))
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
