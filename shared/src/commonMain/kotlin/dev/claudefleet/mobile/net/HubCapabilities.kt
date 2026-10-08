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
) {
    val work: Boolean get() = WORK in tools
    val workLink: Boolean get() = WORK_LINK in tools

    /** The hub's agent (`ensure_operator`) — the desktop's ✦, on the phone. */
    val agent: Boolean get() = ENSURE_OPERATOR in tools

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
        const val REPO_DIFF = "repo_diff"
        const val REPO_LOG = "repo_log"
        const val REPO_COMMIT = "repo_commit"
        const val REPO_COMMIT_DIFF = "repo_commit_diff"
        const val REPO_TREE = "repo_tree"
        const val REPO_FILE = "repo_file"
        const val RELATED_SESSIONS = "related_sessions"
        const val LIST_TASKS = "list_tasks"
        const val CANCEL_TASK = "cancel_task"
        const val ASK = "ask"
        const val SETTING_PROPOSALS = "setting_proposals"
        const val DECIDE_SETTING_PROPOSALS = "decide_setting_proposals"

        fun of(catalog: ToolCatalog) = HubCapabilities(catalog.names, catalog.actions, params = catalog.params)
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
