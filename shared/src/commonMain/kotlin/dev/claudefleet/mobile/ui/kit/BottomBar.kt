package dev.claudefleet.mobile.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.OrbitTokens

/** One destination on the [BottomBar]. [key] is what the caller routes on; the bar never interprets it. */
data class BottomBarItem(val key: String, val label: String, val icon: ImageVector)

/**
 * The one badge the bar may carry: the Needs you count, on the destination
 * named by [key] (Inbox). One value, not a list, so a second badge cannot be
 * added by accident; zero draws nothing.
 */
data class BottomBarBadge(val key: String, val count: Int)

/**
 * The manual's destinations, in its order: Inbox, Sessions, Control, Work,
 * More. A list rather than an enum so 14.2's navigation can change the set
 * (Martin has not confirmed it yet) without touching the bar.
 */
val OrbitDestinations: List<BottomBarItem> = listOf(
    BottomBarItem("inbox", "Inbox", OrbitIcons.Inbox),
    BottomBarItem("sessions", "Sessions", OrbitIcons.Sessions),
    BottomBarItem("control", "Control", OrbitIcons.Control),
    BottomBarItem("work", "Work", OrbitIcons.Work),
    BottomBarItem("more", "More", OrbitIcons.More),
)

/** What the badge says: the count up to 99, then "99+". */
fun badgeText(count: Int): String? = when {
    count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}

/**
 * The phone's bottom bar (manual: BottomBar): `tab-bar-h` tall on `bg-pane`
 * with a hairline on top; each destination fills its share of the width and
 * is at least `touch-min` tall; the current one has an `accent-soft` pill and
 * bold label.
 */
@Composable
fun BottomBar(
    items: List<BottomBarItem>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    badge: BottomBarBadge? = null,
) {
    val o = Fleet.colors
    Column(modifier = modifier.fillMaxWidth().background(o.bgPane).navigationBarsPadding()) {
        HorizontalDivider(color = o.border)
        Row(
            modifier = Modifier.fillMaxWidth().height(OrbitTokens.spacing("tab-bar-h").dp - 1.dp).padding(bottom = 6.dp),
        ) {
            for (item in items) {
                val current = item.key == selected
                val count = badge?.takeIf { it.key == item.key }?.count ?: 0
                val shown = badgeText(count)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .defaultMinSize(minHeight = OrbitTokens.spacing("touch-min").dp)
                        .selectable(selected = current, role = Role.Tab, onClick = { onSelect(item.key) })
                        .semantics {
                            contentDescription = if (shown != null) "${item.label}, $count need you" else item.label
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                ) {
                    Box {
                        Box(
                            modifier = Modifier
                                .width(56.dp)
                                .height(30.dp)
                                .background(if (current) o.accentSoft else Color.Transparent, RoundedCornerShape(15.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(item.icon, contentDescription = null, tint = if (current) o.fg else o.fgMuted, modifier = Modifier.size(22.dp))
                        }
                        if (shown != null) {
                            Text(
                                shown,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .offset(x = 18.dp, y = (-2).dp)
                                    .background(o.statusWaiting, RoundedCornerShape(8.dp))
                                    .defaultMinSize(minWidth = 16.dp)
                                    .padding(horizontal = 4.dp),
                                color = o.onWaiting,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Text(
                        item.label,
                        color = if (current) o.fg else o.fgMuted,
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** The bar's five glyphs, from the manual's BottomBar preview: 24-unit grid, 1.6 strokes. */
object OrbitIcons {
    private fun stroked(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in paths) {
                addPath(
                    pathData = addPathNodes(d),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.6f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Inbox: ImageVector by lazy { stroked("Inbox", "M3 13h5l1.5 2h5L16 13h5", "M5 5h14l2 8v6H3v-6z") }

    val Sessions: ImageVector by lazy {
        stroked(
            "Sessions",
            "M5.5 4.5h13a2.5 2.5 0 0 1 2.5 2.5v10a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 17V7a2.5 2.5 0 0 1 2.5-2.5z",
            "M7 10l3 2.5L7 15M12 15h5",
        )
    }

    val Control: ImageVector by lazy {
        stroked("Control", "M20 12a8 8 0 1 1-16 0a8 8 0 1 1 16 0z", "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z")
    }

    val Work: ImageVector by lazy {
        stroked(
            "Work",
            "M7 4h10a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3V7a3 3 0 0 1 3-3z",
            "M8.5 12l2.5 2.5L16 9.5",
        )
    }

    val More: ImageVector by lazy {
        stroked(
            "More",
            "M7.1 12a1.6 1.6 0 1 1-3.2 0a1.6 1.6 0 1 1 3.2 0z",
            "M13.6 12a1.6 1.6 0 1 1-3.2 0a1.6 1.6 0 1 1 3.2 0z",
            "M20.1 12a1.6 1.6 0 1 1-3.2 0a1.6 1.6 0 1 1 3.2 0z",
        )
    }
}
