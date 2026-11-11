package dev.claudefleet.mobile.net

import dev.claudefleet.mobile.ui.mayHaveRun
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Review of #300: the JVM/Android engine's failures, sorted into never sent and may have run. */
class OkHttpNeverSentTest {
    @Test
    fun failures_before_the_request_left_are_not_sent() {
        for (t in listOf(
            ConnectException("Connection refused"),
            UnknownHostException("hub"),
            SSLHandshakeException("bad certificate"),
            SocketException("Network is unreachable"),
            SocketException("Host is unreachable (connect failed)"),
        )) {
            assertTrue(HubError.Transport(t).neverSent, t.toString())
            assertFalse(mayHaveRun(HubError.Transport(t)), t.toString())
        }
    }

    @Test
    fun failures_after_it_may_have_run() {
        for (t in listOf(
            SocketTimeoutException("timeout"),
            SocketException("Connection reset"),
            SocketException("Broken pipe"),
        )) {
            assertFalse(HubError.Transport(t).neverSent, t.toString())
            assertTrue(mayHaveRun(HubError.Transport(t)), t.toString())
        }
    }
}
