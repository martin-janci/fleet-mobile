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
