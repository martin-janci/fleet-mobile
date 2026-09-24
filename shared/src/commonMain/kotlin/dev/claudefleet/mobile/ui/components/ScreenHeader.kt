package dev.claudefleet.mobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The one header every screen wears: an optional navigation icon, a title that
 * is always exactly one line, an optional one-line subtitle, a few actions,
 * and an optional [below] slot for whatever belongs to the chrome rather than
 * to the scrolling content (the Sessions filters, a session's status line).
 *
 * It replaced three different headers — a `TopAppBar` on Sessions and on a
 * session, and a hand-rolled 8 dp row on Hosts and Settings — and exists
 * because of the session one. Material's `TopAppBar` measures `actions`
 * *before* the title and gives the title only what is left. The session bar
 * put a status strip, a `/compact` chip, a retire chip and five icons into
 * `actions`, which on a 360 dp phone is wider than the screen: the title got
 * zero width, wrapped its host alias one character per line, the bar grew
 * to hundreds of dp tall, and the strip ran off the left edge over the back
 * arrow. Here the title is `weight(1f)` in a plain [Row] and only a couple of
 * icons ever sit beside it; everything that needs room goes in [below], which
 * owns the full width.
 *
 * Draws on `surfaceContainer` with a hairline under it, so the fixed chrome
 * reads as separate from the list that scrolls beneath it without a shadow.
 * Insets are not handled here: `App` pads the whole app with
 * `WindowInsets.safeDrawing` once, and a second pad would double it.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleStyle: TextStyle = MaterialTheme.typography.titleLarge,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = HEADER_HEIGHT)
                    .padding(start = if (navigation != null) 4.dp else 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (navigation != null) {
                    navigation()
                    Spacer(Modifier.width(4.dp))
                }
                Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(
                        text = title,
                        style = titleStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                actions()
            }
            below?.invoke(this)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** Material's small top app bar height, kept so the header does not shrink when a screen has no actions. */
private val HEADER_HEIGHT = 64.dp
