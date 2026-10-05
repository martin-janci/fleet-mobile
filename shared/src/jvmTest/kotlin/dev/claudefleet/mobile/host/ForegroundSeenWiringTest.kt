package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The foreground half of the iOS alerts lives in a composable no test can
 * render on the JVM, so its wiring is held here: started with the lifecycle,
 * only when there is a poster and alerts are on.
 */
class ForegroundSeenWiringTest {

    private val app: String by lazy { Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/App.kt").readText() }
    private val settings: String by lazy { Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/SettingsScreen.kt").readText() }

    @Test
    fun the_open_app_keeps_the_seen_set_with_the_lifecycle() {
        val block = app.substringAfter("val poster = container.notifier.poster").substringBefore("val nav = remember")
        assertTrue("if (poster != null && alertsOn)" in block)
        assertTrue("LifecycleStartEffect(repository, poster)" in block)
        assertTrue("keepSeenWhileOpen(repository, container.prefs, poster)" in block)
        assertTrue("onStopOrDispose { job.cancel() }" in block)
    }

    @Test
    fun settings_shows_the_platforms_note_and_refreshes_it_on_resume() {
        val row = settings.substringAfter("private fun NotifyRow(").substringBefore("\n}\n")
        assertTrue("notifier.note.collectAsState()" in row)
        assertTrue("LifecycleResumeEffect(notifier)" in row && "notifier.refreshNote()" in row)
        assertTrue("note ?:" in row, "a null note keeps the existing line")
    }
}
