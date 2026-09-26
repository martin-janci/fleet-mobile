package dev.claudefleet.mobile.host

import dev.claudefleet.mobile.model.ATTACH_MAX_BYTES
import dev.claudefleet.mobile.model.ATTACH_MAX_TOTAL
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The phone's ceilings against the host's. They are the same two numbers in
 * two languages, and the host's are the ones that hold — so if they drift,
 * a phone refuses a file the hub would have taken, or (worse) queues one it
 * will refuse after the upload.
 *
 * The unconditional constants assertions run every time. The cross-repo check
 * (parsing the Rust source) is opportunistic: it only runs when `claude-fleet`
 * is found in one of the plausible parent directories (it may not be checked
 * out when this repo is used as a library or in CI with no neighbour copy).
 */
class AttachmentDriftTest {
    @Test
    fun the_ceilings_match_the_hosts() {
        // Always assert the local Kotlin constants, unconditionally
        assertEquals(ATTACH_MAX_BYTES, 10L * 1024 * 1024)
        assertEquals(ATTACH_MAX_TOTAL, 25L * 1024 * 1024)

        // Then try to find the Rust source and compare if it exists.
        // `Repo.root` climbs to settings.gradle.kts; this repo is a worktree,
        // so `claude-fleet` is at either Repo.root.parentFile or
        // Repo.root.parentFile.parentFile. Try both.
        val neighbour1 = File(Repo.root.parentFile, "claude-fleet/crates/fleet-core/src/service")
        val neighbour2 = File(Repo.root.parentFile.parentFile, "claude-fleet/crates/fleet-core/src/service")

        val rust = File(neighbour1, "attachments/mod.rs").takeIf { it.isFile }
            ?: File(neighbour1, "attachments.rs").takeIf { it.isFile }
            ?: File(neighbour2, "attachments/mod.rs").takeIf { it.isFile }
            ?: File(neighbour2, "attachments.rs").takeIf { it.isFile }
            ?: return // Skip the cross-repo check if claude-fleet is not found

        val text = rust.readText()
        assertEquals(
            10L * 1024 * 1024,
            Regex("MAX_BYTES: u64 = ([^;]+);").find(text)!!.groupValues[1].evalMib(),
        )
        assertEquals(
            25L * 1024 * 1024,
            Regex("MAX_TOTAL: u64 = ([^;]+);").find(text)!!.groupValues[1].evalMib(),
        )
    }

    /** `10 * 1024 * 1024` → 10485760. Only the one shape these constants use. */
    private fun String.evalMib(): Long =
        trim().split("*").map { it.trim().toLong() }.reduce(Long::times)
}
