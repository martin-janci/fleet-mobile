package dev.claudefleet.mobile.ui.components

import dev.claudefleet.mobile.data.ConnectionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the connection banner says.
 *
 * `ConnectionStatus.Reconnecting.reason` was computed on every failure and read
 * by nothing for three tasks, while two comments said it reached the screen.
 * The repository's side — the reason is a sentence from `explain`, not a raw
 * message — is `FleetRepositoryTest`'s; this is the other half, that the
 * sentence is actually drawn.
 */
class ConnectionNoticeTest {

    @Test
    fun a_retry_says_what_it_is_retrying_from() {
        val text = connectionNotice(ConnectionStatus.Reconnecting(3, "the hub answered HTTP 502"))

        assertEquals("reconnecting to the hub (attempt 3)… the hub answered HTTP 502", text)
    }

    @Test
    fun the_first_connect_has_no_failure_to_name() {
        assertEquals("connecting to the hub…", connectionNotice(ConnectionStatus.Reconnecting(1, null)))
    }

    @Test
    fun offline_says_why_and_connected_says_nothing() {
        assertEquals("paused", connectionNotice(ConnectionStatus.Offline("paused")))
        assertNull(connectionNotice(ConnectionStatus.Connected("0.9.3")))
    }

    /**
     * The third connection state, which was computed and never drawn.
     *
     * A session screen probes the hub directly while the stream is down, and
     * a `true` there leaves the app somewhere the two-word vocabulary could
     * not express: the hub is live, Send works, only the updates are missing.
     * The banner used to say "reconnecting" over a working Send button, which
     * reads as the app contradicting itself.
     */
    @Test
    fun a_live_hub_behind_a_dead_stream_says_so() {
        assertEquals(
            "live over the hub; the update stream is down (reconnecting…)",
            connectionNotice(ConnectionStatus.Reconnecting(4, "the hub closed the stream"), hubReachable = true),
        )
    }

    /** A probe that said no, or one that has not answered, leaves the retry wording alone. */
    @Test
    fun a_hub_that_does_not_answer_is_still_a_plain_retry() {
        val reconnecting = ConnectionStatus.Reconnecting(4, "the hub closed the stream")

        assertEquals(
            "reconnecting to the hub (attempt 4)… the hub closed the stream",
            connectionNotice(reconnecting, hubReachable = false),
        )
        assertEquals(
            "reconnecting to the hub (attempt 4)… the hub closed the stream",
            connectionNotice(reconnecting, hubReachable = null),
        )
    }

    /**
     * A refused hub draws the contract sentence, not "offline" — the hub is
     * up, and what a person needs is which side to update.
     */
    @Test
    fun a_refused_hub_says_which_side_is_behind() {
        val refusal = "This app is too old for this hub (contract 2). Update the app."

        assertEquals(refusal, connectionNotice(ConnectionStatus.Refused(refusal)))
        // And a healthy probe does not talk over it.
        assertEquals(refusal, connectionNotice(ConnectionStatus.Refused(refusal), hubReachable = true))
    }
}
