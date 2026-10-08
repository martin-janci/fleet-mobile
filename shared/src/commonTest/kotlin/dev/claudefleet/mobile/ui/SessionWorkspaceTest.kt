package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.PendingInput
import dev.claudefleet.mobile.model.PendingOption
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HUB_VERSION_DIGIT_KEYS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One session on the New bar (redesign 14.4, MobileSession and
 * MobileSessionFiles): its tabs, the question card's answers, and the Files
 * tab's diff, tree and file helpers.
 */
class SessionWorkspaceTest {

    private fun row(status: String? = "blocked", stuck: String? = null, pending: PendingInput? = null) =
        SessionRow(id = 1, tmuxName = "s", claudeStatus = status, stuckKind = stuck, pendingInput = pending)

    /** Claude Code's own permission question, as the hub reports it. */
    private val permission = PendingInput(
        "permission",
        "Allow Bash: cargo fleet-test -- notify::quiet?",
        listOf(
            PendingOption(1, "Yes", true),
            PendingOption(2, "Yes, and don't ask again for cargo fleet-test"),
            PendingOption(3, "No, and tell Claude what to do differently"),
        ),
    )

    // --- tabs ---

    @Test
    fun the_agent_tab_is_named_after_the_agent_never_terminal() {
        assertEquals("Claude Code", agentName(row()))
        assertEquals("Claude Code", agentName(null))
        assertEquals("Claude Code", SessionTab.Agent.label(agentName(row())))
        assertTrue(SessionTab.entries.none { it.label(agentName(null)).contains("Terminal") })
    }

    @Test
    fun tabs_follow_the_desktop_order_and_files_needs_a_worktree() {
        assertEquals(
            listOf(SessionTab.Conversation, SessionTab.Agent, SessionTab.Files, SessionTab.Details),
            sessionTabs(hasWorktree = true),
        )
        assertEquals(
            listOf(SessionTab.Conversation, SessionTab.Agent, SessionTab.Details),
            sessionTabs(hasWorktree = false),
        )
    }

    // --- the question card: the never-list ---

    /**
     * On a permission question Enter picks the highlighted answer, which is
     * "Yes": a bare Enter on the card would be a second Approve that does not
     * say so. The card offers the agent's numbered answers and nothing else;
     * Enter and Esc live on the agent tab, with their meaning.
     */
    @Test
    fun a_permission_card_offers_the_numbered_answers_and_no_raw_enter() {
        val card = blockedCard(row(pending = permission), HUB_VERSION_DIGIT_KEYS)!!
        val answers = questionAnswers(card, stuckKind = null)

        assertEquals(listOf(1, 2, 3), answers.map { (it.answer as Answer.Option).n })
        assertTrue(answers.none { it.answer == Answer.Enter || it.answer == Answer.Escape })
        assertEquals("1  Yes", answers.first().label)
    }

    @Test
    fun the_keys_move_to_the_agent_tab_with_what_they_do() {
        val card = blockedCard(row(pending = permission), HUB_VERSION_DIGIT_KEYS)!!
        assertEquals(
            listOf("Enter · the highlighted answer", "Esc · cancel"),
            agentKeys(card, stuckKind = null).map { it.label },
        )
        val trust = blockedCard(row(stuck = "trust_prompt"), HUB_VERSION_DIGIT_KEYS)!!
        assertEquals(listOf("Enter · trust", "Esc · exit"), agentKeys(trust, "trust_prompt").map { it.label })
    }

    /** The trust prompt's answers are decisions, each naming its key — not two bare keystrokes. */
    @Test
    fun the_trust_prompt_says_what_each_key_decides() {
        val card = blockedCard(row(stuck = "trust_prompt"), HUB_VERSION_DIGIT_KEYS)!!
        assertEquals(
            listOf("Trust this folder · Enter", "Exit Claude Code · Esc"),
            questionAnswers(card, "trust_prompt").map { it.label },
        )
    }

    /** "Answer in your own words…" presses the question's own No — never an answer that allows. */
    @Test
    fun answering_in_words_declines_and_never_allows() {
        val card = blockedCard(row(pending = permission), HUB_VERSION_DIGIT_KEYS)!!
        assertEquals(Answer.Option(3, "No, and tell Claude what to do differently"), declineOption(card))

        val noNo = blockedCard(
            row(pending = PendingInput("permission", "Allow?", listOf(PendingOption(1, "Yes"), PendingOption(2, "Yes, always")))),
            HUB_VERSION_DIGIT_KEYS,
        )!!
        assertNull(declineOption(noNo), "a question with no No has no own-words answer")
        assertNull(declineOption(blockedCard(row(stuck = "trust_prompt"), HUB_VERSION_DIGIT_KEYS)!!))
    }

    // --- the Files tab ---

    @Test
    fun a_diff_numbers_its_lines_from_each_hunk() {
        val lines = diffLines(
            """
            diff --git a/x b/x
            --- a/x
            +++ b/x
            @@ -88,3 +88,4 @@ HostRow(…)
             @Composable
            -    Text(host.name)
            +    Row {
            +    }
             Text(host.versions)
            @@ -120,2 +121,2 @@
             a
            """.trimIndent(),
        )
        val body = lines.filter { it.kind != LineKind.Meta && it.kind != LineKind.Hunk }
        assertEquals(listOf(88 to 88, 89 to null, null to 89, null to 90, 90 to 91, 120 to 121), body.map { it.old to it.new })
        assertEquals(listOf(3, 9), hunkStarts(lines))
    }

    @Test
    fun a_long_pressed_line_asks_about_its_number_and_text() {
        val line = CodeLine("+    Row {", LineKind.Added, new = 90)
        assertEquals("About ui/HostsScreen.kt line 90:\n```\n+    Row {\n```\n", askAboutLine("ui/HostsScreen.kt", line))
    }

    @Test
    fun a_path_shows_its_name_first() {
        assertEquals("HostsViewModelTest.kt" to "shared/src/jvmTest/kotlin/ui", splitPath("shared/src/jvmTest/kotlin/ui/HostsViewModelTest.kt"))
        assertEquals("README.md" to "", splitPath("README.md"))
        assertEquals("shared/" to "", splitPath("shared/"))
    }

    @Test
    fun the_tree_lists_one_folder_at_a_time_folders_first() {
        val entries = listOf("shared/", "shared/build.gradle.kts", "shared/src/main/A.kt", "README.md", "androidApp/x.kt", ".gitignore")
        assertEquals(
            listOf("androidApp/", "shared/", ".gitignore", "README.md"),
            folderListing(entries, "").map { if (it.folder) "${it.name}/" else it.name },
        )
        assertEquals(
            listOf(TreeEntry("src", "shared/src/", folder = true), TreeEntry("build.gradle.kts", "shared/build.gradle.kts", folder = false)),
            folderListing(entries, "shared/"),
        )
    }

    @Test
    fun markdown_is_told_by_its_extension() {
        assertTrue(isMarkdown("README.md"))
        assertTrue(isMarkdown("docs/Guide.MARKDOWN"))
        assertFalse(isMarkdown("md/notes.txt"))
        assertFalse(isMarkdown("Makefile"))
    }

    @Test
    fun sizes_read_as_a_person_says_them() {
        assertEquals("812 B", sizeLabel(812))
        assertEquals("2.1 KB", sizeLabel(2150))
        assertEquals("3.4 MB", sizeLabel(3_565_158))
    }
}
