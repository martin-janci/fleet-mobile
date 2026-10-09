package dev.claudefleet.mobile.ui.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.ui.theme.Fleet

/**
 * The kit's stand-in for a thin indeterminate bar at the top of a screen or
 * sheet: once [waiting] has lasted `loader-delay` ([rememberLoaderVisible]) it
 * draws a 24 dp row with the 24 dp Orbit mark, and [label] beside it if given.
 * Before that, and once the wait ends, it takes no space at all, so a quick
 * load flashes nothing and moves nothing.
 *
 * [modifier] is applied to the row while it shows; it is full width by
 * default, the footprint of the bar it replaces.
 */
@Composable
fun InlineLoading(waiting: Boolean, modifier: Modifier = Modifier, label: String? = null) {
    if (!rememberLoaderVisible(waiting)) return
    Row(
        modifier = modifier.fillMaxWidth().height(24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OrbitMarkLoader(MarkMotion.Orbit, size = 24.dp)
        if (label != null) Text(label, color = Fleet.colors.fgMuted, fontSize = 13.sp, lineHeight = 18.sp)
    }
}
