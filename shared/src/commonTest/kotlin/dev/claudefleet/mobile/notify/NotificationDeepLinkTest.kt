package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.AppContainer
import dev.claudefleet.mobile.store.Credentials
import dev.claudefleet.mobile.store.Secrets
import dev.claudefleet.mobile.model.PendingInput
import dev.claudefleet.mobile.model.PendingOption
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.store.FakePrefs
import dev.claudefleet.mobile.ui.blockedCard
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private class PairedSecrets(private val hub: String) : Secrets {
    override suspend fun read(): Credentials? = Credentials(hub, "tok", "phone", "full")
    override suspend fun write(credentials: Credentials) {}
    override suspend fun clear() {}
}

private class NoSecrets : Secrets {
    override suspend fun read(): Credentials? = null
    override suspend fun write(credentials: Credentials) {}
    override suspend fun clear() {}
}

/**
 * Redesign 14.8's Verified by: the notification deep link lands on the
 * question card. A tap hands the session to the navigator (open it) and to
 * the session screen (show its question) separately, each taken once, so a
 * session already open on another tab still comes back to its conversation,
 * where the card is drawn.
 */
class NotificationDeepLinkTest {

    private fun container() = AppContainer(
        secrets = NoSecrets(),
        prefs = FakePrefs(),
        http = HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }),
        appVersion = "test",
    )

    @Test
    fun the_notification_deep_link_lands_on_the_question_card() {
        val c = container()
        c.onOpenSession(42)

        // The navigator opens the session, once.
        assertEquals(42L, c.consumeOpenSession())
        assertNull(c.consumeOpenSession(), "taken exactly once")

        // The session screen takes the question focus once, which selects
        // its conversation tab (App.kt's SessionRoute) whatever tab was open.
        assertFalse(c.consumeQuestionFocus(7), "another session's screen takes nothing")
        assertTrue(c.consumeQuestionFocus(42))
        assertFalse(c.consumeQuestionFocus(42), "a later visit is not sent back to the card")

        // And the conversation of a session that asks draws its question card.
        val asking = SessionRow(
            id = 42,
            tmuxName = "s42",
            claudeStatus = "blocked",
            pendingInput = PendingInput("permission", "Allow Bash: git push?", listOf(PendingOption(1, "Yes"), PendingOption(2, "No"))),
        )
        val card = assertNotNull(blockedCard(asking, null), "a blocked row has a question card")
        assertTrue(card.headline.isNotBlank())
    }

    @Test
    fun a_new_pairing_drops_a_parked_tap() {
        val c = container()
        c.onOpenSession(42)
        c.dropOpenRequests()
        assertNull(c.consumeOpenSession())
        assertFalse(c.consumeQuestionFocus(42))
    }

    /** MobileControl: a failure's Retry opens the session and parks the retry for the app to make there. */
    @Test
    fun retry_opens_the_session_and_parks_the_retry_once() {
        val c = container()
        c.onRetrySession(42)
        assertEquals(42L, c.consumeOpenSession(), "Retry lands on the session, as Open log does")
        assertEquals(42L, c.consumeRetrySession())
        assertNull(c.consumeRetrySession(), "taken exactly once")
    }

    /** MobileControl: a mission's Review grant opens that mission, once; a new pairing drops it. */
    @Test
    fun review_grant_opens_the_mission_once_and_a_new_pairing_drops_it() {
        val c = container()
        c.onOpenMission(5)
        assertEquals(5L, c.consumeOpenMission())
        assertNull(c.consumeOpenMission())
        c.onOpenMission(6)
        c.onRetrySession(7)
        c.dropOpenRequests()
        assertNull(c.consumeOpenMission())
        assertNull(c.consumeRetrySession())
    }

    /**
     * Review w16-1: a notification left up from an earlier pairing names
     * that hub's session, mission or retry. On another hub the same id is
     * someone else's, so the tap opens nothing there and retries nothing.
     */
    @Test
    fun a_tap_posted_for_another_hub_opens_and_retries_nothing() = runTest {
        val c = AppContainer(
            secrets = PairedSecrets("https://b.example"),
            prefs = FakePrefs(),
            http = HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }),
            appVersion = "test",
        )
        c.session.restore()

        c.onRetrySession(42, hub = "https://a.example")
        assertNull(c.consumeOpenSession(), "hub A's session 42 is not hub B's")
        assertNull(c.consumeRetrySession(), "and is never retried on B")
        assertFalse(c.consumeQuestionFocus(42))
        c.onOpenMission(5, hub = "https://a.example")
        assertNull(c.consumeOpenMission())

        c.onOpenSession(42, hub = "https://b.example")
        assertEquals(42L, c.consumeOpenSession(), "this hub's tap opens")
        c.onOpenMission(5, hub = "https://b.example")
        assertEquals(5L, c.consumeOpenMission())
        c.onOpenSession(43)
        assertEquals(43L, c.consumeOpenSession(), "a tap that names no hub (posted before this was) still opens")
    }
}
