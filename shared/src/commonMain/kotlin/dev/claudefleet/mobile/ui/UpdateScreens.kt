package dev.claudefleet.mobile.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.kit.MarkMotion
import dev.claudefleet.mobile.ui.kit.OrbitMark
import dev.claudefleet.mobile.ui.kit.OrbitMarkLoader
import dev.claudefleet.mobile.ui.kit.ProgressRing
import dev.claudefleet.mobile.ui.kit.megabytes
import dev.claudefleet.mobile.ui.kit.reducedMotion
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.theme.OrbitTokens
import dev.claudefleet.mobile.update.HubMismatch
import dev.claudefleet.mobile.update.ReleaseInfo
import dev.claudefleet.mobile.update.UpdatePhase
import dev.claudefleet.mobile.update.UpdateState
import dev.claudefleet.mobile.update.WhatsNew

/** "31 MB · you have 0.9.4": the card's second line. */
internal fun updateMeta(release: ReleaseInfo, appVersion: String): String =
    listOfNotNull(
        megabytes(release.sizeBytes, 0).takeIf { release.sizeBytes > 0 },
        "you have $appVersion",
    ).joinToString(" · ")

/**
 * Update ready (MobileUpdate): a card at the top of More, with what changed
 * in plain words. Download opens the Updating screen; nothing installs from
 * here.
 */
@Composable
fun UpdateCard(release: ReleaseInfo, appVersion: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val o = Fleet.colors
    val shape = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp)
    val gutter = OrbitTokens.spacing("phone-gutter").dp
    val uri = LocalUriHandler.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = gutter, vertical = 8.dp)
            .background(o.bgPane, shape)
            .border(1.dp, o.accent, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Orbit Fleet ${release.version} is ready", color = o.fg, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(updateMeta(release, appVersion), color = o.fgMuted, fontSize = 13.sp, lineHeight = 18.sp)
        for (note in release.notes.take(3)) {
            Text("· $note", color = o.fg2, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onOpen,
                modifier = Modifier.heightIn(min = OrbitTokens.spacing("touch-min").dp),
                colors = ButtonDefaults.buttonColors(containerColor = o.accent, contentColor = o.bg),
            ) { Text("Download", fontSize = 15.sp) }
            if (release.pageUrl.isNotBlank()) {
                TextButton(onClick = { uri.openUri(release.pageUrl) }) { Text("Full notes", color = o.accent, fontSize = 15.sp) }
            }
        }
    }
}

/** The one Inbox line for an update: it does not need you, so it is not a Needs you row. */
@Composable
fun UpdateInboxLine(release: ReleaseInfo, onOpen: () -> Unit) {
    val o = Fleet.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .heightIn(min = OrbitTokens.spacing("touch-min").dp)
            .padding(horizontal = OrbitTokens.spacing("phone-gutter").dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OrbitMark(20.dp)
        Text("Update ready · Orbit Fleet ${release.version}", color = o.fg, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text("›", color = o.fgMuted, fontSize = 18.sp)
    }
}

/** Every tap on the Updating screen. */
class UpdateHandlers(
    val onBack: () -> Unit = {},
    val onDownload: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onInstall: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/** The checklist under the ring: download, signature, Android. */
internal fun updateSteps(phase: UpdatePhase): List<Pair<String, Boolean?>> {
    // true done, false running, null not yet.
    val (dl, sig) = when (phase) {
        is UpdatePhase.Downloading, is UpdatePhase.Paused -> false to null
        UpdatePhase.Checking -> true to false
        is UpdatePhase.Ready, UpdatePhase.Handed -> true to true
        else -> null to null
    }
    return listOf(
        "Download" to dl,
        "Check the signature" to sig,
        "Hand it to Android to install" to (if (phase == UpdatePhase.Handed) false else null),
    )
}

/**
 * Updating (MobileUpdate): the Progress ring with the real size, then the
 * signature, then what Android will ask. Sessions keep running and the
 * person can leave at any point; the hub is never touched.
 */
@Composable
fun UpdateScreen(state: UpdateState, appVersion: String, handlers: UpdateHandlers, modifier: Modifier = Modifier) {
    val o = Fleet.colors
    val release = state.available
    val gutter = OrbitTokens.spacing("phone-gutter").dp
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Updating",
            subtitle = release?.let { "Orbit Fleet ${it.version}" },
            navigation = { IconButton(onClick = handlers.onBack) { Icon(FleetIcons.ArrowBack, contentDescription = "Back") } },
        )
        if (release == null) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("This app is up to date ($appVersion).", color = o.fgMuted, fontSize = 15.sp)
            }
            return@Column
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = gutter, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (val phase = state.phase) {
                UpdatePhase.Idle -> {
                    Text(updateMeta(release, appVersion), color = o.fgMuted, fontSize = 14.sp)
                    for (note in release.notes.take(5)) Text("· $note", color = o.fg2, fontSize = 15.sp, modifier = Modifier.fillMaxWidth())
                    PrimaryButton("Download ${release.version}", handlers.onDownload)
                }
                is UpdatePhase.Downloading, is UpdatePhase.Paused -> {
                    val (done, total) = when (phase) {
                        is UpdatePhase.Downloading -> phase.done to phase.total
                        is UpdatePhase.Paused -> phase.done to phase.total
                        else -> 0L to 0L
                    }
                    ProgressRing(
                        fraction = if (total > 0) done.toFloat() / total else 0f,
                        label = megabytes(done, total),
                        detail = if (phase is UpdatePhase.Paused) "Paused" else "From GitHub releases",
                    )
                    Steps(phase)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (phase is UpdatePhase.Paused) {
                            PrimaryButton("Resume", handlers.onDownload)
                        } else {
                            QuietOutline("Pause", handlers.onPause)
                        }
                        QuietOutline("Cancel", handlers.onCancel)
                    }
                    Note("Sessions keep running; you can leave this screen.")
                }
                UpdatePhase.Checking -> {
                    OrbitMarkLoader(MarkMotion.Orbit, size = 48.dp)
                    Steps(phase)
                }
                is UpdatePhase.Ready -> {
                    Text("Ready to install", color = o.fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("✓ Signature matches ${phase.signer}'s release key", color = o.statusDone, fontSize = 15.sp, textAlign = TextAlign.Center)
                    Note(
                        "Android will ask you to confirm the install. The app closes for a few seconds and opens again. " +
                            "Nothing on the hub changes.",
                    )
                    if (phase.needsPermission) {
                        Callout(
                            "First time only",
                            "Allow Orbit Fleet to install updates in Android settings. Install takes you there; come back and tap it again.",
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuietOutline("Later", handlers.onBack)
                        PrimaryButton("Install ${release.version}", handlers.onInstall)
                    }
                }
                is UpdatePhase.Refused -> {
                    Callout("Not installed", phase.reason + " The download was deleted; this app was not changed.")
                    QuietOutline("Back", handlers.onRetry)
                }
                is UpdatePhase.Failed -> {
                    Callout("The download stopped", phase.message)
                    PrimaryButton("Try again", handlers.onRetry)
                }
                UpdatePhase.Handed -> {
                    OrbitMarkLoader(MarkMotion.Orbit, size = 48.dp)
                    Steps(phase)
                    Note("Confirm in Android's dialog. If you closed it, tap Install again from More.")
                }
            }
        }
    }
}

@Composable
private fun Steps(phase: UpdatePhase) {
    val o = Fleet.colors
    Column(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((label, state) in updateSteps(phase)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(22.dp)) {
                    when (state) {
                        true -> Text("✓", color = o.statusDone, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        false -> OrbitMarkLoader(MarkMotion.Orbit, size = 16.dp)
                        null -> Text("○", color = o.fgMuted, fontSize = 15.sp)
                    }
                }
                Text(label, color = if (state == null) o.fgMuted else o.fg, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    val o = Fleet.colors
    Button(
        onClick = onClick,
        modifier = Modifier.heightIn(min = OrbitTokens.spacing("touch-min").dp),
        colors = ButtonDefaults.buttonColors(containerColor = o.accent, contentColor = o.bg),
    ) { Text(label, fontSize = 15.sp) }
}

@Composable
private fun QuietOutline(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = OrbitTokens.spacing("touch-min").dp)) {
        Text(label, color = Fleet.colors.fg2, fontSize = 15.sp)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = Fleet.colors.fgMuted, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
}

@Composable
private fun Callout(title: String, body: String) {
    val o = Fleet.colors
    val shape = RoundedCornerShape(OrbitTokens.radius("radius-md").dp)
    Column(
        modifier = Modifier.fillMaxWidth().background(o.waitingFaint, shape).border(1.dp, o.waitingLine, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, color = o.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(body, color = o.fgMuted, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

/**
 * After an update, once: the wordmark reveal, then up to three things that
 * changed and a way back to work. With reduced motion the wordmark is simply
 * there.
 */
@Composable
fun WhatsNewScreen(whatsNew: WhatsNew, backLabel: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val o = Fleet.colors
    val reduced = reducedMotion()
    val reveal = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) reveal.animateTo(1f, tween(durationMillis = 1_200)) }
    Column(
        modifier = modifier.fillMaxSize().background(o.bg).padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OrbitMark(72.dp, Modifier.alpha(reveal.value))
        Spacer(Modifier.height(14.dp))
        val word = "Orbit Fleet"
        val shown = (word.length * reveal.value).toInt().coerceIn(0, word.length)
        Text(
            word.take(shown).padEnd(word.length, ' '),
            color = o.fg,
            fontSize = 26.sp,
            letterSpacing = 4.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text("Updated to ${whatsNew.version}", color = o.fgMuted, fontSize = 15.sp, modifier = Modifier.alpha(reveal.value))
        Spacer(Modifier.height(20.dp))
        Column(Modifier.fillMaxWidth().alpha(reveal.value), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (note in whatsNew.notes) Text("· $note", color = o.fg2, fontSize = 15.sp, lineHeight = 21.sp)
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton(backLabel, onBack)
    }
}

/** The banner's words when the hub and this app run different releases. */
internal fun hubMismatchWords(m: HubMismatch): Pair<String, String> = when (m) {
    is HubMismatch.HubOlder ->
        "The hub runs ${m.hub}, this app ${m.app}" to
            "What needs the newer hub stays off until its owner updates it from the desktop."
    is HubMismatch.HubNewer ->
        "The hub runs ${m.hub}, newer than this app (${m.app})" to
            "Update this app to use what the hub added."
}

/** One amber banner at the top of the Inbox when the hub and the app disagree on a release. */
@Composable
fun HubVersionBanner(mismatch: HubMismatch, onUpdate: (() -> Unit)?, modifier: Modifier = Modifier) {
    val o = Fleet.colors
    val shape = RoundedCornerShape(OrbitTokens.radius("radius-md").dp)
    val (title, line) = hubMismatchWords(mismatch)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitTokens.spacing("phone-gutter").dp, vertical = 8.dp)
            .background(o.waitingFaint, shape)
            .border(1.dp, o.waitingLine, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = o.fg, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(line, color = o.fgMuted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        if (mismatch is HubMismatch.HubNewer && onUpdate != null) {
            TextButton(onClick = onUpdate) { Text("Update", color = o.accent, fontSize = 15.sp) }
        }
    }
}
