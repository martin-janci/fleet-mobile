package dev.claudefleet.mobile.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * What one `tools/list` answered: tool names, the `action` enum of each tool
 * whose schema has one, and the argument names each tool's schema lists.
 */
data class ToolCatalog(
    val names: Set<String> = emptySet(),
    val actions: Map<String, Set<String>> = emptyMap(),
    val params: Map<String, Set<String>> = emptyMap(),
    /** tool → argument → the values its schema enumerates, for every argument that has an `enum`. */
    val paramValues: Map<String, Map<String, Set<String>>> = emptyMap(),
)

/**
 * What this hub lets *this token* do, beyond the tools every hub has — the
 * gate the work graph's screens hang on (review C19: gate on the hub's tools,
 * not on the contract revision).
 *
 * Built from [ToolCatalog] on every `ready`, so it is per connection:
 *  - [work] — the hub has the work graph at all: chips, grouping, tickets.
 *  - [workLink] — this token may change links and start or resume work. The
 *    hub hides the tool from a readonly token, so that case needs no rule of
 *    its own here; the UI still also checks `canWrite`.
 *  - [has] — one action of a tool. A schema `enum` answers it outright; a
 *    free-string `action` (a hub before M8.0) is taken as present until the
 *    hub refuses it with "unknown … action", which [forgetting] records for
 *    the rest of the connection.
 *  - [accepts] — one argument of a tool, for a feature that is a new
 *    argument of an old action (multi-start's `project_ids`). Absent from the
 *    schema means absent: an older hub would ignore the argument, not refuse
 *    it, so there is no refusal to learn from.
 *
 * The default is the old hub: nothing discovered, every work feature hidden.
 */
data class HubCapabilities(
    val tools: Set<String> = emptySet(),
    val actions: Map<String, Set<String>> = emptyMap(),
    val missing: Map<String, Set<String>> = emptyMap(),
    val params: Map<String, Set<String>> = emptyMap(),
    val paramValues: Map<String, Map<String, Set<String>>> = emptyMap(),
) {
    val work: Boolean get() = WORK in tools

    /**
     * Whether the hub has said what it serves yet. Until it has, every flag
     * reads false, so a screen must not say "this hub does not …" (r13 P20).
     */
    val known: Boolean get() = tools.isNotEmpty()
    val workLink: Boolean get() = WORK_LINK in tools

    /** The hub's agent (`ensure_operator`) — the desktop's ✦, on the phone. */
    val agent: Boolean get() = ENSURE_OPERATOR in tools

    /** Control's state without waking it (`operator_status`, readonly). */
    val operatorStatus: Boolean get() = OPERATOR_STATUS in tools

    /**
     * Control's confirms on this device (redesign 9.2): listed and answered.
     * `Access::PersonDevice` on the hub, so only a person's own device sees them.
     */
    val confirms: Boolean get() = MCP_CONFIRMS in tools && ANSWER_MCP_CONFIRM in tools

    /** What Control's agent handed on (`control_handoffs`, redesign 9.3), for its chips. */
    val handoffs: Boolean get() = CONTROL_HANDOFFS in tools

    /**
     * The hub keeps the composer's chip row (`quick_replies`). Absent on a hub
     * older than that tool — the app then draws its cached chips and never
     * tries to sync them — and absent for a `readonly` token, which the hub
     * does not show a tool that can write.
     */
    val quickReplies: Boolean get() = QUICK_REPLIES in tools

    /**
     * A tool call's row can be expanded to its input and result
     * (`session_tool_detail`, readonly on the hub). Absent on an older hub,
     * whose rows still show their verb and target and simply do not open.
     */
    val toolDetail: Boolean get() = SESSION_TOOL_DETAIL in tools

    /**
     * The fleet's settings pages (claude-fleet declarative pages P6): the hub
     * serves this token the page specs and the settings. It does to a
     * person's own device — a client bound to no org, of either mode — and
     * to nothing else, and an older hub has neither tool.
     */
    val fleetSettings: Boolean get() = LIST_PAGES in tools && GET_SETTINGS in tools

    /**
     * File downloads (claude-fleet contract revision 7): the hub keeps copies
     * of files a session sent and lists them (`list_downloads`, readonly).
     * What draws the Files tab.
     */
    val downloads: Boolean get() = LIST_DOWNLOADS in tools

    /**
     * Pull requests (claude-fleet redesign 6.4, contract revision 12): every
     * PR a session's branch has had, kept past the session (`prs { list }`,
     * readonly). What draws Work's Pull requests sheet; an older hub has no
     * such tool, and the chip is simply not drawn.
     */
    val pullRequests: Boolean get() = has(PRS, PRS_LIST)

    /**
     * This token may ask for a file (`send_file`) and forget one
     * (`remove_download`). Both are writes, which the hub does not list for a
     * readonly token; the UI checks `canWrite` as well.
     */
    val sendFile: Boolean get() = SEND_FILE in tools
    val removeDownload: Boolean get() = REMOVE_DOWNLOAD in tools

    /**
     * A session's worktree, read only: its changes and their diffs, the
     * commit log and each commit's files, and the files themselves. All
     * readonly tools; the screen asks for each only where the hub lists it.
     */
    val repo: Boolean get() = REPO_CHANGES in tools && REPO_DIFF in tools
    val repoLog: Boolean get() = REPO_LOG in tools && REPO_COMMIT in tools && REPO_COMMIT_DIFF in tools
    val repoFiles: Boolean get() = REPO_TREE in tools && REPO_FILE in tools
    /** Changes says how far the branch is from its remote and its base (r09 A3). */
    val repoBranch: Boolean get() = REPO_BRANCH_DIFF in tools

    /** A review session, a workspace repair, a background agent — all writes. */
    val spawnReview: Boolean get() = SPAWN_REVIEW in tools
    val repairSession: Boolean get() = REPAIR_SESSION in tools
    val newBgSession: Boolean get() = NEW_BG_SESSION in tools

    /** A host's re-probe and its recovery after a reboot; a ghost's recreate or dismissal. */
    val probeHost: Boolean get() = PROBE_HOST in tools
    val restoreSessions: Boolean get() = RESTORE_HOST_SESSIONS in tools
    val discoverLost: Boolean get() = DISCOVER_LOST_SESSIONS in tools
    val recreateSession: Boolean get() = RECREATE_SESSION in tools
    val dismissGhost: Boolean get() = DISMISS_GHOST_SESSION in tools

    /** Plain shells beside a session: its Terminals tab (redesign 14.14). */
    val shellSessions: Boolean get() = NEW_SHELL_SESSION in tools

    /**
     * The fleet-agent install job (claude-fleet 4.9, redesign 14.19): reading
     * jobs is a client's, starting one the hub lists only to a token that may
     * (`tools/list` is filtered per caller), so a pairing that cannot is never
     * offered the button.
     */
    val agentInstalls: Boolean get() = AGENT_INSTALLS in tools
    val installAgent: Boolean get() = INSTALL_AGENT in tools && AGENT_INSTALLS in tools

    /**
     * Adding a host from the phone (redesign 14.12's Radar): the hub's SSH
     * config read and `add_host`. Contract 13 lists `add_host` to the hub
     * owner's own phone only; the hub still refuses one it does not trust.
     */
    val addHost: Boolean get() = ADD_HOST in tools && DISCOVER_HOSTS in tools

    /** Archiving a session from its ⋮ menu (`work_link { action: archive }`). */
    val archiveSession: Boolean get() = has(WORK_LINK, "archive")

    /** Moving a session to another host. */
    val moveSession: Boolean get() = MOVE_SESSION in tools

    /** Adding a project and a host's worktrees. */
    val addProject: Boolean get() = ADD_PROJECT in tools
    val githubRepos: Boolean get() = LIST_GITHUB_REPOS in tools
    val hostWorktrees: Boolean get() = LIST_HOST_WORKTREES in tools
    val deleteWorktree: Boolean get() = DELETE_WORKTREE in tools

    /** One setting's writes, for a field's History (a person's own device). */
    val settingHistory: Boolean get() = SETTING_HISTORY in tools

    /** Estimated usage and the fleet's Claude accounts — both readonly. */
    val usage: Boolean get() = USAGE_REPORT in tools
    val accounts: Boolean get() = LIST_ACCOUNTS in tools

    /**
     * A paused-on-limit row's Switch account (claude-fleet 4.4 on the phone,
     * 4.10): the hub says which login has room (`check_account_headroom`,
     * contract 14, readonly) and restarts the session under it
     * (`restart_session { profile }`). Both, or the button is not drawn.
     */
    val switchAccount: Boolean
        get() = CHECK_ACCOUNT_HEADROOM in tools && accepts(RESTART_SESSION, "profile")

    /** Send later (redesign 14.14): the hub keeps a prompt for the session's next idle moment (5.10). */
    val sendLater: Boolean get() = QUEUE_PROMPT in tools && QUEUED_PROMPTS in tools

    /** The phone stamps a session viewed while it is on screen (`touch_session_viewed`, contract 11). */
    val touchViewed: Boolean get() = TOUCH_SESSION_VIEWED in tools

    /** Accounts' usage readings (`account_usage`): when a paused row's limit resets (step 4.10). */
    val accountUsage: Boolean get() = ACCOUNT_USAGE in tools

    /** A session's earlier conversations (`session_conversations`, readonly). */
    val conversations: Boolean get() = SESSION_CONVERSATIONS in tools

    /** A session's Details sheet: its timeline, related sessions and tasks — all readonly. */
    val sessionHistory: Boolean get() = SESSION_HISTORY in tools
    val relatedSessions: Boolean get() = RELATED_SESSIONS in tools
    val tasks: Boolean get() = LIST_TASKS in tools

    /** Cancelling a task — a write, not listed for a readonly token. */
    val cancelTask: Boolean get() = CANCEL_TASK in tools

    /**
     * Chat forms (`ask`, contract revision 9): a session's waiting form can be
     * read, answered and declined here. Not a readonly tool, so the hub does
     * not list it for a readonly token, which then only sees that one waits.
     */
    val ask: Boolean get() = ASK in tools

    /**
     * A settings card's proposal can be read (`setting_proposals`), and
     * decided where the hub lists `decide_setting_proposals` and says this
     * device may write (`can_write`).
     */
    val settingProposals: Boolean get() = SETTING_PROPOSALS in tools
    val decideSettingProposals: Boolean get() = DECIDE_SETTING_PROPOSALS in tools

    /** Reply actions — Rewind here, Retry, Fork here (`rewind_conversation`, a write). */
    val rewind: Boolean get() = REWIND_CONVERSATION in tools

    /**
     * Routines (claude-fleet redesign 8.5): the list, a routine's runs, and
     * its on/off switch. A write tool, so a readonly token is not served it.
     * Its `action` is a free string, so the tool's presence is the gate.
     */
    val routines: Boolean get() = ROUTINES in tools

    /**
     * Every run on the fleet's behalf in one list (claude-fleet 8.3,
     * `runs { list }`, readonly): what the Automation sheet's Runs tab reads.
     * Without it the tab merges each routine's own runs, as before.
     */
    val runs: Boolean get() = has(RUNS, "list")

    /**
     * Debug devices (contract revision 10): the test phones on the fleet's
     * hosts, claimed, booted and read from here. Not readonly on the hub, so
     * a readonly token is not served it.
     */
    val debugDevices: Boolean get() = DEBUG_DEVICES in tools

    /**
     * Org administration (`org_admin`, company administration phase D): the
     * Company screen lists an org's members and changes their roles. Not
     * readonly on the hub, and the hub refuses anyone who does not
     * administer that org; the phone offers it only to an org's admins.
     */
    val orgAdmin: Boolean get() = ORG_ADMIN in tools

    /**
     * Who this device's person is and what is shared with them (`my_grants`,
     * contract revision 8): what tells a shared session from one's own, so
     * its screen can say what the share allows. Readonly on the hub.
     */
    val myGrants: Boolean get() = MY_GRANTS in tools

    /**
     * The owner's share sheet (redesign 11.10): who holds a grant, share,
     * narrow and revoke. All four are served together; none is readonly.
     */
    val share: Boolean get() = SHARE_TOOLS.all { it in tools }

    /** Fleet settings can be written as this device (`set_setting`); the hub still refuses an untrusted one. */
    val setSetting: Boolean get() = SET_SETTING in tools

    /**
     * The keys the full-screen agent's bar may press in a pane (redesign
     * 14.14): Escape, Tab, Enter and C-c on every hub with keys, plus the
     * arrows, ⇧Tab (`BTab`) and the Ctrl letters a hub lists in
     * `send_prompt`'s `keys` enum. A hub that enumerates nothing is the old
     * one, whose guard refuses the rest, so they are not offered.
     */
    val paneKeys: Set<String>
        get() = BASE_PANE_KEYS + (paramValues[SEND_PROMPT]?.get(KEYS).orEmpty() intersect EXTENDED_PANE_KEYS)

    /** Whether a hub tool argument enumerates [value] — for a new value of an old argument. */
    fun offers(tool: String, param: String, value: String): Boolean =
        tool in tools && paramValues[tool]?.get(param)?.contains(value) == true

    fun has(tool: String, action: String): Boolean =
        tool in tools &&
            actions[tool]?.contains(action) != false &&
            action !in missing[tool].orEmpty()

    /**
     * [has], and the hub's schema *names* [action] in [tool]'s enum — not
     * merely a free-string `action` that might take it. For a feature whose
     * whole screen hangs on an action only a newer hub has (the Work tab on
     * `work { tree }`): every hub that serves it enumerates its actions, so a
     * hub that does not is one that cannot, and no tab is drawn to be refused.
     */
    fun lists(tool: String, action: String): Boolean =
        has(tool, action) && actions[tool]?.contains(action) == true

    fun accepts(tool: String, param: String): Boolean = tool in tools && params[tool]?.contains(param) == true

    /** This connection learned [action] is not one [tool] has. */
    fun forgetting(tool: String, action: String): HubCapabilities =
        copy(missing = missing + (tool to (missing[tool].orEmpty() + action)))

    companion object {
        const val WORK = "work"
        const val WORK_LINK = "work_link"
        const val ENSURE_OPERATOR = "ensure_operator"
        const val OPERATOR_STATUS = "operator_status"
        const val MCP_CONFIRMS = "mcp_confirms"
        const val ANSWER_MCP_CONFIRM = "answer_mcp_confirm"
        const val CONTROL_HANDOFFS = "control_handoffs"
        const val QUICK_REPLIES = "quick_replies"
        const val SESSION_TOOL_DETAIL = "session_tool_detail"
        const val LIST_PAGES = "list_pages"
        const val GET_SETTINGS = "get_settings"
        const val LIST_DOWNLOADS = "list_downloads"
        const val PRS = "prs"
        const val PRS_LIST = "list"
        const val SEND_FILE = "send_file"
        const val REMOVE_DOWNLOAD = "remove_download"
        const val REWIND_CONVERSATION = "rewind_conversation"
        const val SESSION_HISTORY = "session_history"
        const val SESSION_CONVERSATIONS = "session_conversations"
        const val REPO_CHANGES = "repo_changes"
        const val USAGE_REPORT = "usage_report"
        const val SETTING_HISTORY = "setting_history"
        const val ADD_PROJECT = "add_project"
        const val MOVE_SESSION = "move_session"
        const val NEW_SHELL_SESSION = "new_shell_session"
        const val INSTALL_AGENT = "install_agent"
        const val ADD_HOST = "add_host"
        const val DISCOVER_HOSTS = "discover_hosts"
        const val AGENT_INSTALLS = "agent_installs"
        const val LIST_GITHUB_REPOS = "list_github_repos"
        const val LIST_HOST_WORKTREES = "list_host_worktrees"
        const val DELETE_WORKTREE = "delete_worktree"
        const val PROBE_HOST = "probe_host"
        const val SPAWN_REVIEW = "spawn_review"
        const val REPAIR_SESSION = "repair_session"
        const val NEW_BG_SESSION = "new_bg_session"
        const val RESTORE_HOST_SESSIONS = "restore_host_sessions"
        const val DISCOVER_LOST_SESSIONS = "discover_lost_sessions"
        const val RECREATE_SESSION = "recreate_session"
        const val DISMISS_GHOST_SESSION = "dismiss_ghost_session"
        const val LIST_ACCOUNTS = "list_accounts"
        const val WORK_ADMIN = "work_admin"
        const val ACCOUNT_USAGE = "account_usage"
        const val REPO_DIFF = "repo_diff"
        const val REPO_LOG = "repo_log"
        const val REPO_COMMIT = "repo_commit"
        const val REPO_COMMIT_DIFF = "repo_commit_diff"
        const val REPO_TREE = "repo_tree"
        const val REPO_BRANCH_DIFF = "repo_branch_diff"
        const val REPO_FILE = "repo_file"
        const val RELATED_SESSIONS = "related_sessions"
        const val LIST_TASKS = "list_tasks"
        const val CANCEL_TASK = "cancel_task"
        const val ASK = "ask"
        const val SETTING_PROPOSALS = "setting_proposals"
        const val DECIDE_SETTING_PROPOSALS = "decide_setting_proposals"
        const val ROUTINES = "routines"
        const val RUNS = "runs"
        const val CHECK_ACCOUNT_HEADROOM = "check_account_headroom"
        const val RESTART_SESSION = "restart_session"
        const val QUEUE_PROMPT = "queue_prompt"
        const val QUEUED_PROMPTS = "queued_prompts"
        const val TOUCH_SESSION_VIEWED = "touch_session_viewed"
        const val SET_SETTING = "set_setting"
        const val DEBUG_DEVICES = "debug_devices"
        const val ORG_ADMIN = "org_admin"
        const val MY_GRANTS = "my_grants"
        val SHARE_TOOLS = listOf("session_access", "session_share", "session_narrow", "session_unshare")
        const val SEND_PROMPT = "send_prompt"
        const val KEYS = "keys"

        /** What every hub with keys presses in a pane: its own list less the digits. */
        val BASE_PANE_KEYS: Set<String> = setOf("Escape", "Tab", "Enter", "C-c")

        /**
         * The keys the bar adds where the hub lists them. A closed list here
         * too: a key name the hub grows later is not pressed until the bar
         * has a cap for it.
         */
        val CTRL_KEYS: List<String> = listOf(
            "C-a", "C-b", "C-d", "C-e", "C-f", "C-g", "C-h", "C-k", "C-l", "C-n",
            "C-o", "C-p", "C-r", "C-t", "C-u", "C-v", "C-w", "C-x", "C-y",
        )
        val ARROW_KEYS: List<String> = listOf("Left", "Up", "Down", "Right")
        val EXTENDED_PANE_KEYS: Set<String> = (ARROW_KEYS + "BTab" + CTRL_KEYS).toSet()

        fun of(catalog: ToolCatalog) =
            HubCapabilities(catalog.names, catalog.actions, params = catalog.params, paramValues = catalog.paramValues)
    }
}

/**
 * The hub's refusal of an `action` it does not know: `E_INVALID` with
 * "unknown work_link action \"confirm\"; one of …". What a hub whose schema
 * does not enumerate its actions says instead of hiding them.
 */
fun HubError.Tool.isUnknownAction(): Boolean =
    code == "E_INVALID" && message.contains("unknown", ignoreCase = true) &&
        message.contains("action", ignoreCase = true)

/**
 * The session an `E_EXISTS` refusal names — "that work is live there; jump to
 * it" — or null. Also null when the refusal names an [orphanSessionId]: the
 * call made a session of its own before it lost, and a jump past it would
 * leave that one running unlinked with nobody told.
 */
fun HubError.Tool.existingSessionId(): Long? =
    if (code != "E_EXISTS" || orphanSessionId() != null) null
    else ((details as? JsonObject)?.get("session_id") as? JsonPrimitive)?.longOrNull

/**
 * The session a `start` or `resume` made and could not link — it lost a race
 * to another client, or the link failed — which the hub names so nobody has
 * to find it. Its message names it too, which is what the screen shows.
 */
fun HubError.Tool.orphanSessionId(): Long? =
    ((details as? JsonObject)?.get("orphan_session_id") as? JsonPrimitive)?.longOrNull
