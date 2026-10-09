package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One session as the hub reports it from `list_sessions` with `summary=false`.
 *
 * The hub's own row (`crates/fleet-core/src/store/rows.rs`) carries about forty
 * columns; this mirrors the ones a phone draws, and `ignoreUnknownKeys` lets the
 * rest — and anything the hub grows later — pass by. Every field but `id` has a
 * default because `list_sessions` serializes through `ok_json_compact`, which
 * strips nulls from the wire entirely rather than sending `"field": null`.
 *
 * `is_controller` is not a column: the tool flattens it onto each row.
 */
@Serializable
data class SessionRow(
    val id: Long,
    @SerialName("tmux_name") val tmuxName: String = "",
    @SerialName("friendly_name") val friendlyName: String? = null,
    @SerialName("host_alias") val hostAlias: String = "",
    @SerialName("project_id") val projectId: Long? = null,
    @SerialName("worktree_id") val worktreeId: Long? = null,
    /** The tmux-level state: `running`, `stopped`, … */
    val status: String = "",
    /** `work` | `shell` | `review` | … */
    val kind: String? = null,
    /** working | blocked | completed | failed | stopped | idle; null when unknown. */
    @SerialName("claude_status") val claudeStatus: String? = null,
    /** auth_menu | reconnect | trust_prompt | oom | press_enter; null when not stuck. */
    @SerialName("stuck_kind") val stuckKind: String? = null,
    /** The hub's one-line summary of what the session is doing. */
    @SerialName("current_activity") val currentActivity: String? = null,
    /** The hub's structured reading of a blocked prompt; null when there is none. */
    @SerialName("pending_input") val pendingInput: PendingInput? = null,
    /**
     * The chat form the session's agent waits on (`ask`, contract revision
     * 9); null when none, and from an older hub. See `model/ChatForms.kt`.
     */
    @SerialName("pending_form") val pendingForm: PendingForm? = null,
    @SerialName("context_pct") val contextPct: Double? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("last_activity_at") val lastActivityAt: Long? = null,
    /** Set on a ghost row — a session fleet knows about but tmux no longer has. */
    @SerialName("lost_at") val lostAt: Long? = null,
    @SerialName("turn_seq") val turnSeq: Long = 0,
    @SerialName("pr_url") val prUrl: String? = null,
    /** passing | failing | pending, from the PR's check rollup. */
    @SerialName("ci_status") val ciStatus: String? = null,
    @SerialName("is_controller") val isController: Boolean = false,
    /**
     * Whose session this is, as a `people` row id (multi-user M1, contract
     * revision 8); null for an unclaimed row and from an older hub. What
     * [dev.claudefleet.mobile.model.MyAccess] tells "mine" from "shared with me" by.
     */
    @SerialName("owner_person_id") val ownerPersonId: Long? = null,
    val tags: List<String> = emptyList(),
    @SerialName("last_prompt") val lastPrompt: String? = null,
    @SerialName("last_stop_at") val lastStopAt: Long? = null,
    @SerialName("last_turn_at") val lastTurnAt: Long? = null,
    @SerialName("started_at") val startedAt: Long? = null,
    @SerialName("usage_cost_micros") val usageCostMicros: Long? = null,
    @SerialName("usage_model") val usageModel: String? = null,
    @SerialName("parent_session_id") val parentSessionId: Long? = null,
    val branch: String? = null,
    /** The hub's own progress through a `safe_kill_session` retirement, or null when none is armed. */
    @SerialName("safe_kill_state") val safeKillState: String? = null,
    /**
     * The hub's own answer to "does this need a person?", stamped on every
     * listed row and every `session:*` frame; absent when nobody is needed, and
     * from any hub released before it stamped one. See [attentionReason].
     */
    @SerialName("needs_attention") val attention: Attention? = null,
    /**
     * The session's primary work link, stamped by the hub; null when it has
     * none, and from a hub older than the work graph. Set only through
     * `work_link` — the phone never derives a key from a branch or a tag.
     */
    val work: WorkSummary? = null,
    /**
     * The hub's top guess nobody has decided yet (work graph M4). Kept apart
     * from [work] on purpose: a guess never moves a session into a work group,
     * it only offers Confirm / Not this.
     */
    @SerialName("work_suggested") val workSuggested: WorkSummary? = null,
    /**
     * The session's organisation (M5), when one claims it — an id; the names
     * come from `work orgs`. In the phone view from claude-fleet's M8.6; an
     * older hub leaves it out, and the row's work's own `org_id` stands in.
     */
    @SerialName("org_id") val orgId: Long? = null,
    /**
     * The Claude account the session runs under (redesign step 4.10): the
     * row's account chip and a paused row's "on <account>". Names come from
     * `list_accounts`. In the phone view from claude-fleet's 4.10; an older
     * hub leaves it out and the row shows no account.
     */
    @SerialName("account_uuid") val accountUuid: String? = null,
    /**
     * A digest of the versions and ids of the session's live work links
     * (claude-fleet M14), omitted when 0. It moves whenever any of them
     * does — a *secondary* link added, ended or made primary included —
     * which [work] (the primary only) cannot show. What tells a Work screen
     * that this row's work changed, apart from its status churn.
     */
    @SerialName("work_rev") val workRev: Long = 0,
    /**
     * Which coding agent runs in the session's pane (hub contract 11,
     * migration 121): `claude`, `codex`, `agy` or `shell`. A hub older than
     * the column sends none, and every session it runs is Claude Code.
     */
    val agent: String? = null,
    /**
     * Who or what started the session (hub contract 11, migration 124):
     * `person`, `operator`, `mission`, `background`, `token` or `routine`;
     * absent for a row fleet did not start (found on a host by reconcile) and
     * from an older hub. [originRef] is what it points at, as text (a person,
     * session or mission id). The desktop draws no origin chip yet, so
     * neither does the phone; the field is read for parity and the tests.
     */
    val origin: String? = null,
    @SerialName("origin_ref") val originRef: String? = null,
    /**
     * When a person last looked at the session (contract 11, migration 125),
     * unix seconds: `touch_session_viewed`. A turn that ended after it is
     * unread ([isUnread]). Absent for a row nobody has opened since fleet
     * found it, and from an older hub.
     */
    @SerialName("last_viewed_at") val lastViewedAt: Long? = null,
    /** The credential profile the session runs under; absent for the host's own login (accounts, step 4.4). */
    @SerialName("claude_profile") val claudeProfile: String? = null,
) {
    /**
     * A turn ended after the session was last viewed — or, for a session fleet
     * started and nobody has opened yet, after it started. The desktop's
     * `isUnread` (`attention.ts`, redesign 2.3): a row reconcile found has
     * neither stamp and is never unread; a turn that ends in the second it is
     * viewed counts as seen.
     */
    val isUnread: Boolean
        get() {
            val seen = lastViewedAt ?: startedAt ?: return false
            val stop = lastStopAt ?: return false
            return stop > seen
        }

    val isBackground: Boolean get() = tmuxName.startsWith("bg:")

    /** The agent's own label; for a background agent its prompt; else the tmux name. */
    val displayName: String
        get() {
            friendlyName?.takeIf { it.isNotBlank() }?.let { return it }
            if (!isBackground) return tmuxName
            lastPrompt?.takeIf { it.isNotBlank() }?.let { return it.take(60) }
            return "Background · ${tmuxName.removePrefix("bg:").take(4)}"
        }

    /**
     * Why this session needs a person — `waiting`, `stuck`, `failed` or
     * `lifecycle` — or null when it does not.
     *
     * The hub decides this (`service::attention` in claude-fleet), so its
     * stamped [attention] is the answer whenever it is there. The fallback is
     * that same rule, ported check for check in its order — which is the
     * precedence — for a hub too old to stamp it; a newer hub that stamps
     * nothing has found nothing, and the port agrees.
     */
    val attentionReason: String?
        get() = attention?.reason ?: when {
            // Running outside fleet: read-only here, so never a person's job.
            kind == "external" -> null
            claudeStatus == "blocked" || pendingForm != null -> "waiting"
            stuckKind != null -> "stuck"
            claudeStatus == "failed" -> "failed"
            safeKillState == "failed" || safeKillState == "requested" ||
                status == "ghost" || lostAt != null -> "lifecycle"
            else -> null
        }

    /** The rows the "needs attention" filter keeps. */
    val needsAttention: Boolean get() = attentionReason != null

    /**
     * The row's second line: the sanitised activity when there is one, else
     * what kind of session it is — and nothing at all for the ordinary
     * `work` kind.
     *
     * The age used to lead this line, and the row's trailing column shows the
     * same age from the same field, so an idle session read `4 min · shell`
     * on the left and `4 min` on the right. One fact, drawn once: the column
     * keeps the age, this line keeps what the column cannot say — which is
     * why it no longer takes a clock at all.
     */
    val supportingLine: String?
        get() = Activity.sanitize(currentActivity)
            ?: kind?.takeIf { it != "work" && it.isNotBlank() }
}

/**
 * Why a session needs a person, and since when — the hub's `needs_attention`.
 * [reason] is kept as the hub's word rather than an enum, so a reason a later
 * hub adds still counts as "needs a person" instead of failing the row.
 */
@Serializable
data class Attention(
    val reason: String,
    /** Unix second the session entered this state, best effort. */
    val since: Long? = null,
)
