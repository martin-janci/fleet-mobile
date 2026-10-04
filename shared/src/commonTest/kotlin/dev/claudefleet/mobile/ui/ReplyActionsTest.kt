package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.ActivityProbe
import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.model.ConvTurn
import dev.claudefleet.mobile.model.PendingInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The reply actions' rules, held to the desktop's `reply_actions.ts`. */
class ReplyActionsTest {

    private fun turn(uuid: String?, prompt: String? = "p", partial: Boolean = false, vararg texts: String) =
        ConvTurn(prompt = prompt, promptUuid = uuid, promptPartial = partial, items = texts.map { ConvItem.Text(it) })

    private val three = listOf(turn("a"), turn("b"), turn("c"))

    @Test
    fun nothing_is_offered_where_the_hub_or_token_cannot() {
        assertEquals(ReplyActionsView(), replyActionsFor(three, 1, truncated = false, supported = false))
    }

    @Test
    fun fork_keeps_this_turn_by_anchoring_on_the_next_prompt() {
        assertEquals("c", replyActionsFor(three, 1, truncated = false, supported = true).forkAnchor)
    }

    @Test
    fun fork_scans_past_prompt_less_turns_and_keeps_everything_at_the_end() {
        val turns = listOf(turn("a"), turn(null, prompt = null), turn("c"))
        assertEquals("c", replyActionsFor(turns, 0, truncated = false, supported = true).forkAnchor)
        assertNull(replyActionsFor(turns, 2, truncated = false, supported = true).forkAnchor)
    }

    @Test
    fun rewind_anchors_on_the_turn_own_prompt() {
        val view = replyActionsFor(three, 1, truncated = false, supported = true)
        assertTrue(view.canRewind)
        assertEquals("b", view.rewindAnchor)
        assertTrue(view.canRetry)
    }

    @Test
    fun the_first_turn_of_a_conversation_cannot_be_rewound_unless_older_turns_were_dropped() {
        assertFalse(replyActionsFor(three, 0, truncated = false, supported = true).canRewind)
        assertTrue(replyActionsFor(three, 0, truncated = true, supported = true).canRewind)
    }

    @Test
    fun a_turn_without_its_own_prompt_cannot_be_rewound_but_can_be_forked() {
        val turns = listOf(turn("a"), turn(null, prompt = null))
        val view = replyActionsFor(turns, 1, truncated = false, supported = true)
        assertFalse(view.canRewind)
        assertTrue(view.canFork)
    }

    @Test
    fun retry_says_why_it_is_not_offered_for_an_image_only_or_partial_prompt() {
        val image = replyActionsFor(listOf(turn("a"), turn("b", prompt = null)), 1, truncated = false, supported = true)
        assertTrue(image.canRewind)
        assertFalse(image.canRetry)
        assertNotNull(image.retryUnavailable)

        val partial = replyActionsFor(listOf(turn("a"), turn("b", partial = true)), 1, truncated = false, supported = true)
        assertFalse(partial.canRetry)
        assertNotNull(partial.retryUnavailable)
    }

    @Test
    fun the_reply_text_is_every_text_item_in_order() {
        assertEquals("one\n\ntwo", replyText(turn("a", "p", false, " one ", "two")))
        assertEquals("", replyText(turn("a")))
    }

    @Test
    fun a_quote_marks_every_line_including_blank_ones() {
        assertEquals("> one\n>\n> two\n\n", quoteText("one\n\ntwo"))
    }

    @Test
    fun ready_is_idle_with_nothing_asked_and_nothing_generating() {
        assertTrue(isReplReady(ActivityProbe(claudeStatus = "idle")))
        assertFalse(isReplReady(ActivityProbe(claudeStatus = "completed")))
        assertFalse(isReplReady(ActivityProbe(claudeStatus = "idle", spinner = "Thinking…")))
        assertFalse(isReplReady(ActivityProbe(claudeStatus = "idle", pendingInput = PendingInput("permission", "?", emptyList()))))
    }

    @Test
    fun a_fork_name_is_git_safe() {
        assertEquals("fork-of-fix-the-login-bug", forkWorktreeName("Fix the login_bug!"))
        assertEquals("fork-of-a/b", forkWorktreeName("a//b"))
        assertEquals("fork-of", forkWorktreeName("!!!"))
        assertTrue(forkWorktreeName("x".repeat(100)).length <= 60)
        assertEquals("", branchSlug("--..//"))
        assertEquals("feat/x", branchSlug("Feat/X-"))
    }
}
