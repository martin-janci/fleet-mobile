package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The hub's `ConvItem` enum has two variants today. The day it grows a third,
 * every phone already in a pocket must degrade to showing "something I don't
 * understand" — not throw on the conversation screen.
 *
 * That is the same promise `ignoreUnknownKeys` makes for a new *field*; these
 * tests make it hold for a new *variant* too.
 */
class ConvItemTest {

    @Test
    fun a_known_kind_still_parses_as_its_own_type() {
        val parsed = json.decodeFromString(
            Conversation.serializer(),
            """{"turns":[{"prompt":"hi","items":[
                 {"kind":"text","text":"hello"},
                 {"kind":"tool","summary":"Read(a.kt)","error":false}
               ]}]}""",
        )
        val items = parsed.turns.single().items
        assertEquals(ConvItem.Text("hello"), items[0])
        assertEquals(ConvItem.Tool("Read(a.kt)", error = false), items[1])
    }

    /** The one the ledger asked for: a third, invented kind must not throw. */
    @Test
    fun an_unknown_kind_degrades_instead_of_throwing() {
        val parsed = json.decodeFromString(
            Conversation.serializer(),
            """{"turns":[{"items":[{"kind":"thinking","text":"...","tokens":41}]}]}""",
        )
        val item = parsed.turns.single().items.single()
        assertIs<ConvItem.Unsupported>(item)
        assertEquals("thinking", item.kind)
    }

    /** A new variant among known ones must not take the known ones down with it. */
    @Test
    fun an_unknown_kind_beside_known_ones_leaves_them_intact() {
        val parsed = json.decodeFromString(
            Conversation.serializer(),
            """{"turns":[{"items":[
                 {"kind":"text","text":"before"},
                 {"kind":"image","url":"https://example.com/a.png"},
                 {"kind":"tool","summary":"Bash(ls)","error":true}
               ]}]}""",
        )
        val items = parsed.turns.single().items
        assertEquals(3, items.size)
        assertEquals(ConvItem.Text("before"), items[0])
        assertEquals(ConvItem.Unsupported("image"), items[1])
        assertEquals(ConvItem.Tool("Bash(ls)", error = true), items[2])
    }

    /** An item with no `kind` at all is malformed, not unknown — same treatment. */
    @Test
    fun an_item_without_a_kind_degrades_too() {
        val parsed = json.decodeFromString(
            Conversation.serializer(),
            """{"turns":[{"items":[{"text":"no discriminator"}]}]}""",
        )
        assertIs<ConvItem.Unsupported>(parsed.turns.single().items.single())
    }

    /**
     * The fallback must not depend on the app's own `Json` being lenient: a
     * caller that builds a strict one still gets a degrade, not a throw.
     */
    @Test
    fun the_fallback_does_not_rely_on_ignore_unknown_keys() {
        val strict = kotlinx.serialization.json.Json { ignoreUnknownKeys = false }
        val parsed = strict.decodeFromString(
            ConvItem.serializer(),
            """{"kind":"thinking","text":"..."}""",
        )
        assertEquals(ConvItem.Unsupported("thinking"), parsed)
    }

    /** An unsupported item still has something a screen can put on the page. */
    @Test
    fun an_unsupported_item_is_displayable() {
        assertTrue(ConvItem.Unsupported("thinking").label.isNotBlank())
    }

    /**
     * Task 5: the hub's `context` and the five item kinds it already sends
     * (`subagent`, `compact`, `command`, `notification`, `interrupt`) must
     * parse as their own types — an invented sixth kind still degrades.
     */
    @Test
    fun context_and_new_kinds_parse_and_nothing_is_unsupported_anymore() {
        val parsed = json.decodeFromString(
            Conversation.serializer(),
            """{"turns":[{"items":[
                 {"kind":"subagent","id":"tu_1","name":"Task","agent_type":"Explore","description":"find it","result":"found","error":false,"at":"t1","ended_at":"t2","done":true},
                 {"kind":"compact","trigger":"auto","pre_tokens":50000,"summary":"summarized"},
                 {"kind":"command","name":"compact","args":"now","output":"done"},
                 {"kind":"notification","task_id":"tk1","tool_use_id":"tu_2","status":"completed","summary":"agent finished","result":"the report","output_file":"/tmp/o","event":null,"at":"t3"},
                 {"kind":"interrupt","during_tool":true},
                 {"kind":"from_the_future","stuff":"???"}
               ]}],
               "context":{"tokens":1000,"window":200000,"pct":0.5,"stale":false}}""",
        )
        val items = parsed.turns.single().items
        assertEquals(
            ConvItem.Subagent(
                id = "tu_1",
                name = "Task",
                agentType = "Explore",
                description = "find it",
                result = "found",
                error = false,
                at = "t1",
                endedAt = "t2",
                done = true,
            ),
            items[0],
        )
        assertEquals(ConvItem.Compact(trigger = "auto", preTokens = 50_000, summary = "summarized"), items[1])
        assertEquals(ConvItem.Command(name = "compact", args = "now", output = "done"), items[2])
        assertEquals(
            ConvItem.Notification(
                taskId = "tk1",
                toolUseId = "tu_2",
                status = "completed",
                summary = "agent finished",
                result = "the report",
                outputFile = "/tmp/o",
                event = null,
                at = "t3",
            ),
            items[3],
        )
        assertEquals(ConvItem.Interrupt(duringTool = true), items[4])
        assertIs<ConvItem.Unsupported>(items[5])
        assertEquals("from_the_future", (items[5] as ConvItem.Unsupported).kind)
        assertEquals(ConvContext(tokens = 1000, window = 200_000, pct = 0.5, stale = false), parsed.context)
    }

    /** Every new kind still owes the screen a one-line label, whatever it turns out to hold. */
    @Test
    fun every_new_kind_has_a_sensible_label() {
        assertEquals("find it", ConvItem.Subagent(name = "Task", description = "find it").label)
        assertEquals("Task", ConvItem.Subagent(name = "Task", description = null).label)
        assertEquals("Compacted", ConvItem.Compact().label)
        assertEquals("/compact", ConvItem.Command(name = "compact").label)
        assertEquals("agent finished", ConvItem.Notification(summary = "agent finished", status = "completed").label)
        assertEquals("completed", ConvItem.Notification(summary = null, status = "completed").label)
        assertEquals("notification", ConvItem.Notification().label)
        assertEquals("Interrupted", ConvItem.Interrupt().label)
    }

    /** A missing field on any of the five new kinds must never throw — same rule as an unknown key elsewhere. */
    @Test
    fun a_new_kind_with_only_its_bare_minimum_still_parses() {
        val parsed = json.decodeFromString(
            Conversation.serializer(),
            """{"turns":[{"items":[
                 {"kind":"subagent"},
                 {"kind":"compact"},
                 {"kind":"command"},
                 {"kind":"notification"},
                 {"kind":"interrupt"}
               ]}]}""",
        )
        val items = parsed.turns.single().items
        assertEquals(ConvItem.Subagent(), items[0])
        assertEquals(ConvItem.Compact(), items[1])
        assertEquals(ConvItem.Command(), items[2])
        assertEquals(ConvItem.Notification(), items[3])
        assertEquals(ConvItem.Interrupt(), items[4])
        assertEquals(null, parsed.context, "no context in the tail is not an error")
    }
}

/**
 * What the list and the session bar actually print.
 *
 * `SessionRow.displayName` and `ProjectRow.label` are the two strings a person
 * reads on every screen, and mutation found both unpinned: `isNotBlank` could
 * become `isBlank` in either, and `&&` could become `||` in the project label,
 * with the whole suite green. Only the project *fallback* ("project #7") was
 * ever asserted — the two branches above it were not.
 */
class RowLabelTest {

    private fun row(friendly: String?, tmux: String) =
        SessionRow(id = 1, tmuxName = tmux, friendlyName = friendly)

    @Test
    fun a_session_shows_the_agents_own_name_when_it_has_one() {
        assertEquals("fixing the parser", row("fixing the parser", "sess-1").displayName)
    }

    @Test
    fun and_falls_back_to_the_tmux_name_when_it_does_not() {
        assertEquals("sess-1", row(null, "sess-1").displayName, "null friendly name")
        assertEquals("sess-1", row("", "sess-1").displayName, "empty is not a name")
        assertEquals("sess-1", row("   ", "sess-1").displayName, "nor is whitespace")
    }

    @Test
    fun a_project_is_owner_slash_repo_when_it_has_both() {
        assertEquals("martin-janci/fleet-mobile", ProjectRow(1, "martin-janci", "fleet-mobile").label)
    }

    @Test
    fun a_project_with_only_a_repo_is_named_by_it() {
        assertEquals("fleet-mobile", ProjectRow(1, "", "fleet-mobile").label, "no owner")
        assertEquals("fleet-mobile", ProjectRow(1, "   ", "fleet-mobile").label, "blank owner")
    }

    @Test
    fun a_project_with_neither_falls_back_to_its_id() {
        assertEquals("project #7", ProjectRow(7, "", "").label)
        assertEquals("project #7", ProjectRow(7, "owner", "").label, "an owner alone is not a name")
    }
}
