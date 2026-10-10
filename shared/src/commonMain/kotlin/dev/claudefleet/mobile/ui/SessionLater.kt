package dev.claudefleet.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import dev.claudefleet.mobile.epochSeconds
import dev.claudefleet.mobile.utcOffsetSeconds
import dev.claudefleet.mobile.model.Headroom
import dev.claudefleet.mobile.model.HostLogin
import dev.claudefleet.mobile.model.QueuedPrompt
import dev.claudefleet.mobile.model.SendLaterTiming
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.localMidnight
import dev.claudefleet.mobile.ui.kit.SheetOption
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.loginLabel
import dev.claudefleet.mobile.model.relativeWithin
import dev.claudefleet.mobile.model.usedText
import dev.claudefleet.mobile.ui.kit.BottomSheet
import dev.claudefleet.mobile.ui.kit.SheetAction
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.OrbitTokens

/**
 * What a session screen hands its paused-on-limit card and its Send later
 * sheet (redesign 4.10 and 14.14). The defaults do nothing: a preview, a test
 * that does not care, or a hub that serves neither.
 */
class SessionLaterHost(
    /** The prompt and when it goes (gap plan G5.5); a plain [SendLaterTiming] is "when idle". */
    val onSendLater: (String, SendLaterTiming) -> Unit = { _, _ -> },
    val onLoadQueued: () -> Unit = {},
    val onCancelQueued: (Long) -> Unit = {},
    val onDismissNotice: () -> Unit = {},
    val onProposeSwitch: () -> Unit = {},
    val onConfirmSwitch: () -> Unit = {},
    val onCancelSwitch: () -> Unit = {},
    val onWait: (Long) -> Unit = {},
    /** When the session's account limit resets (unix seconds), from `account_usage`; null when unknown. */
    val limitResetsAt: Long? = null,
    /** An account uuid's label from `list_accounts`; null when the hub named none. */
    val accountName: (String) -> String? = { null },
)

const val LIMIT_CARD_TAG = "session.limit"
const val LIMIT_SWITCH_TAG = "session.limit.switch"
const val LIMIT_CONFIRM_TAG = "session.limit.confirm"
const val LIMIT_WAIT_TAG = "session.limit.wait"
const val SEND_LATER_FIELD_TAG = "session.sendLater.field"

/** "Wait · resets in 2 h": the Wait button; null when the reading has no reset. */
internal fun waitLabel(resetsAt: Long?, nowSeconds: Long): String? =
    relativeWithin(resetsAt, nowSeconds)?.let { "Wait · resets in $it" }

/**
 * A "Paused · limit" session's two answers (claude-fleet 4.4 on the phone,
 * 4.10), as the desktop's `LimitActions` draws them: Switch account asks the
 * hub which login on the host has the most room on another account and shows
 * it; a second tap restarts the session under it, resuming its conversation.
 * Wait folds the buttons into "Waiting for the reset". Nothing moves without
 * the second tap, and neither button is pre-selected.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LimitCard(state: SessionUiState, host: SessionLaterHost, modifier: Modifier = Modifier) {
    val o = Fleet.colors
    val target = state.switchTarget
    val waiting = state.waitingUntil
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag(LIMIT_CARD_TAG),
        color = o.bgRaise,
        contentColor = o.fg,
        shape = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp),
        border = BorderStroke(1.dp, o.border),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Paused · its account is at its usage limit",
                style = Fleet.type.textSm,
                color = o.fg,
            )
            state.limitNotice?.let {
                Text(it, style = Fleet.type.textXs, color = o.fgMuted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            val enabled = !state.busy && state.connected
            when {
                target != null -> {
                    Text(
                        "Resume under ${loginLabel(target, host.accountName)} · ${usedText(target)}?",
                        style = Fleet.type.textXs,
                        color = o.fgMuted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = host.onConfirmSwitch, enabled = enabled, modifier = Modifier.testTag(LIMIT_CONFIRM_TAG)) { Text("Switch") }
                        TextButton(onClick = host.onCancelSwitch, enabled = enabled) { Text("Cancel") }
                    }
                }
                waiting != null -> Text(
                    relativeWithin(waiting, state.nowSeconds)?.let { "Waiting for the reset · in $it" } ?: "Waiting for the reset",
                    style = Fleet.type.textXs,
                    color = o.fgMuted,
                )
                else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.canSwitchAccount) {
                        OutlinedButton(onClick = host.onProposeSwitch, enabled = enabled, modifier = Modifier.testTag(LIMIT_SWITCH_TAG)) {
                            Text("Switch account")
                        }
                    }
                    val resets = host.limitResetsAt
                    waitLabel(resets, state.nowSeconds)?.let { label ->
                        TextButton(onClick = { if (resets != null) host.onWait(resets) }, modifier = Modifier.testTag(LIMIT_WAIT_TAG)) { Text(label) }
                    }
                }
            }
        }
    }
}

/** Send later's "when" (MobileSessionExtras, gap plan G5.5 on claude-fleet G1.8). */
enum class SendLaterWhen(val label: String, val sub: String) {
    Idle("When it is next idle", "never into a question"),
    InAnHour("In 1 hour", "then when it is idle"),
    Tomorrow("Tomorrow 09:00", "then when it is idle"),
    LimitReset("When its usage limit resets", "held while its account is at the limit"),
    At("At a time…", "today, or tomorrow once that time has passed"),
}

/**
 * The choices this hub and session allow: "when idle" always; the clock
 * times where `queue_prompt` takes `not_before`; the limit wait where it
 * takes `until_limit_reset` and the session runs on a known account.
 */
fun sendLaterChoices(at: Boolean, afterLimit: Boolean, hasAccount: Boolean): List<SendLaterWhen> = buildList {
    add(SendLaterWhen.Idle)
    if (at) {
        add(SendLaterWhen.InAnHour)
        add(SendLaterWhen.Tomorrow)
    }
    if (afterLimit && hasAccount) add(SendLaterWhen.LimitReset)
    if (at) add(SendLaterWhen.At)
}

/** "9", "09:30", "9.30" or "21:05" as (hour, minute); null for anything else. */
fun parseClock(text: String): Pair<Int, Int>? {
    val m = Regex("""^\s*(\d{1,2})(?:[:.](\d{2}))?\s*$""").find(text) ?: return null
    val h = m.groupValues[1].toInt()
    val min = m.groupValues[2].ifEmpty { "0" }.toInt()
    return if (h in 0..23 && min in 0..59) h to min else null
}

private const val DAY_S = 86_400L

/** The next local [hour]:[minute] after [now]: today's, or tomorrow's once today's has passed. */
fun nextLocalClock(now: Long, utcOffset: (Long) -> Int, hour: Int, minute: Int): Long {
    val today = localMidnight(now, utcOffset(now)) + hour * 3_600L + minute * 60L
    return if (today > now) today else today + DAY_S
}

/** Tomorrow's local 09:00. */
fun tomorrowMorning(now: Long, utcOffset: (Long) -> Int): Long =
    localMidnight(now, utcOffset(now)) + DAY_S + 9 * 3_600L

/**
 * What a choice sends: the time it holds the prompt until, or the limit
 * wait, and skip-if-archived when ticked. Null for At… with a time that
 * does not read.
 */
fun sendLaterTiming(choice: SendLaterWhen, now: Long, utcOffset: (Long) -> Int, clock: String, skipIfArchived: Boolean): SendLaterTiming? {
    val base = SendLaterTiming(skipIfArchived = skipIfArchived)
    return when (choice) {
        SendLaterWhen.Idle -> base
        SendLaterWhen.InAnHour -> base.copy(notBefore = now + 3_600L)
        SendLaterWhen.Tomorrow -> base.copy(notBefore = tomorrowMorning(now, utcOffset))
        SendLaterWhen.LimitReset -> base.copy(untilLimitReset = true)
        SendLaterWhen.At -> parseClock(clock)?.let { (h, m) -> base.copy(notBefore = nextLocalClock(now, utcOffset, h, m)) }
    }
}

/**
 * When a waiting prompt goes, in words: "at 14:30", "tomorrow 09:00",
 * "in 3 d", "after the limit resets", or "when idle"; "· skipped if
 * archived" when it would be.
 */
fun queuedWhen(q: QueuedPrompt, now: Long, utcOffset: (Long) -> Int): String {
    val time = q.notBefore?.takeIf { it > now }?.let { t ->
        val midnight = localMidnight(now, utcOffset(now))
        val clock = clockLabel(t, utcOffset(t))
        when {
            t < midnight + DAY_S -> "at $clock"
            t < midnight + 2 * DAY_S -> "tomorrow $clock"
            else -> relativeWithin(t, now)?.let { "in $it" } ?: "at $clock"
        }
    }
    val wait = listOfNotNull(time, "after the limit resets".takeIf { q.untilLimitReset }).joinToString(", ").ifEmpty { "when idle" }
    return if (q.skipIfArchived) "$wait · skipped if archived" else wait
}

const val SEND_LATER_WHEN_TAG = "session.sendLater.when."
const val SEND_LATER_AT_TAG = "session.sendLater.at"
const val SEND_LATER_SKIP_TAG = "session.sendLater.skip"

/**
 * Send later (redesign 14.14): a prompt the hub keeps and types as a new turn
 * — never into a dialog, and whether or not this phone is still running.
 * claude-fleet's deferred prompts (5.10) hold it; since G1.8 the hub also
 * holds it until a time, or until the session's account is under its usage
 * limit again, and can drop it if the session is archived first. A hub
 * without those offers "when idle" only. Lists what is still waiting, each
 * with when it goes and Take back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SendLaterSheet(
    state: SessionUiState,
    host: SessionLaterHost,
    onDismiss: () -> Unit,
    now: () -> Long = { epochSeconds() },
    utcOffset: (Long) -> Int = { utcOffsetSeconds(it) },
) {
    var text by remember { mutableStateOf("") }
    var choice by remember { mutableStateOf(SendLaterWhen.Idle) }
    var clock by remember { mutableStateOf("") }
    var skip by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { host.onLoadQueued() }
    val o = Fleet.colors
    val choices = sendLaterChoices(
        at = state.sendLaterAtAvailable,
        afterLimit = state.sendLaterAfterLimitAvailable,
        hasAccount = state.session?.accountUuid != null,
    )
    val clockOk = choice != SendLaterWhen.At || parseClock(clock) != null
    BottomSheet(
        title = "Send later",
        meta = "It goes in as a new turn when its time comes and the session is idle; never into a question.",
        onDismiss = {
            host.onDismissNotice()
            onDismiss()
        },
        primary = SheetAction(
            label = when {
                text.isBlank() -> "Write the prompt"
                choice == SendLaterWhen.Idle -> "Send when idle"
                else -> "Schedule"
            },
            enabled = text.isNotBlank() && clockOk && state.canSendLater && !state.busy,
        ) {
            val timing = sendLaterTiming(choice, now(), utcOffset, clock, skip && state.sendLaterSkipAvailable)
            if (timing != null) {
                host.onSendLater(text, timing)
                text = ""
            }
        },
        scrollable = true,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Prompt") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth().testTag(SEND_LATER_FIELD_TAG),
        )
        if (choices.size > 1) {
            for (c in choices) {
                SheetOption(
                    title = c.label,
                    sub = c.sub,
                    selected = c == choice,
                    onSelect = { choice = c },
                    modifier = Modifier.testTag(SEND_LATER_WHEN_TAG + c.name),
                )
            }
            if (choice == SendLaterWhen.At) {
                OutlinedTextField(
                    value = clock,
                    onValueChange = { clock = it },
                    singleLine = true,
                    label = { Text("Time (HH:MM)") },
                    isError = clock.isNotBlank() && parseClock(clock) == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag(SEND_LATER_AT_TAG),
                )
            }
        }
        if (state.sendLaterSkipAvailable) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = skip, role = Role.Checkbox, onValueChange = { skip = it })
                    .testTag(SEND_LATER_SKIP_TAG),
            ) {
                Checkbox(checked = skip, onCheckedChange = null)
                Text("Skip it if the session is archived first", style = Fleet.type.textSm, color = o.fg)
            }
        }
        state.sendLaterNotice?.let {
            Text(it, style = Fleet.type.textSm, color = o.fg, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (state.queued.isNotEmpty()) {
            Text("Waiting · ${state.queued.size}", style = Fleet.type.textXs, color = o.fgMuted)
            val at = now()
            for (q in state.queued) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(q.body, style = Fleet.type.textSm, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(queuedWhen(q, at, utcOffset), style = Fleet.type.textXs, color = o.fgMuted)
                    }
                    TextButton(onClick = { host.onCancelQueued(q.id) }, enabled = state.connected) { Text("Take back") }
                }
            }
        }
    }
}

/**
 * Details › Switch account… (gap plan G5.5): the logins on the session's
 * host other than the one it runs under, read from `check_account_headroom`.
 * [picked] starts on the hub's suggestion only when the session is over the
 * line; nothing restarts until Switch.
 */
data class AccountSwitch(
    val loading: Boolean = false,
    val logins: List<HostLogin> = emptyList(),
    val picked: HostLogin? = null,
    val notice: String? = null,
)

/** Every login on the host but the one the session runs under ([currentProfile], null = the host's own). */
fun otherLogins(h: Headroom, currentProfile: String?): List<HostLogin> =
    h.logins.filter { it.profile != currentProfile }

const val SWITCH_ACCOUNT_SHEET_TAG = "session.switchAccount"

@Composable
internal fun SwitchAccountSheet(
    switch: AccountSwitch,
    session: SessionRow?,
    busy: Boolean,
    connected: Boolean,
    accountName: (String) -> String?,
    onPick: (HostLogin) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val o = Fleet.colors
    BottomSheet(
        title = "Switch account",
        meta = "Restarts the agent in ${session?.tmuxName ?: "this session"} under another login and resumes the same conversation.",
        onDismiss = onDismiss,
        modifier = Modifier.testTag(SWITCH_ACCOUNT_SHEET_TAG),
        primary = SheetAction("Switch", enabled = switch.picked != null && !busy && connected, onClick = onConfirm),
        scrollable = true,
    ) {
        if (switch.loading) Text("Reading the logins on ${session?.hostAlias ?: "the host"}…", style = Fleet.type.textSm, color = o.fgMuted)
        switch.notice?.let { Text(it, style = Fleet.type.textSm, color = o.fg) }
        for (l in switch.logins) {
            SheetOption(
                title = loginLabel(l, accountName),
                sub = usedText(l),
                selected = l == switch.picked,
                onSelect = { onPick(l) },
            )
        }
    }
}
