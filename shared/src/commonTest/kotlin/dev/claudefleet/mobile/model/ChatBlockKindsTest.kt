package dev.claudefleet.mobile.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Step 10.8: the phone reads every fleet.ui/1 kind the desktop and the hub
 * read, with the same problems, word for word ([CHAT_BLOCK_EXAMPLES]).
 */
class ChatBlockKindsTest {
    private val fence = "```"

    @Test
    fun every_shared_case_gives_the_desktops_problems() {
        val cases = (Json.parseToJsonElement(CHAT_BLOCK_EXAMPLES) as JsonObject)["cases"] as JsonArray
        assertTrue(cases.size > 30)
        for (c in cases) {
            c as JsonObject
            val name = c["name"]!!.jsonPrimitive.content
            val want = (c["problems"] as JsonArray).map { it.jsonPrimitive.content }
            val got = when (val r = checkUiBlock(c["block"].toString())) {
                is UiCheck.Ok -> emptyList()
                is UiCheck.Bad -> r.problems
            }
            assertEquals(want, got, name)
        }
    }

    private fun block(json: String): UiBlock = assertIs<UiCheck.Ok>(checkUiBlock(json)).block

    @Test
    fun a_progress_reads_its_count_and_steps() {
        val b = assertIs<UiBlock.Progress>(
            block("""{"spec":"fleet.ui/1","kind":"progress","id":"deploy-42","title":"Deploying","done":3,"total":7,"unit":"hosts","steps":[{"title":"Build","state":"done"},{"title":"Restart"}]}"""),
        )
        assertEquals("running", b.state)
        assertEquals(listOf(ProgressStep("Build", "done"), ProgressStep("Restart", "pending")), b.steps)
        assertEquals(3L to 7L, b.done to b.total)
    }

    @Test
    fun a_guide_with_a_page_is_that_page() {
        assertEquals(UiBlock.GuidePage("guide.cleanup"), block("""{"spec":"fleet.ui/1","kind":"guide","page":"guide.cleanup","title":"ignored"}"""))
    }

    @Test
    fun a_setting_carries_only_its_proposal() {
        assertEquals(UiBlock.Setting(42, "why"), block("""{"spec":"fleet.ui/1","kind":"setting","proposal":42,"note":"why"}"""))
    }

    @Test
    fun results_keep_numbers_and_text_apart() {
        val b = assertIs<UiBlock.Results>(
            block(
                """{"spec":"fleet.ui/1","kind":"results","items":[
                {"type":"stat","label":"p95","value":412},{"type":"stat","label":"Region","value":"eu"},
                {"type":"chart","chart":"line","title":"C","x":{"label":"day"},"y":{"label":"n","ty":"int"},"points":[["mon",1],[2,3.5]]},
                {"type":"table","columns":[{"label":"A"},{"label":"B","ty":"tokens"}],"rows":[["x",1500],[null,true]]}]}""",
            ),
        )
        val stats = b.items.filterIsInstance<ResultItem.Stat>()
        assertEquals(412.0, stats[0].value)
        assertEquals("eu", stats[1].value)
        val chart = b.items.filterIsInstance<ResultItem.Chart>().single()
        assertEquals(listOf<Pair<Any, Double>>("mon" to 1.0, 2.0 to 3.5), chart.points)
        assertEquals(ResultAxis("n", "int"), chart.y)
        val table = b.items.filterIsInstance<ResultItem.Table>().single()
        assertEquals(listOf(JsonNull, JsonPrimitive(true)), table.rows[1])
    }

    @Test
    fun cells_read_as_the_desktops_formatCell() {
        val now = 1_000_000L
        assertEquals("$1.83", formatResultCell("usd_micros", JsonPrimitive(1_830_000), now))
        assertEquals("$250", formatResultCell("usd_micros", JsonPrimitive(250_000_000), now))
        assertEquals("1.5k", formatResultCell("tokens", JsonPrimitive(1500), now))
        assertEquals("2.0M", formatResultCell("tokens", JsonPrimitive(2_000_000), now))
        assertEquals("999", formatResultCell("tokens", JsonPrimitive(999), now))
        assertEquals("12,345", formatResultCell("int", JsonPrimitive(12345), now))
        assertEquals("1,234.5", formatResultCell("int", JsonPrimitive(1234.5), now))
        assertEquals("5 min ago", formatResultCell("time", JsonPrimitive(now - 300), now))
        assertEquals("never", formatResultCell("time", JsonNull, now))
        assertEquals("—", formatResultCell("int", JsonNull, now))
        assertEquals("2026-10-08", formatResultCell("day", JsonPrimitive("2026-10-08"), now))
    }

    private fun progress(id: String, state: String, done: Int) =
        "$fence" + "fleet-ui\n{\"spec\":\"fleet.ui/1\",\"kind\":\"progress\",\"id\":\"$id\",\"title\":\"Import\",\"state\":\"$state\",\"done\":$done,\"total\":10}\n$fence"

    @Test
    fun progress_of_one_id_is_one_card_showing_the_newest() {
        val first = progress("import", "running", 2)
        val second = "Moving on.\n\n" + progress("import", "running", 6)
        val third = progress("import", "done", 10)
        val other = progress("export", "running", 1)
        val board = progressBoard(listOf(first, "no blocks here", second, other, third))
        val raw = { t: String -> (splitRich(t).filterIsInstance<RichSegment.Ui>().single()).raw }
        assertTrue(board.home("import", raw(first)))
        assertFalse(board.home("import", raw(second)))
        assertFalse(board.home("import", raw(third)))
        assertTrue(board.home("export", raw(other)))
        assertEquals("done", board.newest("import")?.state)
        assertEquals(10L, board.newest("import")?.done)
        assertEquals(2, board.updates("import"))
        assertEquals(0, board.updates("export"))
        // A block the board never saw draws as its own card.
        assertTrue(ProgressBoard.EMPTY.home("import", raw(first)))
        assertNull(ProgressBoard.EMPTY.newest("import"))
    }
}
