package dev.claudefleet.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The pairs FleetTheme makes out of the tokens hold WCAG's 4.5:1 for text, in
 * both themes. The manual checks its own pairs; these are the phone's: the
 * status chips (word on a translucent fill, over either ground) and Material's
 * metadata colour on every surface container a screen puts text on.
 */
class OrbitThemeContrastTest {

    private val themes = listOf(OrbitColors(isDark = true), OrbitColors(isDark = false))

    private fun ratio(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return max(la, lb) / min(la, lb)
    }

    @Test
    fun status_words_read_on_their_chips() {
        for (o in themes) {
            for (ground in listOf(o.bg, o.bgPane)) {
                for (tone in StatusTone.entries.filterNot { it == StatusTone.STOPPED || it == StatusTone.UNKNOWN }) {
                    val c = orbitStatusColors(o, tone)
                    val fill = c.container.compositeOver(ground)
                    val r = ratio(c.onContainer, fill)
                    assertTrue(r >= 4.5f, "$tone word on its chip, dark=${o.isDark}: $r:1")
                }
            }
        }
    }

    @Test
    fun metadata_reads_on_every_surface_container() {
        for (o in themes) {
            val s = orbitColorScheme(o)
            for ((name, surface) in listOf(
                "surface" to s.surface,
                "surfaceContainerLow" to s.surfaceContainerLow,
                "surfaceContainer" to s.surfaceContainer,
                "surfaceContainerHigh" to s.surfaceContainerHigh,
                "surfaceContainerHighest" to s.surfaceContainerHighest,
            )) {
                val r = ratio(s.onSurfaceVariant, surface)
                assertTrue(r >= 4.5f, "onSurfaceVariant on $name, dark=${o.isDark}: $r:1")
            }
        }
    }
}
