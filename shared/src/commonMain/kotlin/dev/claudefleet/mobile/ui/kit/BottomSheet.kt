package dev.claudefleet.mobile.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.OrbitTokens

/**
 * The primary action at the foot of a sheet. When a choice is missing,
 * [enabled] is false and [label] names the missing choice ("Choose a host").
 */
data class SheetAction(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * The phone's dialog (manual: BottomSheet), as a modal sheet: grip, title,
 * an optional one-line explanation, the content, then Cancel and one primary
 * at the bottom in thumb reach.
 *
 * A form passes [scrollable]: its fields scroll and Cancel and the verb stay
 * pinned under them, so with the keyboard up (the sheet's own window insets
 * lift it, `App`'s root padding does not reach a sheet) Save is still in
 * reach rather than pushed off under the keys.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    meta: String? = null,
    primary: SheetAction? = null,
    cancelLabel: String = "Cancel",
    state: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val o = Fleet.colors
    val radius = OrbitTokens.radius("radius-sheet").dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = state,
        shape = RoundedCornerShape(topStart = radius, topEnd = radius),
        containerColor = o.bgPane,
        contentColor = o.fg,
        dragHandle = { SheetGrip() },
    ) {
        SheetBody(title = title, meta = meta, primary = primary, cancelLabel = cancelLabel, onCancel = onDismiss, scrollable = scrollable, content = content)
    }
}

/** The grip: 36 × 4 in `control-border`. */
@Composable
fun SheetGrip() {
    Box(
        modifier = Modifier
            .padding(top = 12.dp, bottom = 6.dp)
            .size(width = 36.dp, height = 4.dp)
            .background(Fleet.colors.controlBorder, RoundedCornerShape(2.dp)),
    )
}

/**
 * Everything inside the sheet, apart from the modal machinery, so previews
 * and tests can draw it without a window to animate in.
 */
@Composable
fun SheetBody(
    title: String,
    meta: String?,
    primary: SheetAction?,
    cancelLabel: String,
    onCancel: () -> Unit,
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val o = Fleet.colors
    val gutter = OrbitTokens.spacing("phone-gutter").dp
    val touch = OrbitTokens.spacing("touch-min").dp
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = gutter, end = gutter, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = o.fg, fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
        if (meta != null) Text(meta, color = o.fgMuted, fontSize = 14.sp, lineHeight = 20.sp)
        if (scrollable) {
            // `fill = false`: a short form keeps its own height, a tall one
            // takes what is left above the pinned buttons and scrolls.
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        } else {
            content()
        }
        Row(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f).height(touch),
                border = BorderStroke(1.dp, o.controlBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = o.fg2),
                shape = RoundedCornerShape(OrbitTokens.radius("radius-md").dp),
            ) { Text(cancelLabel, fontSize = 15.sp) }
            if (primary != null) {
                Button(
                    onClick = primary.onClick,
                    enabled = primary.enabled,
                    modifier = Modifier.weight(1f).height(touch),
                    colors = ButtonDefaults.buttonColors(containerColor = o.accent, contentColor = o.accentFg),
                    shape = RoundedCornerShape(OrbitTokens.radius("radius-md").dp),
                ) { Text(primary.label, fontSize = 15.sp) }
            }
        }
    }
}

/**
 * A choice inside a sheet (manual: of-popt): a radio with a title and a
 * sub-line. Nothing is selected until the person picks; a disabled option
 * says why in its sub-line ("Signal lost · cannot move there now").
 */
@Composable
fun SheetOption(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
    enabled: Boolean = true,
) {
    val o = Fleet.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = OrbitTokens.spacing("touch-min").dp)
            .alpha(if (enabled) 1f else 0.45f)
            .background(if (selected) o.accentSoft else o.bgPane, shape)
            .border(1.dp, if (selected) o.accent else o.controlBorder, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .border(if (selected) 5.dp else 2.dp, if (selected) o.accent else o.controlBorder, CircleShape),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = if (selected) o.fg else o.fg2, fontSize = 15.sp, lineHeight = 21.sp)
            if (sub != null) Text(sub, color = o.fgMuted, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}
