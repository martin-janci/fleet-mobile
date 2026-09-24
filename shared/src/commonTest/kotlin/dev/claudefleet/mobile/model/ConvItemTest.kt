package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The hub's `ConvItem` enum has **nine** variants, and this app models all of
 * them plus a fallback.
 *
 * It modelled two for a while, and the cost of that is the reason these tests
 * exist in this shape. `crates/fleet-core/src/service/transcript.rs` grew
 * `subagent`, `compact`, `command` and `interrupt`, and then on 2026-09-20
 * `notification` — a change (`74c82b3`, "parse task notifications instead of
 * printing their XML") that improved the desktop and quietly made the phone
 * worse: before it, a task notification arrived as a `text` item holding raw
 * XML, which was ugly and readable; after it, it arrived tagged and the phone
 * drew "(unsupported item: notification)". Content that had been visible
 * stopped being visible, nothing failed, and no test on either side could see
 * it, because each repo was internally consistent.
 *
 * It happened a second time on 2026-09-22, and this time the hub's commit said
 * so: "stop printing harness XML at the reader" added `bash` and `harness` —
 * a `!` shell line and a lone harness `<tag>…</tag>` block, both of which had
 * been arriving as `text` items full of raw XML. Same trade as before, and the
 * same answer: model them here.
 *
 * So the fixtures below are the hub's own JSON, field names and all. A shape
 * change upstream fails here rather than turning into a placeholder on a
 * screen. The fallback still has its own test: the tenth variant, whenever it
 * comes, must degrade rather than throw.
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


    // ---- the seven kinds the hub grew, in the hub's own wire shapes ----

    private fun itemOf(itemJson: String): ConvItem =
        json.decodeFromString(Conversation.serializer(), """{"turns":[{"items":[$itemJson]}]}""")
            .turns.single().items.single()

    /**
     * A `Task`/`Agent` call. This fleet runs subagents constantly, so these are
     * among the most common items on the screen rather than an edge case.
     */
    @Test
    fun a_subagent_call_is_parsed_with_what_it_was_asked_and_what_it_answered() {
        val item = itemOf(
            """{"kind":"subagent","id":"tu_1","name":"Task","agent_type":"general-purpose",
                "description":"Find every caller","result":"Four call sites.",
                "error":false,"at":"t1","ended_at":"t2","done":true}""",
        )

        assertIs<ConvItem.Subagent>(item)
        assertEquals("general-purpose", item.agentType)
        assertEquals("Find every caller", item.description)
        assertEquals("Four call sites.", item.result)
        assertEquals("Find every caller", item.label, "the label says what it was asked to do")
    }

    /** A subagent still in flight, and one that failed: both have to look it. */
    @Test
    fun a_subagent_reports_running_and_failed_states() {
        val running = itemOf("""{"kind":"subagent","name":"Task","done":false}""")
        assertIs<ConvItem.Subagent>(running)
        assertEquals(false, running.done)

        val failed = itemOf("""{"kind":"subagent","name":"Task","error":true,"done":true}""")
        assertIs<ConvItem.Subagent>(failed)
        assertTrue(failed.error)
    }

    /**
     * A task notification: the one whose absence was a regression.
     *
     * `summary` is what the hub puts the readable sentence in, so that is what
     * the screen shows; the other fields are kept because a notification with
     * no summary still has to say something.
     */
    @Test
    fun a_task_notification_is_parsed_and_has_something_to_show() {
        val item = itemOf(
            """{"kind":"notification","task_id":"b12","tool_use_id":"tu_9","status":"completed",
                "summary":"Background command finished","result":"exit 0",
                "output_file":"/tmp/out","event":"task-notification","at":"t1"}""",
        )

        assertIs<ConvItem.Notification>(item)
        assertEquals("completed", item.status)
        assertEquals("Background command finished", item.label)
    }

    /** And one with no summary still says something rather than nothing. */
    @Test
    fun a_notification_without_a_summary_falls_back_through_its_other_fields() {
        assertEquals("exit 0", itemOf("""{"kind":"notification","result":"exit 0"}""").label)
        assertEquals("task-notification", itemOf("""{"kind":"notification","event":"task-notification"}""").label)
        assertEquals("task notification", itemOf("""{"kind":"notification"}""").label, "never blank")
    }

    @Test
    fun a_compaction_is_parsed_and_says_so_even_with_no_summary() {
        val withSummary = itemOf(
            """{"kind":"compact","trigger":"auto","pre_tokens":183000,"summary":"Earlier work on the parser."}""",
        )
        assertIs<ConvItem.Compact>(withSummary)
        assertEquals(183_000L, withSummary.preTokens)
        assertEquals("Earlier work on the parser.", withSummary.label)

        assertEquals("Context compacted", itemOf("""{"kind":"compact","trigger":"manual"}""").label)
    }

    @Test
    fun a_slash_command_is_parsed_with_its_arguments_and_output() {
        val item = itemOf("""{"kind":"command","name":"review","args":"--fast","output":"no findings"}""")

        assertIs<ConvItem.Command>(item)
        assertEquals("no findings", item.output)
        assertEquals("/review --fast", item.label)
        assertEquals("/compact", itemOf("""{"kind":"command","name":"compact"}""").label, "no args, no trailing space")
    }

    /** An interrupt says *where* it landed, which is the useful half. */
    @Test
    fun an_interrupt_distinguishes_one_during_a_tool_call() {
        val duringTool = itemOf("""{"kind":"interrupt","during_tool":true}""")
        assertIs<ConvItem.Interrupt>(duringTool)
        assertEquals("Interrupted during a tool call", duringTool.label)

        assertEquals("Interrupted", itemOf("""{"kind":"interrupt","during_tool":false}""").label)
    }

    /**
     * A `!` shell line the person ran in the REPL.
     *
     * The hub used to hand this over as a `text` item holding
     * `<bash-input>…</bash-input><bash-stdout>…</bash-stdout>`, so the screen
     * drew the tags. Modelling it is what turns that back into a command and
     * its output.
     */
    @Test
    fun a_bash_line_is_parsed_with_its_command_and_output() {
        val item = itemOf(
            """{"kind":"bash","command":"git pull --ff-only",
                "stdout":"Already up to date.","stderr":null}""",
        )

        assertIs<ConvItem.Bash>(item)
        assertEquals("git pull --ff-only", item.command)
        assertEquals("Already up to date.", item.stdout)
        assertEquals("!git pull --ff-only", item.label, "it reads the way it was typed")
    }

    /** stderr is the half worth seeing when a command fails. */
    @Test
    fun a_bash_line_keeps_stderr() {
        val item = itemOf("""{"kind":"bash","command":"false","stderr":"boom"}""")
        assertIs<ConvItem.Bash>(item)
        assertEquals("boom", item.stderr)
        assertEquals(null, item.stdout)
    }

    /**
     * A lone harness block: the hub's catch-all for a `<tag>…</tag>` entry it
     * has no dedicated item for, so a tag the harness adds later never reaches
     * a reader as XML on either end.
     */
    @Test
    fun a_harness_block_is_parsed_and_labelled_by_what_is_in_it() {
        val item = itemOf("""{"kind":"harness","tag":"ci-monitor-event","body":"PR #12 checks failed"}""")

        assertIs<ConvItem.Harness>(item)
        assertEquals("ci-monitor-event", item.tag)
        assertEquals("PR #12 checks failed", item.label)
    }

    /** With nothing in it, the tag is still something to draw. */
    @Test
    fun a_harness_block_with_an_empty_body_falls_back_to_its_tag() {
        assertEquals("ci-monitor-event", itemOf("""{"kind":"harness","tag":"ci-monitor-event"}""").label)
        assertTrue(itemOf("""{"kind":"harness"}""").label.isNotBlank(), "never blank")
    }

    /**
     * The hub lifts `<system-reminder>` blocks out of the prompt into
     * `reminders`, which is why a prompt on this screen is the person's own
     * words again. The field is carried rather than drawn: what matters on a
     * phone is that it is no longer *inside* `prompt`.
     */
    @Test
    fun a_turns_reminders_are_parsed_and_kept_off_the_prompt() {
        val parsed = json.decodeFromString(
            Conversation.serializer(),
            """{"turns":[{"prompt":"UI nereflektuje","reminders":["You are operating in a git worktree."],
                 "items":[{"kind":"text","text":"ok"}]}]}""",
        )
        val turn = parsed.turns.single()
        assertEquals("UI nereflektuje", turn.prompt)
        assertEquals(listOf("You are operating in a git worktree."), turn.reminders)
    }

    /** An older hub sends no `reminders` at all, and that must still decode. */
    @Test
    fun a_turn_from_an_older_hub_without_reminders_still_parses() {
        val parsed = json.decodeFromString(
            Conversation.serializer(),
            """{"turns":[{"prompt":"hi","items":[]}]}""",
        )
        assertEquals(emptyList(), parsed.turns.single().reminders)
    }

    /**
     * Every kind the hub emits today, parsed as itself.
     *
     * The list is the point: it is this app's copy of
     * `transcript.rs`'s `ConvItem`, and the thing that went wrong before was
     * that the two drifted with nothing comparing them. A kind added upstream
     * still degrades safely — the test below this one — but it will show a
     * placeholder until it is added here.
     */
    @Test
    fun every_kind_the_hub_emits_today_is_modelled() {
        val known = mapOf(
            "text" to """{"kind":"text","text":"x"}""",
            "tool" to """{"kind":"tool","summary":"Read(a)"}""",
            "subagent" to """{"kind":"subagent","name":"Task"}""",
            "compact" to """{"kind":"compact"}""",
            "command" to """{"kind":"command","name":"c"}""",
            "notification" to """{"kind":"notification"}""",
            "interrupt" to """{"kind":"interrupt"}""",
            "bash" to """{"kind":"bash","command":"ls"}""",
            "harness" to """{"kind":"harness","tag":"ci-monitor-event"}""",
        )

        val placeholders = known.filterValues { itemOf(it) is ConvItem.Unsupported }.keys
        assertEquals(
            emptySet(),
            placeholders,
            "these kinds come from the hub and would draw as '(unsupported item: …)'",
        )
        for ((kind, payload) in known) {
            assertTrue(itemOf(payload).label.isNotBlank(), "$kind must have something to draw")
        }
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
        assertEquals("Context compacted", ConvItem.Compact().label)
        assertEquals("/compact", ConvItem.Command(name = "compact").label)
        assertEquals("agent finished", ConvItem.Notification(summary = "agent finished", status = "completed").label)
        assertEquals("completed", ConvItem.Notification(summary = null, status = "completed").label)
        assertEquals("task notification", ConvItem.Notification().label)
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
