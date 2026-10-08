package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A "needs you" notification never answers (redesign 14.8; the manual's
 * never-list): no platform poster calls a write to the hub, and every button
 * it draws comes from `needsYouActions`, which only opens the app or puts the
 * notification away (`NeedsYouActionsTest`). A source scan, because what is
 * guarded is that the posting code reaches nothing else.
 */
class NotificationsNeverAnswerTest {

    private fun code(path: String): String =
        Repo.file(path).readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    private val android by lazy { code("androidApp/src/main/kotlin/dev/claudefleet/mobile/android/NeedsYouService.kt") }
    private val ios by lazy { code("shared/src/iosMain/kotlin/dev/claudefleet/mobile/notify/IosAlertPoster.kt") }
    private val delegate by lazy { Repo.file("iosApp/iosApp/AppDelegate.swift").readText() }

    private val writes = listOf("sendKeys", "sendPrompt", "answer(", "approve", "Approve", "respond", "pressEnter")

    @Test
    fun the_posters_make_no_write_to_the_hub() {
        assertEquals(emptyList(), writes.filter { it in android }, "NeedsYouService")
        assertEquals(emptyList(), writes.filter { it in ios }, "IosAlertPoster")
    }

    @Test
    fun every_android_button_comes_from_needs_you_actions() {
        assertEquals(1, Regex("""\.addAction\(""").findAll(android).count(), "one addAction, inside the loop over the content's actions")
        assertTrue(Regex("""for \(action in c\.actions\)""").containsMatchIn(android))
        assertTrue("setPublicVersion(" in android && "VISIBILITY_PRIVATE" in android, "the lock screen gets the public version")
    }

    @Test
    fun ios_registers_its_buttons_from_the_shared_categories_and_later_opens_nothing() {
        assertTrue("needsYouCategories()" in ios)
        assertTrue(Regex("""actionIdentifier == "later"[\s\S]*?return""").containsMatchIn(delegate))
    }
}
