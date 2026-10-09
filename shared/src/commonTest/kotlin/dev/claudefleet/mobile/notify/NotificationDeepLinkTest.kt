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
}
