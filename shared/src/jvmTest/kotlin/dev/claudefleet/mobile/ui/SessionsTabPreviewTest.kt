package dev.claudefleet.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.Attention
import dev.claudefleet.mobile.model.PendingInput
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.WorkSummary
import dev.claudefleet.mobile.ui.kit.OrbitMark
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.FleetTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The New layout's Sessions tab and Inbox (redesign 14.3) drawn in both
 * themes into `shared/build/kit-previews/`, which CI uploads as the
 * `kit-previews` artifact, to hold next to the MobileNav and
 * MobileSessionsTools boards.
 */
class SessionsTabPreviewTest {

    private val out = File(System.getProperty("user.dir"), "build/kit-previews").apply { mkdirs() }
    private val scale = 2.625f
    private val now = 1_000_000L

    private fun shot(name: String, heightDp: Int, content: @Composable () -> Unit) {
        for (dark in listOf(true, false)) {
            val scene = ImageComposeScene(
                width = (360 * scale).toInt(),
                height = (heightDp * scale).toInt(),
                density = Density(scale),
                content = { FleetTheme(dark = dark) { Box(Modifier.fillMaxSize().background(Fleet.colors.bg)) { content() } } },
            )
            try {
                val data = scene.render().encodeToData(EncodedImageFormat.PNG) ?: error("could not encode $name")
                val file = File(out, "$name-${if (dark) "dark" else "light"}.png")
                file.writeBytes(data.bytes)
                assertTrue(file.length() > 0)
            } finally {
                scene.close()
            }
        }
    }

    private fun row(
        id: Long,
        name: String,
        host: String,
        status: String,
        ago: Long,
        activity: String? = null,
        question: String? = null,
        pr: String? = null,
        ci: String? = null,
        work: String? = null,
    ) = SessionRow(
        id = id,
        tmuxName = name,
        friendlyName = name,
        hostAlias = host,
        claudeStatus = status,
        currentActivity = activity,
        pendingInput = question?.let { PendingInput(kind = "permission", question = it) },
        attention = if (status == "blocked") Attention("waiting", since = now - ago) else null,
        prUrl = pr,
        ciStatus = ci,
        work = work?.let { WorkSummary(key = it) },
        lastActivityAt = now - ago,
    )

    private val rows = listOf(
        row(1, "Review the HostsScreen diff", "mercury", "working", 10, activity = "reading HostsScreen.kt", pr = "https://github.com/o/r/pull/112"),
        row(2, "Quiet hours", "mercury", "blocked", 420, question = "allow cargo fleet-test", work = "FLEET-150"),
        row(3, "Hub client split", "hetzner-1", "idle", 3600, activity = "CI passed on #118", pr = "https://github.com/o/r/pull/118", ci = "passing"),
        row(4, "Dispatchers", "hetzner-1", "failed", 1080, activity = "tests crashed"),
    )

    private fun state(mode: GroupMode) = SessionsUiState(
        groups = if (mode == GroupMode.URGENCY) emptyList() else rows.groupBy { it.hostAlias }.map { (host, inHost) ->
            HostGroup(alias = host, reachable = true, projects = listOf(ProjectGroup(projectId = null, label = "", sessions = inHost)))
        },
        urgent = if (mode == GroupMode.URGENCY) rows else emptyList(),
        status = ConnectionStatus.Connected("0.9.4"),
        nowSeconds = now,
        shown = rows.size,
        total = rows.size,
        groupMode = mode,
    )

    @Test
    fun sessions_by_host() {
        shot("sessions-tab-host", heightDp = 640) {
            SessionsTab(state = state(GroupMode.HOST), handlers = SessionsHandlers(onNewSession = {}, onOpenToday = {}))
        }
    }

    @Test
    fun sessions_by_state() {
        shot("sessions-tab-state", heightDp = 640) {
            SessionsTab(state = state(GroupMode.URGENCY), handlers = SessionsHandlers(onNewSession = {}))
        }
    }

    @Test
    fun sessions_bulk_select() {
        shot("sessions-tab-select", heightDp = 640) {
            SessionsTab(
                state = state(GroupMode.HOST),
                handlers = SessionsHandlers(onNewSession = {}),
                bulk = BulkUiState(enabled = true, selected = setOf(1L, 3L), killable = 2),
            )
        }
    }

    @Test
    fun inbox() {
        shot("inbox", heightDp = 420) {
            InboxScreen(
                rows = inboxRows(rows),
                running = 1,
                nowSeconds = now,
                onOpenSession = {},
                onOpenToday = {},
            )
        }
    }

    @Test
    fun orbit_draws_as_you_pull() {
        shot("orbit-pull", heightDp = 72) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (p in listOf(0.2f, 0.45f, 0.7f, 1f)) OrbitMark(size = 40.dp, drawn = p)
                OrbitMark(size = 40.dp, chase = 0.25f)
            }
        }
    }
}
