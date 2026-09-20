package dev.claudefleet.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.claudefleet.mobile.data.AppSession
import dev.claudefleet.mobile.data.AuthState
import dev.claudefleet.mobile.data.FleetRepository
import dev.claudefleet.mobile.data.HubSessionActions
import dev.claudefleet.mobile.data.SessionActions
import dev.claudefleet.mobile.net.HubClient
import dev.claudefleet.mobile.net.HubEventStream
import dev.claudefleet.mobile.net.withHubTimeouts
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * The things a platform has to supply, in one object the shared UI can hold.
 *
 * The secure store needs a `Context` on Android and nothing on iOS, and the
 * Ktor engine differs per platform, so both are constructed by the host and
 * handed in. Everything downstream of them is shared.
 */
class AppContainer(
    secrets: Secrets,
    http: HttpClient,
    val appVersion: String,
    /**
     * Whether a `claudefleet:` link may pair without somebody tapping.
     *
     * Wired to the **build** — `BuildConfig.DEBUG` on Android, `#if DEBUG` on
     * iOS — and never to anything in the link itself. A release build fills the
     * Pair screen's fields and stops, because a link is something anyone can
     * send and a silent pair would re-point the app at a hub of the sender's
     * choosing. A debug build submits, because the reason a debug build is on a
     * dev machine is that something other than a person is driving it.
     */
    val autoPairFromLink: Boolean = false,
) {
    /**
     * The last `claudefleet:` link the platform handed over, if the Pair screen
     * has not consumed it yet.
     *
     * A flow rather than a call, because the link can arrive before the screen
     * exists: a cold start from `adb shell am start -d …` delivers the URL in
     * `onCreate`, well before Compose has built a `PairViewModel`. Holding it
     * here means the screen picks it up whenever it appears, and a link that
     * arrives while the app is already paired simply sits unread — which is the
     * right outcome, since pairing again is not something a link may decide.
     */
    private val _pairLink = MutableStateFlow<String?>(null)
    val pairLink: StateFlow<String?> = _pairLink.asStateFlow()

    /** The platform's entry point for an incoming URL. */
    fun onPairLink(uri: String) {
        _pairLink.value = uri
    }

    /** Taken exactly once, so a link cannot be re-applied on every recomposition. */
    fun consumePairLink(): String? = _pairLink.getAndUpdate { null }

    // The platform hands in a bare engine (`HttpClient(OkHttp)` on Android,
    // `HttpClient(Darwin)` on iOS); this is the one shared place that gives
    // every call the app's timeout policy so both platforms get it the same
    // way. See `withHubTimeouts()`.
    private val http: HttpClient = http.withHubTimeouts()

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
            // The repository is the one caller holding a raw `HubClient` rather
            // than going through `AppSession.withClient`, so a 401 there has to
            // be routed back to the same rule by hand. Without this the app sat
            // on a revoked token behind a banner, while the identical 401
            // through `HubSessionActions` returned it to Pair. `revoke()`, not
            // `forget()`, so the Pair screen can say why.
            onRevoked = { session.revoke() },
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
            // Every screen is inset once, here, rather than each one insetting
            // itself. An app targeting SDK 35 is drawn edge to edge by the
            // system whether or not it asked to be, so without this the Pair
            // screen's heading sits under the status bar and the prompt box
            // under the gesture handle. `windowInsetsPadding` *consumes* what it
            // applies, so the `Scaffold` further down adds nothing a second
            // time.
            Box(
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                val auth by container.session.state.collectAsState()

                LaunchedEffect(container) { container.session.restore() }

                // What the hub answered on the pair that just happened, held so
                // the confirmation can be read rather than flashed past. Null on
                // a cold start with a stored credential, which is why that case
                // goes straight in.
                var justPaired by remember(container) { mutableStateOf<PairedHub?>(null) }

                when (val state = auth) {
                    AuthState.Unknown -> Splash()
                    AuthState.Unpaired -> {
                        // Not `justPaired = null` in the composable body. Writing
                        // Compose state during composition happens to converge
                        // here, because the write is idempotent once it has
                        // landed — but it is the shape that produces endless
                        // recomposition the moment someone makes it conditional,
                        // and it costs nothing to say it in an effect instead.
                        LaunchedEffect(state) { justPaired = null }
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

    // A `claudefleet:` link, if one is waiting. Keyed on the flow's value so a
    // link delivered while this screen is already up is picked up too, not only
    // one that arrived before it existed. `consumePairLink` takes it once.
    val pending by container.pairLink.collectAsState()
    LaunchedEffect(pending) {
        container.consumePairLink()?.let { vm.onPairLink(it, container.autoPairFromLink) }
    }

    PairScreen(
        state = state,
        onAddressChange = vm::onAddressChange,
        onCodeChange = vm::onCodeChange,
        onSubmit = { vm.submit() },
        onScanningChange = vm::setScanning,
        onScanned = vm::onScanned,
        onScannerUnavailable = vm::onScannerUnavailable,
        onDismissError = vm::dismissError,
        onDismissReason = vm::dismissReason,
    )
}

/**
 * The paired app: three tabs, and a session pushed over the first.
 *
 * The event stream follows the **lifecycle**, not the composition. A
 * backgrounded Android activity keeps its composition, so a `DisposableEffect`
 * here — which is what this was — held the SSE connection open for as long as
 * the app was installed and had been opened once: a phone in a pocket keeping a
 * socket alive, spending battery and data, while the hub counted a subscriber
 * that nobody was watching. The design says subscribe on resume and drop on
 * background, and `LifecycleStartEffect` is that sentence.
 *
 * `start()` is idempotent and `stop()` leaves the snapshot alone, so coming back
 * to the foreground re-subscribes and redraws the fleet as it was last seen,
 * with the refetch on `ready` filling in what changed while the app was away.
 */
// `BackHandler` is `@ExperimentalComposeUiApi` in Compose Multiplatform 1.12.
// Opted in here, on the one function that uses it, rather than repo-wide: the
// opt-in is a promise to re-read this call when Compose changes the API, and a
// module-level `freeCompilerArgs` entry is a promise nobody is reminded of.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FleetRoute(container: AppContainer, credentials: Credentials) {
    val scope = rememberCoroutineScope()
    val repository = remember(credentials) { container.repository(credentials, scope) }
    LifecycleStartEffect(repository) {
        repository.start()
        onStopOrDispose { repository.stop() }
    }

    val nav = remember(credentials) { Navigator() }
    val screen by nav.screen.collectAsState()
    val tab by nav.tab.collectAsState()

    // `Navigator.back()` returns false on a tab specifically so the
    // platform can have the gesture instead, `NavigatorTest` pins that, and a
    // mutation guards it — and until now the only caller was the Back *button*
    // on the session bar, which discards the Boolean. There was no `BackHandler`
    // anywhere in the repository, so the system back gesture out of an open
    // session did not return to the list: it finished the activity and left the
    // app. A designed, documented, tested contract wired to nothing.
    //
    // `enabled` is the whole of the contract in one expression: on a session the
    // app handles back, and on a tab it does not, which lets Android close the
    // app and iOS do whatever it does with an unclaimed swipe. That is why the
    // return value still does not need reading here.
    BackHandler(enabled = screen is Screen.Session) { nav.back() }

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
                        onDismissError = sessions::dismissError,
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
                    HostsScreen(
                        state = state,
                        onRefresh = { hosts.refresh() },
                        onDismissError = hosts::dismissError,
                    )
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
        onDismissError = vm::dismissError,
    )
}
