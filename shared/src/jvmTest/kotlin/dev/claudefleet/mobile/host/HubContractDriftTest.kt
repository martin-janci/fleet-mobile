package dev.claudefleet.mobile.host

import dev.claudefleet.mobile.net.MAX_HUB_CONTRACT
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The app's hub-contract ceiling against the desktop's own.
 *
 * `MAX_HUB_CONTRACT` is copied out of claude-fleet's
 * `src-tauri/src/backend/contract.rs`, and a copy with nothing watching it is
 * the whole problem: when the desktop raises its maximum, this app keeps
 * refusing a hub it should accept — silently, because both sides still
 * compile and every test here still passes.
 *
 * `MIN_HUB_CONTRACT` is deliberately NOT compared here. The desktop raised
 * its own minimum to 3 because a pre-3 hub would silently misperform
 * `move_session { when: cancel }` (revision 2 added `MoveOutcome`/`dry_run`;
 * revision 3 gave `move_session` a `when` argument) — but this app never
 * calls `move_session` (see `ToolsTheAppMayCallTest`'s `permitted` set), so
 * that hazard does not apply to it. This app's minimum is its own floor, set
 * by which hub release is still in production (0.2.34, revision 1), not a
 * mirror of the desktop's minimum — so drift in the desktop's `MIN` is not a
 * bug here and must not fail this test.
 *
 * The check is a source scan of the sibling checkout, which is where that
 * repository sits on a machine that has both. It is a **soft** gate by
 * necessity: CI checks out this repository alone, and a test that fails
 * whenever claude-fleet is absent would fail on every run that matters and be
 * deleted within a week. So it skips with a printed note instead — which is
 * honest about what it can see, and still fires on the machine where the
 * constant is actually being changed.
 *
 * It does NOT stand alone. `HubContractVerdictTest` pins the literals `0` and
 * `3` inside this repository, so a change to the app's own constants is caught
 * by a test that always runs; this one is what catches a change to the *other*
 * side's maximum.
 */
class HubContractDriftTest {

    @Test
    fun the_max_matches_the_desktops_contract_rs_when_it_is_next_door() {
        val contract = desktopContract()
        if (contract == null) {
            println(
                "HubContractDriftTest: skipped — no $DESKTOP checkout beside ${Repo.root.name}, " +
                    "so the desktop's $MAX could not be read. " +
                    "HubContractVerdictTest still pins this app's own literal.",
            )
            return
        }
        val text = contract.readText()

        assertEquals(
            constant(text, MAX),
            MAX_HUB_CONTRACT,
            "$MAX drifted: ${contract.path} and this app no longer agree on the newest hub to trust",
        )
    }

    /** The desktop's `contract.rs`, or null when there is no checkout beside this one. */
    private fun desktopContract(): File? =
        Repo.root.resolveSibling(DESKTOP)
            .resolve("src-tauri/src/backend/contract.rs")
            .takeIf { it.isFile }

    /** `pub const NAME: u32 = N;` — the literal, not whatever Rust computes from it. */
    private fun constant(text: String, name: String): Int {
        val match = Regex("""pub const $name:\s*u32\s*=\s*(\d+)\s*;""").find(text)
            ?: fail("$name is no longer a `pub const … u32` literal in the desktop's contract.rs")
        return match.groupValues[1].toInt()
    }

    private companion object {
        const val DESKTOP = "claude-fleet"
        const val MAX = "MAX_HUB_CONTRACT"
    }
}
