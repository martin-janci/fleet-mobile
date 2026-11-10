package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals

class PromptPartsTest {
    @Test
    fun theOwnersMessageReadsAsChipsAndWhatHeTyped() {
        val prompt = "[context] the person is looking at session \"ST staging deploy z mac (bez Actions)\" on host mac. " +
            "\"this one\" and \"here\" mean that session unless they say otherwise.\n\n" +
            "The built-in dashboard \"My work\", to make my own copy of (keep the id dash.developer.mine):\n\n" +
            "```fleet-ui\n{\"dashboard\":{\"id\":\"dash.developer.mine\"}}\n```\n\n" +
            "Save the change with `dashboards { pin }` under the same id, then answer with the `dashboard` block. " +
            "Change it:  Add more data to dashboard"
        val p = promptParts(prompt)
        assertEquals(
            listOf(
                PromptChip.Session("ST staging deploy z mac (bez Actions)", "mac", null),
                PromptChip.Dashboard("My work", null, copy = true),
            ),
            p.chips,
        )
        assertEquals("Add more data to dashboard", p.text)
        assertEquals("ST staging deploy z mac (bez Actions) · mac", p.chips[0].label)
        assertEquals("Dashboard “My work” (your copy)", p.chips[1].label)
    }

    @Test
    fun aWidgetABranchAHostAndAMission() {
        val w = promptParts(
            "My pinned dashboard \"Release 4.2\", as it is now:\n\n```fleet-ui\n{}\n```\n\n" +
                "Answer with a `dashboard` block with the same id. Change the widget \"Open\": split it by person",
        )
        assertEquals(listOf(PromptChip.Dashboard("Release 4.2", "Open", copy = false)), w.chips)
        assertEquals("Dashboard “Release 4.2” › Open", w.chips[0].label)
        assertEquals("split it by person", w.text)

        val s = promptParts(
            "[context] the person is looking at session \"api\" on host mac (branch main). " +
                "\"this one\" and \"here\" mean that session unless they say otherwise.\n\nfix it",
        )
        assertEquals(PromptParts(listOf(PromptChip.Session("api", "mac", "main")), "fix it"), s)

        val h = promptParts("[context] the person is looking at host htz with no session selected. \"here\" means that host.\n\nwhy is it slow")
        assertEquals(PromptParts(listOf(PromptChip.Host("htz")), "why is it slow"), h)

        val m = promptParts("About mission #4 \"Payments v2\":\n\nSplit the refunds step")
        assertEquals(PromptParts(listOf(PromptChip.Mission(4, "Payments v2")), "Split the refunds step"), m)
    }

    @Test
    fun aHugeMissionIdDoesNotCrashAndAFinalNewlineIsNotTheEnd() {
        // Review requests 9: NumberFormatException in composition; `$` matched before a final "\n".
        assertEquals(
            PromptParts(listOf(PromptChip.Mission(0, "Payments")), "Split the refunds step"),
            promptParts("About mission #99999999999999999999 \"Payments\":\n\nSplit the refunds step"),
        )
        val t = "About mission #4 \"Payments v2\":\n"
        assertEquals(PromptParts(emptyList(), t), promptParts(t), "as the desktop reads it")
    }

    @Test
    fun aPersonsOwnWordsStayAsTheyWrote() {
        for (t in listOf("About mission #4 Payments: go", "  [context] the person is looking at host x with no session selected. \"here\" means that host.", "Change it: bigger")) {
            assertEquals(PromptParts(emptyList(), t), promptParts(t))
        }
    }
}
