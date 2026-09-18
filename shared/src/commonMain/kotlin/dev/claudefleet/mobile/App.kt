package dev.claudefleet.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.claudefleet.mobile.data.AppSession
import dev.claudefleet.mobile.data.AuthState
import dev.claudefleet.mobile.data.FleetRepository
import dev.claudefleet.mobile.data.HubSessionActions
import dev.claudefleet.mobile.data.SessionActions
import dev.claudefleet.mobile.net.HubClient
import dev.claudefleet.mobile.net.HubEventStream
import dev.claudefleet.mobile.store.Credentials
import dev.claudefleet.mobile.store.Secrets
import dev.claudefleet.mobile.ui.HostsScreen
import dev.claudefleet.mobile.ui.HostsViewModel
import dev.claudefleet.mobile.ui.Navigator
import dev.claudefleet.mobile.ui.PairScreen
import dev.claudefleet.mobile.ui.PairViewModel
import dev.claudefleet.mobile.ui.PairedHub
import dev.claudefleet.mobile.ui.PairedScreen
import dev.claudefleet.mobile.ui.Screen
import dev.claudefleet.mobile.ui.SessionScreen
import dev.claudefleet.mobile.ui.SessionViewModel
import dev.claudefleet.mobile.ui.SessionsScreen
import dev.claudefleet.mobile.ui.SessionsViewModel
import dev.claudefleet.mobile.ui.SettingsScreen
import dev.claudefleet.mobile.ui.SettingsViewModel
import dev.claudefleet.mobile.ui.Tab
import dev.claudefleet.mobile.ui.scan.qrScannerSupported
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope

/**
 * The things a platform has to supply, in one object the shared UI can hold.
 *
 * The secure store needs a `Context` on Android and nothing on iOS, and the
 * Ktor engine differs per platform, so both are constructed by the host and
 * handed in. Everything downstream of them is shared.
 */
class AppContainer(
    secrets: Secrets,
    private val http: HttpClient,
    val appVersion: String,
) {
    val session: AppSession = AppSession(secrets, http)

    /** The two calls a session screen may make, through the 401 rule. */
    val sessionActions: SessionActions = HubSessionActions(session)

    /**
     * The live fleet picture for one credential.
     *
     * Built per credential rather than once, because the base URL and the token
     * are baked into both the client and the stream: re-pairing against a
     * different hub has to produce a different repository, not a mutated one.
     */
    fun repository(credentials: Credentials, scope: CoroutineScope): FleetRepository =
        FleetRepository(
            client = HubClient(http, credentials.hub, credentials.token),
            events = HubEventStream(http, credentials.hub, credentials.token),
            scope = scope,
        )
}

/**
 * The whole app: Pair when there is no credential, the three tabs when there
 * is, and one session's screen pushed over the first of them.
 *
 * Pairing is not a tab and is not navigated to — the app is on the Pair screen
 * exactly when [AuthState] says it holds nothing, which is also what puts it
 * back there when the hub answers 401 (`AppSession.withClient`).
 */
@Composable
fun App(container: AppContainer) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val auth by container.session.state.collectAsState()

            LaunchedEffect(container) { container.session.restore() }

            // What the hub answered on the pair that just happened, held so the
            // confirmation can be read rather than flashed past. Null on a cold
            // start with a stored credential, which is why that case goes
            // straight in.
            var justPaired by remember(container) { mutableStateOf<PairedHub?>(null) }

            when (val state = auth) {
                AuthState.Unknown -> Splash()
                AuthState.Unpaired -> {
                    justPaired = null
                    PairRoute(container) { justPaired = it }
                }
                is AuthState.Paired -> {
                    val paired = justPaired
                    if (paired != null) {
                        PairedScreen(paired, onContinue = { justPaired = null })
                    } else {
                        FleetRoute(container, state.credentials)
                    }
                }
            }
        }
    }
}

@Composable
private fun Splash() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("claude-fleet", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun PairRoute(container: AppContainer, onPaired: (PairedHub) -> Unit) {
    val scope = rememberCoroutineScope()
    val vm = remember(container, scope) {
        PairViewModel(container.session, scope, cameraAvailable = qrScannerSupported())
    }
    val state by vm.state.collectAsState()

    // The view model publishes `paired` a beat before `AuthState` reaches this
    // composable; reporting it here is what lets the confirmation name the hub.
    LaunchedEffect(state.paired) { state.paired?.let(onPaired) }

    PairScreen(
        state = state,
        onAddressChange = vm::onAddressChange,
        onCodeChange = vm::onCodeChange,
        onSubmit = { vm.submit() },
        onScanned = vm::onScanned,
        onScannerUnavailable = vm::onScannerUnavailable,
        onDismissError = vm::dismissError,
    )
}

/**
 * The paired app: three tabs, and a session pushed over the first.
 *
 * The repository is started with the composition and stopped when it leaves, so
 * the event stream follows the screen rather than the process. That is the
 * design's "subscribe on resume, drop on background" only insofar as the host
 * tears the composition down — see the task report; nothing here listens to a
 * lifecycle directly.
 */
@Composable
private fun FleetRoute(container: AppContainer, credentials: Credentials) {
    val scope = rememberCoroutineScope()
    val repository = remember(credentials) { container.repository(credentials, scope) }
    DisposableEffect(repository) {
        repository.start()
        onDispose { repository.stop() }
    }

    val nav = remember(credentials) { Navigator() }
    val screen by nav.screen.collectAsState()
    val tab by nav.tab.collectAsState()

    val sessions = remember(repository, scope) { SessionsViewModel(repository, scope) }
    val hosts = remember(repository, scope) { HostsViewModel(repository, scope) }
    val settings = remember(container, scope) {
        SettingsViewModel(container.session, scope, container.appVersion)
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                for (entry in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { nav.select(entry) },
                        icon = { Text(entry.name.take(1)) },
                        label = { Text(entry.name) },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val current = screen) {
                Screen.Sessions -> {
                    val state by sessions.state.collectAsState()
                    SessionsScreen(
                        state = state,
                        onOpenSession = nav::open,
                        onToggleNeedsAttention = sessions::toggleNeedsAttentionOnly,
                        onRefresh = { sessions.refresh() },
                    )
                }
                is Screen.Session -> SessionRoute(
                    sessionId = current.id,
                    container = container,
                    repository = repository,
                    credentials = credentials,
                    onBack = { nav.back() },
                )
                Screen.Hosts -> {
                    val state by hosts.state.collectAsState()
                    HostsScreen(state = state, onRefresh = { hosts.refresh() })
                }
                Screen.Settings -> {
                    val state by settings.state.collectAsState()
                    SettingsScreen(
                        state = state,
                        onForget = { settings.forget() },
                        onDismissError = settings::dismissError,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRoute(
    sessionId: Long,
    container: AppContainer,
    repository: FleetRepository,
    credentials: Credentials,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val vm = remember(sessionId, repository, scope) {
        SessionViewModel(
            sessionId = sessionId,
            fleet = repository,
            actions = container.sessionActions,
            scope = scope,
            // `send_prompt` is not in the hub's readonly allow-list, so a
            // readonly credential disables the box rather than making a call it
            // knows would be refused.
            canSendPrompts = credentials.canWrite,
        )
    }
    LaunchedEffect(sessionId) { vm.load() }

    val state by vm.state.collectAsState()
    val status by repository.status.collectAsState()
    SessionScreen(
        state = state,
        status = status,
        onDraftChange = vm::onDraftChange,
        onSend = { vm.send() },
        onRefresh = { vm.refresh() },
        onBack = onBack,
    )
}
