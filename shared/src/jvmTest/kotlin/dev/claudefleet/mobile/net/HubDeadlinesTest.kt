package dev.claudefleet.mobile.net

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every tool this app calls that the hub bounds at `Deadline::Lifecycle`
 * (300 s) or `Deadline::LongPoll` (660 s) gets the matching client deadline.
 * On the ordinary 45 s one the phone gave up while the hub kept working, and
 * a retry sent the work a second time (review wave 24, w24-2).
 *
 * A source scan, like [ToolsTheAppMayCallTest]: the sets live in
 * `HubClient`'s private companion. The hub's lists below are a copy of
 * `TOOL_POLICIES` in claude-fleet's `crates/fleet-core/src/mcp/guard.rs`
 * (every `deadline: Deadline::Lifecycle` / `Deadline::LongPoll` row); update
 * them from there when the hub changes a deadline.
 */
class HubDeadlinesTest {

    private val hubLifecycle = setOf(
        "usage_report", "propose_host_placement", "update_admin", "add_host", "probe_host",
        "check_host", "provision_hosts", "routines", "autofix", "add_account", "new_session",
        "new_shell_session", "recreate_session", "new_bg_session", "ensure_operator",
        "kill_session", "safe_kill_session", "restart_session", "rewind_conversation",
        "spawn_review", "repair_session", "move_session", "resolve_move", "broadcast_prompt",
        "session_transcript", "session_conversation", "session_summary_since",
        "session_context_help", "session_prompt_tune", "session_tool_detail", "dispatch_task",
        "work_link", "work_admin", "refresh_projects", "delete_worktree", "scan_assets",
        "import_assets", "plan_sync", "apply_sync", "catalog_admin", "changesets",
        "discover_lost_sessions", "restore_host_sessions",
    )

    private val hubLongPoll = setOf(
        "debug_devices", "wait_for_session", "wait_for_reply", "ask", "run_prompt",
        "wait_for_task", "add_project",
    )

    /** Called on the ordinary deadline on purpose, each with its reason. */
    private val exempt = setOf(
        // Capped at 20 s on the hub (`host_check.rs`), well inside 45 s.
        "check_host",
        // The phone asks for `timeout_s` 30, inside 45 s; it rides the
        // framed mount for its keep-alive (`FRAMED_TOOLS`).
        "wait_for_session",
    )

    @Test
    fun every_slow_hub_tool_the_app_calls_gets_the_hub_deadline() {
        val text = hubClient().readText()
        val called = Regex("""call\(\s*"([a-z_]+)"""").findAll(text).map { it.groupValues[1] }.toSet()
        assertTrue(called.isNotEmpty(), "found no tool calls at all — the pattern has gone stale")
        val lifecycle = namesIn(text, "LIFECYCLE_TOOLS")
        val longPoll = namesIn(text, "LONG_POLL_TOOLS")

        assertEquals(
            emptySet(),
            (called intersect hubLifecycle) - lifecycle - longPoll - exempt,
            "a tool the hub bounds at Deadline::Lifecycle is on the 45 s deadline: add it to LIFECYCLE_TOOLS",
        )
        assertEquals(
            emptySet(),
            (called intersect hubLongPoll) - longPoll - exempt,
            "a tool the hub bounds at Deadline::LongPoll is not on the long-poll deadline: add it to LONG_POLL_TOOLS",
        )
    }

    /** The quoted names inside `val <name> = setOf( … )`. */
    private fun namesIn(text: String, name: String): Set<String> {
        val body = Regex("""val $name = setOf\((.*?)\n\s*\)""", RegexOption.DOT_MATCHES_ALL).find(text)
            ?.groupValues?.get(1) ?: fail("$name not found in HubClient.kt")
        return Regex("""^\s*"([a-z_]+)",""", RegexOption.MULTILINE).findAll(body).map { it.groupValues[1] }.toSet()
            .also { assertTrue(it.isNotEmpty(), "$name parsed empty — the pattern has gone stale") }
    }

    private fun hubClient(): File {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            for (root in listOf("src/commonMain", "shared/src/commonMain")) {
                val f = File(dir, "$root/kotlin/dev/claudefleet/mobile/net/HubClient.kt")
                if (f.isFile) return f
            }
            dir = dir.parentFile
        }
        fail("could not find HubClient.kt from ${File(".").absolutePath}")
    }
}
