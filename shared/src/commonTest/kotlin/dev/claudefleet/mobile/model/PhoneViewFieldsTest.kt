package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every column [SessionRow] reads survives the hub's `view=phone` projection.
 *
 * The re-list (every `ready`, every `lagged` frame, every pull-to-refresh)
 * REPLACES the rows with that projection, so a column the phone reads but the
 * view drops falls back to its default until the row's next `session:updated`
 * frame — the Pinned section emptied and every viewed session read unread
 * again (review wave 24, w24-1; R3-1 before it).
 *
 * [HUB_PHONE_FIELDS] is a copy of `PHONE_SESSION_FIELDS` in claude-fleet's
 * `crates/fleet-core/src/mcp/tools/views.rs`. A new `SessionRow` column fails
 * here until the hub's view carries it (or it joins [NOT_HUB_COLUMNS] with a
 * reason).
 */
class PhoneViewFieldsTest {

    @Test
    fun every_session_row_column_is_in_the_hubs_phone_view() {
        val d = SessionRow.serializer().descriptor
        val read = (0 until d.elementsCount).map { d.getElementName(it) }.toSet()
        assertEquals(emptySet(), read - HUB_PHONE_FIELDS - NOT_HUB_COLUMNS)
    }

    private companion object {
        val HUB_PHONE_FIELDS = setOf(
            "account_uuid",
            "agent",
            "ci_status",
            "claude_profile",
            "claude_status",
            "context_pct",
            "created_at",
            "current_activity",
            "form_draft",
            "friendly_name",
            "host_alias",
            "id",
            "is_controller",
            "kind",
            "last_activity_at",
            "last_prompt",
            "last_stop_at",
            "last_turn_at",
            "last_viewed_at",
            "lost_at",
            "lost_reason",
            "needs_attention",
            "org_id",
            "origin",
            "origin_ref",
            "owner_person_id",
            "parent_session_id",
            "pending_form",
            "pending_input",
            "pinned_at",
            "pr_evidence",
            "pr_url",
            "project_id",
            "prompt_suggestion",
            "safe_kill_state",
            "started_at",
            "status",
            "stuck_kind",
            "tags",
            "tmux_name",
            "turn_seq",
            "usage_cost_micros",
            "usage_model",
            "work",
            "work_rev",
            "work_suggested",
            "worktree_id",
        )

        /** Read by [SessionRow] but not a hub row column, so never projected. */
        val NOT_HUB_COLUMNS = setOf(
            // Not a column of the hub's session row (its worktree row has one).
            "branch",
        )
    }
}
