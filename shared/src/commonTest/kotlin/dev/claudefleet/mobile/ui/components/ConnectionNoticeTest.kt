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
}
