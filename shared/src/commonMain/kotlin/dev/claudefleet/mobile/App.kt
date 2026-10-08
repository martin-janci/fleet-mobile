package dev.claudefleet.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.AgentActions
import dev.claudefleet.mobile.data.AppSession
import dev.claudefleet.mobile.data.AuthState
import dev.claudefleet.mobile.ui.components.ChatHost
import dev.claudefleet.mobile.data.ChatFormActions
import dev.claudefleet.mobile.data.HubChatFormActions
import dev.claudefleet.mobile.data.DownloadActions
import dev.claudefleet.mobile.data.HubDownloadActions
import dev.claudefleet.mobile.data.FleetRepository
import dev.claudefleet.mobile.data.HubAgentActions
import dev.claudefleet.mobile.data.HubNewSessionActions
import dev.claudefleet.mobile.data.FleetSettingsActions
import dev.claudefleet.mobile.data.HubFleetSettingsActions
import dev.claudefleet.mobile.data.HubQuickReplyActions
import dev.claudefleet.mobile.data.HubRepoActions
import dev.claudefleet.mobile.data.RepoActions
import dev.claudefleet.mobile.data.HostActions
import dev.claudefleet.mobile.data.HubHostActions
import dev.claudefleet.mobile.data.HubProjectActions
import dev.claudefleet.mobile.data.HubMoveActions
import dev.claudefleet.mobile.data.HubSessionActions
import dev.claudefleet.mobile.data.MoveActions
import dev.claudefleet.mobile.data.ProjectActions
import dev.claudefleet.mobile.data.HubUsageActions
import dev.claudefleet.mobile.data.CompanyActions
import dev.claudefleet.mobile.data.HubCompanyActions
import dev.claudefleet.mobile.data.UsageActions
import dev.claudefleet.mobile.data.HubSessionDetailsActions
import dev.claudefleet.mobile.data.HubWorkActions
import dev.claudefleet.mobile.data.HubMissionActions
import dev.claudefleet.mobile.data.MissionActions
import dev.claudefleet.mobile.data.WorkActions
import dev.claudefleet.mobile.data.NewSessionActions
import dev.claudefleet.mobile.data.SessionActions
import dev.claudefleet.mobile.data.SessionDetailsActions
import dev.claudefleet.mobile.data.HubVersionActions
import dev.claudefleet.mobile.update.AppInstaller
import dev.claudefleet.mobile.update.GitHubReleases
import dev.claudefleet.mobile.update.ReleaseSource
import dev.claudefleet.mobile.update.UpdateViewModel
import dev.claudefleet.mobile.update.hubMismatch
import dev.claudefleet.mobile.update.takeWhatsNew
import dev.claudefleet.mobile.data.VersionActions
import dev.claudefleet.mobile.net.HubClient
import dev.claudefleet.mobile.net.HubEventStream
import dev.claudefleet.mobile.net.withHubTimeouts
import dev.claudefleet.mobile.store.Credentials
import dev.claudefleet.mobile.store.Prefs
import dev.claudefleet.mobile.store.Secrets
import dev.claudefleet.mobile.ui.AgentViewModel
import dev.claudefleet.mobile.ui.FilesHandlers
import dev.claudefleet.mobile.ui.FilesScreen
import dev.claudefleet.mobile.ui.FilesViewModel
import dev.claudefleet.mobile.ui.rememberFileHandoff
import dev.claudefleet.mobile.ui.MyWorkHandlers
import dev.claudefleet.mobile.ui.MyWorkScreen
import dev.claudefleet.mobile.ui.PhoneTaskHandlers
import dev.claudefleet.mobile.ui.PhoneTaskScreen
import dev.claudefleet.mobile.ui.PhoneReviewSheet
import dev.claudefleet.mobile.ui.PhoneMyWorkScreen
import dev.claudefleet.mobile.ui.MyWorkViewModel
import dev.claudefleet.mobile.ui.ReviewHandlers
import dev.claudefleet.mobile.ui.ReviewSheet
import dev.claudefleet.mobile.ui.ReviewViewModel
import dev.claudefleet.mobile.ui.SessionTasksHandlers
import dev.claudefleet.mobile.ui.SessionTasksViewModel
import dev.claudefleet.mobile.ui.TaskHandlers
import dev.claudefleet.mobile.ui.TaskScreen
import dev.claudefleet.mobile.ui.TaskViewModel
import dev.claudefleet.mobile.model.GroupRef
import dev.claudefleet.mobile.ui.HostsScreen
import dev.claudefleet.mobile.ui.HostsViewModel
import dev.claudefleet.mobile.ui.MultiStartHandlers
import dev.claudefleet.mobile.ui.ControlScreen
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.InboxScreen
import dev.claudefleet.mobile.ui.help.GuideScreen
import dev.claudefleet.mobile.ui.help.HelpPicker
import dev.claudefleet.mobile.ui.help.HelpSettings
import dev.claudefleet.mobile.ui.help.HelpSettingsSection
import dev.claudefleet.mobile.ui.help.LESSONS
import dev.claudefleet.mobile.ui.help.LearnScreen
import dev.claudefleet.mobile.ui.help.LessonBar
import dev.claudefleet.mobile.ui.help.LessonPlace
import dev.claudefleet.mobile.ui.help.PracticeFleet
import dev.claudefleet.mobile.ui.help.PracticeScreen
import dev.claudefleet.mobile.ui.help.Tip
import dev.claudefleet.mobile.ui.help.TipFor
import dev.claudefleet.mobile.ui.help.TourAnchor
import dev.claudefleet.mobile.ui.help.TourAnchors
import dev.claudefleet.mobile.ui.help.TourOverlay
import dev.claudefleet.mobile.ui.help.guidePages
import dev.claudefleet.mobile.ui.help.tourAnchor
import dev.claudefleet.mobile.ui.HubVersionBanner
import dev.claudefleet.mobile.ui.UpdateCard
import dev.claudefleet.mobile.ui.UpdateHandlers
import dev.claudefleet.mobile.ui.UpdateInboxLine
import dev.claudefleet.mobile.ui.UpdateScreen
import dev.claudefleet.mobile.ui.WhatsNewScreen
import dev.claudefleet.mobile.ui.MoreEntry
import dev.claudefleet.mobile.ui.MoreScreen
import dev.claudefleet.mobile.ui.Navigator
import dev.claudefleet.mobile.ui.PhoneLayout
import dev.claudefleet.mobile.ui.hostsLine
import dev.claudefleet.mobile.ui.inboxRows
import dev.claudefleet.mobile.ui.loadPhoneLayout
import dev.claudefleet.mobile.ui.savePhoneLayout
import dev.claudefleet.mobile.ui.kit.BottomBar
import dev.claudefleet.mobile.ui.kit.BottomBarBadge
import dev.claudefleet.mobile.ui.kit.BottomBarItem
import dev.claudefleet.mobile.ui.kit.OrbitIcons
import dev.claudefleet.mobile.ui.NewSessionScreen
import dev.claudefleet.mobile.ui.NewSessionViewModel
import dev.claudefleet.mobile.ui.WizardStep
import dev.claudefleet.mobile.ui.PairScreen
import dev.claudefleet.mobile.ui.PairViewModel
import dev.claudefleet.mobile.ui.PairedHub
import dev.claudefleet.mobile.ui.PairedScreen
import dev.claudefleet.mobile.ui.Hints
import dev.claudefleet.mobile.ui.QuickReplies
import dev.claudefleet.mobile.ui.Screen
import dev.claudefleet.mobile.ui.SessionDetailsHandlers
import dev.claudefleet.mobile.ui.SessionDetailsSheet
import dev.claudefleet.mobile.ui.SessionDetailsList
import dev.claudefleet.mobile.ui.DetailsAction
import dev.claudefleet.mobile.ui.SessionTab
import dev.claudefleet.mobile.ui.SessionTabsHost
import dev.claudefleet.mobile.ui.sessionTabs
import dev.claudefleet.mobile.ui.agentName
import dev.claudefleet.mobile.ui.appendToDraft
import dev.claudefleet.mobile.ui.RepoBody
import dev.claudefleet.mobile.ui.SessionDetailsViewModel
import dev.claudefleet.mobile.ui.RepoHandlers
import dev.claudefleet.mobile.ui.RepoScreen
import dev.claudefleet.mobile.ui.RepoViewModel
import dev.claudefleet.mobile.ui.BulkViewModel
import dev.claudefleet.mobile.ui.HostDetailHandlers
import dev.claudefleet.mobile.ui.HostDetailSheet
import dev.claudefleet.mobile.ui.HostDetailViewModel
import dev.claudefleet.mobile.ui.ProjectToolsHandlers
import dev.claudefleet.mobile.ui.ProjectToolsViewModel
import dev.claudefleet.mobile.ui.MoveHandlers
import dev.claudefleet.mobile.ui.MoveSheet
import dev.claudefleet.mobile.ui.MoveViewModel
import dev.claudefleet.mobile.ui.SessionScreen
import dev.claudefleet.mobile.ui.searchEverywhere
import dev.claudefleet.mobile.ui.TidyHandlers
import dev.claudefleet.mobile.ui.TidySheet
import dev.claudefleet.mobile.ui.TidyViewModel
import dev.claudefleet.mobile.ui.MissionsHandlers
import dev.claudefleet.mobile.ui.MissionsSheet
import dev.claudefleet.mobile.ui.MissionsViewModel
import dev.claudefleet.mobile.ui.UsageHandlers
import dev.claudefleet.mobile.ui.CompanyHandlers
import dev.claudefleet.mobile.ui.CompanyScreen
import dev.claudefleet.mobile.ui.CompanyViewModel
import dev.claudefleet.mobile.ui.UsageScreen
import dev.claudefleet.mobile.ui.UsageViewModel
import dev.claudefleet.mobile.ui.SessionViewModel
import dev.claudefleet.mobile.ui.SessionWorkHandlers
import dev.claudefleet.mobile.ui.SessionWorkViewModel
import dev.claudefleet.mobile.ui.ToolDetailsHost
import dev.claudefleet.mobile.ui.ToolDetailsModel
import dev.claudefleet.mobile.ui.SessionFiltersHandlers
import dev.claudefleet.mobile.ui.SessionFiltersSheet
import dev.claudefleet.mobile.model.SessionFacetId
import dev.claudefleet.mobile.ui.SessionsHandlers
import dev.claudefleet.mobile.ui.SessionsScreen
import dev.claudefleet.mobile.ui.SessionsTab
import dev.claudefleet.mobile.ui.BulkHandlers
import dev.claudefleet.mobile.ui.SessionsSheet
import dev.claudefleet.mobile.ui.DraftMemory
import dev.claudefleet.mobile.ui.SessionsViewModel
import dev.claudefleet.mobile.ui.FleetSettingsSection
import dev.claudefleet.mobile.ui.FleetSettingsViewModel
import dev.claudefleet.mobile.ui.SettingsScreen
import dev.claudefleet.mobile.ui.FleetCheck
import dev.claudefleet.mobile.ui.hubLabel
import dev.claudefleet.mobile.ui.kit.FullscreenWait
import dev.claudefleet.mobile.ui.OrbitSettingsScreen
import dev.claudefleet.mobile.ui.OrbitSettingsInput
import dev.claudefleet.mobile.ui.OrbitSettingsHandlers
import dev.claudefleet.mobile.ui.SettingsPlace
import dev.claudefleet.mobile.ui.LayoutRow
import dev.claudefleet.mobile.ui.SettingsViewModel
import dev.claudefleet.mobile.ui.Tab
import dev.claudefleet.mobile.ui.TicketsHandlers
import dev.claudefleet.mobile.ui.TicketsSheet
import dev.claudefleet.mobile.ui.TicketsViewModel
import dev.claudefleet.mobile.ui.TodayHandlers
import dev.claudefleet.mobile.ui.TodaySheet
import dev.claudefleet.mobile.ui.TodayViewModel
import dev.claudefleet.mobile.ui.scan.qrScannerSupported
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.theme.FleetTheme
import dev.claudefleet.mobile.ui.PhoneSettings
import androidx.compose.foundation.isSystemInDarkTheme
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import dev.claudefleet.mobile.notify.NoBackgroundNotifier
import dev.claudefleet.mobile.notify.BackgroundNotifier
import dev.claudefleet.mobile.notify.keepSeenWhileOpen

/**
 * The things a platform has to supply, in one object the shared UI can hold.
 *
 * The secure store needs a `Context` on Android and nothing on iOS, and the
 * Ktor engine differs per platform, so both are constructed by the host and
 * handed in. Everything downstream of them is shared.
 */
class AppContainer(
    secrets: Secrets,
    val prefs: Prefs,
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
    /** Notifications while the app is away — Android's foreground service, or nothing. */
    val notifier: BackgroundNotifier = NoBackgroundNotifier,
    /**
     * The app's own updates (redesign 14.18): Android downloads and installs
     * the signed APK from GitHub releases; null where the app is updated
     * elsewhere (iOS, through TestFlight), and then no update card shows.
     */
    val installer: AppInstaller? = null,
) {
    /**
     * A session a notification asked to open, until the paired screens take
     * it — held for the same reason [pairLink] is: the tap can start the app
     * cold, before the screens exist.
     */
    private val _openSession = MutableStateFlow<Long?>(null)
    val openSession: StateFlow<Long?> = _openSession.asStateFlow()

    /**
     * The session a notification asked to show its question for (redesign
     * 14.8). Kept apart from [openSession], which the navigator takes at once:
     * the session screen takes this one, so a tap lands on the conversation
     * and its question card even when that session was already open on
     * another tab.
     */
    private val _questionFocus = MutableStateFlow<Long?>(null)
    val questionFocus: StateFlow<Long?> = _questionFocus.asStateFlow()

    /** The platform's entry point for a tapped "needs you" notification. */
    fun onOpenSession(sessionId: Long) {
        _questionFocus.value = sessionId
        _openSession.value = sessionId
    }

    /** True once for [sessionId] after a notification asked for its question; then false. */
    fun consumeQuestionFocus(sessionId: Long): Boolean {
        var taken = false
        _questionFocus.update { if (it == sessionId) { taken = true; null } else it }
        return taken
    }

    /** Taken exactly once. */
    fun consumeOpenSession(): Long? = _openSession.getAndUpdate { null }

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
    val sessionDetailsActions: SessionDetailsActions = HubSessionDetailsActions(session)
    val repoActions: RepoActions = HubRepoActions(session)
    val usageActions: UsageActions = HubUsageActions(session)
    val companyActions: CompanyActions = HubCompanyActions(session)
    val hostActions: HostActions = HubHostActions(session)
    val projectActions: ProjectActions = HubProjectActions(session)
    val moveActions: MoveActions = HubMoveActions(session)

    /** The hub's own version, for the Settings screen to show beside this app's. */
    val versionActions: VersionActions = HubVersionActions(session)

    /** Where a newer release of this app is found: fleet-mobile's GitHub releases. */
    val releases: ReleaseSource = GitHubReleases(this.http)

    /** The New session form's one call, through the same 401 rule. */
    val newSessionActions: NewSessionActions = HubNewSessionActions(session)

    /** The work graph's calls, through the same `withClient` as every other. */
    val workActions: WorkActions = HubWorkActions(session)

    /** Missions (claude-fleet orchestration), through the same `withClient`. */
    val missionActions: MissionActions = HubMissionActions(session)

    /** The way into the hub's agent, through the same `withClient`. */
    val agentActions: AgentActions = HubAgentActions(session)

    /**
     * The chip row and the draft history — one instance for the whole app,
     * not one per session screen, so a chip added on one session's screen is
     * there the next time any session's screen opens, and so is the shared
     * history. Handed to every [SessionViewModel] this container builds; see
     * [SessionRoute].
     *
     * The chips themselves live on the hub (`quick_replies`), so the list is
     * the same one the desktop composer draws; the device keeps only a cache
     * of it and the draft history, which is this phone's alone.
     */
    val quickReplies: QuickReplies = QuickReplies(prefs, HubQuickReplyActions(session))

    /** Unsent text per session, across visits to it. */
    val drafts: DraftMemory = DraftMemory()
    val hints: Hints = Hints(prefs)

    /** This phone's own settings: notification kinds and the theme (redesign 14.11). */
    val phone: PhoneSettings = PhoneSettings(prefs)

    /** The fleet's settings pages' calls (claude-fleet declarative pages P6). */
    val fleetSettingsActions: FleetSettingsActions = HubFleetSettingsActions(session)

    /** A session's chat form (`ask`): read, answer, decline. */
    val chatFormActions: ChatFormActions = HubChatFormActions(session)

    /** The Files tab's calls (claude-fleet file downloads), through the same `withClient`. */
    val downloadActions: DownloadActions = HubDownloadActions(session)

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
    val theme by container.phone.theme.collectAsState()
    FleetTheme(dark = theme.isDark(isSystemInDarkTheme())) {
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
                // The first look at the fleet after a pair (14.11): the Hex
                // field until the first connection lands. Not on a cold start.
                var fleetCheck by remember(container) { mutableStateOf(false) }

                when (val state = auth) {
                    AuthState.Unknown -> Splash()
                    AuthState.Unpaired -> {
                        // Not `justPaired = null` in the composable body. Writing
                        // Compose state during composition happens to converge
                        // here, because the write is idempotent once it has
                        // landed — but it is the shape that produces endless
                        // recomposition the moment someone makes it conditional,
                        // and it costs nothing to say it in an effect instead.
                        LaunchedEffect(state) {
                            justPaired = null
                            fleetCheck = false
                        }
                        PairRoute(container) {
                            justPaired = it
                            fleetCheck = true
                        }
                    }
                    is AuthState.Paired -> {
                        val paired = justPaired
                        if (paired != null) {
                            PairedScreen(paired, onContinue = { justPaired = null }, notifier = container.notifier)
                        } else {
                            FleetRoute(container, state.credentials, fleetCheck = fleetCheck, onFleetCheckDone = { fleetCheck = false })
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

/**
 * A scope for view models and the repository: composition-lived, but **not**
 * the UI dispatcher.
 *
 * `rememberCoroutineScope()` inherits the composition's context, and on Android
 * that is `AndroidUiDispatcher.Main` — the main thread, resumed on a frame
 * callback. Every view model here was built with one, so the SSE stream, every
 * `tools/call`, every JSON parse and every re-derivation of the session list
 * ran on the thread that also has to draw: work measured in frames, plus a
 * frame of latency at each suspension point even when the work is trivial.
 *
 * `Dispatchers.Default` replaces only the dispatcher. The scope is still
 * cancelled when the composition leaves, which is the property the call sites
 * rely on — a background scope held by the container would outlive the screen
 * and leak the stream.
 *
 * Safe because nothing reached from these scopes touches Compose state: the
 * view models publish `StateFlow`s and the screens collect them with
 * `collectAsState`, which hops back to the composition on its own. A scope used
 * for UI work — a scroll animation, a snackbar — must stay the plain
 * `rememberCoroutineScope()`; see `SessionScreen`.
 */
@Composable
private fun rememberWorkScope(): CoroutineScope = rememberCoroutineScope { Dispatchers.Default }

@Composable
private fun PairRoute(container: AppContainer, onPaired: (PairedHub) -> Unit) {
    val scope = rememberWorkScope()
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
        onManualChange = vm::setManual,
        onPaste = vm::paste,
        onCancel = vm::cancel,
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
private fun FleetRoute(
    container: AppContainer,
    credentials: Credentials,
    fleetCheck: Boolean = false,
    onFleetCheckDone: () -> Unit = {},
) {
    val scope = rememberWorkScope()
    val repository = remember(credentials) { container.repository(credentials, scope) }
    LifecycleStartEffect(repository) {
        repository.start()
        onStopOrDispose { repository.stop() }
    }

    // A platform with no always-on watcher (iOS): while the app is open, keep
    // the seen set the background check reads, so the first check after
    // closing the app does not announce what the person just looked at.
    val poster = container.notifier.poster
    val alertsOn by container.notifier.enabled.collectAsState()
    if (poster != null && alertsOn) {
        LifecycleStartEffect(repository, poster) {
            val job = scope.launch { keepSeenWhileOpen(repository, container.prefs, poster) }
            onStopOrDispose { job.cancel() }
        }
    }

    // The phone's layout switch (redesign 14.2): Classic until someone picks
    // New in Settings, read once per pairing and written on every change.
    val nav = remember(credentials) { Navigator(loadPhoneLayout(container.prefs)) }
    val screen by nav.screen.collectAsState()
    val layout by nav.layout.collectAsState()
    // A tapped "needs you" notification: open its session.
    val openRequest by container.openSession.collectAsState()
    LaunchedEffect(openRequest) { if (openRequest != null) container.consumeOpenSession()?.let(nav::open) }
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
    BackHandler(enabled = nav.isPushed(screen)) { nav.back() }

    val sessions = remember(repository, scope) { SessionsViewModel(repository, scope, prefs = container.prefs) }
    val bulk = remember(repository, scope) { BulkViewModel(repository, container.sessionActions, scope, credentials.canWrite) }
    val hostDetail = remember(repository, scope) { HostDetailViewModel(repository, container.hostActions, scope, credentials.canWrite) }
    // The fleet's scope, like the New session form's `callScope`: a resume
    // started from the sheet must not be cancelled by closing it.
    val tickets = remember(repository, scope) {
        TicketsViewModel(
            fleet = repository,
            actions = container.workActions,
            scope = scope,
            canWrite = credentials.canWrite,
            onOpenSession = { nav.openFrom(it, SessionsSheet.Tickets) },
            onStartHere = { nav.newSession(ticketKey = it) },
            prefs = container.prefs,
        )
    }
    val tidy = remember(repository, scope) { TidyViewModel(repository, container.workActions, scope, credentials.canWrite) }
    val tidyState by tidy.state.collectAsState()
    val missions = remember(repository, scope) { MissionsViewModel(repository, container.missionActions, scope, credentials.canWrite) }
    val missionsState by missions.state.collectAsState()
    val today = remember(repository, scope) {
        TodayViewModel(
            fleet = repository,
            actions = container.workActions,
            scope = scope,
            orgFilter = sessions.orgFilter,
            onOpenSession = { nav.openFrom(it, SessionsSheet.Today) },
        )
    }
    val agent = remember(repository, scope) {
        AgentViewModel(
            fleet = repository,
            actions = container.agentActions,
            scope = scope,
            canWrite = credentials.canWrite,
            onOpenSession = { nav.open(it) },
        )
    }
    // The Work view (claude-fleet M14): the My work tab and its Review sheet.
    // Built once per repository like the sheets above; the tab follows the
    // fleet only while it is showing (`attach` / `detach` below).
    val myWork = remember(repository, scope) {
        MyWorkViewModel(
            fleet = repository,
            actions = container.workActions,
            scope = scope,
            canWrite = credentials.canWrite,
            prefs = container.prefs,
        )
    }
    val review = remember(repository, scope) {
        ReviewViewModel(
            fleet = repository,
            actions = container.workActions,
            scope = scope,
            canWrite = credentials.canWrite,
            onChanged = { myWork.reload() },
        )
    }
    val workState by myWork.state.collectAsState()
    // The hub stopped serving the Work view (or a reconnect found an older
    // hub): the tab leaves the bar, and the app leaves the tab.
    LaunchedEffect(workState.available) { if (!workState.available) nav.workUnavailable() }
    // The Files tab (claude-fleet file downloads): drawn when the hub keeps
    // downloads, following `download:changed` only while it is showing.
    val fileHandoff = rememberFileHandoff()
    val files = remember(repository, scope, fileHandoff) {
        FilesViewModel(
            fleet = repository,
            actions = container.downloadActions,
            scope = scope,
            // `remove_download` is not readonly; the view model checks the
            // hub's `tools/list` as well.
            canWrite = credentials.canWrite,
            handoff = fileHandoff,
        )
    }
    val filesState by files.state.collectAsState()
    LaunchedEffect(filesState.available) { if (!filesState.available) nav.filesUnavailable() }
    val hosts = remember(repository, scope) { HostsViewModel(repository, scope) }
    val settings = remember(container, scope) {
        SettingsViewModel(container.session, scope, container.appVersion, container.versionActions)
    }
    // The app's own update (redesign 14.18): looked for once per pairing,
    // offered on More and in the Inbox, never installed without a tap.
    val updates = remember(container, scope) {
        UpdateViewModel(container.releases, container.installer, container.prefs, container.appVersion, scope)
    }
    LaunchedEffect(updates) { updates.check() }
    // Back from Android's "install unknown apps" page: Install works now.
    LifecycleStartEffect(updates) {
        updates.recheckPermission()
        onStopOrDispose { }
    }
    val updateState by updates.state.collectAsState()
    // Once, on the first launch after an update: the wordmark and what changed.
    var whatsNew by remember(container) { mutableStateOf(takeWhatsNew(container.prefs, container.appVersion)) }
    // Help (redesign 14.22): the mode picked after pairing, tips, the tour,
    // lessons. On this phone only; nothing in it reaches the hub.
    val helpSettings = remember(container) { HelpSettings(container.prefs) }
    val help by helpSettings.state.collectAsState()
    val practice = remember(container) { PracticeFleet() }
    val practiceState by practice.state.collectAsState()
    val tourAnchors = remember(credentials) { TourAnchors() }
    // A lesson step only says where to look: going there is the one thing it does.
    val lessonPlace = help.lesson?.let { (lesson, step) -> lesson.steps.getOrNull(step)?.place }
    LaunchedEffect(lessonPlace) {
        when (lessonPlace) {
            null -> Unit
            LessonPlace.Practice -> nav.openPractice()
            LessonPlace.Inbox -> nav.select(if (layout == PhoneLayout.New) Tab.Inbox else Tab.Sessions)
            LessonPlace.Sessions -> nav.select(Tab.Sessions)
            LessonPlace.Control -> nav.select(if (layout == PhoneLayout.New) Tab.Control else Tab.Sessions)
            LessonPlace.Settings -> nav.openFromMore(Screen.Settings)
        }
    }
    // The fleet's settings (claude-fleet declarative pages P6): offered when
    // the hub serves this token the page specs and the settings, read again
    // on every connection that does.
    val fleetSettings = remember(repository, scope) {
        FleetSettingsViewModel(container.fleetSettingsActions, scope, credentials.canWrite)
    }
    val settingsCaps by repository.capabilities.collectAsState()
    // The Company entry shows once the hub has listed an org to this device.
    val orgDirectory by repository.orgs.collectAsState()
    LaunchedEffect(settingsCaps) { fleetSettings.setHistoryAvailable(settingsCaps.settingHistory) }
    LaunchedEffect(settingsCaps.fleetSettings) { if (settingsCaps.fleetSettings) fleetSettings.load() }

    Scaffold(
        bottomBar = {
            // Not on a session: it is a detail screen with its own Back, and
            // on a phone the bar's 80 dp were the conversation's to lose —
            // see `SessionChrome.kt`. Not on the New session form either: a
            // tab tapped by mistake there threw away a half-filled form.
            AnimatedVisibility(
                visible = screen !is Screen.Session && screen !is Screen.Repo && screen !is Screen.NewSession,
                enter = expandVertically(expandFrom = Alignment.Top),
                exit = shrinkVertically(shrinkTowards = Alignment.Top),
            ) {
                val attention by sessions.state.collectAsState()
                if (layout == PhoneLayout.New) {
                    // The Orbit Fleet bar: one badge, the Needs you count, on
                    // Inbox. Work's To review count stays on the Work screen.
                    val items = PhoneLayout.New.tabs
                        .filter { it != Tab.Work || workState.available }
                        .map { newBarItem(it) }
                    BottomBar(
                        modifier = Modifier.tourAnchor(tourAnchors, TourAnchor.Bar),
                        items = items,
                        selected = tab.name,
                        onSelect = { key -> nav.select(Tab.valueOf(key)) },
                        badge = BottomBarBadge(Tab.Inbox.name, attention.attentionCount),
                    )
                } else NavigationBar {
                    // Work only when the hub serves `work { tree }`, Files only
                    // when it keeps downloads.
                    val shown = PhoneLayout.Classic.tabs.filter {
                        (it != Tab.Work || workState.available) && (it != Tab.Files || filesState.available)
                    }
                    for (entry in shown) {
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { nav.select(entry) },
                            icon = {
                                val icon = when (entry) {
                                    Tab.Sessions -> FleetIcons.Sessions
                                    Tab.Work -> FleetIcons.Work
                                    Tab.Files -> FleetIcons.Files
                                    Tab.Hosts -> FleetIcons.Hosts
                                    else -> FleetIcons.Settings
                                }
                                if (entry == Tab.Sessions && attention.attentionCount > 0) {
                                    BadgedBox(badge = { Badge { Text("${attention.attentionCount}") } }) {
                                        Icon(icon, contentDescription = entry.name)
                                    }
                                } else if (entry == Tab.Work && workState.reviewCount > 0) {
                                    BadgedBox(badge = { Badge { Text("${workState.reviewCount}") } }) {
                                        Icon(icon, contentDescription = entry.name)
                                    }
                                } else {
                                    Icon(icon, contentDescription = entry.name)
                                }
                            },
                            label = { Text(entry.name) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val current = screen) {
                is Screen.Sessions -> {
                    // The screen's own filter drives the view model, not the
                    // other way round: `Navigator.showSessionsFor` (a host
                    // tap), the plain tab tap, and `back()` restoring a
                    // filtered screen all change `current`, and this is what
                    // applies whichever one just happened. A structurally
                    // equal `Screen.Sessions` — the clear chip below calling
                    // `setHostFilter` directly, with no navigation involved —
                    // does NOT re-fire this effect, which is fine: the view
                    // model already holds the filter the chip just set.
                    LaunchedEffect(current) { sessions.setHostFilter(current.hostAlias) }
                    // Back from a session opened out of a sheet: that sheet again.
                    LaunchedEffect(current.reopen) {
                        when (current.reopen) {
                            SessionsSheet.Today -> today.open()
                            SessionsSheet.Tidy -> tidy.open()
                            SessionsSheet.Tickets -> tickets.open()
                            null -> return@LaunchedEffect
                        }
                        nav.sheetReopened()
                    }
                    val state by sessions.state.collectAsState()
                    val ticketsState by tickets.state.collectAsState()
                    val todayState by today.state.collectAsState()
                    val agentState by agent.state.collectAsState()
                    val bulkState by bulk.state.collectAsState()
                    val searchHosts by repository.hosts.collectAsState()
                    val searchProjects by repository.projects.collectAsState()
                    val hits = remember(state.filters.query, searchHosts, searchProjects, ticketsState.available) {
                        searchEverywhere(state.filters.query, searchHosts, searchProjects, ticketsState.available)
                    }
                    val sessionsHandlers = SessionsHandlers(
                        onOpenSession = nav::open,
                        onToggleNeedsAttention = sessions::toggleNeedsAttentionOnly,
                        onRefresh = { sessions.refresh() },
                        onDismissError = sessions::dismissError,
                        onCycleGroupMode = { sessions.cycleGroupMode(state.workAvailable) },
                        onToggleSearch = sessions::toggleSearch,
                        onSetQuery = sessions::setQuery,
                        onOpenFilters = { sessions.setFiltersOpen(true) },
                        onToggleHost = sessions::toggleHost,
                        // Both, in this order, and this is the only place
                        // that knows to: `clearFilters` deliberately leaves
                        // the host alone because `Screen.Sessions.hostAlias`
                        // owns it (see `onSetHost` below), so a *clear all*
                        // that called only the view model would leave the
                        // one filter a person most often wants gone.
                        onClearAll = {
                            sessions.clearFilters()
                            nav.clearHostFilter()
                        },
                        onClearFacet = { id ->
                            sessions.clearFacet(id)
                            if (id == SessionFacetId.HOST) nav.clearHostFilter()
                        },
                        onSetShowArchived = sessions::setShowArchived,
                        // `new_session` is not a readonly tool: a readonly
                        // pairing is not offered a form the hub would refuse.
                        onNewSession = if (credentials.canWrite) ({ nav.newSession() }) else null,
                        onOpenTickets = if (ticketsState.available) ({ tickets.open() }) else null,
                        onOpenToday = if (todayState.available) ({ today.open() }) else null,
                        onOpenMissions = if (missionsState.available) ({ missions.open() }) else null,
                        // On the New bar Control replaces the agent button.
                        onOpenAgent = if (agentState.available && layout == PhoneLayout.Classic) ({ agent.open() }) else null,
                        onDismissAgentError = agent::dismissError,
                        onToggleSelect = bulk::toggle,
                        onClearSelection = bulk::clear,
                        onStartSelect = bulk::start,
                        onBulkSend = { bulk.send(it) },
                        onBulkKill = { bulk.kill() },
                        onDismissBulkOutcome = bulk::dismissOutcome,
                        onSearchHost = { nav.showSessionsFor(it) },
                        onSearchProject = { nav.newSessionIn(it) },
                        onSearchTicket = { q -> tickets.open(); tickets.onQuery(q); tickets.search() },
                    )
                    // The New bar's Sessions tab (redesign 14.3); Classic keeps its list.
                    if (layout == PhoneLayout.New) {
                        SessionsTab(
                            state = state,
                            handlers = sessionsHandlers,
                            bulk = bulkState,
                            hits = hits,
                            bulkHandlers = BulkHandlers(
                                onSelectAll = bulk::selectAll,
                                onRetry = { bulk.retry(it) },
                                onRetryAll = { bulk.retryFailed() },
                            ),
                        )
                    } else {
                        SessionsScreen(
                            state = state,
                            agent = agentState,
                            bulk = bulkState,
                            hits = hits,
                            handlers = sessionsHandlers,
                        )
                    }
                    if (state.filtersOpen) {
                        SessionFiltersSheet(
                            state = state,
                            handlers = SessionFiltersHandlers(
                                onClose = { sessions.setFiltersOpen(false) },
                                onSetWindow = sessions::setWindow,
                                onSetDirection = sessions::setDirection,
                                onToggleStatus = sessions::toggleStatus,
                                // Through the navigator, not
                                // `sessions.setHostFilter(...)` directly:
                                // `Screen.Sessions.hostAlias` is the one source of
                                // truth for the filter, and `open()` reads
                                // `nav.screen.value` to build `returnTo`. Setting
                                // the view model alone left that screen value
                                // stale, so opening a session and coming back
                                // resurrected the filter the sheet had just
                                // changed. See `Navigator.clearHostFilter`.
                                onSetHost = { alias ->
                                    if (alias == null) nav.clearHostFilter() else nav.showSessionsFor(alias)
                                },
                                onSetProject = sessions::setProjectFilter,
                                onToggleWorkStatus = sessions::toggleWorkStatus,
                                onToggleWorkStatusName = sessions::toggleWorkStatusName,
                                onToggleArchived = sessions::toggleArchived,
                                onToggleOrg = sessions::toggleOrg,
                                onToggleMyWork = sessions::toggleMyWorkOnly,
                                onToggleBackground = sessions::toggleBackground,
                                onClearAll = {
                                    sessions.clearFilters()
                                    nav.clearHostFilter()
                                },
                                onClearFacet = { id ->
                                    sessions.clearFacet(id)
                                    if (id == SessionFacetId.HOST) nav.clearHostFilter()
                                },
                                // New: "Filters and grouping" in one sheet.
                                onSetGroupMode = if (layout == PhoneLayout.New) sessions::setGroupMode else null,
                                onToggleNeedsAttention = if (layout == PhoneLayout.New) sessions::toggleNeedsAttentionOnly else null,
                            ),
                        )
                    }
                    if (ticketsState.open) {
                        TicketsSheet(
                            state = ticketsState,
                            handlers = TicketsHandlers(
                                onClose = tickets::close,
                                onQuery = tickets::onQuery,
                                onSearch = { tickets.search() },
                                onSelect = { tickets.select(it) },
                                onOpenLive = tickets::openLive,
                                onStartHere = tickets::startHere,
                                onResumeHost = tickets::selectResumeHost,
                                onResume = tickets::resume,
                                onConfirmResume = { tickets.confirmResume() },
                                onCancelResume = tickets::cancelResume,
                                onDismissError = tickets::dismissError,
                                onOpenFilters = tickets::openFilters,
                                onCloseFilters = tickets::closeFilters,
                                onToggleList = tickets::toggleList,
                                onToggleStatus = tickets::toggleStatus,
                                onToggleStatusName = tickets::toggleStatusName,
                                onSetOrg = tickets::setOrg,
                                onSetTracker = tickets::setTracker,
                                onSetSession = tickets::setSession,
                                onClearFacet = tickets::clearFacet,
                                onClearAll = tickets::clearAll,
                                onCycleSort = tickets::cycleSort,
                            ),
                        )
                    }
                }
                is Screen.NewSession -> key(current) {
                    NewSessionRoute(
                        initialHost = current.hostAlias,
                        ticketKey = current.ticketKey,
                        initialProject = current.projectId,
                        container = container,
                        repository = repository,
                        credentials = credentials,
                        // The fleet's scope, not the form's: see
                        // `NewSessionViewModel.callScope`.
                        callScope = scope,
                        onCreated = nav::created,
                        onBack = { nav.back() },
                        wizard = layout == PhoneLayout.New,
                    )
                }
                is Screen.Session -> key(current.id) {
                    SessionRoute(
                        sessionId = current.id,
                        container = container,
                        repository = repository,
                        credentials = credentials,
                        onBack = { nav.back() },
                        onOpenTask = nav::openTask,
                        onOpenSession = nav::open,
                        onOpenRepo = nav::openRepo,
                        // The fleet's scope: a change to the session's tasks
                        // is not cancelled by leaving the session.
                        callScope = scope,
                        // The New bar draws the session as tabs (redesign 14.4).
                        newLayout = layout == PhoneLayout.New,
                    )
                }
                Screen.Work -> {
                    DisposableEffect(myWork) {
                        myWork.attach()
                        onDispose { myWork.detach() }
                    }
                    val reviewState by review.state.collectAsState()
                    val workHandlers = MyWorkHandlers(
                        onOpenTask = nav::openTask,
                        onRefresh = { myWork.refresh() },
                        onReload = { myWork.reload() },
                        onToggleSection = myWork::toggleSection,
                        onLoadMore = { myWork.loadMore(it) },
                        onToggleSearch = myWork::toggleSearch,
                        onSetQuery = myWork::setQuery,
                        onOpenFilters = { myWork.setFiltersOpen(true) },
                        onCloseFilters = { myWork.setFiltersOpen(false) },
                        onSetOrg = myWork::setOrg,
                        onSetTracker = myWork::setTracker,
                        onSetStatus = myWork::setStatus,
                        onSetHas = myWork::setHas,
                        onToggleMine = myWork::toggleMine,
                        onToggleReview = myWork::toggleReview,
                        onClearFilters = myWork::clearFilters,
                        onClearFacet = myWork::clearFacet,
                        onSetArchived = { myWork.setArchived(it) },
                        onApplyView = myWork::applyView,
                        onSaveView = { myWork.saveView(it) },
                        onUpdateView = { myWork.updateView(it) },
                        onDeleteView = { myWork.deleteView(it) },
                        onOpenReview = if (reviewState.available) ({ review.open() }) else null,
                        onOpenRules = if (workState.rulesAvailable) ({ myWork.openRules() }) else null,
                        onCloseRules = myWork::closeRules,
                        onDismissError = myWork::dismissError,
                    )
                    // The New bar's Work (redesign 14.9): rows say what their
                    // sessions are doing, and To review sits beside Mine.
                    val workRows by repository.sessions.collectAsState()
                    val workRowOf: (Long) -> dev.claudefleet.mobile.model.SessionRow? = { id -> workRows.firstOrNull { it.id == id } }
                    if (layout == PhoneLayout.New) {
                        val workStatus by repository.status.collectAsState()
                        PhoneMyWorkScreen(
                            state = workState,
                            status = workStatus,
                            nowSeconds = epochSeconds(),
                            rowOf = workRowOf,
                            handlers = workHandlers,
                            reviewCount = if (reviewState.loaded) reviewState.total else workState.reviewCount,
                            onStart = { nav.newSession(ticketKey = it) },
                        )
                    } else {
                        MyWorkScreen(state = workState, handlers = workHandlers)
                    }
                    if (reviewState.open) {
                        val reviewHandlers = ReviewHandlers(
                            onClose = review::close,
                            onReload = { review.reload() },
                            onLoadMore = { review.loadMore() },
                            onConfirm = { review.confirm(it) },
                            onReject = { review.reject(it) },
                            onKeep = { review.keep(it) },
                            onRemove = { review.remove(it) },
                            onMakePrimary = { review.makePrimary(it) },
                            onToggleChange = review::toggleChange,
                            onChange = { item, alt -> review.change(item, alt) },
                            onConfirmAll = { review.confirmAllShown() },
                            onUndo = { review.undo() },
                            onDismissUndo = review::dismissUndo,
                            onDismissError = review::dismissError,
                        )
                        if (layout == PhoneLayout.New) {
                            PhoneReviewSheet(state = reviewState, handlers = reviewHandlers, rowOf = workRowOf)
                        } else {
                            ReviewSheet(state = reviewState, handlers = reviewHandlers)
                        }
                    }
                }
                Screen.Usage -> {
                    val usage = remember(repository, scope) { UsageViewModel(repository, container.usageActions, scope) }
                    LaunchedEffect(usage) { usage.load() }
                    val usageState by usage.state.collectAsState()
                    UsageScreen(
                        state = usageState,
                        nowSeconds = epochSeconds(),
                        handlers = UsageHandlers(
                            onBack = { nav.back() },
                            onRefresh = { usage.refresh() },
                            onSelect = { usage.select(it) },
                            onOpenSession = nav::open,
                            onDismissError = usage::dismissError,
                        ),
                    )
                }
                Screen.Company -> {
                    val company = remember(repository, scope) { CompanyViewModel(repository, container.companyActions, scope) }
                    LaunchedEffect(company) { company.load() }
                    val companyState by company.state.collectAsState()
                    // An org's detail is drawn inside this screen: back closes it first.
                    BackHandler(enabled = companyState.openId != null) { company.close() }
                    CompanyScreen(
                        state = companyState,
                        nowSeconds = epochSeconds(),
                        handlers = CompanyHandlers(
                            onBack = { if (!company.close()) nav.back() },
                            onRefresh = { company.refresh() },
                            onOpen = company::open,
                            onDismissError = company::dismissError,
                        ),
                    )
                }
                is Screen.Repo -> key(current.sessionId) {
                    RepoRoute(
                        sessionId = current.sessionId,
                        container = container,
                        repository = repository,
                        credentials = credentials,
                        onBack = { nav.back() },
                    )
                }
                is Screen.Task -> key(current.taskId) {
                    TaskRoute(
                        taskId = current.taskId,
                        container = container,
                        repository = repository,
                        credentials = credentials,
                        knownGroups = myWork::knownGroups,
                        // The fleet's scope: a placement or a resume is not
                        // cancelled by backing out of the task.
                        callScope = scope,
                        onOpenSession = nav::open,
                        onStartHere = { nav.newSession(ticketKey = it) },
                        onBack = { nav.back() },
                        newLayout = layout == PhoneLayout.New,
                    )
                }
                Screen.Files -> {
                    DisposableEffect(files) {
                        files.attach()
                        onDispose { files.detach() }
                    }
                    val status by repository.status.collectAsState()
                    FilesScreen(
                        state = filesState,
                        status = status,
                        handlers = FilesHandlers(
                            onRefresh = { files.refresh() },
                            onTap = { files.tap(it) },
                            onCancelTransfer = files::cancelTransfer,
                            onHandOff = { files.handOff(it) },
                            onCloseOpened = files::closeOpened,
                            onRemove = if (filesState.canRemove) files::askRemove else null,
                            onConfirmRemove = { files.confirmRemove() },
                            onCancelRemove = files::cancelRemove,
                            onDismissError = files::dismissError,
                            onDismissNotice = files::dismissNotice,
                        ),
                    )
                }
                Screen.Hosts -> {
                    val state by hosts.state.collectAsState()
                    val hostCaps by repository.capabilities.collectAsState()
                    val hostSheet by hostDetail.state.collectAsState()
                    HostsScreen(
                        state = state,
                        onRefresh = { hosts.refresh() },
                        onDismissError = hosts::dismissError,
                        onOpenHost = { nav.showSessionsFor(it) },
                        onHostDetails = { alias: String -> hostDetail.open(alias); Unit }
                            .takeIf { hostCaps.probeHost || hostCaps.restoreSessions || hostCaps.discoverLost },
                    )
                    if (hostSheet.alias != null) {
                        HostDetailSheet(
                            state = hostSheet,
                            nowSeconds = epochSeconds(),
                            handlers = HostDetailHandlers(
                                onClose = hostDetail::close,
                                onProbe = { hostDetail.probe() },
                                onShowSessions = { hostSheet.alias?.let { alias -> hostDetail.close(); nav.showSessionsFor(alias) } },
                                onCheckLost = { hostDetail.checkLost() },
                                onRestore = { hostDetail.restore() },
                                onResume = { c -> hostDetail.resume(c) { id -> hostDetail.close(); nav.open(id) } },
                                onDismissError = hostDetail::dismissError,
                            ),
                        )
                    }
                }
                Screen.Inbox -> {
                    val all by repository.sessions.collectAsState()
                    val rows = remember(all) { inboxRows(all) }
                    val todayInbox by today.state.collectAsState()
                    val inboxList by sessions.state.collectAsState()
                    // The hub's version, read again on every connection: an owner
                    // can upgrade it under a running app.
                    val connected = inboxList.status is ConnectionStatus.Connected
                    var hubVersion by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(connected) { if (connected) hubVersion = container.versionActions.hubVersion() }
                    val mismatch = remember(hubVersion) { hubMismatch(container.appVersion, hubVersion) }
                    InboxScreen(
                        rows = rows,
                        running = all.count { it.claudeStatus == "working" },
                        nowSeconds = inboxList.nowSeconds,
                        live = inboxList.status is ConnectionStatus.Connected,
                        refreshing = inboxList.refreshing,
                        onRefresh = { sessions.refresh() },
                        onOpenSession = nav::open,
                        // Today is an Inbox view until Control grows its own.
                        onOpenToday = if (todayInbox.available) ({ today.open() }) else null,
                        anchors = tourAnchors,
                        top = {
                            mismatch?.let { m -> HubVersionBanner(m, onUpdate = nav::openUpdate.takeIf { updateState.available != null }) }
                            updateState.available?.let { UpdateInboxLine(it, onOpen = nav::openUpdate) }
                            TipFor(Tip.INBOX, help, helpSettings)
                        },
                    )
                }
                Screen.Control -> {
                    val agentState by agent.state.collectAsState()
                    val attention by sessions.state.collectAsState()
                    Column(modifier = Modifier.fillMaxSize()) {
                        ErrorBanner(agentState.error, onDismiss = agent::dismissError)
                        TipFor(Tip.CONTROL, help, helpSettings)
                        ControlScreen(
                            subtitle = "${attention.attentionCount} need you",
                            entries = buildList {
                                add(
                                    MoreEntry(
                                        title = "Chat with Control",
                                        line = when {
                                            !agentState.available -> "The hub does not offer the coordinator to this device"
                                            agentState.waking -> "Waking the coordinator…"
                                            else -> "The fleet's coordinator, the same one as on the desktop"
                                        },
                                        onOpen = { agent.open() },
                                    ),
                                )
                                if (missionsState.available) {
                                    add(MoreEntry("Missions", "${missionsState.missions.size} missions · Pause all inside") { missions.open() })
                                }
                            },
                        )
                    }
                }
                Screen.More -> {
                    val hostRows by repository.hosts.collectAsState()
                    MoreScreen(
                        top = { updateState.available?.let { UpdateCard(it, container.appVersion, onOpen = nav::openUpdate) } },
                        entries = buildList {
                            add(MoreEntry("Hosts", hostsLine(hostRows)) { nav.openFromMore(Screen.Hosts) })
                            if (settingsCaps.usage || settingsCaps.accounts) {
                                add(MoreEntry("Accounts and usage", "Quotas, and estimated spend by host and day") { nav.openUsage() })
                            }
                            if (missionsState.available) {
                                add(MoreEntry("Automation", "Missions, and Pause all") { missions.open() })
                            }
                            if (filesState.available) {
                                val line = if (filesState.loaded) "${filesState.files.size} files" else "Files sessions sent to the hub"
                                add(MoreEntry("Files", line) { nav.openFromMore(Screen.Files) })
                            }
                            if (orgDirectory.orgs.isNotEmpty()) {
                                add(MoreEntry("Organisations", orgDirectory.orgs.values.joinToString(" · ") { it.name }) { nav.openCompany() })
                            }
                            add(
                                MoreEntry(
                                    "Learn",
                                    "${help.lessonsDone.count { id -> LESSONS.any { it.id == id } }} of ${LESSONS.size} lessons · practice fleet",
                                ) { nav.openLearn() },
                            )
                            add(MoreEntry("Settings", "This phone, the hub, fleet settings") { nav.openFromMore(Screen.Settings) })
                        },
                    )
                }
                Screen.Learn -> {
                    val fleet by fleetSettings.state.collectAsState()
                    LearnScreen(
                        help = help,
                        guides = if (settingsCaps.fleetSettings) guidePages(fleet.pages) else emptyList(),
                        onBack = { nav.back() },
                        onPractice = { practice.reset(); nav.openPractice() },
                        onLesson = helpSettings::startLesson,
                        onGuide = nav::openGuide,
                        tip = { TipFor(Tip.LEARN, help, helpSettings) },
                    )
                }
                Screen.Practice -> {
                    // Back closes the sample session first, then leaves the practice fleet.
                    BackHandler(enabled = practiceState.open != null) { practice.back() }
                    PracticeScreen(
                        practice = practice,
                        state = practiceState,
                        onLeave = { nav.back() },
                        tip = { TipFor(Tip.QUESTION, help, helpSettings) },
                    )
                }
                is Screen.Guide -> {
                    val fleet by fleetSettings.state.collectAsState()
                    val page = fleet.pages.firstOrNull { it.id == current.pageId }
                    if (page == null) {
                        LaunchedEffect(current) { nav.back() }
                    } else {
                        GuideScreen(
                            page = page,
                            state = fleet,
                            onBack = { nav.back() },
                            onSet = fleetSettings::set,
                            onRefuse = fleetSettings::refuse,
                            onDecide = { id, apply -> fleetSettings.decide(id, apply) },
                            onConfirm = fleetSettings::confirm,
                            onCancelConfirm = fleetSettings::cancelConfirm,
                        )
                    }
                }
                Screen.Update -> UpdateScreen(
                    state = updateState,
                    appVersion = container.appVersion,
                    handlers = UpdateHandlers(
                        onBack = { nav.back() },
                        onDownload = updates::download,
                        onPause = updates::pause,
                        onCancel = updates::cancel,
                        onInstall = updates::install,
                        onRetry = updates::reset,
                    ),
                )
                Screen.Settings -> {
                    val state by settings.state.collectAsState()
                    val fleet by fleetSettings.state.collectAsState()
                    // On opening, not at construction: this view model lives
                    // as long as the paired UI does, and a hub upgraded in
                    // the meantime would otherwise be reported at whatever
                    // version it ran when the app started.
                    LaunchedEffect(settings) { settings.load() }
                    val fleetPageOpen = settingsCaps.fleetSettings && fleet.openPage != null
                    val fleetSection: @Composable () -> Unit = {
                        if (settingsCaps.fleetSettings) {
                            FleetSettingsSection(
                                state = fleet,
                                clientName = state.clientName,
                                onOpen = fleetSettings::open,
                                onBack = { fleetSettings.back() },
                                onSet = fleetSettings::set,
                                onRefuse = fleetSettings::refuse,
                                onDecide = { id, apply -> fleetSettings.decide(id, apply) },
                                onConfirm = fleetSettings::confirm,
                                onCancelConfirm = fleetSettings::cancelConfirm,
                                onHistory = { fleetSettings.showHistory(it) },
                                onCloseHistory = fleetSettings::closeHistory,
                            )
                        }
                    }
                    val onSetLayout: (PhoneLayout) -> Unit = { chosen ->
                        savePhoneLayout(container.prefs, chosen)
                        nav.setLayout(chosen)
                    }
                    val onOpenUsage = nav::openUsage.takeIf { settingsCaps.usage || settingsCaps.accounts }
                    val onOpenCompany = nav::openCompany.takeIf { orgDirectory.orgs.isNotEmpty() }
                    if (layout == PhoneLayout.New) {
                        // The Orbit settings (redesign 14.11): This phone and the
                        // desktop's groups over the hub's pages. Back closes an
                        // open page first, then the group, then leaves Settings.
                        val place by settings.place.collectAsState()
                        val theme by container.phone.theme.collectAsState()
                        val notifyKinds by container.phone.notifyKinds.collectAsState()
                        BackHandler(enabled = fleetPageOpen || place != SettingsPlace.Home) {
                            if (!fleetSettings.back()) settings.back()
                        }
                        OrbitSettingsScreen(
                            place = place,
                            input = OrbitSettingsInput(
                                settings = state,
                                fleet = fleet.takeIf { settingsCaps.fleetSettings },
                                theme = theme,
                                notifyKinds = notifyKinds,
                                updateMode = updateState.mode.takeIf { updateState.supported },
                            ),
                            handlers = OrbitSettingsHandlers(
                                onOpen = settings::open,
                                onBack = { if (!fleetSettings.back()) settings.back() },
                                onOpenPage = fleetSettings::open,
                                onSetTheme = container.phone::setTheme,
                                onSetNotify = container.phone::setNotify,
                                onSetUpdateMode = updates::setMode,
                                onForget = { settings.forget() },
                                onDismissError = settings::dismissError,
                                onOpenUsage = onOpenUsage,
                                onOpenCompany = onOpenCompany,
                            ),
                            fleetPageOpen = fleetPageOpen,
                            fleetPage = fleetSection,
                            notifier = container.notifier,
                            homeExtras = { LayoutRow(layout, onSetLayout) },
                            thisPhoneExtras = {
                                HelpSettingsSection(
                                    help = help,
                                    settings = helpSettings,
                                    tourAvailable = layout == PhoneLayout.New,
                                    onTour = { helpSettings.startTour(); nav.select(Tab.Inbox) },
                                    onPractice = { practice.reset(); nav.openPractice() },
                                )
                            },
                        )
                    } else {
                        // A fleet settings page is drawn inside the Settings tab:
                        // back closes the page, not the app.
                        BackHandler(enabled = fleetPageOpen) { fleetSettings.back() }
                        // Classic has no More: Update ready sits at the top of Settings.
                        Column(Modifier.fillMaxSize()) {
                            updateState.available?.let { UpdateCard(it, container.appVersion, onOpen = nav::openUpdate) }
                            SettingsScreen(
                                state = state,
                                onForget = { settings.forget() },
                                onDismissError = settings::dismissError,
                                fleetPageOpen = fleetPageOpen,
                                onOpenUsage = onOpenUsage,
                                onOpenCompany = onOpenCompany,
                                notifier = container.notifier,
                                layout = layout,
                                onSetLayout = onSetLayout,
                                fleetSettings = fleetSection,
                            )
                        }
                    }
                }
            }
            help.lesson?.let { (lesson, step) ->
                LessonBar(
                    lesson = lesson,
                    step = step,
                    onNext = helpSettings::nextStep,
                    onEnd = helpSettings::endLesson,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            whatsNew?.let { news ->
                WhatsNewScreen(
                    whatsNew = news,
                    backLabel = if (layout == PhoneLayout.New) "Back to Inbox" else "Back to Sessions",
                    onBack = { whatsNew = null },
                )
            }
            // Today (with its Tidy) and Missions open from Inbox, Control and More on the New bar
            // as well as from the Sessions header, so they are drawn over any tab.
            val todayOverlay by today.state.collectAsState()
            val todaySessions by repository.sessions.collectAsState()
            if (todayOverlay.open) {
                TodaySheet(
                    state = todayOverlay,
                    handlers = TodayHandlers(
                        onClose = today::close,
                        onRefresh = { today.refresh() },
                        onOpenSession = today::openSession,
                        onDismissError = today::dismissError,
                        onToggleSection = today::toggleSection,
                        onSetHost = today::setHost,
                        onToggleTicketsOnly = today::toggleTicketsOnly,
                        onClearFilters = today::clearFilters,
                        onOpenTidy = { today.close(); tidy.open(); Unit }.takeIf { tidyState.available },
                    ),
                    // New: Waiting on me is the Inbox's own list, so the counts agree.
                    waitingNow = if (layout == PhoneLayout.New) inboxRows(todaySessions) else null,
                    nowSeconds = epochSeconds(),
                )
            }
            if (tidyState.open) {
                TidySheet(
                    state = tidyState,
                    handlers = TidyHandlers(
                        onClose = tidy::close,
                        onToggle = tidy::toggle,
                        onChoose = tidy::choose,
                        onApply = { tidy.apply() },
                        onOpenSession = { id -> tidy.close(); nav.openFrom(id, SessionsSheet.Tidy) },
                        onDismissReopened = { tidy.dismissReopened(it) },
                        onDismissError = tidy::dismissError,
                    ),
                )
            }
            if (missionsState.open) {
                MissionsSheet(
                    state = missionsState,
                    handlers = MissionsHandlers(
                        onClose = missions::close,
                        onRefresh = { missions.refresh() },
                        onSelect = { missions.select(it) },
                        onBack = missions::back,
                        onStart = { missions.start(it) },
                        onDecide = { card, ok, note -> missions.decide(card, ok, note) },
                        onTogglePause = { missions.togglePause() },
                        onPauseAll = { missions.pauseAll() },
                        onDismissError = missions::dismissError,
                    ),
                )
            }
        }
    }
    // The tour runs on the real Inbox, over the bar as well, and stops for nothing else.
    val stop = help.tourStop
    if (stop != null && screen == Screen.Inbox && !fleetCheck) {
        TourOverlay(stop, tourAnchors, onNext = helpSettings::nextStop, onSkip = helpSettings::skipTour)
    }
    // Once, after pairing and the fleet check: how much help.
    if (help.mode == null && !fleetCheck && whatsNew == null) {
        HelpPicker(
            onPick = { mode -> helpSettings.pick(mode, tourHere = layout == PhoneLayout.New && screen == Screen.Inbox) },
            onPracticeFirst = { mode ->
                helpSettings.pick(mode, tourHere = false)
                practice.reset()
                nav.openPractice()
            },
        )
    }
    // Over the whole fleet, in App's Box: the first connection after a pair.
    if (fleetCheck) {
        val status by repository.status.collectAsState()
        FleetCheck(
            status = status,
            hub = hubLabel(credentials.hub),
            clientName = credentials.name,
            exitLabel = if (layout == PhoneLayout.New) FullscreenWait.FleetCheck.exitLabel else "Skip, open Sessions",
            onDone = onFleetCheckDone,
        )
    }
}

/** A destination on the New bar, keyed by its [Tab] name so `Navigator.select` can take it back. */
private fun newBarItem(tab: Tab): BottomBarItem = when (tab) {
    Tab.Inbox -> BottomBarItem(tab.name, "Inbox", OrbitIcons.Inbox)
    Tab.Sessions -> BottomBarItem(tab.name, "Sessions", OrbitIcons.Sessions)
    Tab.Control -> BottomBarItem(tab.name, "Control", OrbitIcons.Control)
    Tab.Work -> BottomBarItem(tab.name, "Work", OrbitIcons.Work)
    else -> BottomBarItem(tab.name, "More", OrbitIcons.More)
}

// The wizard's `BackHandler`; see the opt-in note on `FleetRoute`.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NewSessionRoute(
    initialHost: String?,
    ticketKey: String?,
    initialProject: Long? = null,
    container: AppContainer,
    repository: FleetRepository,
    credentials: Credentials,
    callScope: CoroutineScope,
    onCreated: (Long) -> Unit,
    onBack: () -> Unit,
    wizard: Boolean = false,
) {
    val scope = rememberWorkScope()
    val vm = remember(repository, scope) {
        NewSessionViewModel(
            fleet = repository,
            actions = container.newSessionActions,
            scope = scope,
            canWrite = credentials.canWrite,
            initialHost = initialHost,
            onCreated = onCreated,
            callScope = callScope,
            ticketKey = ticketKey,
            workActions = container.workActions,
        )
    }
    val state by vm.state.collectAsState()
    LaunchedEffect(vm, initialProject) { initialProject?.let(vm::selectProject) }
    // The New layout's wizard step lives here so its back gesture is one of
    // `App`'s handlers: on Project or Review it goes one step back; on Where
    // (and while a start runs, which is safe to leave) `nav.back()` leaves.
    var wizardStep by remember { mutableStateOf(WizardStep.Where) }
    BackHandler(enabled = wizard && wizardStep.previous != null && !state.creating) { wizardStep.previous?.let { wizardStep = it } }
    val tools = remember(repository, scope) { ProjectToolsViewModel(repository, container.projectActions, scope, credentials.canWrite) }
    val toolsState by tools.state.collectAsState()
    LaunchedEffect(tools, state.host, state.projectId) { tools.loadWorktrees(state.host, state.projectId) }
    NewSessionScreen(
        state = state,
        onBack = onBack,
        onSelectHost = vm::selectHost,
        onProjectQuery = vm::onProjectQuery,
        onSelectProject = vm::selectProject,
        onNewWorktree = vm::setNewWorktree,
        onBranchChange = vm::onBranchChange,
        onBaseBranchChange = vm::onBaseBranchChange,
        onFriendlyNameChange = vm::onFriendlyNameChange,
        onCreate = { vm.create() },
        onDismissError = vm::dismissError,
        // An agent the hub could not match to a row yet has no screen to
        // open: back to the list, where it appears with the next pass.
        onStartBackground = { name, prompt -> vm.startBackground(name, prompt) { onBack() } },
        tools = toolsState,
        toolHandlers = ProjectToolsHandlers(
            onOpenAdd = { tools.openAdd(it) },
            onCloseAdd = tools::closeAdd,
            onClone = { url -> tools.clone(url, vm::selectProject) },
            onCreate = { owner, repo, onGithub -> tools.create(owner, repo, onGithub, vm::selectProject) },
            onConfirmCreate = { tools.confirmCreate(vm::selectProject) },
            onCancelCreate = tools::cancelCreate,
            onDeleteWorktree = { tools.deleteWorktree(it) },
            onDismissError = tools::dismissError,
        ),
        onSelectWorktree = vm::selectWorktree,
        multiStart = MultiStartHandlers(
            onToggle = vm::toggleAlsoIn,
            onConfirm = { vm.confirmMultiStart() },
            onCancel = vm::cancelMultiStart,
            onOpen = vm::openStarted,
            onDone = vm::dismissResult,
        ),
        wizard = wizard,
        wizardStep = wizardStep,
        onWizardStep = { wizardStep = it },
    )
}

@Composable
private fun TaskRoute(
    taskId: String,
    container: AppContainer,
    repository: FleetRepository,
    credentials: Credentials,
    knownGroups: () -> List<GroupRef>,
    callScope: CoroutineScope,
    onOpenSession: (Long) -> Unit,
    onStartHere: (String) -> Unit,
    onBack: () -> Unit,
    newLayout: Boolean = false,
) {
    val scope = rememberWorkScope()
    val vm = remember(taskId, repository, scope) {
        TaskViewModel(
            taskId = taskId,
            fleet = repository,
            actions = container.workActions,
            scope = scope,
            // `work_link` is not readonly; the view model checks the hub's
            // list of actions as well.
            canWrite = credentials.canWrite,
            knownGroups = knownGroups,
            onOpenSession = onOpenSession,
            onStartHere = onStartHere,
            callScope = callScope,
        )
    }
    val state by vm.state.collectAsState()
    val status by repository.status.collectAsState()
    val handlers = TaskHandlers(
        onBack = onBack,
        onRefresh = { vm.refresh() },
        onOpenSession = vm::openSession,
        onContinue = { vm.continueWork() },
        onStartHere = vm::startHere,
        onOpenPlace = vm::openPlace,
        onClosePlace = vm::closePlace,
        onPlace = { group, note -> vm.place(group, note) },
        onClearPlacement = { vm.clearPlacement() },
        onDismissError = vm::dismissError,
        onSummarize = { vm.summarize(it) },
        onDismissSummary = vm::dismissSummary,
    )
    if (newLayout) {
        val rows by repository.sessions.collectAsState()
        PhoneTaskScreen(
            state = state,
            status = status,
            nowSeconds = epochSeconds(),
            rowOf = { id -> rows.firstOrNull { it.id == id } },
            handlers = PhoneTaskHandlers(
                task = handlers,
                onLink = { vm.confirmLink(it) },
                onNotThis = { vm.rejectLink(it) },
                onClearSummary = vm::clearSummary,
            ),
        )
    } else {
        TaskScreen(state = state, status = status, handlers = handlers)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SessionRoute(
    sessionId: Long,
    container: AppContainer,
    repository: FleetRepository,
    credentials: Credentials,
    onBack: () -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenSession: (Long) -> Unit,
    onOpenRepo: (Long) -> Unit,
    callScope: CoroutineScope,
    newLayout: Boolean = false,
) {
    val scope = rememberWorkScope()
    val vm = remember(sessionId, repository, scope, newLayout) {
        SessionViewModel(
            sessionId = sessionId,
            fleet = repository,
            actions = container.sessionActions,
            scope = scope,
            // `send_prompt` is not in the hub's readonly allow-list, so a
            // readonly credential disables the box rather than making a call it
            // knows would be refused.
            canSendPrompts = credentials.canWrite,
            // The container's one instance, not a fresh one per session — see
            // `AppContainer.quickReplies`.
            quickReplies = container.quickReplies,
            drafts = container.drafts,
            // The New bar keeps a refused prompt in the conversation (redesign 14.5).
            keepNotSent = newLayout,
        )
    }
    val workVm = remember(sessionId, repository, scope) {
        SessionWorkViewModel(
            sessionId = sessionId,
            fleet = repository,
            actions = container.workActions,
            scope = scope,
            // `work_link` is not readonly; the hub hides it from such a token
            // too, and the view model checks both.
            canWrite = credentials.canWrite,
        )
    }
    val tasksVm = remember(sessionId, repository, scope) {
        SessionTasksViewModel(
            sessionId = sessionId,
            fleet = repository,
            actions = container.workActions,
            scope = scope,
            canWrite = credentials.canWrite,
            onOpenTask = onOpenTask,
            callScope = callScope,
        )
    }
    val toolDetailsModel = remember(sessionId, repository, scope) {
        ToolDetailsModel(
            sessionId = sessionId,
            fleet = repository,
            actions = container.sessionActions,
            scope = scope,
        )
    }
    LaunchedEffect(sessionId) { vm.load() }

    val state by vm.state.collectAsState()
    val work by workVm.state.collectAsState()
    val tasks by tasksVm.state.collectAsState()
    val status by repository.status.collectAsState()
    // Collected here, not folded into `SessionUiState`: `QuickReplies.chips`
    // is its own `StateFlow`, one per app rather than one per session, and
    // `status` right above is the same shape for the same reason — a value
    // `SessionViewModel.state`'s own `combine` does not own.
    val chips by vm.quickReplies.chips.collectAsState()
    // A hub older than the shared chip row has nowhere to put an edit, so the
    // row draws its cached chips and offers no editor. Collected here rather
    // than folded into `SessionUiState` for the same reason `chips` is: it is
    // the fleet's own value, not one this screen's view model owns.
    val caps by repository.capabilities.collectAsState()
    val toolDetailStates by toolDetailsModel.states.collectAsState()
    val detailsVm = remember(sessionId, repository, scope) {
        SessionDetailsViewModel(
            sessionId = sessionId,
            fleet = repository,
            actions = container.sessionDetailsActions,
            scope = scope,
            canWrite = credentials.canWrite,
            clock = { epochSeconds() },
        )
    }
    val details by detailsVm.state.collectAsState()
    val moveVm = remember(sessionId, repository, scope) {
        MoveViewModel(sessionId, repository, container.moveActions, scope, credentials.canWrite)
    }
    val move by moveVm.state.collectAsState()
    // The New bar's tabs (redesign 14.4): the conversation, the agent's own
    // screen, the worktree (instead of the worktree screen) and Details
    // (instead of the sheet). Each reads when it is opened.
    val hasWorktree = caps.repo || caps.repoLog || caps.repoFiles
    var sessionTab by remember(sessionId) { mutableStateOf(SessionTab.Conversation) }
    val repoVm = if (newLayout && hasWorktree) {
        remember(sessionId, repository, scope) {
            RepoViewModel(
                sessionId = sessionId,
                fleet = repository,
                actions = container.repoActions,
                downloads = container.downloadActions,
                scope = scope,
                canWrite = credentials.canWrite,
            )
        }
    } else {
        null
    }
    val repoFiles = repoVm?.state?.collectAsState()?.value
    fun selectTab(next: SessionTab) {
        val was = sessionTab
        if (next == was) return
        sessionTab = next
        // The agent's pane is captured while its tab shows, and dropped after.
        if (was == SessionTab.Agent) vm.hideTerminal()
        when (next) {
            SessionTab.Agent -> vm.showTerminal()
            SessionTab.Files -> repoVm?.load()
            SessionTab.Details -> detailsVm.open()
            SessionTab.Conversation -> Unit
        }
    }
    // A tapped notification lands on the conversation, where the question card is (redesign 14.8).
    val focus by container.questionFocus.collectAsState()
    LaunchedEffect(focus, sessionId) {
        if (focus == sessionId && container.consumeQuestionFocus(sessionId)) selectTab(SessionTab.Conversation)
    }
    // Back closes an open diff, commit or file in the Files tab before it
    // leaves the session; composed after `App`'s handler, so asked first.
    val filesOpen = newLayout && sessionTab == SessionTab.Files && repoFiles?.views?.isNotEmpty() == true
    BackHandler(enabled = filesOpen) { repoVm?.back() }
    // A word for the composer from another tab ("Ask Claude Code to commit",
    // a long-pressed diff line): added to the draft, never sent, and the
    // conversation shown so the person sees it before they send it.
    val askInConversation: (String) -> Unit = { text ->
        vm.onDraftChange(appendToDraft(vm.state.value.draft, text))
        selectTab(SessionTab.Conversation)
    }
    // A tool row in an earlier conversation is looked up in that transcript.
    SideEffect { toolDetailsModel.claudeSessionId = state.viewing?.claudeSessionId }
    // Read once per visit: whether the hint is owed does not change under
    // a screen that is showing it.
    val foldHintOwed = remember(container) { !container.hints.shown(Hints.DOUBLE_TAP) }
    SessionScreen(
        sessionId = sessionId,
        state = state,
        status = status,
        onDraftChange = vm::onDraftChange,
        onSend = { vm.send() },
        onRefresh = { vm.refresh() },
        onBack = onBack,
        onDismissError = vm::dismissError,
        onAtBottom = vm::onAtBottom,
        onAnswer = { vm.answer(it) },
        onShowTerminal = { vm.showTerminal() },
        onHideTerminal = vm::hideTerminal,
        onRestart = { vm.restart() },
        onSafeKill = { vm.safeKill() },
        onKill = { vm.kill() },
        onSetTags = { vm.setTags(it) },
        onRename = { vm.rename(it) },
        onSendCommand = { vm.sendCommand(it) },
        quickReplies = chips,
        quickRepliesEditable = caps.quickReplies,
        onSendQuick = { vm.sendQuick(it) },
        // Through the view model, not straight at the store: an edit is a hub
        // write that can fail, and the view model is what turns that into the
        // screen's error banner (and puts the row back).
        onAddQuickReply = { vm.addQuickReply(it) },
        onEditQuickReply = { original, edited -> vm.editQuickReply(original, edited) },
        onRemoveQuickReply = { vm.removeQuickReply(it) },
        onMoveQuickReply = { chip, by -> vm.moveQuickReply(chip, by) },
        onOpenHistory = { vm.quickReplies.history() },
        work = work,
        workHandlers = SessionWorkHandlers(
            onOpen = workVm::openSheet,
            onClose = workVm::closeSheet,
            onConfirm = { workVm.confirm(it) },
            onReject = { workVm.reject(it) },
            onClear = { workVm.clear() },
            onSetWork = { workVm.setWork(it) },
            onDismissError = workVm::dismissError,
            onHandover = { workVm.handover() },
            onNameWork = { title, key -> workVm.nameWork(title, key) },
            onRenameWork = { workVm.renameWork(it) },
        ),
        tasks = tasks,
        tasksHandlers = SessionTasksHandlers(
            onOpen = tasksVm::openSheet,
            onClose = tasksVm::closeSheet,
            onReload = { tasksVm.reload() },
            onOpenTask = tasksVm::openTask,
            onMakePrimary = { tasksVm.makePrimary(it) },
            onRemove = { tasksVm.remove(it) },
            onConfirm = { tasksVm.confirm(it) },
            onReject = { tasksVm.reject(it) },
            onOpenAdd = { tasksVm.openAdd() },
            onCloseAdd = tasksVm::closeAdd,
            onAddQuery = tasksVm::setAddQuery,
            onAdd = { tasksVm.add(it) },
            onAddTyped = { tasksVm.addTyped() },
            onDismissError = tasksVm::dismissError,
            onShareAcrossOrgs = { tasksVm.shareAcrossOrgs() },
            onDismissCrossOrg = tasksVm::dismissCrossOrg,
        ),
        toolDetails = ToolDetailsHost(
            available = caps.toolDetail,
            states = toolDetailStates,
            request = toolDetailsModel::request,
        ),
        chat = ChatHost(
            settings = container.fleetSettingsActions.takeIf { caps.settingProposals },
            mayDecideSettings = credentials.canWrite && caps.decideSettingProposals,
        ),
        chatForms = container.chatFormActions.takeIf { caps.ask },
        onRewind = { anchor -> vm.rewind(anchor) },
        onRetry = { anchor, prompt -> vm.retry(anchor, prompt) },
        // A fork is a new session: open it, with this one a Back away.
        onFork = { anchor, worktree -> vm.fork(anchor, worktree, onOpenSession) },
        onOpenDetails = { if (newLayout) selectTab(SessionTab.Details) else detailsVm.open() },
        onLoadOlder = { vm.loadOlder() },
        onViewConversation = { vm.view(it) },
        onBackToCurrent = vm::backToCurrent,
        onRecreate = { vm.recreate() },
        onReview = { prompt -> vm.spawnReview(prompt, onOpenSession) },
        onRepair = { vm.repair() },
        onDismissRepair = vm::dismissRepair,
        onPressEnter = { vm.pressEnter() },
        onStop = { vm.interrupt() },
        // The New bar opens Move with no host chosen (redesign 14.5).
        onMove = { moveVm.open(fresh = newLayout) }.takeIf { move.available },
        // A dismissed ghost has no screen left to show: back to where it was opened from.
        onDismissGhost = { vm.dismissGhost(onBack) },
        onOpenRepo = (if (newLayout) ({ selectTab(SessionTab.Files) }) else ({ onOpenRepo(sessionId) })).takeIf { hasWorktree },
        showFoldHint = foldHintOwed,
        onFoldHintShown = { container.hints.markShown(Hints.DOUBLE_TAP) },
        onAnswerInWords = { vm.answerInWords() },
        onRetryNotSent = { vm.retryNotSent() },
        onEditNotSent = vm::editNotSent,
        onRetryLastTurn = { vm.retryLastTurn(it) },
        tabs = if (!newLayout) {
            null
        } else {
            SessionTabsHost(
                tabs = sessionTabs(hasWorktree),
                selected = sessionTab,
                agent = agentName(state.session),
                onSelect = ::selectTab,
                files = {
                    if (repoVm != null && repoFiles != null) {
                        RepoBody(
                            state = repoFiles,
                            handlers = RepoHandlers(
                                onRefresh = { repoVm.refresh() },
                                onSelect = { repoVm.select(it) },
                                onQuery = repoVm::setQuery,
                                onOpenDiff = { repoVm.openDiff(it) },
                                onOpenCommit = { repoVm.openCommit(it) },
                                onOpenCommitDiff = { hash, path -> repoVm.openCommitDiff(hash, path) },
                                onOpenFile = { repoVm.openFile(it) },
                                onMoreLog = { repoVm.moreLog() },
                                onSendToDownloads = { repoVm.sendToDownloads(it) },
                                onDismissError = repoVm::dismissError,
                                onDismissNotice = repoVm::dismissNotice,
                                onAsk = askInConversation.takeIf { !state.readOnly },
                                onClose = { repoVm.back() },
                            ),
                        )
                    }
                },
                details = {
                    val rows by repository.sessions.collectAsState()
                    SessionDetailsList(
                        state = details,
                        sessions = rows,
                        handlers = SessionDetailsHandlers(
                            onReload = { detailsVm.reload() },
                            onToggle = detailsVm::toggle,
                            onCancelTask = { detailsVm.cancel(it) },
                            onOpenSession = onOpenSession,
                            onDismissError = detailsVm::dismissError,
                            onOpenRepo = { selectTab(SessionTab.Files) }.takeIf { hasWorktree },
                        ),
                        actions = listOfNotNull(
                            DetailsAction("Move to host…", { moveVm.open(fresh = true) }).takeIf { move.available && state.canManage },
                            DetailsAction(if (tasks.count > 0) "Tasks ${tasks.count}" else "Tasks", tasksVm::openSheet).takeIf { tasks.available },
                            work.chip?.key?.let { key -> DetailsAction("Ticket $key", workVm::openSheet) },
                        ),
                    )
                },
            )
        },
    )
    if (move.open) {
        MoveSheet(
            state = move,
            nowSeconds = epochSeconds(),
            orbit = newLayout,
            handlers = MoveHandlers(
                onClose = moveVm::close,
                onTarget = { moveVm.selectTarget(it) },
                onKeepSource = moveVm::setKeepSource,
                onWhenIdle = moveVm::setWhenIdle,
                // The moved session is another row: open it, with this one a Back away.
                onMove = { moveVm.move(onOpenSession) },
                onCancelWait = { moveVm.cancelWait() },
                onDismissError = moveVm::dismissError,
            ),
        )
    }
    // On the New bar Details is a tab, not a sheet.
    if (details.open && !newLayout) {
        val rows by repository.sessions.collectAsState()
        SessionDetailsSheet(
            state = details,
            sessions = rows,
            handlers = SessionDetailsHandlers(
                onClose = detailsVm::close,
                onReload = { detailsVm.reload() },
                onToggle = detailsVm::toggle,
                onCancelTask = { detailsVm.cancel(it) },
                // Another session: close the sheet, then open it a Back away.
                onOpenSession = { id -> detailsVm.close(); onOpenSession(id) },
                onDismissError = detailsVm::dismissError,
                onOpenRepo = { detailsVm.close(); onOpenRepo(sessionId) }.takeIf { caps.repo || caps.repoLog || caps.repoFiles },
            ),
        )
    }
}

/**
 * A session's worktree (changes, history, files), read-only. Back steps out
 * of an open diff, commit or file before it leaves the screen — the inner
 * handler is composed after `App`'s, so it is asked first.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RepoRoute(
    sessionId: Long,
    container: AppContainer,
    repository: FleetRepository,
    credentials: Credentials,
    onBack: () -> Unit,
) {
    val scope = rememberWorkScope()
    val vm = remember(sessionId, repository, scope) {
        RepoViewModel(
            sessionId = sessionId,
            fleet = repository,
            actions = container.repoActions,
            downloads = container.downloadActions,
            scope = scope,
            canWrite = credentials.canWrite,
        )
    }
    LaunchedEffect(vm) { vm.load() }
    val state by vm.state.collectAsState()
    BackHandler(enabled = state.views.isNotEmpty()) { vm.back() }
    RepoScreen(
        state = state,
        handlers = RepoHandlers(
            onBack = { if (!vm.back()) onBack() },
            onRefresh = { vm.refresh() },
            onSelect = { vm.select(it) },
            onQuery = vm::setQuery,
            onOpenDiff = { vm.openDiff(it) },
            onOpenCommit = { vm.openCommit(it) },
            onOpenCommitDiff = { hash, path -> vm.openCommitDiff(hash, path) },
            onOpenFile = { vm.openFile(it) },
            onMoreLog = { vm.moreLog() },
            onSendToDownloads = { vm.sendToDownloads(it) },
            onDismissError = vm::dismissError,
            onDismissNotice = vm::dismissNotice,
        ),
    )
}
