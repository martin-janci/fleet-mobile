package dev.claudefleet.mobile.net

import dev.claudefleet.mobile.ui.mayHaveRun
import io.ktor.client.engine.darwin.DarwinHttpRequestException
import platform.Foundation.NSError
import platform.Foundation.NSURLErrorCannotConnectToHost
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorNetworkConnectionLost
import platform.Foundation.NSURLErrorNotConnectedToInternet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Review of #300: Darwin reports a refused connection as a
 * `DarwinHttpRequestException`, which read "May have gone through". It is
 * "Not sent" now; a connection lost mid-request still may have run.
 */
class DarwinNeverSentTest {
    private fun darwin(code: Long) =
        HubError.Transport(DarwinHttpRequestException(NSError.errorWithDomain(NSURLErrorDomain, code, null)))

    @Test
    fun the_domain_constant_is_foundations() {
        assertEquals(NSURLErrorDomain, NS_URL_ERROR_DOMAIN)
    }

    @Test
    fun a_refused_connection_was_never_sent() {
        assertFalse(mayHaveRun(darwin(NSURLErrorCannotConnectToHost)))
        assertFalse(mayHaveRun(darwin(NSURLErrorNotConnectedToInternet)))
    }

    @Test
    fun a_connection_lost_mid_request_may_have_run() {
        assertTrue(mayHaveRun(darwin(NSURLErrorNetworkConnectionLost)))
    }
}
