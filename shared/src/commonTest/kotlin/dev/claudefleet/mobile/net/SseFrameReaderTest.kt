package dev.claudefleet.mobile.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The streaming half of the framing, which `extractJsonRpcPayload` deliberately
 * is not: every frame, in order, with its `event:` name kept.
 *
 * The shapes here are what `axum::response::sse` actually writes for
 * `crates/fleet-core/src/mcp/events_route.rs` — `sse_event(name, payload)` is
 * `Event::default().event(name).data(payload.to_string())`, and the keep-alive
 * is axum's default, a bare `:` comment every 15 s.
 */
class SseFrameReaderTest {

    private fun read(vararg lines: String): List<SseFrame> {
        val reader = SseFrameReader()
        return lines.mapNotNull { reader.accept(it) }
    }

    @Test
    fun a_named_frame_comes_back_with_its_name_and_its_data() {
        val frames = read("event: session:updated", """data: {"id":7}""", "")

        assertEquals(1, frames.size)
        assertEquals("session:updated", frames[0].event)
        assertEquals("""{"id":7}""", frames[0].data)
    }

    /** The thing `extractJsonRpcPayload` cannot do: hand back more than one. */
    @Test
    fun every_frame_arrives_not_just_the_first() {
        val frames = read(
            "event: session:updated",
            """data: {"id":1}""",
            "",
            "event: session:killed",
            """data: {"id":1}""",
            "",
        )

        assertEquals(listOf("session:updated", "session:killed"), frames.map { it.event })
    }

    /** State from one frame must not bleed into the next. */
    @Test
    fun an_unnamed_frame_after_a_named_one_does_not_inherit_the_name() {
        val frames = read(
            "event: session:updated",
            """data: {"id":1}""",
            "",
            """data: {"id":2}""",
            "",
        )

        assertEquals(2, frames.size)
        assertEquals("session:updated", frames[0].event)
        assertNull(frames[1].event, "a frame that names no event has none")
    }

    /** The 15-second heartbeat: a bare `:` comment, which is not a frame. */
    @Test
    fun a_keep_alive_comment_produces_nothing() {
        assertEquals(emptyList(), read(":", "", ": ping", ""))
    }

    @Test
    fun a_comment_between_two_frames_does_not_disturb_them() {
        val frames = read(
            "event: host:probed",
            """data: {"alias":"a"}""",
            "",
            ":",
            "",
            "event: host:removed",
            """data: {"alias":"a"}""",
            "",
        )

        assertEquals(listOf("host:probed", "host:removed"), frames.map { it.event })
    }

    /** Exactly one space after the colon is part of the framing, not the value. */
    @Test
    fun one_leading_space_is_stripped_and_only_one() {
        assertEquals("""{"id":7}""", read("data: {\"id\":7}", "")[0].data)
        assertEquals(""" {"id":7}""", read("data:  {\"id\":7}", "")[0].data)
        assertEquals("""{"id":7}""", read("data:{\"id\":7}", "")[0].data)
    }

    /** A multi-line payload rejoins with the newline the framing removed. */
    @Test
    fun several_data_lines_rejoin_with_newlines() {
        val frames = read("event: session:updated", "data: {", """data:   "id": 7""", "data: }", "")

        assertEquals("{\n  \"id\": 7\n}", frames[0].data)
    }

    /** A proxy that writes CRLF must not leave a stray carriage return in the JSON. */
    @Test
    fun a_trailing_carriage_return_is_not_part_of_the_value() {
        val frames = read("event: session:updated\r", "data: {\"id\":7}\r", "\r")

        assertEquals("session:updated", frames[0].event)
        assertEquals("""{"id":7}""", frames[0].data)
    }

    /**
     * A connection cut mid-frame leaves a partial payload. Nothing is dispatched
     * for it — a truncated JSON object is not something to hand to a parser, and
     * the reconnect refetches anyway.
     * `a_body_cut_off_mid_frame_yields_only_the_complete_frames` is the other
     * half: the stream really does drop it.
     */
    @Test
    fun a_frame_the_stream_was_cut_in_the_middle_of_is_never_dispatched() {
        val reader = SseFrameReader()

        assertNull(reader.accept("event: session:updated"))
        assertNull(reader.accept("""data: {"id":"""), "no blank line yet, so no frame")
        assertTrue(reader.partial, "and what is buffered is known to be incomplete")
    }

    /** `id:` and `retry:` are framing fields this client has no use for. */
    @Test
    fun unknown_and_unused_fields_are_skipped_without_ending_the_frame() {
        val frames = read(
            "id: 12",
            "retry: 3000",
            "event: session:updated",
            "nonsense",
            """data: {"id":7}""",
            "",
        )

        assertEquals(1, frames.size)
        assertEquals("session:updated", frames[0].event)
        assertEquals("""{"id":7}""", frames[0].data)
    }

    /** A frame carrying no data at all is not dispatched (the SSE rule). */
    @Test
    fun a_frame_with_a_name_but_no_data_is_not_dispatched() {
        assertTrue(read("event: session:updated", "").isEmpty())
    }
}

/**
 * Turning a frame into something the repository can act on. Every payload here
 * is the exact JSON `events_route.rs` builds.
 */
class EventFrameMeaningTest {

    @Test
    fun the_ready_frame_names_the_hub_version_and_what_the_stream_will_carry() {
        val event = frameToEvent(
            SseFrame("ready", """{"version":"0.9.3","now":1758153600,"kinds":["session","host"]}"""),
        )

        assertEquals(HubEvent.Ready("0.9.3", listOf("session", "host")), event)
    }

    /** `lagged` is the hub saying the picture has a hole in it. */
    @Test
    fun the_lagged_frame_says_how_many_events_were_missed() {
        val event = frameToEvent(SseFrame("lagged", """{"skipped":42}"""))

        assertEquals(HubEvent.Lagged(42), event)
    }

    @Test
    fun a_row_event_keeps_its_name_and_its_payload() {
        val event = frameToEvent(SseFrame("session:killed", """{"id":7}"""))

        assertTrue(event is HubEvent.Row)
        assertEquals("session:killed", event.name)
        assertEquals("""{"id":7}""", event.payload.toString())
    }

    /** A frame with no name at all is not something this stream ever sends. */
    @Test
    fun an_unnamed_frame_is_ignored() {
        assertNull(frameToEvent(SseFrame(null, """{"id":7}""")))
    }

    /**
     * A hub — or whatever is standing in for one — answering something
     * unintelligible must not kill the stream.
     *
     * `<html>` is the one that caught this out: `parseToJsonElement` accepts a
     * bare token as a JSON literal, so a captive portal's login page would have
     * parsed cleanly and arrived as a `ready` frame naming no version. Every
     * payload this route sends is an object, so anything else is refused.
     */
    @Test
    fun a_payload_that_is_not_a_json_object_is_ignored_rather_than_thrown() {
        assertNull(frameToEvent(SseFrame("session:updated", "not json at all")))
        assertNull(frameToEvent(SseFrame("ready", "<html>")))
        assertNull(frameToEvent(SseFrame("ready", "")))
        assertNull(frameToEvent(SseFrame("session:updated", "[1,2,3]")))
        assertNull(frameToEvent(SseFrame("lagged", "42")))
    }
}
