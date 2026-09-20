package dev.claudefleet.mobile.store

import dev.claudefleet.mobile.ui.SettingsUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whether this device may send a prompt — decided the way the hub decides it.
 *
 * The hub's rule is one match arm, in `crates/fleet-core/src/mcp/auth.rs`:
 *
 * ```rust
 * match s { "full" => TokenMode::Full, _ => TokenMode::Readonly }
 * ```
 *
 * Everything that is not the literal `full` is refused write, and
 * `store/clients.rs` states the intent plainly: "a typo would silently
 * downgrade a client rather than fail." The app's rule was
 * `mode != "readonly"`, which is the **opposite** on every input that is
 * neither literal — so the two agreed on exactly two strings and disagreed on
 * all the rest, in the direction that hands out access.
 *
 * It is not a hole in the fleet: the hub enforces, and refuses. What it breaks
 * is the promise the app is built on and repeats in three files — that it only
 * ever calls tools its token may use, rather than finding out from an error.
 * The prompt box would be live, the send would go out, and `E_FORBIDDEN` would
 * come back on a button the app had just promised would work.
 *
 * These tests are written against the hub's arm rather than against the app's
 * old behaviour, because the property worth keeping is *agreement with the
 * hub*, not any particular string comparison.
 */
class ClientModeTest {

    /** The only string that grants write, on either side. */
    @Test
    fun only_the_literal_full_grants_write() {
        assertTrue(Credentials.grantsWrite("full"))
        assertTrue(credential("full").canWrite)
    }

    /**
     * Every other spelling is refused, including the ones that look like they
     * ought to work. Each of these was previously granted write.
     */
    @Test
    fun everything_that_is_not_full_is_refused_write() {
        val refused = listOf(
            // Case variants. The hub writes lowercase and matches exactly, so
            // an upper-cased value is a different mode to it and must be here.
            "Full", "FULL", "fUll",
            // A mode a later hub might grow. The app should read the fleet and
            // decline to send, which is exactly what the hub would do with it.
            "observer", "admin", "full-with-extras",
            // Truncation, padding, and the empty string.
            "ful", "full ", " full", "",
            // The other literal, and its variants, which the old rule *did*
            // get right — kept here so a future edit cannot fix one and break
            // the other.
            "readonly", "READONLY", "read-only", "readOnly",
        )

        val wronglyGranted = refused.filter { Credentials.grantsWrite(it) }
        assertEquals(
            emptyList(),
            wronglyGranted,
            "these are not `full`, so the hub refuses them write and so must this",
        )
    }

    /**
     * The app's answer equals the hub's for every input, not merely for the
     * two the documentation mentions.
     *
     * Written as the hub's match arm rather than as a list of expected results,
     * so that it is the *rule* being compared and not a table someone kept in
     * step by hand.
     */
    @Test
    fun the_app_agrees_with_the_hubs_rule_on_every_input() {
        fun hubTokenModeGrantsWrite(mode: String): Boolean = when (mode) {
            "full" -> true
            else -> false
        }

        val everySpelling = listOf(
            "full", "readonly", "Full", "READONLY", "", " ", "read-only",
            "observer", "admin", "ful", "fullx", "full\n", "\"full\"", "0", "null",
        )

        for (mode in everySpelling) {
            assertEquals(
                hubTokenModeGrantsWrite(mode),
                Credentials.grantsWrite(mode),
                "the app and the hub must reach the same verdict for \"$mode\"",
            )
        }
    }

    /**
     * Settings says what the session screen enforces.
     *
     * These were two separate comparisons — `mode != "readonly"` for the prompt
     * box and `mode == "readonly"` for the Settings label — which are not each
     * other's negation. On an unknown mode the session screen enabled sending
     * *and* Settings printed the raw mode as though it were an access level in
     * good standing, so the one screen whose job is to say what this device may
     * do agreed with the bug instead of exposing it.
     */
    @Test
    fun settings_and_the_prompt_box_cannot_disagree() {
        for (mode in listOf("full", "readonly", "observer", "Full", "", "anything")) {
            val settings = SettingsUiState(hub = "https://h", clientName = "phone", mode = mode)
            assertEquals(
                credential(mode).canWrite,
                !settings.readOnly,
                "Settings and the prompt box must answer the same question for \"$mode\"",
            )
        }
    }

    // ---- what the store will accept as a credential ----

    /**
     * A stored blob with no `mode` is not a credential.
     *
     * It used to default to `full`, which is the third and quietest way a
     * credential could come to claim write it was never granted: the other two
     * misread a mode that was there, this one invented one that was not. The
     * hub cannot produce such a blob — `/pair` always answers `"mode":
     * row.mode` and the row is validated at insert — so it means truncation, a
     * hand-edit, or something else writing the file. None of those is a reason
     * to assume the most permissive answer.
     */
    @Test
    fun a_stored_credential_with_no_mode_is_not_a_credential() {
        assertNull(decodeCredentials("""{"hub":"https://h","token":"t","name":"phone"}"""))
        assertNull(decodeCredentials("""{"hub":"https://h","token":"t","name":"phone","mode":""}"""))
        assertNull(decodeCredentials("""{"hub":"https://h","token":"t","name":"phone","mode":"   "}"""))
    }

    /**
     * An *unknown* mode is kept, and declines to write. Missing is corruption;
     * unknown is a hub newer than this app, and the app should go on reading
     * the fleet rather than refusing to start.
     */
    @Test
    fun a_stored_credential_with_an_unknown_mode_is_kept_and_reads_only() {
        val stored = decodeCredentials(
            """{"hub":"https://h","token":"t","name":"phone","mode":"observer"}""",
        )

        assertEquals("observer", stored?.mode, "a newer hub's mode is not a reason to re-pair")
        assertFalse(stored!!.canWrite, "but it is a reason not to assume it grants write")
    }

    /** Both literal modes still round-trip through the store unchanged. */
    @Test
    fun both_real_modes_round_trip() {
        for (mode in listOf(Credentials.FULL, Credentials.READONLY)) {
            val stored = decodeCredentials(credential(mode).encode())
            assertEquals(credential(mode), stored)
            assertEquals(Credentials.grantsWrite(mode), stored!!.canWrite)
        }
    }

    private fun credential(mode: String) = Credentials(
        hub = "https://fleet.example.com",
        token = "0123456789abcdef0123456789abcdef",
        name = "phone",
        mode = mode,
    )
}
