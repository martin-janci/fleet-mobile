package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Review r09 B1: the Inbox badge, the Inbox list, Today's *Waiting on me* and
 * Control's "N need you" count the same rows, `inboxRows(all, access)`, so a
 * session shared with this person (its owner's question) is in none of them.
 */
class NeedsYouCountTest {
    private val app = Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/App.kt").readText()

    @Test
    fun every_count_of_the_inbox_passes_the_access() {
        val calls = Regex("""inboxRows\(([^)]*)\)""").findAll(app).map { it.groupValues[1] }.toList()
        assertTrue(calls.size >= 4, "badge, Inbox, Today and Control each count: $calls")
        for (args in calls) assertTrue(',' in args, "inboxRows($args) leaves out the access, so shared rows count")
    }

    @Test
    fun the_badge_is_not_the_fleet_wide_count() {
        assertFalse("BottomBarBadge(Tab.Inbox.name, attention.attentionCount)" in app)
    }
}

/** Review r09 F5: the notification path closes the sheets, as the in-app paths do. */
class NotificationOpensOverSheetsTest {
    private val app = Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/App.kt").readText()

    @Test
    fun the_tap_goes_through_open_over_sheets() {
        val path = app.substringAfter("container.consumeOpenSession()", "").take(600)
        assertTrue("openOverSheets(" in path, "a tapped notification opens through openOverSheets")
        for (sheet in listOf("today", "tidy", "tickets", "missions", "automation", "debugDevices", "pullRequests")) {
            assertTrue("$sheet.close()" in path, "$sheet stays open over the session")
        }
    }
}
