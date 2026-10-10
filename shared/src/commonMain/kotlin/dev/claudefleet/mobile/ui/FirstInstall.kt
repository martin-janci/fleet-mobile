package dev.claudefleet.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.data.AgentInstallActions
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.AgentInstall
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.kit.BottomSheet
import dev.claudefleet.mobile.ui.kit.LoaderStep
import dev.claudefleet.mobile.ui.kit.OrbitMark
import dev.claudefleet.mobile.ui.kit.OrbitMarkLarge
import dev.claudefleet.mobile.ui.kit.PulseSequence
import dev.claudefleet.mobile.ui.kit.SheetAction
import dev.claudefleet.mobile.ui.kit.Sonar
import dev.claudefleet.mobile.ui.kit.StepState
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.theme.OrbitTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * First install on the phone (redesign 14.19, the MobileInstall board): the
 * welcome a new phone sees once, the "no hub yet" steps in place of a dead
 * end, and a host joining the fleet from Hosts — what will be installed, then
 * the hub's fleet-agent install job (claude-fleet 4.9) followed step by step,
 * Pulse while it works and Sonar until the host's first heartbeat. Nothing is
 * installed until the person taps Install agent. New layout only.
 */

// ── Welcome and no hub yet ──

/** The [Hints] key the welcome is marked shown under: once per phone. */
const val WELCOME_HINT: String = "welcome"

/**
 * Whether an unpaired phone opens on the welcome rather than on Pair: the New
 * layout, never welcomed before, and not here because it was signed out or
 * forgot a hub (Pair says why then, and the welcome would hide it).
 */
fun showWelcome(layout: PhoneLayout, welcomed: Boolean, signedOut: Boolean): Boolean =
    layout == PhoneLayout.New && !welcomed && !signedOut

/** Where an unpaired phone is on the New layout. */
enum class FirstRun { Welcome, NoHub, Pair }

/** The three promises, as the board words them. */
internal val WELCOME_PROMISES: List<Pair<String, String>> = listOf(
    "Answer when a session needs you" to "Permissions and questions, from the lock screen to the answer in two taps.",
    "See every host and session" to "What runs, what failed, what waits, across all your machines.",
    "Tell the fleet what to do" to "Control starts sessions and missions for you; nothing runs until you confirm.",
)

/** The command that runs a hub on a server, copied from the second step. */
const val HUB_SERVE_COMMAND: String = "fleet-hub serve"

/** The "no hub yet" steps as text, for the share sheet: to read on the machine that will run the hub. */
val NO_HUB_STEPS_TEXT: String = """
    Set up a Claude Fleet hub

    1. Install the desktop app on the Mac or Linux machine you work on. It can run the hub for you.
    2. Or run the hub on a server that stays on (a NAS or VPS): $HUB_SERVE_COMMAND
    3. Come back to the phone and scan: Settings → Devices → Add a phone shows the code.
""".trimIndent()

const val WELCOME_PAIR_TAG = "welcome.pair"
const val WELCOME_NO_HUB_TAG = "welcome.nohub"

/** The first thing a new phone sees on the New layout: three plain promises and the one next step. */
@Composable
fun WelcomeScreen(onPair: () -> Unit, onNoHub: () -> Unit, modifier: Modifier = Modifier) {
    val o = Fleet.colors
    val gutter = OrbitTokens.spacing("phone-gutter").dp
    val touch = OrbitTokens.spacing("touch-min").dp
    Column(modifier = modifier.fillMaxSize().background(o.bg)) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = gutter),
        ) {
            Spacer(Modifier.height(40.dp))
            OrbitMark(OrbitMarkLarge)
            Spacer(Modifier.height(20.dp))
            Text(
                "Your Claude Code sessions, in your pocket",
                style = Fleet.type.textXl,
                color = o.fg,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(20.dp))
            for ((title, line) in WELCOME_PROMISES) {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Box(Modifier.padding(top = 6.dp).size(8.dp).background(o.accent, CircleShape))
                    Column(modifier = Modifier.padding(start = 14.dp)) {
                        Text(title, style = Fleet.type.textLg, color = o.fg)
                        Text(line, style = Fleet.type.textMd, color = o.fgMuted)
                    }
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onPair,
                modifier = Modifier.fillMaxWidth().heightIn(min = touch).testTag(WELCOME_PAIR_TAG),
                shape = RoundedCornerShape(OrbitTokens.radius("radius-md").dp),
            ) { Text("Pair with your fleet") }
            TextButton(
                onClick = onNoHub,
                modifier = Modifier.fillMaxWidth().heightIn(min = touch).testTag(WELCOME_NO_HUB_TAG),
            ) { Text("I don't have a hub yet") }
        }
    }
}

/**
 * No hub yet: what used to be a dead end, as three steps — the desktop app,
 * or `fleet-hub serve` on a server, then come back and scan. The steps can be
 * sent on to the machine that will run the hub.
 */
@Composable
fun NoHubScreen(onBack: () -> Unit, onPair: () -> Unit, onShare: (String) -> Unit, modifier: Modifier = Modifier) {
    val o = Fleet.colors
    val gutter = OrbitTokens.spacing("phone-gutter").dp
    val touch = OrbitTokens.spacing("touch-min").dp
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    Column(modifier = modifier.fillMaxSize().background(o.bg)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp)) {
            IconButton(onClick = onBack) { Icon(FleetIcons.ArrowBack, contentDescription = "Back") }
        }
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = gutter)) {
            Text("Set up a hub", style = Fleet.type.textXl, color = o.fg, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text(
                "The hub is a small program on one of your machines. It talks to your hosts over SSH, and the phone talks to it.",
                style = Fleet.type.textMd,
                color = o.fgMuted,
            )
            Spacer(Modifier.height(16.dp))
            NoHubStep(1, "Install the desktop app", "On the Mac or Linux machine you work on. It can run the hub for you.")
            NoHubStep(2, "Or run the hub on a server", "A NAS or VPS that stays on.") {
                Surface(
                    color = o.bgSunk,
                    shape = RoundedCornerShape(OrbitTokens.radius("radius-md").dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                        Text(HUB_SERVE_COMMAND, style = Fleet.type.code, color = o.fg, modifier = Modifier.weight(1f))
                        IconButton(onClick = { clipboard.setText(AnnotatedString(HUB_SERVE_COMMAND)) }) {
                            Icon(FleetIcons.Copy, contentDescription = "Copy $HUB_SERVE_COMMAND")
                        }
                    }
                }
            }
            NoHubStep(3, "Come back and scan", "Settings → Devices → Add a phone shows the code.")
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onPair,
                modifier = Modifier.fillMaxWidth().heightIn(min = touch),
                shape = RoundedCornerShape(OrbitTokens.radius("radius-md").dp),
            ) { Text("I have a code, pair now") }
            TextButton(onClick = { onShare(NO_HUB_STEPS_TEXT) }, modifier = Modifier.fillMaxWidth().heightIn(min = touch)) {
                Text("Send these steps")
            }
        }
    }
}

@Composable
private fun NoHubStep(n: Int, title: String, line: String, extra: @Composable () -> Unit = {}) {
    val o = Fleet.colors
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Box(
            modifier = Modifier.size(28.dp).border(1.5.dp, o.controlBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("$n", style = Fleet.type.textSm, color = o.fg2) }
        Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
            Text(title, style = Fleet.type.textLg, color = o.fg)
            Text(line, style = Fleet.type.textMd, color = o.fgMuted)
            extra()
        }
    }
}

// ── A host joins: the fleet-agent install job ──

/**
 * The job's steps in order, as `agent_installs` names them. `tmux` checks
 * that the host has tmux and installs it when it does not (claude-fleet
 * 0.6.0, `service::agent_install::tmux_script`).
 */
internal val INSTALL_STEPS: List<String> = listOf("target", "tmux", "download", "start", "connect")

/** Where an install stands, which decides the loader: Pulse while steps run, Sonar for the heartbeat. */
enum class InstallPhase { Running, Heartbeat, Done, Failed }

fun installPhase(job: AgentInstall): InstallPhase = when {
    job.state == "done" -> InstallPhase.Done
    job.state == "failed" -> InstallPhase.Failed
    job.step == "connect" -> InstallPhase.Heartbeat
    else -> InstallPhase.Running
}

/**
 * The job's real steps as the checklist draws them: done, the one running,
 * and the ones still to come. A failed job's failing step is the one it
 * stopped on, with the hub's reason beside it. Never ticked off on a timer.
 */
fun installSteps(job: AgentInstall): List<LoaderStep> {
    val version = job.version.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
    val at = if (job.state == "done" || job.step == "done") INSTALL_STEPS.size else INSTALL_STEPS.indexOf(job.step).coerceAtLeast(0)
    return INSTALL_STEPS.mapIndexed { i, step ->
        val done = i < at
        val label = when (step) {
            "target" -> if (done) "Found the host's platform" else "Finding the host's platform"
            "tmux" -> if (done) "tmux is on the host" else "Checking for tmux, installing it if missing"
            "download" -> if (done) "Copied fleet-agent$version" else "Copying fleet-agent$version"
            "start" -> if (done) "Started the service" else "Starting the service"
            else -> if (done) "First heartbeat" else "Waiting for the first heartbeat"
        }
        val state = when {
            done -> StepState.Done
            i == at -> StepState.Running
            else -> StepState.Pending
        }
        LoaderStep(label, state, detail = if (i == at && job.state == "failed") "failed" else null)
    }
}

data class AgentInstallUiState(
    /** The host this is about; null when nothing is open. */
    val alias: String? = null,
    val host: HostRow? = null,
    /** The release the hub installs by default: its own. */
    val hubVersion: String? = null,
    /** This pairing may start the job here: the hub lists the tool to it, and the host is on SSH and answers. */
    val canInstall: Boolean = false,
    val starting: Boolean = false,
    /** The job once started (or found running); the screen follows it until it ends. */
    val job: AgentInstall? = null,
    val error: Friendly? = null,
) {
    val reviewing: Boolean get() = alias != null && job == null
    val installing: Boolean get() = job != null
}

/**
 * A host joining the fleet from the phone: [open] shows what will be
 * installed, [install] starts the hub's job — only then, on a tap — and the
 * job is read every [pollMs] until it ends. [close] stops reading; the job
 * runs on the hub either way, and the host lands in Hosts when it answers.
 */
class AgentInstallViewModel(
    private val fleet: FleetState,
    private val actions: AgentInstallActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
    private val pollMs: Long = INSTALL_POLL_MS,
) {
    private data class Local(
        val alias: String? = null,
        val starting: Boolean = false,
        val job: AgentInstall? = null,
        val error: Friendly? = null,
    )

    private val local = MutableStateFlow(Local())
    private var poll: Job? = null

    val state: StateFlow<AgentInstallUiState> =
        combine(local, fleet.hosts, fleet.capabilities, fleet.hubVersion, fleet.status) { l, hosts, caps, version, status ->
            val host = l.alias?.let { a -> hosts.firstOrNull { it.alias == a } }
            AgentInstallUiState(
                alias = l.alias,
                host = host,
                hubVersion = version,
                canInstall = canInstallOn(host, canWrite, caps.installAgent) && status is ConnectionStatus.Connected,
                starting = l.starting,
                job = l.job,
                error = l.error,
            )
        }.stateIn(scope, SharingStarted.Eagerly, AgentInstallUiState())

    /** What will be installed on [alias], before anything is. */
    fun open(alias: String) {
        poll?.cancel()
        local.value = Local(alias = alias)
    }

    /** Install agent: the one tap that starts the job. */
    fun install(): Job = scope.launch {
        val l = local.value
        val alias = l.alias ?: return@launch
        val host = fleet.hosts.value.firstOrNull { it.alias == alias }
        if (l.starting || l.job != null || !canInstallOn(host, canWrite, fleet.capabilities.value.installAgent)) return@launch
        local.update { it.copy(starting = true, error = null) }
        val job = try {
            actions.install(alias)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(starting = false, error = friendly(t)) }
            return@launch
        }
        local.update { it.copy(starting = false, job = job) }
        follow(alias, job.id)
    }

    /** Leave the screen; the job keeps running on the hub. */
    fun close() {
        poll?.cancel()
        poll = null
        local.value = Local()
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    private fun follow(alias: String, id: Long) {
        poll?.cancel()
        poll = scope.launch {
            while (local.value.job?.state == "running") {
                delay(pollMs)
                try {
                    val fresh = actions.jobs(alias).firstOrNull { it.id == id } ?: continue
                    local.update { if (it.alias == alias) it.copy(job = fresh) else it }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // A missed read is retried on the next tick; the job runs on the hub regardless.
                }
            }
        }
    }

    companion object {
        const val INSTALL_POLL_MS = 2_000L
    }
}

/** Whether the job can be started on [host]: the hub lists it to this token, and the host is on SSH and answering. */
fun canInstallOn(host: HostRow?, canWrite: Boolean, hubOffers: Boolean): Boolean =
    canWrite && hubOffers && host != null && host.reachable && host.transport == "ssh"

class AgentInstallHandlers(
    val onInstall: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onDismissError: () -> Unit = {},
)

const val INSTALL_REVIEW_TAG = "install.review"
const val INSTALLING_TAG = "install.running"

/**
 * Install the agent: what will be installed, where, with which rights, and
 * how the hub reaches the machine. Cancel or Install agent; nothing happens
 * before the tap.
 */
// The kit sheet's default `rememberModalBottomSheetState` is experimental at the call site (Kotlin/Native).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallAgentSheet(state: AgentInstallUiState, handlers: AgentInstallHandlers) {
    val alias = state.alias ?: return
    val o = Fleet.colors
    val host = state.host
    BottomSheet(
        title = "Add $alias",
        meta = "On SSH today; the agent keeps it connected",
        onDismiss = handlers.onClose,
        primary = SheetAction(
            label = if (state.starting) "Starting…" else "Install agent",
            enabled = state.canInstall && !state.starting,
            onClick = handlers.onInstall,
        ),
        modifier = Modifier.testTag(INSTALL_REVIEW_TAG),
    ) {
        ErrorBanner(state.error, onDismiss = handlers.onDismissError)
        if (host != null && host.tmuxVersion == null) {
            Text(
                TMUX_MISSING_REVIEW,
                style = Fleet.type.textMd,
                color = o.statusWaiting,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        Text("What gets installed", style = Fleet.type.textSm, color = o.fgMuted, modifier = Modifier.padding(top = 8.dp))
        for ((what, how) in installFacts(state)) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(what, style = Fleet.type.textMd, color = o.fgMuted, modifier = Modifier.weight(0.38f))
                Text(how, style = Fleet.type.textMd, color = o.fg, modifier = Modifier.weight(0.62f))
            }
        }
        Text("How to reach it", style = Fleet.type.textSm, color = o.fgMuted, modifier = Modifier.padding(top = 12.dp))
        Text(
            "SSH as ${host?.sshAlias ?: alias}, the way the hub reaches it today",
            style = Fleet.type.textMd,
            color = o.fg,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        if (!state.canInstall) {
            Text(
                when {
                    host == null -> "The hub does not list $alias."
                    !host.reachable -> "$alias does not answer right now; the hub needs SSH to install."
                    host.transport != "ssh" -> "$alias already runs the agent."
                    else -> "This pairing cannot install on the hub's hosts. The hub's operator can, from the desktop app."
                },
                style = Fleet.type.textMd,
                color = o.fgMuted,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

/**
 * The review's word on a host with no tmux: the install puts it there with
 * the host's package manager, and says why when it cannot.
 */
internal const val TMUX_MISSING_REVIEW: String =
    "tmux is missing. The install adds it with the host's package manager first; that needs root, " +
        "passwordless sudo or Homebrew there. Without them the install stops and says so."

/** The review's lines: the binary and where it goes, tmux, the service, and which way it connects. */
internal fun installFacts(state: AgentInstallUiState): List<Pair<String, String>> = listOf(
    "tmux" to (state.host?.tmuxVersion?.let { "already there ($it)" } ?: "installed if missing"),
    "fleet-agent" to listOfNotNull(state.hubVersion, "~/.local/bin", "checked against SHA256SUMS").joinToString(" · "),
    "Service" to "systemd user unit, starts at boot (or a plain process where there is no systemd)",
    "Connects to" to "this hub, outbound only",
)

/**
 * Installing: the job's real steps, Pulse while they run and Sonar while the
 * hub waits for the host's first heartbeat; then the host has joined, or the
 * hub's reason it did not. Leaving is safe at any point.
 */
@Composable
fun InstallingScreen(state: AgentInstallUiState, handlers: AgentInstallHandlers, modifier: Modifier = Modifier) {
    val job = state.job ?: return
    val alias = state.alias ?: job.hostAlias
    val o = Fleet.colors
    val gutter = OrbitTokens.spacing("phone-gutter").dp
    val phase = installPhase(job)
    Column(
        modifier = modifier.fillMaxSize().background(o.bg).padding(horizontal = gutter).testTag(INSTALLING_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Text("Installing on $alias", style = Fleet.type.textXl, color = o.fg, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(24.dp))
        when (phase) {
            InstallPhase.Running -> PulseSequence()
            InstallPhase.Heartbeat -> Sonar()
            InstallPhase.Done, InstallPhase.Failed -> OrbitMark(OrbitMarkLarge)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            when (phase) {
                InstallPhase.Running -> "Setting up $alias"
                InstallPhase.Heartbeat -> "Waiting for the first heartbeat"
                InstallPhase.Done -> "$alias joined the fleet"
                InstallPhase.Failed -> "$alias did not join"
            },
            style = Fleet.type.textLg,
            color = o.fg,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(16.dp))
        Surface(color = o.bgPane, shape = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (step in installSteps(job)) InstallStepLine(step)
            }
        }
        val detail = job.detail
        if (detail != null) {
            Text(
                detail,
                style = Fleet.type.textMd,
                color = if (phase == InstallPhase.Failed) o.statusFailed else o.fgMuted,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (phase == InstallPhase.Running || phase == InstallPhase.Heartbeat) {
            Text(
                "The hub runs this on its own; you can leave. The host lands in Hosts when it answers.",
                style = Fleet.type.textSm,
                color = o.fgMuted,
                textAlign = TextAlign.Center,
            )
        }
        OutlinedButton(
            onClick = handlers.onClose,
            border = BorderStroke(1.dp, o.controlBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = o.fg2),
            shape = RoundedCornerShape(OrbitTokens.radius("radius-md").dp),
            modifier = Modifier.padding(vertical = 16.dp).heightIn(min = OrbitTokens.spacing("touch-min").dp),
        ) { Text(if (phase == InstallPhase.Done || phase == InstallPhase.Failed) "Done" else "Leave") }
    }
}

@Composable
private fun InstallStepLine(step: LoaderStep) {
    val o = Fleet.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            when (step.state) {
                StepState.Done -> "✓"
                StepState.Running -> "•"
                StepState.Pending -> "○"
            },
            style = Fleet.type.textMd,
            color = if (step.state == StepState.Done) o.statusDone else o.fgMuted,
        )
        Text(
            step.label,
            style = Fleet.type.textMd,
            color = if (step.state == StepState.Pending) o.fgMuted else o.fg,
            modifier = Modifier.padding(start = 12.dp).weight(1f),
        )
        step.detail?.let { Text(it, style = Fleet.type.textSm, color = o.statusFailed) }
    }
}
