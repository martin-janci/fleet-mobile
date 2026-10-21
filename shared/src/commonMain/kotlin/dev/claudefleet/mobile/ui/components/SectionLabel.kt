package dev.claudefleet.mobile.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.TextUnit
import dev.claudefleet.mobile.ui.theme.Fleet

/**
 * The Orbit section label: the text uppercased, at the smallest type size,
 * in the muted colour. Where it sits (padding) and whether it is a heading
 * are the screen's to say through [modifier].
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, letterSpacing: TextUnit = TextUnit.Unspecified) {
    Text(text.uppercase(), style = Fleet.type.text2xs, color = Fleet.colors.fgMuted, letterSpacing = letterSpacing, modifier = modifier)
}
