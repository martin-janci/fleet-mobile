package dev.claudefleet.mobile.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.FleetTheme
import dev.claudefleet.mobile.ui.theme.OrbitTokens
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The phone kit (redesign step 14.1) drawn in both themes, to
 * `shared/build/kit-previews/`, which CI uploads as the `kit-previews`
 * artifact: the previews a reviewer holds next to the manual's BottomBar,
 * PhoneRow and BottomSheet cards.
 *
 * Compose Multiplatform's `@Preview` needs an IDE to look at; these render on
 * the CI runner and on any machine that runs `jvmTest`. Each shot also checks
 * that it drew on the theme's own ground, so a preview that silently fell back
 * to Material's defaults fails rather than producing a plausible picture.
 */
class KitPreviewTest {

    private val out = File(System.getProperty("user.dir"), "build/kit-previews").apply { mkdirs() }
    private val scale = 2.625f
    private val widthDp = 360

    private fun shot(name: String, heightDp: Int, content: @Composable () -> Unit) {
        for (dark in listOf(true, false)) {
            val scene = ImageComposeScene(
                width = (widthDp * scale).toInt(),
                height = (heightDp * scale).toInt(),
                density = Density(scale),
                content = {
                    FleetTheme(dark = dark) {
                        Column(Modifier.fillMaxSize().background(Fleet.colors.bg).padding(vertical = 16.dp)) { content() }
                    }
                },
            )
            try {
                val image = scene.render()
                val bitmap = Bitmap.makeFromImage(image)
                val ground = OrbitTokens.argb("bg", dark).toInt()
                assertEquals(
                    Integer.toHexString(ground),
                    Integer.toHexString(bitmap.getColor(2, 2)),
                    "$name (${if (dark) "dark" else "light"}) is not drawn on the theme's bg",
                )
                val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("could not encode $name")
                File(out, "$name-${if (dark) "dark" else "light"}.png").writeBytes(data.bytes)
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun bottom_bar() {
        shot("bottom-bar-inbox", heightDp = 104) {
            BottomBar(OrbitDestinations, selected = "inbox", onSelect = {}, badge = BottomBarBadge("inbox", 4))
        }
        shot("bottom-bar-sessions", heightDp = 104) {
            BottomBar(OrbitDestinations, selected = "sessions", onSelect = {}, badge = BottomBarBadge("inbox", 128))
        }
    }

    @Test
    fun phone_rows() {
        shot("phone-row", heightDp = 420) {
            PhoneRow(
                title = "Fix hub-e2e flake",
                line = "approve push to main",
                word = StatusWord.NEEDS_YOU,
                age = "2m",
                chips = { OrbitChip("PR #476 ✓"); OrbitChip("mac") },
            )
            PhoneRow(
                title = "Api tenant resolution",
                line = "API overloaded (529)",
                word = StatusWord.FAILED,
                age = "18m",
                chips = { OrbitChip("Retry"); OrbitChip("Open") },
            )
            PhoneRow(title = "FLEET-151 Orbit tokens on the phone", line = "In Progress", word = StatusWord.WORKING, age = "4m", selected = true)
            PhoneRow(title = "oci-arm", line = "last seen 11:52 · 2 sessions", lead = "Signal lost", leadColor = Fleet.colors.statusWaiting, age = "4h", divider = false)
        }
    }

    @Test
    fun status_chips() {
        shot("status-chips", heightDp = 140) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (word in StatusWord.entries.take(3)) OrbitChip(word.label, word = word)
                }
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (word in StatusWord.entries.drop(3)) OrbitChip(word.label, word = word)
                    OrbitChip("mac")
                }
            }
        }
    }

    @Test
    fun bottom_sheet() {
        shot("bottom-sheet-move", heightDp = 460) {
            Column(Modifier.width(widthDp.dp).background(Fleet.colors.bgPane)) {
                SheetGrip()
                SheetBody(
                    title = "Move to another host",
                    meta = "Uncommitted work, the conversation and its memory go with it.",
                    primary = SheetAction("Choose a host", enabled = false) {},
                    cancelLabel = "Cancel",
                    onCancel = {},
                ) {
                    SheetOption("hetzner-1", selected = false, onSelect = {}, sub = "1 running · Android SDK not checked")
                    SheetOption("nas", selected = true, onSelect = {}, sub = "agent · 1 running · no Android SDK")
                    SheetOption("oci-arm", selected = false, onSelect = {}, sub = "Signal lost · cannot move there now", enabled = false)
                }
            }
        }
        assertTrue(File(out, "bottom-sheet-move-dark.png").length() > 0)
    }
}
