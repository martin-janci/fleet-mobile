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
        "send_prompt",
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
