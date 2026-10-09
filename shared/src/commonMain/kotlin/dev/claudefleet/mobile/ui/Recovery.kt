package dev.claudefleet.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.model.ConvTurn
import dev.claudefleet.mobile.model.RepairReport
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.OrbitTokens

/*
 * Recovery on the New bar (redesign 14.5, the MobileRecovery board): no dead
 * ends. A failed session says why in plain words and offers Retry, Repair and
 * Details in the conversation rather than behind ⋮; a prompt the hub did not
 * take stays where it was written, marked, with Retry and Edit; a repair's
 * result sits in the conversation with real next steps instead of ending in
 * OK. The Classic bar keeps its banner, its dialog and its draft-back rule.
 */

/** A prompt the hub did not take: the words, kept, and why. */
data class NotSent(val text: String, val why: Friendly)

/** What a failed session's card says and offers; see [failedSession]. */
data class FailedSession(
    /** The cause in plain words. */
    val cause: String,
    /** The tool that failed last in the latest turn, as its row reads; null when none did. */
    val failedStep: String?,
    /** The prompt "Retry the last turn" sends again; null when there is none to send. */
    val retryPrompt: String?,
)

/**
 * The failed-session card for [row], or null when the session has not failed.
 * The cause is the row's own activity line when it has one (the hub's
 * sanitised last word on the session), else a plain sentence; the step that
 * failed is the latest turn's last tool row with an error.
 */
fun failedSession(row: SessionRow?, turns: List<ConvTurn>): FailedSession? {
    if (row == null || row.claudeStatus != "failed") return null
    val last = turns.lastOrNull()
    val step = last?.items?.filterIsInstance<ConvItem.Tool>()?.lastOrNull { it.error }?.summary
    return FailedSession(
        cause = row.currentActivity?.trim()?.takeIf { it.isNotEmpty() } ?: "Claude Code stopped with an error on the last turn.",
        failedStep = step,
        retryPrompt = turns.lastOrNull { !it.prompt.isNullOrBlank() }?.prompt ?: row.lastPrompt?.takeIf { it.isNotBlank() },
    )
}

/**
 * What a session screen does once it opens, asked from outside it: an Inbox
 * failed row's Open log (the agent's own screen) or Retry (the last turn's
 * prompt again, through the screen's own [SessionViewModel.retryLastTurn]
 * so its checks and its Not sent card apply). Taken once.
 */
sealed interface SessionIntent {
    val sessionId: Long

    data class OpenLog(override val sessionId: Long) : SessionIntent

    data class RetryLastTurn(override val sessionId: Long, val prompt: String) : SessionIntent
}

/** The words a failed session's quick replies change to (MobileRecovery): Retry and Show the error. */
internal val FAILED_QUICK_REPLIES = listOf("Retry", "Show the error")

/** Send's label on the New bar: while Claude works a message waits its turn, so the button says Queue. */
fun sendLabel(working: Boolean): String = if (working) "Queue" else "Send"

/** The "Not sent" line's headline: the hub's own short reason after the words. */
fun notSentHeadline(n: NotSent): String = "Not sent: " + n.why.title.replaceFirstChar { it.lowercase() }

/** What the repair did, as ticked lines; a report that did nothing says so. */
fun repairDone(report: RepairReport): List<String> =
    report.actions.ifEmpty { if (report.healthy) listOf("Nothing needed repairing") else emptyList() }

/** What the repair left for a person: what it deferred, then its warnings. */
fun repairLeft(report: RepairReport): List<String> = report.deferred + report.warnings

internal const val NOT_SENT_TAG = "recovery.notSent"
internal const val FAILED_TAG = "recovery.failed"
internal const val REPAIRED_TAG = "recovery.repaired"
const val FAILED_DETAILS_TAG = "recovery.failed.details"

@Composable
private fun RecoverySurface(tone: RecoveryTone, tag: String, modifier: Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp)
    val (fill, line) = when (tone) {
        RecoveryTone.Failed -> Fleet.colors.failedSoft to Fleet.colors.failedLine
        RecoveryTone.Done -> Fleet.colors.doneSoft to Fleet.colors.border
    }
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag(tag),
        color = fill,
        contentColor = Fleet.colors.fg,
        shape = shape,
        border = BorderStroke(1.dp, line),
    ) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) { content() }
    }
}

private enum class RecoveryTone { Failed, Done }

/**
 * The failed-session card: the failed step, the cause, and the next steps —
 * Retry the last turn, Repair session, Details. Retry and Repair are left out
 * where they cannot run (no prompt to send again, a hub without repair, a
 * read-only pairing); Details always opens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FailedSessionCard(
    failed: FailedSession,
    canWrite: Boolean,
    onRetry: (() -> Unit)?,
    onRepair: (() -> Unit)?,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RecoverySurface(RecoveryTone.Failed, FAILED_TAG, modifier) {
        failed.failedStep?.let {
            Text("⊗ $it · failed", style = Fleet.type.code, color = Fleet.colors.statusFailed, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
        }
        Text(failed.cause, style = Fleet.type.textLg)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (onRetry != null && failed.retryPrompt != null) {
                OutlinedButton(onClick = onRetry, enabled = canWrite, modifier = Modifier.heightIn(min = OrbitTokens.spacing("touch-min").dp)) {
                    Text("Retry the last turn")
                }
            }
            if (onRepair != null) {
                OutlinedButton(onClick = onRepair, enabled = canWrite, modifier = Modifier.heightIn(min = OrbitTokens.spacing("touch-min").dp)) {
                    Text("Repair session")
                }
            }
            TextButton(onClick = onDetails, modifier = Modifier.heightIn(min = OrbitTokens.spacing("touch-min").dp).testTag(FAILED_DETAILS_TAG)) { Text("Details") }
        }
    }
}

/**
 * A prompt the hub did not take, kept in the conversation where it was
 * written: the words, "Not sent: why", Retry (sends the same words again) and
 * Edit (puts them back in the box). Never Dismiss alone: the words are not
 * thrown away by this card.
 */
@Composable
internal fun NotSentCard(
    notSent: NotSent,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RecoverySurface(RecoveryTone.Failed, NOT_SENT_TAG, modifier) {
        Text(notSent.text, style = Fleet.type.textMd, maxLines = 6, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(notSentHeadline(notSent), style = Fleet.type.textSm, color = Fleet.colors.statusFailed, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry, enabled = canRetry) { Text("Retry") }
            TextButton(onClick = onEdit) { Text("Edit") }
        }
        if (notSent.why.body.isNotBlank()) {
            Text(notSent.why.body, style = Fleet.type.textXs, color = Fleet.colors.fgMuted)
        }
    }
}

/**
 * A repair's result in the conversation: what it did, ticked; what still
 * needs a person; and real next steps — Show changes (the Files tab) and Ask
 * Claude Code to commit (into the box, not sent). Nothing is committed until
 * the person asks. Done closes it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RepairResultCard(
    report: RepairReport,
    agent: String,
    onShowChanges: (() -> Unit)?,
    onAskToCommit: (() -> Unit)?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val left = repairLeft(report)
    RecoverySurface(if (left.isEmpty()) RecoveryTone.Done else RecoveryTone.Failed, REPAIRED_TAG, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (report.healthy || left.isEmpty()) "Repaired" else "Repaired, with something left", style = Fleet.type.textLg, modifier = Modifier.weight(1f))
            TextButton(onClick = onDone) { Text("Done") }
        }
        for (line in repairDone(report)) {
            Text("✓  $line", style = Fleet.type.textMd, color = Fleet.colors.fg2)
        }
        if (left.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Still needs you", style = Fleet.type.textSm, color = Fleet.colors.fgMuted)
            for (line in left) Text("•  $line", style = Fleet.type.textMd)
        }
        if (onShowChanges != null || onAskToCommit != null) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                onShowChanges?.let {
                    OutlinedButton(onClick = it, modifier = Modifier.heightIn(min = OrbitTokens.spacing("touch-min").dp)) { Text("Show changes") }
                }
                onAskToCommit?.let {
                    OutlinedButton(onClick = it, modifier = Modifier.heightIn(min = OrbitTokens.spacing("touch-min").dp)) { Text("Ask $agent to commit") }
                }
            }
        }
    }
}
