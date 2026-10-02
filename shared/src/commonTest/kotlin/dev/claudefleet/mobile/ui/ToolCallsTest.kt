package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.ConvItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure rules behind a conversation's tool rows: names, targets, grouping,
 * the line diff and the result parsers. The verb table and the short-target
 * rule mirror the desktop's `conversation.ts` (`toolVerb`, `shortTarget`), so
 * those cases are the desktop's own.
 */
class ToolCallsTest {

    private fun tool(
        name: String = "Read",
        target: String? = "a.kt",
        error: Boolean = false,
        done: Boolean = true,
        id: String? = "id-$name-$target",
    ) = ConvItem.Tool(summary = "$name($target)", error = error, id = id, name = name, target = target, done = done)

    // ─── Verb and target ────────────────────────────────────────────────

    @Test
    fun the_verb_table_matches_the_desktop() {
        val expected = mapOf(
            "Read" to "Read",
            "Edit" to "Edit",
            "MultiEdit" to "Edit",
            "Write" to "Write",
            "Bash" to "Run",
            "Grep" to "Search",
            "Glob" to "Find",
            "WebFetch" to "Fetch",
            "WebSearch" to "Search web",
            "TodoWrite" to "Update todos",
        )
        for ((name, verb) in expected) assertEquals(verb, toolVerb(name), name)
        assertEquals("fleet · list_sessions", toolVerb("mcp__fleet__list_sessions"))
        assertEquals("srv · a__b", toolVerb("mcp__srv__a__b"))
        assertEquals("mcp__lonely", toolVerb("mcp__lonely"))
        assertEquals("NotebookRead", toolVerb("NotebookRead"))
        assertEquals("Tool", toolVerb(""))
    }

    @Test
    fun a_path_is_cut_to_its_last_two_segments_and_anything_else_is_left_alone() {
        assertEquals("…/store/reconcile.rs", shortTarget("/repo/crates/fleet-core/src/store/reconcile.rs"))
        assertEquals("lib/poll.ts", shortTarget("lib/poll.ts"))
        assertEquals("…/lib/poll.ts", shortTarget("src/lib/poll.ts"))
        assertEquals("poll.ts", shortTarget("poll.ts"))
        // A command, a pattern with a space, a URL: not paths.
        assertEquals("pnpm vitest run src/lib/poll.test.ts", shortTarget("pnpm vitest run src/lib/poll.test.ts"))
        assertEquals("https://example.com/a/b/c", shortTarget("https://example.com/a/b/c"))
        assertTrue(looksLikePath("src/a/b.kt"))
        assertFalse(looksLikePath("cargo test -p a/b"))
        assertFalse(looksLikePath("backoff"))
    }

    @Test
    fun a_current_hubs_row_uses_name_and_target_as_given() {
        val line = tool(name = "Edit", target = "/repo/src/lib/poll.ts").line()
        assertEquals(ToolLine(verb = "Edit", target = "…/lib/poll.ts", pathLike = true, kind = ToolKind.Edit), line)

        val bash = tool(name = "Bash", target = "pnpm check").line()
        assertEquals(ToolLine("Run", "pnpm check", pathLike = false, kind = ToolKind.Bash), bash)

        // A TodoWrite has no target; an empty one is none.
        assertNull(tool(name = "TodoWrite", target = "").line().target)
        assertNull(tool(name = "TodoWrite", target = null).line().target)
    }

    @Test
    fun an_older_hubs_one_liner_still_gives_a_verb_and_a_target() {
        val legacy = ConvItem.Tool(summary = "Read(build.gradle.kts)")
        assertEquals("Read", legacy.toolName())
        assertEquals("build.gradle.kts", legacy.toolTarget())
        assertEquals("Read", legacy.line().verb)

        val named = ConvItem.Tool(summary = "Bash(command=cargo test -p fleet-core)")
        assertEquals("Run", named.line().verb)
        assertEquals("cargo test -p fleet-core", named.line().target)

        val bare = ConvItem.Tool(summary = "TodoWrite")
        assertEquals("TodoWrite", bare.toolName())
        assertNull(bare.toolTarget())
        assertEquals(ToolKind.Todo, bare.line().kind)
    }

    @Test
    fun each_tool_has_its_kind() {
        assertEquals(ToolKind.Edit, toolKind("MultiEdit"))
        assertEquals(ToolKind.Edit, toolKind("Write"))
        assertEquals(ToolKind.Bash, toolKind("Bash"))
        assertEquals(ToolKind.Read, toolKind("Read"))
        assertEquals(ToolKind.Search, toolKind("Grep"))
        assertEquals(ToolKind.Search, toolKind("Glob"))
        assertEquals(ToolKind.Todo, toolKind("TodoWrite"))
        assertEquals(ToolKind.Other, toolKind("WebFetch"))
    }

    @Test
    fun a_block_is_headed_by_its_agent_type_or_by_its_own_name() {
        val sub = { type: String?, name: String ->
            ConvItem.Subagent(agentType = type, name = name).typeLabel()
        }
        assertEquals("Explore", sub("Explore", "Task"), "the agent type when there is one")
        assertEquals("subagent", sub(null, "Task"))
        assertEquals("subagent", sub(null, "Agent"))
        assertEquals("subagent", sub(null, ""), "an older hub sent no name at all")
        // The case this exists for: a workflow shares the `subagent` kind and
        // has no agent type, and heading it "subagent" would be a lie.
        assertEquals("workflow", sub(null, "Workflow"))
    }

    // ─── Grouping ───────────────────────────────────────────────────────

    @Test
    fun one_or_two_calls_stay_rows_and_three_fold() {
        val text = ConvItem.Text("x")
        val two = groupToolRuns(listOf(tool("Read"), tool("Grep"), text), running = false)
        assertEquals(3, two.size)
        assertTrue(two.all { it is ItemRun.One })

        val three = groupToolRuns(listOf(text, tool("Read"), tool("Grep"), tool("Edit"), text), running = false)
        assertEquals(3, three.size)
        val group = assertIs<ItemRun.Tools>(three[1])
        assertEquals(3, group.tools.size)
        assertFalse(group.startExpanded)
    }

    @Test
    fun a_run_is_broken_by_anything_that_is_not_a_tool_call() {
        val runs = groupToolRuns(
            listOf(tool("A"), tool("B"), ConvItem.Subagent(name = "Task"), tool("C"), tool("D"), tool("E")),
            running = false,
        )
        assertEquals(listOf("One", "One", "One", "Tools"), runs.map { it::class.simpleName })
    }

    @Test
    fun a_group_with_a_failure_starts_open() {
        val runs = groupToolRuns(listOf(tool("A"), tool("B", error = true), tool("C")), running = false)
        val group = assertIs<ItemRun.Tools>(runs.single())
        assertTrue(group.startExpanded)
        assertEquals(1, group.failed)
    }

    @Test
    fun only_the_last_group_of_a_running_turn_starts_open() {
        val items = listOf(tool("A"), tool("B"), tool("C"), ConvItem.Text("so far"), tool("D"), tool("E"), tool("F"))
        val running = groupToolRuns(items, running = true).filterIsInstance<ItemRun.Tools>()
        assertEquals(listOf(false, true), running.map { it.startExpanded })
        val finished = groupToolRuns(items, running = false).filterIsInstance<ItemRun.Tools>()
        assertEquals(listOf(false, false), finished.map { it.startExpanded })
    }

    @Test
    fun a_pending_call_means_the_turn_is_running() {
        assertTrue(anyPending(listOf(tool("A"), tool("B", done = false))))
        assertTrue(anyPending(listOf(ConvItem.Subagent(name = "Task", done = false))))
        assertFalse(anyPending(listOf(tool("A"), ConvItem.Text("x"))))
    }

    @Test
    fun a_group_is_labelled_by_count_and_its_first_three_tools() {
        val tools = listOf(tool("Read"), tool("Grep"), tool("Read"), tool("Edit"), tool("Bash"), tool("Write"), tool("Glob"))
        assertEquals("7 tool calls", toolGroupTitle(tools))
        assertEquals("Read, Grep, Edit +3", toolGroupNames(tools))
        assertEquals("Read, Grep", toolGroupNames(tools.take(3)))
        assertEquals("1 tool call", toolGroupTitle(tools.take(1)))
        // An older hub's rows are named from their one-liners.
        assertEquals("Bash", toolGroupNames(listOf(ConvItem.Tool("Bash(ls)"))))
    }

    // ─── Diff ───────────────────────────────────────────────────────────

    private fun List<DiffLine>.render(): List<String> = map {
        val sign = when (it.kind) {
            DiffKind.Add -> "+"
            DiffKind.Del -> "-"
            DiffKind.Context -> " "
        }
        "$sign${it.text}"
    }

    @Test
    fun a_change_in_the_middle_keeps_what_is_around_it() {
        val diff = lineDiff("a\nb\nc\nd", "a\nB\nc\nd")
        assertEquals(listOf(" a", "-b", "+B", " c", " d"), diff.render())
        // Numbers: old side for a deletion, new side for an addition, both for context.
        assertEquals(DiffLine(DiffKind.Del, "b", 2, null), diff[1])
        assertEquals(DiffLine(DiffKind.Add, "B", null, 2), diff[2])
        assertEquals(DiffLine(DiffKind.Context, "d", 4, 4), diff[4])
    }

    @Test
    fun the_lcs_keeps_a_line_that_moved_past_an_insertion_as_context() {
        // Prefix/suffix trimming alone would call everything between changed.
        val diff = lineDiff("x\nkeep\ny", "x2\nnew\nkeep\ny2")
        assertEquals(listOf("-x", "+x2", "+new", " keep", "-y", "+y2"), diff.render())
        val keep = diff.single { it.kind == DiffKind.Context }
        assertEquals(2, keep.oldNo)
        assertEquals(3, keep.newNo)
    }

    @Test
    fun a_new_file_is_all_additions_and_a_deleted_one_all_deletions() {
        assertEquals(listOf("+a", "+b"), lineDiff("", "a\nb").render())
        assertEquals(listOf(1, 2), lineDiff("", "a\nb").map { it.newNo })
        assertEquals(listOf("-a"), lineDiff("a", "").render())
        assertEquals(emptyList(), lineDiff("", ""))
    }

    @Test
    fun past_the_cell_budget_the_middle_is_deletions_then_additions() {
        val diff = lineDiff("p\na\nkeep\nb\ns", "p\nA\nkeep\nB\ns", maxCells = 4)
        assertEquals(listOf(" p", "-a", "-keep", "-b", "+A", "+keep", "+B", " s"), diff.render())
        // Within budget the same input aligns on `keep`.
        assertEquals(
            listOf(" p", "-a", "+A", " keep", "-b", "+B", " s"),
            lineDiff("p\na\nkeep\nb\ns", "p\nA\nkeep\nB\ns").render(),
        )
    }

    @Test
    fun a_large_diff_stays_within_budget_and_still_counts_right() {
        // 400 × 400 lines is inside the budget: aligned line by line.
        val old = (1..400).joinToString("\n") { "line $it" }
        val new = (1..400).joinToString("\n") { if (it % 2 == 0) "LINE $it" else "line $it" }
        assertEquals(DiffStat(added = 200, removed = 200), diffStat(lineDiff(old, new)))
        // 1 000 × 1 000 is not: every line of the changed middle, both ways.
        val bigOld = (1..1000).joinToString("\n") { "line $it" }
        val bigNew = (1..1000).joinToString("\n") { if (it % 2 == 0) "LINE $it" else "line $it" }
        assertEquals(DiffStat(added = 999, removed = 999), diffStat(lineDiff(bigOld, bigNew)))
    }

    @Test
    fun long_unchanged_runs_fold_to_a_count_with_two_lines_of_context() {
        val old = (1..20).joinToString("\n") { "l$it" }
        val new = old.replace("l10", "L10")
        val rows = collapseContext(lineDiff(old, new))
        // 9 lines before the change: 7 folded, 2 shown; the same after (10 lines).
        assertEquals(DiffRow.Gap(7), rows[0])
        assertEquals("l8", (rows[1] as DiffRow.Line).line.text)
        assertEquals("l9", (rows[2] as DiffRow.Line).line.text)
        assertEquals(DiffKind.Del, (rows[3] as DiffRow.Line).line.kind)
        assertEquals(DiffKind.Add, (rows[4] as DiffRow.Line).line.kind)
        assertEquals("l11", (rows[5] as DiffRow.Line).line.text)
        assertEquals("l12", (rows[6] as DiffRow.Line).line.text)
        assertEquals(DiffRow.Gap(8), rows[7])
        assertEquals(8, rows.size)
    }

    @Test
    fun a_gap_between_two_changes_keeps_context_on_both_sides() {
        val old = listOf("a", "1", "2", "3", "4", "5", "6", "b").joinToString("\n")
        val new = listOf("A", "1", "2", "3", "4", "5", "6", "B").joinToString("\n")
        val rows = collapseContext(lineDiff(old, new))
        val texts = rows.map { if (it is DiffRow.Line) it.line.text else "gap${(it as DiffRow.Gap).count}" }
        assertEquals(listOf("a", "A", "1", "2", "gap2", "5", "6", "b", "B"), texts)
    }

    @Test
    fun hiding_a_single_line_is_not_worth_a_fold() {
        val old = listOf("a", "1", "2", "3", "4", "5", "b").joinToString("\n")
        val new = old.replace("a", "A").replace("b", "B")
        val rows = collapseContext(lineDiff(old, new))
        assertTrue(rows.none { it is DiffRow.Gap })
    }

    @Test
    fun a_path_splits_into_its_name_and_its_directory() {
        assertEquals("poll.ts" to "/repo/src/lib/", splitPath("/repo/src/lib/poll.ts"))
        assertEquals("poll.ts" to "", splitPath("poll.ts"))
    }

    // ─── Read / search / todos / output ─────────────────────────────────

    @Test
    fun cat_n_output_parses_to_numbered_lines() {
        val lines = parseNumberedLines("     1\texport fn a() {\n     2\t\treturn 1;\n    10\t}\n")
        assertEquals(
            listOf(NumberedLine(1, "export fn a() {"), NumberedLine(2, "\treturn 1;"), NumberedLine(10, "}")),
            lines,
        )
        // The arrow form a newer Claude Code writes.
        assertEquals(listOf(NumberedLine(7, "x")), parseNumberedLines("     7→x"))
        // Whatever follows the numbered lines is not code.
        assertEquals(1, parseNumberedLines("     1\ta\n<system-reminder>…</system-reminder>")?.size)
        // Not that shape at all: the caller shows it as text.
        assertNull(parseNumberedLines("File does not exist."))
        assertNull(parseNumberedLines(""))
    }

    @Test
    fun a_todo_list_parses_with_its_statuses() {
        val todos = parseTodos(
            """{"todos":[
                {"content":"Read the code","status":"completed","activeForm":"Reading"},
                {"content":"Fix it","status":"in_progress"},
                {"content":"Test it","status":"pending"},
                {"status":"pending"}
            ]}""",
        )
        assertEquals(
            listOf(
                TodoEntry("Read the code", TodoStatus.Completed),
                TodoEntry("Fix it", TodoStatus.InProgress),
                TodoEntry("Test it", TodoStatus.Pending),
            ),
            todos,
        )
        assertNull(parseTodos("{}"))
        assertNull(parseTodos("not json"))
        assertNull(parseTodos("[1,2]"))
        // Nesting past the bound is refused before the parser sees it.
        assertNull(parseTodos("[".repeat(10_000)))
    }

    @Test
    fun an_input_string_is_read_by_key() {
        val input = """{"file_path":"/repo/a.kt","limit":20}"""
        assertEquals("/repo/a.kt", inputString(input, "file_path"))
        assertNull(inputString(input, "limit"))
        assertNull(inputString(input, "pattern"))
        assertNull(inputString("", "pattern"))
    }

    @Test
    fun a_search_result_drops_its_count_header_and_blank_lines() {
        assertEquals(
            listOf("src/lib/poll.ts", "docs/hub.md"),
            searchResultLines("Found 2 files\nsrc/lib/poll.ts\n\ndocs/hub.md\n"),
        )
        assertEquals(listOf("a.kt:3:val x"), searchResultLines("a.kt:3:val x"))
        assertEquals(emptyList(), searchResultLines(""))
    }

    @Test
    fun the_tail_of_an_output_says_how_much_came_before_it() {
        assertEquals(0 to listOf("a", "b"), tailLines("a\nb\n", 30))
        val long = (1..40).joinToString("\n") { "$it" }
        val (hidden, tail) = tailLines(long, 30)
        assertEquals(10, hidden)
        assertEquals("11", tail.first())
        assertEquals("40", tail.last())
    }
}
