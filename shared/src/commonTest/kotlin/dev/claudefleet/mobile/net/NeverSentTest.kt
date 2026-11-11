package dev.claudefleet.mobile.net

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Review of #300: on iOS every transport failure read "May have gone
 * through", because Darwin names a refused connection, an unknown host and no
 * network alike. The NSURLErrorDomain table is tested here, on every host;
 * `iosTest`'s `DarwinNeverSentTest` holds the Darwin exception to it.
 */
class NeverSentTest {
    @Test
    fun urlsession_errors_before_the_request_left_are_never_sent() {
        for (code in listOf(-1003L, -1004L, -1006L, -1009L, -1022L, -1200L)) {
            assertTrue(nsUrlErrorNeverSent(NS_URL_ERROR_DOMAIN, code), "code $code")
        }
    }

    @Test
    fun a_timeout_or_a_lost_connection_may_have_been_delivered() {
        // Timed out (-1001), connection lost (-1005), bad server response (-1011).
        for (code in listOf(-1001L, -1005L, -1011L)) {
            assertFalse(nsUrlErrorNeverSent(NS_URL_ERROR_DOMAIN, code), "code $code")
        }
        // The same number in another domain says nothing.
        assertFalse(nsUrlErrorNeverSent("NSPOSIXErrorDomain", -1004L))
        assertFalse(nsUrlErrorNeverSent(null, -1004L))
    }
}
