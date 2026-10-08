package dev.claudefleet.mobile.net

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The app calls only tools a paired client token may call.
 *
 * This is a source scan rather than a unit test because the thing it guards is
 * a property of the whole module, not of one function: the hub's `guard.rs`
 * refuses fleet administration to a client token, and the design's answer is
 * that the app never asks — "a refusal is a bug, not a flow". Three tasks have
 * now checked this by hand with grep and written the result in a report. A
 * report is not a gate.
 *
 * It matches **quoted** names, so a KDoc that names a tool in prose or backticks
 * to explain why the app does not call it is fine, and the only way to trip this
 * is to write the string a call would need.
 *
 * JVM-only on purpose: `commonTest` compiles for Kotlin/Native too, where there
 * is no `java.io.File` and no source tree to read. What it guards is the shared
 * source, so where it runs does not matter.
 */
class ToolsTheAppMayCallTest {

    /**
     * Fleet administration, client management, and the secret store. Every one
     * of these is either absent from `READONLY_TOOLS` and gated on the master
     * token, or is the operator's own from the terminal.
     */
    private val forbidden = listOf(
        "provision_hosts",
        "add_host",
        "remove_host",
        "hide_host",
        "apply_sync",
        "set_secret",
        "pair_client",
        "list_clients",
        "revoke_client",
        // Tracker administration — connecting Jira, storing its credential.
        // Master-only on the hub, and the phone must never even name it.
        "work_admin",
    )

    /**
     * What a paired client is actually for. `send_prompt` is the one that is not
     * in the hub's readonly allow-list, which is why a `readonly` credential
     * disables the prompt box rather than calling it.
     */
    private val permitted = setOf(
        "list_sessions",
        "list_hosts",
        "list_projects",
        "session_conversation",
        // One tool call's input and result, for an expanded tool row. In the
        // hub's `READONLY_TOOLS`, so every client token may; additive, so it
        // is only called when `tools/list` names it (`HubCapabilities.toolDetail`).
        "session_tool_detail",
        "send_prompt",
        "fleet_health",
        "capture_session",
        // The pane read made immediately before a dialog's numbered option is
        // pressed (`SessionViewModel.answer`). Readonly and `Access::Client`
        // in the hub's `guard.rs`.
        "session_activity",
        "wait_for_session",
        "restart_session",
        "safe_kill_session",
        "kill_session",
        "set_session_tags",
        "set_friendly_name",
        // Fleet-wide session control, like `send_prompt`: `Access::Client` in
        // the hub's `guard.rs`, so a `full` client may and a `readonly` one is
        // never offered the form.
        "new_session",
        // The work graph (M8). `work` is readonly — tickets, lookup, the
        // resume plan. `work_link` is `Access::Client` and not readonly, so a
        // readonly token is never shown it by `tools/list` and the app never
        // offers what it decides; see `HubCapabilities`.
        "work",
        "work_link",
        // The fleet's agent: `Access::Client` in `guard.rs`, not readonly
        // (it may start the agent's session), so only a `full` pairing is
        // offered it — see `AgentViewModel`.
        "ensure_operator",
        // The composer's shared chip row. `Access::Client` and not readonly
        // (one tool both reads and replaces the list), so a `full` client may
        // and a `readonly` one is never shown it — which is also the token
        // that draws no chip row at all.
        "quick_replies",
        // The fleet's settings (claude-fleet declarative pages P6):
        // `Access::Person` / `PersonDevice` in `guard.rs` — a person's own
        // paired device, bound to no org. The reads are readonly tools;
        // `set_setting` and the decision are writes the hub takes only from a
        // `full` device its operator trusts, which `setting_proposals`
        // answers as `can_write` and `FleetSettingsViewModel` checks.
        "list_pages",
        "get_settings",
        "set_setting",
        "setting_proposals",
        "decide_setting_proposals",
        // File downloads (claude-fleet contract revision 7): `Access::Client`.
        // `list_downloads` is readonly; `send_file` and `remove_download` are
        // writes the hub does not list for a readonly token, and the Files tab
        // checks `canWrite` too (`HubCapabilities.sendFile` / `removeDownload`).
        // The bytes come over `GET /downloads/<id>`, not a tool.
        "list_downloads",
        "send_file",
        "remove_download",
        // Reply actions (Rewind here, Retry, Fork here): `Access::Client`,
        // not readonly — the hub does not list it for a readonly token, and
        // the screen offers it only with `HubCapabilities.rewind` and a
        // token that may write. Its confirmation gate binds the operator
        // only (`rewind_conversation` is not `confirm: true`).
        "rewind_conversation",
        // Chat forms (contract revision 9): read, answer and decline the form
        // a session's agent waits on. `Access::Client`; the tool itself
        // refuses a host token, and answering needs drive on the session.
        // Not readonly, so a readonly token is never shown it.
        "ask",
        // A session's Details sheet: the timeline, the sessions sharing its
        // worktree and the fleet tasks — all readonly, `Access::Client`.
        // `cancel_task` is the one write (`confirm: true` on the hub, which
        // may answer `E_CONFIRM_REQUIRED`); offered only to a token that may
        // write and a hub that lists it.
        "session_history",
        "related_sessions",
        "list_tasks",
        "cancel_task",
        // A session's earlier conversations (after a /clear, a resume, a
        // compaction or a rewind): readonly, `Access::Client`; each is then
        // read through `session_conversation { claude_session_id }`.
        "session_conversations",
        // A session's worktree, read only (the desktop's Files panel without
        // its writes): readonly, `Access::Client`. Stage, commit, checkout and
        // push are not here — the hub refuses them to a client, LocalOnly.
        "repo_changes",
        "repo_diff",
        "repo_log",
        "repo_commit",
        "repo_commit_diff",
        "repo_tree",
        "repo_file",
        // The Usage screen: estimated usage and the fleet's Claude accounts,
        // both readonly and `Access::Client`. A subscription's 5-hour and
        // weekly windows come from `account_usage` (readonly, contract 11),
        // called only when `tools/list` names it.
        "usage_report",
        "list_accounts",
        "account_usage",
        // A host's sheet and a ghost: `probe_host` and `discover_lost_sessions`
        // are readonly; `restore_host_sessions`, `recreate_session` and
        // `dismiss_ghost_session` are writes, offered only to a token that
        // may write. All `Access::Client`. A resumed conversation starts
        // through `new_session { resume_claude_session_id }`.
        "probe_host",
        "restore_host_sessions",
        "discover_lost_sessions",
        "recreate_session",
        "dismiss_ghost_session",
        // A review session, a workspace repair, a background agent: writes,
        // `Access::Client`, offered only to a token that may write.
        // `repair_session` is `confirm: true` on the hub and may answer
        // `E_CONFIRM_REQUIRED` (approve it on the desktop).
        "spawn_review",
        "repair_session",
        "new_bg_session",
        // A fleet setting's History: `Access::PersonDevice` and readonly —
        // a person's own device, which is what reads Fleet settings at all.
        "setting_history",
        // Adding a project and a host's worktrees: `add_project` and
        // `delete_worktree` are writes (a token that may write; the first
        // refuses a GitHub creation once, with a token to send back), the
        // lists readonly. All `Access::Client`.
        "add_project",
        "list_github_repos",
        "list_host_worktrees",
        "delete_worktree",
        // Move to host: a write, `Access::Client`, `confirm: true` on the hub
        // (a desktop may have to approve it: `E_CONFIRM_REQUIRED`). Offered
        // only to a token that may write.
        "move_session",
        // A session's Terminals tab (redesign 14.14): a plain shell in the
        // session's worktree. A write, `Access::Client`, not `confirm: true`
        // (the operator is the one caller it asks about); read with
        // `capture_session` and typed into with `send_prompt`.
        "new_shell_session",
        // Work's Pull requests sheet (claude-fleet redesign 6.4 / 6.7):
        // `prs { list }`, readonly and `Access::Client`; a row is served only
        // to a token that may see the session that opened it.
        "prs",
    )

    @Test
    fun no_source_set_names_a_tool_a_client_token_may_not_call() {
        val offences = sharedSources().flatMap { file ->
            val text = file.readText()
            forbidden.filter { "\"$it\"" in text }.map { "${file.name}: \"$it\"" }
        }
        assertEquals(emptyList(), offences, "the app must not name a tool the hub would refuse it")
    }

    /**
     * Decision D15 (work graph M13.4d), as the owner narrowed it on
     * 2026-09-28: a start, a multi-start or a resume refused across orgs is
     * said on the phone and never overridden from it; only a session's
     * *Tasks* link may be shared across orgs, after the person chose to.
     * So `force_cross_org` is written exactly once — the constant in
     * `HubClient.kt` — and sent only by `linkWork`.
     */
    @Test
    fun force_cross_org_is_named_once_and_sent_only_by_a_link() {
        val offences = sharedSources().filter { "\"force_cross_org\"" in it.readText() }.map { it.name }
        assertEquals(listOf("HubClient.kt"), offences, "only the constant in HubClient.kt names it")
        val client = sharedSources().single { it.name == "HubClient.kt" }.readText()
        assertEquals(1, Regex("\"force_cross_org\"").findAll(client).count())
        // Each use, by the function it sits in: the nearest `fun` above it.
        val users = Regex("""\bFORCE_CROSS_ORG\b""").findAll(client)
            .filterNot { client.substring(0, it.range.first).endsWith("const val ") }
            .map { use -> Regex("""fun (\w+)\(""").findAll(client.substring(0, use.range.first)).last().groupValues[1] }
            .toList()
        assertEquals(listOf("linkWork"), users, "no start, multi-start or resume ever forces")
    }

    /**
     * The other half, and the stronger one: an allow-list. A new tool call has
     * to be added here deliberately, rather than merely not being on a list of
     * things someone thought to forbid.
     */
    @Test
    fun every_tool_the_client_calls_is_one_a_client_token_may_call() {
        val client = sharedSources().singleOrNull { it.name == "HubClient.kt" }
            ?: fail("HubClient.kt not found under ${sourceRoot()}")
        val called = CALL_SITE.findAll(client.readText()).map { it.groupValues[1] }.toSet()

        assertTrue(called.isNotEmpty(), "found no tool calls at all — the pattern has gone stale")
        assertEquals(emptySet(), called - permitted, "an unexpected tool is being called")
    }

    private fun sharedSources(): List<File> = sourceRoot().walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        // The test sources name forbidden tools on purpose: this file does.
        .filter { "commonTest" !in it.path && "jvmTest" !in it.path }
        .toList()

    /**
     * Gradle runs a test with the project directory as its working directory,
     * but that is a default rather than a promise, so the walk starts where it
     * is and climbs until it finds the module.
     */
    private fun sourceRoot(): File {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            val candidate = File(dir, "src/commonMain")
            if (candidate.isDirectory) return File(dir, "src")
            val nested = File(dir, "shared/src/commonMain")
            if (nested.isDirectory) return File(dir, "shared/src")
            dir = dir.parentFile
        }
        fail("could not find the shared module's sources from ${File(".").absolutePath}")
    }

    private companion object {
        /** `call("list_sessions"` — the one way this client names a tool. */
        val CALL_SITE = Regex("""call\(\s*"([a-z_]+)"""")
    }
}
