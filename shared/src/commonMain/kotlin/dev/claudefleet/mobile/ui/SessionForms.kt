package dev.claudefleet.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.model.ConvTurn
import dev.claudefleet.mobile.model.QuickReply
import dev.claudefleet.mobile.ui.components.DangerTextButton
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.kit.BottomSheet
import dev.claudefleet.mobile.ui.kit.SheetAction
import dev.claudefleet.mobile.ui.kit.SheetOption
import dev.claudefleet.mobile.ui.theme.Fleet
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable

/*
 * The forms a session opens (MobileFormsSession): bottom sheets, not
 * dialogs — title, fields, then Cancel and the verb at the thumb. Each sheet
 * is drawn over a pure rule below it, so what Save or Fork sends is tested
 * without a window to render in.
 */

// ---------------------------------------------------------------------------
// Rename and tags: one sheet, one Save.

/**
 * What the rename-and-tags sheet's Save sends: only what changed. [name] is
 * the trimmed new name or null when it is unchanged; [tags] the whole new
 * list or null when it is the same list. Both null closes the sheet and calls
 * nothing.
 */
data class SessionEdit(val name: String?, val tags: List<String>?) {
    val changes: Boolean get() = name != null || tags != null
}

/**
 * [SessionEdit] for a sheet opened on [initialName] / [initialTags] and now
 * holding [name] / [tags]. A blank name is not a rename — the hub would
 * store an empty label — so it reads as unchanged here and [canSaveEdit]
 * keeps Save off while the field is blank.
 */
fun sessionEdit(initialName: String, initialTags: List<String>, name: String, tags: List<String>): SessionEdit {
    val trimmed = name.trim()
    return SessionEdit(
        name = trimmed.takeIf { it.isNotEmpty() && it != initialName.trim() },
        tags = tags.takeIf { it != initialTags },
    )
}

/** Save is on while there is a name; with nothing changed it only closes the sheet. */
fun canSaveEdit(name: String): Boolean = name.isNotBlank()

/** [current] with the [draft] tag added: trimmed, once, never empty. */
fun withTag(current: List<String>, draft: String): List<String> {
    val t = draft.trim()
    return if (t.isEmpty() || t in current) current else current + t
}

const val EDIT_SHEET_TAG = "session.edit"

/**
 * ⋮ Rename and ⋮ Tags… both open this: the Name field, the tags as chips
 * that remove on tap, + Tag to add one, and one Save that sends what changed
 * ([sessionEdit]) as one call ([SessionViewModel.edit]).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SessionEditSheet(
    initialName: String,
    initialTags: List<String>,
    onSave: (SessionEdit) -> Unit,
    onDismiss: () -> Unit,
) {
    val o = Fleet.colors
    var name by remember { mutableStateOf(initialName) }
    var tags by remember { mutableStateOf(initialTags) }
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    fun addDraft() {
        tags = withTag(tags, draft)
        draft = ""
        adding = false
    }
    BottomSheet(
        title = "Rename session",
        onDismiss = onDismiss,
        modifier = Modifier.testTag(EDIT_SHEET_TAG),
        primary = SheetAction("Save", enabled = canSaveEdit(name)) {
            // A tag typed and not yet added is what the person meant to keep.
            val all = if (adding) withTag(tags, draft) else tags
            onSave(sessionEdit(initialName, initialTags, name, all))
        },
        scrollable = true,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Tags", color = o.fg2, fontSize = 14.sp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (tag in tags) {
                InputChip(
                    selected = false,
                    // The whole chip removes: a tag has no "selected" state.
                    onClick = { tags = tags - tag },
                    label = { Text(tag) },
                    trailingIcon = {
                        Icon(FleetIcons.Close, contentDescription = "Remove $tag", modifier = Modifier.size(InputChipDefaults.IconSize))
                    },
                )
            }
            if (!adding) {
                InputChip(selected = false, onClick = { adding = true }, label = { Text("+ Tag") })
            }
        }
        if (adding) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                placeholder = { Text("New tag") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addDraft() }),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Edit quick reply.

/** What Save writes for a chip: trimmed label and prompt, and whether a tap sends it. Null while there is no prompt. */
fun quickReplyFrom(label: String, prompt: String, sendOnTap: Boolean): QuickReply? =
    prompt.trim().takeIf { it.isNotEmpty() }?.let { QuickReply(label = label.trim(), text = it, autoSend = sendOnTap) }

/**
 * Write one chip: label, prompt, Send on tap. [original] null is a new chip
 * and has nothing to remove. Remove sits apart from Cancel — a tap meant to
 * back out must never delete a chip from every device — and asks first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditQuickReplySheet(
    original: QuickReply?,
    onSave: (QuickReply) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val o = Fleet.colors
    var label by remember { mutableStateOf(original?.label.orEmpty()) }
    var text by remember { mutableStateOf(original?.text.orEmpty()) }
    // A new chip fills the box by default, as on the desktop.
    var sendOnTap by remember { mutableStateOf(original?.sendsOnTap ?: false) }
    var confirmRemove by remember { mutableStateOf(false) }
    if (confirmRemove && onRemove != null) {
        BottomSheet(
            title = "Remove this quick reply?",
            meta = "It goes from every device's chip row.",
            onDismiss = { confirmRemove = false },
            primary = SheetAction("Remove") { confirmRemove = false; onRemove() },
        ) {}
        return
    }
    val chip = quickReplyFrom(label, text, sendOnTap)
    BottomSheet(
        title = if (original == null) "New quick reply" else "Edit quick reply",
        onDismiss = onDismiss,
        primary = SheetAction("Save", enabled = chip != null) { chip?.let(onSave) },
        scrollable = true,
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            singleLine = true,
            label = { Text("Label") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Prompt") },
            minLines = 2,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().toggleable(value = sendOnTap, role = Role.Switch) { sendOnTap = it },
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Send on tap", color = o.fg, fontSize = 15.sp)
                Text(
                    if (sendOnTap) "On: a tap sends it at once" else "Off: puts the text in the box to edit first",
                    color = o.fgMuted,
                    fontSize = 13.sp,
                )
            }
            Switch(checked = sendOnTap, onCheckedChange = null)
        }
        if (onRemove != null) {
            DangerTextButton(onClick = { confirmRemove = true }) { Text("Remove") }
        }
    }
}

// ---------------------------------------------------------------------------
// Fork.

/** One line of Fork's "From" picker: keep the conversation through turn [index], which is [anchor] for the hub. */
data class ForkTurnChoice(val index: Int, val anchor: String?, val label: String)

/**
 * The turns a fork can start from, newest first — the ones [replyActionsFor]
 * offers Fork on, each with the anchor it would send. Numbered from 1 when
 * the whole conversation is loaded; when only its tail is ([truncated]), a
 * number would be wrong, so a turn says how far back it is instead. The
 * reply's first words follow, so a turn can be told apart without opening it.
 */
fun forkTurnChoices(turns: List<ConvTurn>, truncated: Boolean, supported: Boolean): List<ForkTurnChoice> =
    turns.indices.reversed().mapNotNull { i ->
        val view = replyActionsFor(turns, i, truncated, supported)
        if (!view.canFork) return@mapNotNull null
        val where = when {
            !truncated -> "Turn ${i + 1}"
            i == turns.lastIndex -> "Latest turn"
            else -> (turns.lastIndex - i).let { if (it == 1) "1 turn back" else "$it turns back" }
        }
        val words = (replyText(turns[i]).ifEmpty { turns[i].prompt.orEmpty() }).trim().lineSequence().firstOrNull().orEmpty()
        val gist = if (words.length > FORK_GIST) cutAtWord(words, FORK_GIST) + "…" else words
        ForkTurnChoice(i, view.forkAnchor, if (gist.isEmpty()) where else "$where · “$gist”")
    }

private const val FORK_GIST = 28

/** [text] to at most [max] characters, back to the last whole word when there is one. */
private fun cutAtWord(text: String, max: Int): String {
    val cut = text.take(max)
    if (text.length <= max || text[max] == ' ') return cut.trimEnd()
    val space = cut.lastIndexOf(' ')
    return (if (space > 0) cut.take(space) else cut).trimEnd()
}

/** Fork's choices besides the turn: New worktree (with its name) or Same worktree. */
data class ForkForm(val newWorktree: Boolean = true, val name: String) {
    /** What git is given, which is not always what was typed. */
    val slug: String get() = branchSlug(name)
    val canFork: Boolean get() = !newWorktree || slug.isNotEmpty()
    /** `rewind_conversation`'s `new_worktree`: the slug, or null to share this session's tree. */
    val worktree: String? get() = if (newWorktree) slug else null
}

/** What carries and what does not, under the worktree choice. */
fun forkNote(newWorktree: Boolean): String =
    if (newWorktree) "Uncommitted changes are not carried." else "Both sessions edit the same files."

const val FORK_SHEET_TAG = "session.fork"

/**
 * Fork this session: from a turn ([forkTurnChoices], opened on the one whose
 * menu was used), into a New worktree (fresh branch, named) or the Same
 * worktree. The original never changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ForkSheet(
    choices: List<ForkTurnChoice>,
    initialIndex: Int,
    suggestedName: String,
    onFork: (anchor: String?, worktree: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val o = Fleet.colors
    var picked by remember { mutableStateOf(choices.firstOrNull { it.index == initialIndex } ?: choices.firstOrNull()) }
    var form by remember { mutableStateOf(ForkForm(name = suggestedName)) }
    var picking by remember { mutableStateOf(false) }
    BottomSheet(
        title = "Fork this session",
        meta = "The original never changes.",
        onDismiss = onDismiss,
        modifier = Modifier.testTag(FORK_SHEET_TAG),
        primary = SheetAction("Fork", enabled = picked != null && form.canFork) {
            picked?.let { onFork(it.anchor, form.worktree) }
        },
        scrollable = true,
    ) {
        Text("From", color = o.fg2, fontSize = 14.sp)
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, o.controlBorder, RoundedCornerShape(10.dp))
                    .clickable(enabled = choices.size > 1) { picking = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(picked?.label ?: "No turn to fork from", color = o.fg, fontSize = 15.sp, modifier = Modifier.weight(1f))
                if (choices.size > 1) Text("▾", color = o.fgMuted, fontSize = 15.sp)
            }
            DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                for (c in choices) {
                    DropdownMenuItem(text = { Text(c.label) }, onClick = { picked = c; picking = false })
                }
            }
        }
        SheetOption(
            title = "New worktree",
            sub = "fresh branch",
            selected = form.newWorktree,
            onSelect = { form = form.copy(newWorktree = true) },
        )
        SheetOption(
            title = "Same worktree",
            sub = "shares this session's files",
            selected = !form.newWorktree,
            onSelect = { form = form.copy(newWorktree = false) },
        )
        if (form.newWorktree) {
            OutlinedTextField(
                value = form.name,
                onValueChange = { form = form.copy(name = it) },
                singleLine = true,
                label = { Text("Name") },
                supportingText = { if (form.slug != form.name) Text("As: ${form.slug.ifEmpty { "—" }}") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            )
        }
        Text(forkNote(form.newWorktree), color = o.fgMuted, fontSize = 13.sp)
    }
}
