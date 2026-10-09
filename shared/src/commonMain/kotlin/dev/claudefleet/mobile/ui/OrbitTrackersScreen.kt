package dev.claudefleet.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.model.TrackerAdminRow
import dev.claudefleet.mobile.model.relativeAgo
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.kit.BottomSheet
import dev.claudefleet.mobile.ui.kit.DotWave
import dev.claudefleet.mobile.ui.kit.PhoneRow
import dev.claudefleet.mobile.ui.kit.SheetAction
import dev.claudefleet.mobile.ui.kit.SheetOption
import dev.claudefleet.mobile.ui.kit.Sonar
import dev.claudefleet.mobile.ui.kit.StepBars
import dev.claudefleet.mobile.ui.kit.StatusWord
import dev.claudefleet.mobile.ui.kit.rememberLoaderVisible
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.theme.OrbitTokens
import dev.claudefleet.mobile.ui.kit.LoadFailed

/*
 * The hub's trackers on the phone (redesign 14.20, board MobileWizards
 * "Connect a tracker"): each tracker with its state in words, Test, Sign in
 * again and Remove, and Connect a tracker as a two-step wizard. The sign-in
 * goes to the hub, which keeps it encrypted; the phone never stores it and
 * the hub never sends it back. Which org a tracker belongs to stays on the
 * desktop: the hub lets only its master assign one.
 */

data class OrbitTrackersHandlers(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onConnect: () -> Unit = {},
    val onTest: (TrackerAdminRow) -> Unit = {},
    val onSignInAgain: (TrackerAdminRow) -> Unit = {},
    val onRemove: (TrackerAdminRow) -> Unit = {},
    val onConfirmRemove: () -> Unit = {},
    val onCancelRemove: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
    val wizard: TrackerWizardHandlers = TrackerWizardHandlers(),
)

data class TrackerWizardHandlers(
    val onSite: (String) -> Unit = {},
    val onProvider: (String?) -> Unit = {},
    val onEmail: (String) -> Unit = {},
    val onSecret: (String) -> Unit = {},
    val onNext: () -> Unit = {},
    val onConnect: () -> Unit = {},
    val onBack: () -> Unit = {},
    /** Close, asking first when something was typed. */
    val onClose: () -> Unit = {},
    val onKeepEditing: () -> Unit = {},
    /** Close without asking: Discard on the ask. */
    val onDiscard: () -> Unit = {},
)

/** A tracker's state as a row's word: the colour and the word together. */
internal fun trackerWord(state: String): StatusWord = when (state) {
    "ok" -> StatusWord.DONE
    "rate_limited" -> StatusWord.PAUSED
    "unconfigured" -> StatusWord.IDLE
    else -> StatusWord.FAILED
}

/** "2 connected · 1 needs a sign-in". */
internal fun trackersHeadline(rows: List<TrackerAdminRow>): String = buildList {
    val ok = rows.count { it.state == "ok" }
    val rest = rows.size - ok
    if (ok > 0) add("$ok connected")
    if (rest > 0) add("$rest ${if (rest == 1) "needs" else "need"} a look")
}.joinToString(" · ").ifEmpty { "No trackers yet" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrbitTrackersScreen(
    state: TrackersUiState,
    nowSeconds: Long,
    handlers: OrbitTrackersHandlers,
    modifier: Modifier = Modifier,
) {
    val wizard = state.wizard
    if (wizard != null) {
        TrackerWizardScreen(wizard, state.error, handlers.wizard, handlers.onDismissError, modifier)
        return
    }
    val o = Fleet.colors
    var picked by remember { mutableStateOf<TrackerAdminRow?>(null) }
    Column(modifier = modifier.fillMaxSize().background(o.bg)) {
        ScreenHeader(
            title = "Trackers",
            subtitle = if (state.trackers.isEmpty() && state.loading) null else trackersHeadline(state.trackers),
            navigation = {
                IconButton(onClick = handlers.onBack) { Icon(FleetIcons.ArrowBack, contentDescription = "Back") }
            },
        )
        // A list that never loaded: Retry runs the read again. An action's
        // failure (Test, Remove) is not retried from here — that is its row's.
        val listFailed = !state.loaded && !state.loading && state.error != null
        ErrorBanner(state.error, onDismiss = handlers.onDismissError, onRetry = handlers.onRefresh.takeIf { listFailed })
        state.notice?.let {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = gutter(), vertical = 4.dp)) {
                Text(it, color = o.fg2, fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = handlers.onDismissNotice) { Text("OK", color = o.accent) }
            }
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.loading && state.trackers.isEmpty()) {
                item(key = "loading") {
                    if (rememberLoaderVisible(true)) DotWave(modifier = Modifier.padding(horizontal = gutter(), vertical = 24.dp))
                }
            } else if (listFailed && state.trackers.isEmpty()) {
                item(key = "failed") { LoadFailed("trackers", state.error?.body, handlers.onRefresh) }
            } else if (state.loaded && state.trackers.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No trackers yet. Connect Jira, GitHub, Linear or Asana and their tickets show up as work.",
                        color = o.fgMuted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = gutter(), vertical = 24.dp),
                    )
                }
            }
            items(state.trackers, key = { "t-${it.id}" }) { row ->
                PhoneRow(
                    title = row.name.ifBlank { providerLabel(row.provider) },
                    line = trackerLine(row, relativeAgo(row.lastSyncAt, nowSeconds)),
                    word = trackerWord(row.state),
                    lead = trackerStateWord(row.state),
                    onClick = { picked = row },
                )
            }
            item(key = "actions") {
                Column(modifier = Modifier.padding(horizontal = gutter(), vertical = 12.dp)) {
                    TextButton(onClick = handlers.onConnect) { Text("Connect a tracker…", color = o.accent) }
                }
            }
        }
    }
    picked?.let { row ->
        BottomSheet(
            title = row.name.ifBlank { providerLabel(row.provider) },
            meta = listOfNotNull(providerLabel(row.provider), trackerStateWord(row.state), row.credentialHint?.let { "sign-in $it" })
                .joinToString(" · "),
            onDismiss = { picked = null },
            cancelLabel = "Close",
        ) {
            row.lastError?.takeIf { it.isNotBlank() && row.state != "ok" }?.let { Text(it, color = o.fgMuted, fontSize = 13.sp) }
            TextButton(onClick = { picked = null; handlers.onTest(row) }, enabled = state.busy == null) {
                Text("Test the sign-in", color = o.accent)
            }
            TextButton(onClick = { picked = null; handlers.onSignInAgain(row) }) { Text("Sign in again…", color = o.accent) }
            TextButton(onClick = { picked = null; handlers.onRemove(row) }, enabled = state.busy == null) {
                Text("Disconnect…", color = o.statusFailed)
            }
        }
    }
    state.removing?.let { row ->
        BottomSheet(
            title = "Disconnect ${row.name.ifBlank { providerLabel(row.provider) }}?",
            meta = "The hub stops syncing it and forgets its sign-in. Its work items stay, and you can connect it again.",
            onDismiss = handlers.onCancelRemove,
            primary = SheetAction("Disconnect", enabled = state.busy == null) { handlers.onConfirmRemove() },
        ) {
            Text(row.siteUrl, color = o.fgMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun TrackerWizardScreen(
    w: TrackerWizard,
    error: Friendly?,
    handlers: TrackerWizardHandlers,
    onDismissError: () -> Unit,
    modifier: Modifier,
) {
    val signingInAgain = w.created != null && w.step == TrackerStep.SignIn
    Column(modifier = modifier.fillMaxSize().background(Fleet.colors.bg)) {
        ScreenHeader(
            title = if (signingInAgain) "Sign in again" else "Connect a tracker",
            subtitle = trackerStepHeading(w.step),
            navigation = {
                IconButton(onClick = handlers.onBack, enabled = w.working == null) { Icon(FleetIcons.ArrowBack, contentDescription = "Back") }
            },
        )
        StepBars(step = w.step.ordinal + 1, total = TrackerStep.entries.size)
        ErrorBanner(error, onDismiss = onDismissError)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (w.step) {
                TrackerStep.Site -> SiteStep(w, handlers)
                TrackerStep.SignIn -> SignInStep(w, handlers)
            }
        }
        val site = w.step == TrackerStep.Site
        val blocker = if (site) siteBlocker(w) else signInBlocker(w)
        WizardFooter(
            caption = when {
                w.working != null -> null
                site -> blocker.takeIf { w.siteUrl.isNotBlank() }
                else -> blocker
            },
            backLabel = if (site || w.created != null) "Cancel" else "Back",
            onBackTap = { if (site || w.created != null) handlers.onClose() else handlers.onBack() },
            primaryLabel = if (site) "Next" else "Connect",
            primaryEnabled = blocker == null && w.working == null,
            onPrimary = if (site) handlers.onNext else handlers.onConnect,
            editable = w.working == null,
        )
    }
    if (w.askingClose) DiscardSheet(onKeep = handlers.onKeepEditing, onDiscard = handlers.onDiscard)
}

@Composable
private fun SiteStep(w: TrackerWizard, handlers: TrackerWizardHandlers) {
    val inferred = inferTrackerProvider(w.siteUrl)
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = gutterPadding()) {
        item(key = "site") {
            StepLabel("Site")
            OutlinedTextField(
                value = w.siteUrl,
                onValueChange = handlers.onSite,
                singleLine = true,
                label = { Text("Address") },
                placeholder = { Text("https://acme.atlassian.net") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                ),
            )
            StepHint(
                inferred?.let { "That is ${providerLabel(it)}." }
                    ?: "The tracker's address, or a link to any ticket on it.",
            )
        }
        if (inferred == null && w.siteUrl.isNotBlank()) {
            item(key = "provider-label") { StepLabel("Which tracker is it?") }
            items(TRACKER_PROVIDERS, key = { "p-$it" }) { p ->
                SheetOption(
                    title = providerLabel(p),
                    selected = w.chosenProvider == p,
                    onSelect = { handlers.onProvider(p) },
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SignInStep(w: TrackerWizard, handlers: TrackerWizardHandlers) {
    val o = Fleet.colors
    val provider = w.provider
    val editable = w.working == null
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = gutterPadding()) {
        item(key = "card") {
            Column(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth()
                    .border(1.dp, o.controlBorder, RoundedCornerShape(OrbitTokens.radius("radius-md").dp))
                    .padding(12.dp),
            ) {
                Text(providerLabel(provider), color = o.fg, style = Fleet.type.textMd)
                Text(w.siteUrl.trim().substringAfter("://").trimEnd('/'), color = o.fgMuted, style = Fleet.type.textSm)
            }
        }
        if (needsEmail(provider)) {
            item(key = "email") {
                StepLabel("Email")
                OutlinedTextField(
                    value = w.email,
                    onValueChange = handlers.onEmail,
                    enabled = editable,
                    singleLine = true,
                    label = { Text("The account's email") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Email,
                    ),
                )
            }
        }
        item(key = "secret") {
            StepLabel(tokenLabel(provider))
            OutlinedTextField(
                value = w.secret,
                onValueChange = handlers.onSecret,
                enabled = editable,
                singleLine = true,
                label = { Text(tokenLabel(provider)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            )
            StepHint(TOKEN_PROMISE)
        }
        item(key = "progress") { ConnectProgress(w) }
    }
}

/** The board's line under the token field. */
internal const val TOKEN_PROMISE = "Stored on the hub, encrypted. It never reaches an agent or a log."

@Composable
private fun ColumnScope.ConnectProgressBody(w: TrackerWizard) {
    val o = Fleet.colors
    val work = w.working
    if (work != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Sonar beside the line (the board), once the wait passes loader-delay; never full-screen in a wizard.
            if (rememberLoaderVisible(true)) Sonar(size = 28.dp)
            Text(connectingLine(work, w.provider), color = o.fg2, style = Fleet.type.textMd)
        }
    }
    w.failure?.let { Text(it, color = o.statusFailed, style = Fleet.type.textMd) }
}

@Composable
private fun ConnectProgress(w: TrackerWizard) {
    Column(modifier = Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConnectProgressBody(w)
    }
}

private fun gutter() = OrbitTokens.spacing("phone-gutter").dp
