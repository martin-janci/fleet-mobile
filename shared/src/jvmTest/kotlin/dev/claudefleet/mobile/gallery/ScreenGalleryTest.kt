@file:OptIn(ExperimentalMaterial3Api::class)

package dev.claudefleet.mobile.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.*
import dev.claudefleet.mobile.net.json
import dev.claudefleet.mobile.ui.*
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.theme.FleetTheme
import kotlinx.serialization.Serializable
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Renders every screen and sheet of the app, with sample data, to PNG files
 * under `shared/build/screens/` — a gallery for design work, not a test.
 * Each shot is independent: one that throws is logged and the rest go on.
 */
class ScreenGalleryTest {

    private val out = File(System.getProperty("user.dir"), "build/screens").apply { mkdirs() }
    private val log = StringBuilder()

    private val dpW = 412
    private val dpH = 892
    private val scale = 2.625f

    private fun shot(name: String, heightDp: Int = dpH, content: @Composable () -> Unit) {
        try {
            val scene = ImageComposeScene(
                width = (dpW * scale).toInt(),
                height = (heightDp * scale).toInt(),
                density = Density(scale),
                content = {
                    FleetTheme { Surface(modifier = Modifier.fillMaxSize()) { content() } }
                },
            )
            try {
                var t = 0L
                repeat(120) {
                    scene.render(t)
                    t += 16_666_667L
                }
                val image = scene.render(t)
                val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("encode failed")
                File(out, "$name.png").writeBytes(data.bytes)
                log.appendLine("ok   $name")
            } finally {
                scene.close()
            }
        } catch (t: Throwable) {
            log.appendLine("FAIL $name: ${t::class.simpleName}: ${t.message}")
            var c: Throwable? = t; while (c != null) { log.appendLine("  cause: ${c::class.simpleName}: ${c.message?.take(600)}"); c = c.cause }
        }
    }

    @Composable
    private fun WithNav(tab: Tab, attention: Int = 3, review: Int = 2, content: @Composable () -> Unit) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    for (entry in Tab.entries) {
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = {},
                            icon = {
                                val icon = when (entry) {
                                    Tab.Sessions -> FleetIcons.Sessions
                                    Tab.Work -> FleetIcons.Work
                                    Tab.Files -> FleetIcons.Files
                                    Tab.Hosts -> FleetIcons.Hosts
                                    Tab.Settings -> FleetIcons.Settings
                                }
                                if (entry == Tab.Sessions && attention > 0) {
                                    BadgedBox(badge = { Badge { Text("$attention") } }) { Icon(icon, contentDescription = entry.name) }
                                } else if (entry == Tab.Work && review > 0) {
                                    BadgedBox(badge = { Badge { Text("$review") } }) { Icon(icon, contentDescription = entry.name) }
                                } else {
                                    Icon(icon, contentDescription = entry.name)
                                }
                            },
                            label = { Text(entry.name) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) { content() }
        }
    }

    @Test
    fun render_gallery() {
        val all = listOf(
            ::pair, ::sessions, ::session, ::work, ::files, ::hosts, ::settings, ::newSession, ::repo, ::sheets,
        )
        for (group in all) {
            try {
                group()
            } catch (t: Throwable) {
                log.appendLine("FAIL group ${group.name}: $t"); var c: Throwable? = t.cause; while (c != null) { log.appendLine("  cause: ${c::class.simpleName}: ${c.message?.take(600)}"); c = c.cause }
            }
        }
        File(out, "_render-log.txt").writeText(log.toString())
        println(log)
    }

    // ---------------------------------------------------------------- data

    private val now = Fixtures.NOW
    private val connected = ConnectionStatus.Connected(hubVersion = "0.9.4")

    // ---------------------------------------------------------------- pair

    private fun pair() {
        val noop: (String) -> Unit = {}
        shot("01-pair-empty") {
            PairScreen(PairUiState(cameraAvailable = true), noop, noop, {}, {}, noop, noop, {}, {})
        }
        shot("02-pair-filled") {
            PairScreen(
                PairUiState(address = "https://fleet.janci.dev", code = "K7QF-29XD", cameraAvailable = true),
                noop, noop, {}, {}, noop, noop, {}, {},
            )
        }
        shot("03-pair-pairing") {
            PairScreen(
                PairUiState(address = "https://fleet.janci.dev", code = "K7QF-29XD", cameraAvailable = true, pairing = true),
                noop, noop, {}, {}, noop, noop, {}, {},
            )
        }
        shot("04-pair-error") {
            PairScreen(
                PairUiState(
                    address = "https://fleet.janci.dev",
                    code = "K7QF-29XE",
                    cameraAvailable = true,
                    error = "That code was not accepted. Codes last ten minutes; make a new one on the desktop.",
                ),
                noop, noop, {}, {}, noop, noop, {}, {},
            )
        }
        shot("05-pair-signed-out-reason") {
            PairScreen(
                PairUiState(cameraAvailable = true, reason = "This phone was removed from the fleet on the desktop. Pair again to continue."),
                noop, noop, {}, {}, noop, noop, {}, {},
            )
        }
        shot("06-paired-confirmation") {
            PairedScreen(PairedHub(hub = "https://fleet.janci.dev", clientName = "Pixel 9 Pro", mode = "full"), onContinue = {})
        }
    }

    // ---------------------------------------------------------------- sessions

    private fun baseSessions(): SessionsUiState {
        val rows = Fixtures.sessions
        val groups = groupSessions(rows, Fixtures.hosts, Fixtures.projects, SessionFilters(), now)
        return SessionsUiState(
            groups = groups,
            status = connected,
            attentionCount = rows.count { it.needsAttention },
            shown = rows.size,
            total = rows.size,
            nowSeconds = now,
            workAvailable = true,
            pinned = rows.filter { it.needsAttention },
            myWorkAvailable = true,
            orgChoices = listOf(OrgInfo(1, "Personal", "#6750A4"), OrgInfo(2, "Sefcik & co", "#2E7D32")),
            hostChoices = Fixtures.hosts.map { HostFilterChoice(it.alias, it.reachable) },
            projectChoices = Fixtures.projects.map { ProjectFilterChoice(it.id, "${it.owner}/${it.repo}") },
        )
    }

    private fun sessionsHandlers() = SessionsHandlers(
        onNewSession = {},
        onOpenTickets = {},
        onOpenToday = {},
        onOpenMissions = {},
        onOpenAgent = {},
    )

    private fun sessions() {
        val base = baseSessions()
        val agent = AgentUiState(available = true)
        shot("10-sessions-list") {
            WithNav(Tab.Sessions) { SessionsScreen(base, sessionsHandlers(), agent = agent) }
        }
        shot("11-sessions-list-long", heightDp = 2200) {
            WithNav(Tab.Sessions) { SessionsScreen(base, sessionsHandlers(), agent = agent) }
        }
        shot("12-sessions-needs-you-filter") {
            val f = SessionFilters(needsAttentionOnly = true)
            WithNav(Tab.Sessions) {
                SessionsScreen(
                    base.copy(
                        filters = f,
                        groups = groupSessions(Fixtures.sessions, Fixtures.hosts, Fixtures.projects, f, now),
                        pinned = emptyList(),
                        shown = base.attentionCount,
                    ),
                    sessionsHandlers(),
                    agent = agent,
                )
            }
        }
        shot("13-sessions-by-urgency") {
            WithNav(Tab.Sessions) {
                SessionsScreen(
                    base.copy(groupMode = GroupMode.URGENCY, groups = emptyList(), pinned = emptyList(), urgent = Fixtures.sessions.byTriage(now = now)),
                    sessionsHandlers(),
                    agent = agent,
                )
            }
        }
        shot("14-sessions-by-work") {
            WithNav(Tab.Sessions) {
                SessionsScreen(
                    base.copy(
                        groupMode = GroupMode.WORK,
                        groups = groupSessions(Fixtures.sessions, Fixtures.hosts, Fixtures.projects, SessionFilters(), now, byWork = true),
                    ),
                    sessionsHandlers(),
                    agent = agent,
                )
            }
        }
        shot("15-sessions-search") {
            val f = SessionFilters(query = "fleet")
            WithNav(Tab.Sessions) {
                SessionsScreen(
                    base.copy(
                        filters = f,
                        searchOpen = true,
                        groups = groupSessions(Fixtures.sessions, Fixtures.hosts, Fixtures.projects, f, now),
                        pinned = emptyList(),
                    ),
                    sessionsHandlers(),
                    agent = agent,
                    hits = SearchHits(
                        query = "fleet",
                        hosts = emptyList(),
                        projects = Fixtures.projects.filter { it.repo.contains("fleet") },
                        ticket = false,
                    ),
                )
            }
        }
        shot("16-sessions-bulk-select") {
            WithNav(Tab.Sessions) {
                SessionsScreen(
                    base,
                    sessionsHandlers(),
                    agent = agent,
                    bulk = BulkUiState(enabled = true, selecting = true, selected = setOf(101L, 104L, 107L), killable = 2),
                )
            }
        }
        shot("17-sessions-offline-stale") {
            WithNav(Tab.Sessions) {
                SessionsScreen(
                    base.copy(status = ConnectionStatus.Offline("hub unreachable"), staleFor = "12 min"),
                    sessionsHandlers(),
                    agent = agent,
                )
            }
        }
        shot("18-sessions-reconnecting") {
            WithNav(Tab.Sessions) {
                SessionsScreen(
                    base.copy(status = ConnectionStatus.Reconnecting(attempt = 3, reason = "connection reset"), connecting = true),
                    sessionsHandlers(),
                    agent = agent,
                )
            }
        }
        shot("19-sessions-empty") {
            WithNav(Tab.Sessions, attention = 0) {
                SessionsScreen(SessionsUiState(status = connected, nowSeconds = now), sessionsHandlers())
            }
        }
        shot("20-sessions-error-banner") {
            WithNav(Tab.Sessions) {
                SessionsScreen(
                    base.copy(error = Friendly("Couldn't refresh", "The hub took too long to answer. Pull to try again.", isError = true)),
                    sessionsHandlers(),
                    agent = agent,
                )
            }
        }
        shot("21-sessions-filters-sheet") {
            SessionsScreen(base, sessionsHandlers(), agent = agent)
            SessionFiltersSheet(
                base.copy(
                    filtersOpen = true,
                    filters = SessionFilters(window = TimeWindow.entries[1], hostFilter = "mercury"),
                    workStatusNameChoices = listOf("To Do", "In Progress", "In Review", "Done"),
                ),
                SessionFiltersHandlers(),
            )
        }
    }

    // ---------------------------------------------------------------- one session

    private val noopS: (String) -> Unit = {}

    @Composable
    private fun Session(
        state: SessionUiState,
        status: ConnectionStatus = connected,
        work: SessionWorkUiState = SessionWorkUiState(),
        tasks: SessionTasksUiState = SessionTasksUiState(),
    ) {
        SessionScreen(
            sessionId = state.session?.id ?: 1,
            state = state,
            status = status,
            onDraftChange = noopS,
            onSend = {},
            onRefresh = {},
            onBack = {},
            onDismissError = {},
            onAtBottom = {},
            onAnswer = {},
            onShowTerminal = {},
            onHideTerminal = {},
            onRestart = {},
            onSafeKill = {},
            onKill = {},
            onSetTags = {},
            onRename = noopS,
            onSendCommand = noopS,
            quickReplies = Fixtures.quickReplies,
            onSendQuick = noopS,
            onAddQuickReply = {},
            onEditQuickReply = { _, _ -> },
            onRemoveQuickReply = {},
            onOpenHistory = { listOf("run the tests", "commit and push") },
            work = work,
            tasks = tasks,
            onOpenRepo = {},
            onMove = {},
        )
    }

    private fun session() {
        val working = Fixtures.sessions.first { it.id == 101L }
        val blocked = Fixtures.sessions.first { it.id == 102L }
        val work = SessionWorkUiState(work = working.work, card = Fixtures.card, canClear = true, canHandover = true, canRenameWork = true)
        val tasks = SessionTasksUiState(available = true, loaded = true, connected = true, active = Fixtures.links.take(1))
        val st = SessionUiState(
            session = working,
            conversation = Fixtures.conversation,
            loaded = true,
            nowSeconds = now,
            rewindAvailable = true,
            reviewAvailable = true,
            conversations = listOf(ConversationSummary(claudeSessionId = "c0ffee01", model = "claude-opus", turns = 14, compactions = 1, current = true)),
        )
        shot("30-session-conversation") { Session(st, work = work, tasks = tasks) }
        shot("31-session-conversation-long", heightDp = 2400) { Session(st, work = work, tasks = tasks) }
        shot("32-session-draft") { Session(st.copy(draft = "Looks good. Now add a test for the empty host list and push."), work = work, tasks = tasks) }
        shot("33-session-sending") { Session(st.copy(draft = "", sending = true, pending = "Run the full suite and push when green."), work = work) }
        shot("34-session-blocked-permission") {
            Session(
                SessionUiState(
                    session = blocked,
                    conversation = Fixtures.blockedConversation,
                    loaded = true,
                    nowSeconds = now,
                    card = blockedCard(blocked, "0.9.4"),
                ),
            )
        }
        val stuck = Fixtures.sessions.first { it.id == 105L }
        shot("35-session-stuck-trust-prompt") {
            Session(
                SessionUiState(session = stuck, conversation = Conversation(), loaded = true, nowSeconds = now, card = blockedCard(stuck, "0.9.4")),
            )
        }
        shot("36-session-terminal") { Session(st.copy(terminal = Fixtures.terminal), work = work) }
        shot("37-session-offline-readonly") {
            Session(st.copy(connected = false, readOnly = true, hubReachable = false), status = ConnectionStatus.Offline("hub unreachable"), work = work)
        }
        shot("38-session-loading") { Session(SessionUiState(session = working, loading = true, nowSeconds = now)) }
        shot("39-session-empty-new") {
            val fresh = Fixtures.sessions.first { it.id == 108L }
            Session(SessionUiState(session = fresh, conversation = Conversation(), loaded = true, nowSeconds = now))
        }
        shot("40-session-error") {
            Session(
                st.copy(error = Friendly("Not sent", "The session's host did not answer. Your message is kept in the box.", isError = true), errorFromSend = true, draft = "Run the full suite"),
                work = work,
            )
        }
        shot("41-session-failed") {
            val failed = Fixtures.sessions.first { it.id == 104L }
            Session(SessionUiState(session = failed, conversation = Fixtures.failedConversation, loaded = true, nowSeconds = now, recreateAvailable = true, repairAvailable = true))
        }
        shot("42-session-repair-report") {
            val failed = Fixtures.sessions.first { it.id == 104L }
            Session(
                SessionUiState(
                    session = failed,
                    conversation = Fixtures.failedConversation,
                    loaded = true,
                    nowSeconds = now,
                    repairAvailable = true,
                    repair = RepairReport(healthy = false, actions = listOf("Restarted the tmux pane", "Re-attached the conversation"), warnings = listOf("The worktree has 3 uncommitted files")),
                ),
            )
        }
        shot("43-session-work-sheet") {
            Session(st, work = work.copy(sheetOpen = true), tasks = tasks)
        }
        shot("44-session-work-suggestion-sheet") {
            Session(
                st,
                work = SessionWorkUiState(
                    suggested = Fixtures.suggestedWork,
                    sheetOpen = true,
                    suggestion = SuggestionRow(Fixtures.suggestedWork, why = "Branch name and the first prompt mention FLEET-150", besideConfirmed = false, canConfirm = true, canReject = true),
                    canSetWork = true,
                    canNameWork = true,
                ),
            )
        }
        shot("45-session-tasks-sheet") {
            Session(
                st,
                work = work,
                tasks = tasks.copy(
                    sheetOpen = true,
                    active = Fixtures.links.take(2),
                    suggested = Fixtures.links.drop(2).take(1),
                    past = Fixtures.links.drop(3),
                    canMakePrimary = true,
                    canRemove = true,
                    canConfirm = true,
                    canReject = true,
                    canAdd = true,
                    sessionOrgName = "Personal",
                ),
            )
        }
    }

    // ---------------------------------------------------------------- work

    private fun work() {
        val my = MyWorkUiState(
            available = true,
            loaded = true,
            connected = true,
            orgs = Fixtures.workOrgs,
            total = Fixtures.workOrgs.sumOf { it.count },
            viewsAvailable = true,
            views = listOf(WorkView(id = 1, name = "Mine, in progress"), WorkView(id = 2, name = "Mobile")),
            reviewAvailable = true,
            reviewCount = 2,
            rulesAvailable = true,
            archivedHidden = 4,
        )
        val h = MyWorkHandlers(onOpenReview = {}, onOpenRules = {})
        shot("50-work-my-work") { WithNav(Tab.Work) { MyWorkScreen(my, h) } }
        shot("51-work-my-work-long", heightDp = 1800) { WithNav(Tab.Work) { MyWorkScreen(my, h) } }
        shot("52-work-search") { WithNav(Tab.Work) { MyWorkScreen(my.copy(searchOpen = true, filters = WorkTreeFilters(query = "mobile")), h) } }
        shot("53-work-filters") {
            WithNav(Tab.Work) {
                MyWorkScreen(
                    my.copy(
                        filtersOpen = true,
                        filterOrgs = listOf(TreeOrg(id = 1, name = "Personal", color = "#6750A4"), TreeOrg(id = 2, name = "Sefcik & co", color = "#2E7D32")),
                        filterTrackers = listOf(TreeTracker(id = 1, name = "GitHub issues", provider = "github", state = "ok"), TreeTracker(id = 2, name = "Sefcik Jira", provider = "jira", state = "ok")),
                        filters = WorkTreeFilters(mine = true),
                    ),
                    h,
                )
            }
        }
        shot("54-work-rules") {
            WithNav(Tab.Work) {
                MyWorkScreen(
                    my.copy(
                        rulesOpen = true,
                        rules = listOf(
                            WorkRule(id = 1, name = "Fleet repo → Fleet", group = "Fleet", conditions = RuleConditions(repo = "martin-janci/claude-fleet")),
                            WorkRule(id = 2, name = "Mobile repo → Mobile", group = "Mobile", conditions = RuleConditions(repo = "martin-janci/fleet-mobile")),
                        ),
                    ),
                    h,
                )
            }
        }
        shot("55-work-empty") { WithNav(Tab.Work, review = 0) { MyWorkScreen(MyWorkUiState(available = true, loaded = true, connected = true), h) } }
        shot("56-work-offline-stale") { WithNav(Tab.Work) { MyWorkScreen(my.copy(connected = false, stale = "Offline · as of 14:52"), h) } }
        val task = TaskUiState(
            taskId = "item:142",
            detail = Fixtures.taskDetail,
            active = Fixtures.links.take(2),
            suggested = Fixtures.links.drop(2).take(1),
            past = Fixtures.links.drop(3),
            orgLine = "Personal · from the tracker",
            groupLine = "Mobile · by rule \"Mobile repo → Mobile\"",
            connected = true,
            canPlace = true,
            canContinue = true,
            canStart = true,
            canSummarize = true,
        )
        shot("57-task-detail") { WithNav(Tab.Work) { TaskScreen(task, connected) } }
        shot("58-task-detail-long", heightDp = 1800) { WithNav(Tab.Work) { TaskScreen(task, connected) } }
        shot("59-task-place-dialog") { WithNav(Tab.Work) { TaskScreen(task.copy(placeOpen = true, knownGroups = listOf("Fleet", "Mobile", "Security")), connected) } }
        shot("60-task-past-summary") {
            WithNav(Tab.Work) {
                TaskScreen(
                    task.copy(summary = PastWorkSummary(key = "FLEET-142", model = "claude-haiku", summary = "Split the hub client into read and write halves, added retry on 503, and left the SSE reconnect for a follow-up. Tests green; PR #118 merged.")),
                    connected,
                )
            }
        }
    }

    // ---------------------------------------------------------------- files

    private fun files() {
        val handlers = FilesHandlers({}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        val st = FilesUiState(
            available = true,
            loaded = true,
            canRemove = true,
            usage = "5 files · 38 MB",
            files = Fixtures.files,
            handoffs = Handoff.entries.toList(),
        )
        shot("70-files-list") { WithNav(Tab.Files) { FilesScreen(st, connected, handlers) } }
        shot("71-files-transfer") {
            WithNav(Tab.Files) { FilesScreen(st.copy(transfer = Transfer(3, "release-0.9.4.apk", received = 14_000_000, total = 31_000_000)), connected, handlers) }
        }
        shot("72-files-opened") {
            WithNav(Tab.Files) { FilesScreen(st.copy(opened = OpenedFile(1, "coverage-report.html", "/data/files/coverage-report.html", "412 KB")), connected, handlers) }
        }
        shot("73-files-confirm-remove") { WithNav(Tab.Files) { FilesScreen(st.copy(confirmRemove = Fixtures.files[1]), connected, handlers) } }
        shot("74-files-empty") { WithNav(Tab.Files) { FilesScreen(FilesUiState(available = true, loaded = true), connected, handlers) } }
    }

    // ---------------------------------------------------------------- hosts

    private fun hosts() {
        val st = HostsUiState(hosts = Fixtures.hostLines, status = connected)
        shot("80-hosts-list") { WithNav(Tab.Hosts) { HostsScreen(st, {}, {}, {}, onHostDetails = {}) } }
        shot("81-hosts-empty") { WithNav(Tab.Hosts) { HostsScreen(HostsUiState(status = connected), {}, {}, {}) } }
        shot("82-host-detail-sheet") {
            WithNav(Tab.Hosts) { HostsScreen(st, {}, {}, {}, onHostDetails = {}) }
            HostDetailSheet(
                HostDetailUiState(alias = "mercury", host = Fixtures.hosts[0], sessionCount = 5, canProbe = true, canRestore = true, canDiscover = true),
                HostDetailHandlers(),
                now,
            )
        }
        shot("83-host-detail-recovery") {
            WithNav(Tab.Hosts) { HostsScreen(st, {}, {}, {}, onHostDetails = {}) }
            HostDetailSheet(
                HostDetailUiState(
                    alias = "oci-arm",
                    host = Fixtures.hosts[3],
                    sessionCount = 2,
                    canProbe = true,
                    canRestore = true,
                    canDiscover = true,
                    plan = Fixtures.restorePlan,
                    candidates = listOf(LostCandidate(claudeSessionId = "5e1f0a", cwd = "~/src/sales-twins-app", resumable = true), LostCandidate(claudeSessionId = "77ab21", cwd = "~/src/scratch", resumable = false)),
                ),
                HostDetailHandlers(),
                now,
            )
        }
    }

    // ---------------------------------------------------------------- settings

    private fun settings() {
        val st = SettingsUiState(hub = "https://fleet.janci.dev", clientName = "Pixel 9 Pro", mode = "full", appVersion = "0.9.4", hubVersion = "0.9.4")
        val fs = Fixtures.fleetSettings
        shot("90-settings") {
            WithNav(Tab.Settings) {
                SettingsScreen(
                    st, {}, {},
                    fleetSettings = { FleetSettingsSection(fs, "Pixel 9 Pro", {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }, {}, {}) },
                    onOpenUsage = {},
                    onOpenCompany = {},
                )
            }
        }
        shot("91-settings-long", heightDp = 2000) {
            WithNav(Tab.Settings) {
                SettingsScreen(
                    st, {}, {},
                    fleetSettings = { FleetSettingsSection(fs, "Pixel 9 Pro", {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }, {}, {}) },
                    onOpenUsage = {},
                    onOpenCompany = {},
                )
            }
        }
        for ((i, page) in fs.pages.take(6).withIndex()) {
            shot("92-fleet-settings-page-${i + 1}-${page.id.replace(Regex("[^a-z0-9]+"), "-")}") {
                WithNav(Tab.Settings) {
                    SettingsScreen(
                        st, {}, {},
                        fleetSettings = { FleetSettingsSection(fs.copy(openPage = page.id), "Pixel 9 Pro", {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }, {}, {}) },
                        fleetPageOpen = true,
                    )
                }
            }
        }
        shot("93-settings-forget-error") {
            WithNav(Tab.Settings) { SettingsScreen(st.copy(error = "Couldn't reach the hub to sign out. The phone forgot it anyway."), {}, {}) }
        }
        val usage = UsageUiState(available = true, accountsAvailable = true, report = Fixtures.usage, accounts = Fixtures.accounts)
        shot("94-usage") { WithNav(Tab.Settings) { UsageScreen(usage, UsageHandlers(), now) } }
        shot("95-usage-long", heightDp = 2000) { WithNav(Tab.Settings) { UsageScreen(usage, UsageHandlers(), now) } }
        shot("96-company-list") { WithNav(Tab.Settings) { CompanyScreen(CompanyUiState(available = true, orgs = Fixtures.orgs), CompanyHandlers(), now) } }
        shot("97-company-org") { WithNav(Tab.Settings) { CompanyScreen(CompanyUiState(available = true, orgs = Fixtures.orgs, openId = 2), CompanyHandlers(), now) } }
    }

    // ---------------------------------------------------------------- new session

    private fun newSession() {
        val st = NewSessionUiState(
            backgroundAvailable = true,
            hosts = Fixtures.hosts.map { HostChoice(it.alias, it.reachable) },
            host = "mercury",
            projects = Fixtures.projects.map { ProjectChoice(it.id, "${it.owner}/${it.repo}") },
            status = connected,
        )
        val ns: @Composable (NewSessionUiState, ProjectToolsUiState) -> Unit = { s, tools ->
            NewSessionScreen(s, {}, noopS, noopS, {}, {}, noopS, noopS, noopS, {}, {}, tools = tools)
        }
        val tools = ProjectToolsUiState(canAdd = true, canListGithub = true, canSeeWorktrees = true)
        shot("100-new-session-pick-host") { ns(st.copy(host = null), tools) }
        shot("101-new-session-pick-project") { ns(st.copy(projectQuery = "fleet"), tools) }
        shot("102-new-session-worktree") {
            ns(
                st.copy(projectId = 2, projectLabel = "martin-janci/fleet-mobile", newWorktree = true, branch = "orbit-redesign", baseBranch = "main", friendlyName = "Orbit redesign", canCreate = true),
                tools.copy(worktrees = HostWorktrees(cloned = true, worktrees = listOf(WorktreeRow(id = 1, name = "main", path = "~/src/fleet-mobile", branch = "main"), WorktreeRow(id = 2, name = "screens", path = "~/src/fleet-mobile-screens", branch = "claude/screens")))),
            )
        }
        shot("103-new-session-from-ticket-multi") {
            ns(
                st.copy(
                    projectId = 1,
                    projectLabel = "martin-janci/claude-fleet",
                    ticketKey = "FLEET-150",
                    canMultiStart = true,
                    alsoIn = listOf(ProjectChoice(2, "martin-janci/fleet-mobile")),
                    alsoInIds = listOf(2),
                    orgLabel = "Personal",
                    canCreate = true,
                    friendlyName = "FLEET-150 quiet hours",
                ),
                tools,
            )
        }
        shot("104-new-session-invalid-branch") {
            ns(st.copy(projectId = 2, projectLabel = "martin-janci/fleet-mobile", newWorktree = true, branch = "bad branch name", branchInvalid = true, missing = "a valid branch name"), tools)
        }
        shot("105-new-session-creating") { ns(st.copy(projectId = 2, projectLabel = "martin-janci/fleet-mobile", creating = true), tools) }
        shot("106-multistart-confirm-sheet") {
            ns(st, tools)
            MultiStartConfirmSheet(MultiStartConfirm("FLEET-150", "mercury", listOf("martin-janci/claude-fleet", "martin-janci/fleet-mobile"), "Personal"), {}, {})
        }
        shot("107-multistart-result-sheet") {
            ns(st, tools)
            MultiStartResultSheet(
                MultiStartResult(
                    "FLEET-150",
                    listOf(
                        ProjectResult(1, "martin-janci/claude-fleet", dev.claudefleet.mobile.ui.StartOutcome.STARTED, "Started on mercury", 120),
                        ProjectResult(2, "martin-janci/fleet-mobile", dev.claudefleet.mobile.ui.StartOutcome.ALREADY_RUNNING, "Already running: mobile-quiet-hours", 121),
                    ),
                ),
                {}, {},
            )
        }
    }

    // ---------------------------------------------------------------- repo

    private fun repo() {
        val row = Fixtures.sessions.first { it.id == 101L }
        val base = RepoUiState(session = row, tabs = RepoTab.entries.toList(), changes = Fixtures.changes, log = Fixtures.commits, tree = Fixtures.tree, canSendFile = true)
        shot("110-repo-changes") { RepoScreen(base, RepoHandlers()) }
        shot("111-repo-history") { RepoScreen(base.copy(tab = RepoTab.History), RepoHandlers()) }
        shot("112-repo-files") { RepoScreen(base.copy(tab = RepoTab.Files), RepoHandlers()) }
        shot("113-repo-diff") { RepoScreen(base.copy(views = listOf(RepoView.Diff("shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/HostsScreen.kt", Fixtures.diff))), RepoHandlers()) }
        shot("114-repo-commit") { RepoScreen(base.copy(tab = RepoTab.History, views = listOf(RepoView.CommitView(Fixtures.commits[0].hash, Fixtures.commitDetail))), RepoHandlers()) }
        shot("115-repo-file") { RepoScreen(base.copy(tab = RepoTab.Files, views = listOf(RepoView.File("README.md", Fixtures.fileContent))), RepoHandlers()) }
    }

    // ---------------------------------------------------------------- sheets over the sessions list

    private fun sheets() {
        val base = baseSessions()
        @Composable
        fun under() = WithNav(Tab.Sessions) { SessionsScreen(base, sessionsHandlers(), agent = AgentUiState(available = true)) }

        val today = Fixtures.todayView
        val todayState = TodayUiState(
            available = true,
            open = true,
            loaded = true,
            view = today,
            shown = today,
            sectionCounts = TodaySection.entries.associateWith { today.count(it) },
            hostChoices = todayHosts(today),
        )
        shot("120-today-sheet") { under(); TodaySheet(todayState, TodayHandlers(onOpenTidy = {})) }
        shot("121-today-sheet-filtered") {
            under()
            TodaySheet(todayState.copy(filters = TodayFilters(sections = setOf(TodaySection.Waiting), host = "mercury")), TodayHandlers(onOpenTidy = {}))
        }
        val tidy = TidyUiState(available = true, open = true, candidates = Fixtures.tidy, ticked = setOf(201L, 202L), chosen = mapOf(202L to TidyChoice.Archive))
        shot("122-tidy-sheet") { under(); TidySheet(tidy, TidyHandlers()) }
        shot("123-tidy-results") {
            under()
            TidySheet(
                tidy.copy(results = listOf(TidyApplyResult(sessionId = 201, action = "safe_kill", ok = true, outcome = "Killed after pushing 2 commits"), TidyApplyResult(sessionId = 202, action = "archive", ok = false, error = "The session changed since the list was made"))),
                TidyHandlers(),
            )
        }
        val tickets = TicketsUiState(
            available = true,
            open = true,
            sections = Fixtures.ticketSections,
            shown = Fixtures.ticketSections.sumOf { it.tickets.size },
            total = 23,
            ticketOrgs = mapOf(1L to "Personal", 2L to "Sefcik & co"),
        )
        shot("124-tickets-sheet") { under(); TicketsSheet(tickets, TicketsHandlers()) }
        shot("125-ticket-detail") {
            under()
            TicketsSheet(
                tickets.copy(
                    selected = TicketDetail(
                        ticket = Fixtures.ticketSections[0].tickets[0],
                        canStart = true,
                        canResume = true,
                        resumeHosts = listOf("mercury", "hetzner-1"),
                        resumeHost = "mercury",
                        card = Fixtures.card,
                        pastWork = listOf(ResumeCandidate(linkId = 9, resumable = true, name = "fleet-quiet-hours", branch = "claude/quiet-hours", conversations = 3)),
                    ),
                ),
                TicketsHandlers(),
            )
        }
        shot("126-tickets-filters") {
            under()
            TicketsSheet(
                tickets.copy(
                    filtersOpen = true,
                    statusNameChoices = listOf("To Do", "In Progress", "In Review"),
                    orgChoices = listOf(OrgInfo(1, "Personal", "#6750A4"), OrgInfo(2, "Sefcik & co", "#2E7D32")),
                    trackerChoices = listOf(TrackerRow(id = 1, provider = "github", name = "GitHub issues", state = "ok"), TrackerRow(id = 2, provider = "jira", name = "Sefcik Jira", state = "ok")),
                ),
                TicketsHandlers(),
            )
        }
        val missions = MissionsUiState(available = true, canStart = true, canDecide = true, canPause = true, canPauseAll = true, open = true, missions = Fixtures.missions)
        shot("127-missions-sheet") { under(); MissionsSheet(missions, MissionsHandlers()) }
        shot("128-mission-detail") { under(); MissionsSheet(missions.copy(detail = Fixtures.missionDetail), MissionsHandlers()) }
        shot("129-review-sheet") {
            WithNav(Tab.Work) { MyWorkScreen(MyWorkUiState(available = true, loaded = true, connected = true, orgs = Fixtures.workOrgs, total = 9, reviewAvailable = true, reviewCount = 3), MyWorkHandlers(onOpenReview = {})) }
            ReviewSheet(
                ReviewUiState(
                    available = true, open = true, loaded = true, connected = true, items = Fixtures.review, total = Fixtures.review.size,
                    canConfirm = true, canReject = true, canKeep = true, canRemove = true, canMakePrimary = true, canChange = true, canBatch = true, batchCount = 2,
                ),
                ReviewHandlers(),
            )
        }
        val row = Fixtures.sessions.first { it.id == 101L }
        shot("130-session-details-sheet") {
            Session(SessionUiState(session = row, conversation = Fixtures.conversation, loaded = true, nowSeconds = now))
            SessionDetailsSheet(
                SessionDetailsUiState(
                    open = true, session = row, historyAvailable = true, relatedAvailable = true, tasksAvailable = true,
                    events = Fixtures.events, related = Fixtures.sessions.filter { it.id == 106L },
                    tasks = listOf(FleetTask(id = 1, prompt = "Review the HostsScreen diff", state = "done", result = "Two nits, otherwise fine")),
                    canCancel = true, nowSeconds = now,
                ),
                SessionDetailsHandlers(onOpenRepo = {}),
                Fixtures.sessions,
            )
        }
        shot("131-move-sheet") {
            Session(SessionUiState(session = row, conversation = Fixtures.conversation, loaded = true, nowSeconds = now))
            MoveSheet(
                MoveUiState(
                    available = true, open = true, targets = Fixtures.hosts.filter { it.alias != "mercury" }, target = "hetzner-1",
                    preview = Fixtures.movePreview, warnings = listOf("hetzner-1 has an older Claude Code (2.0.31)"),
                ),
                MoveHandlers(),
                now,
            )
        }
        shot("132-bulk-outcome-dialog") {
            WithNav(Tab.Sessions) {
                SessionsScreen(
                    base, sessionsHandlers(),
                    bulk = BulkUiState(
                        enabled = true,
                        outcome = listOf(BulkOutcome(101, "Hosts screen polish", true), BulkOutcome(104, "sales twins api", false, "the host did not answer")),
                    ),
                )
            }
        }
    }
}

/** A fleet of five hosts and four projects, as the hub would send it. */
@Suppress("MaxLineLength")
internal object Fixtures {
    const val NOW = 1_791_471_600L
    private fun ago(min: Long) = NOW - min * 60

    internal inline fun <reified T> j(s: String): T = json.decodeFromString(s)

    val hosts: List<HostRow> by lazy {j(
        """[
        {"alias":"mercury","reachable":true,"claude_version":"2.1.4","tmux_version":"3.5a","last_pinged_at":${ago(1)}},
        {"alias":"hetzner-1","reachable":true,"claude_version":"2.0.31","tmux_version":"3.4","last_pinged_at":${ago(2)}},
        {"alias":"nas","reachable":true,"claude_version":"2.1.4","tmux_version":"3.3a","last_pinged_at":${ago(3)},"transport":"agent"},
        {"alias":"oci-arm","reachable":false,"claude_version":"2.1.2","tmux_version":"3.4","last_pinged_at":${ago(190)}}
        ]""",
    )
    }

    val hostLines by lazy {listOf(
        HostLine("mercury", true, "2.1.4", "3.5a", 5, false, "ssh", ago(1)),
        HostLine("hetzner-1", true, "2.0.31", "3.4", 3, false, "ssh", ago(2)),
        HostLine("nas", true, "2.1.4", "3.3a", 1, false, "agent", ago(3)),
        HostLine("oci-arm", false, "2.1.2", "3.4", 2, false, "ssh", ago(190)),
        HostLine("old-laptop", true, null, null, 0, true, "ssh", null),
    )
    }

    val projects by lazy {listOf(
        ProjectRow(id = 1, owner = "martin-janci", repo = "claude-fleet"),
        ProjectRow(id = 2, owner = "martin-janci", repo = "fleet-mobile"),
        ProjectRow(id = 3, owner = "martin-janci", repo = "property-management"),
        ProjectRow(id = 4, owner = "FrantisekSefcik", repo = "sales-twins-app"),
    )
    }

    private const val WORK_142 = """{"link_id":11,"item_id":142,"key":"FLEET-142","title":"Hosts screen: show last ping and transport","source":"tracker","url":"https://github.com/martin-janci/claude-fleet/issues/142","state":"active","status_category":"in_progress","status_name":"In Progress","org_id":1}"""
    private const val WORK_150 = """{"link_id":12,"item_id":150,"key":"FLEET-150","title":"Quiet hours for needs-you notifications","source":"tracker","state":"active","status_category":"todo","status_name":"To Do","org_id":1}"""
    private const val WORK_SAL = """{"link_id":13,"item_id":519,"key":"SAL-519","title":"Verify email language defaulting","source":"tracker","state":"active","status_category":"in_progress","status_name":"In Review","org_id":2}"""

    val sessions: List<SessionRow> by lazy {j(
        """[
        {"id":101,"tmux_name":"hosts-polish","friendly_name":"Hosts screen polish","host_alias":"mercury","project_id":2,"status":"running","claude_status":"working","current_activity":"Running ./gradlew :shared:jvmTest","context_pct":41.0,"created_at":${ago(180)},"last_activity_at":${ago(0)},"branch":"claude/hosts-polish","usage_cost_micros":2310000,"usage_model":"claude-opus","work":$WORK_142,"org_id":1,"tags":["mobile"]},
        {"id":102,"tmux_name":"quiet-hours","friendly_name":"Quiet hours","host_alias":"mercury","project_id":1,"status":"running","claude_status":"blocked","current_activity":"Waiting for permission","pending_input":{"kind":"permission","question":"Allow Bash: cargo fleet-test -- notify::quiet?","options":[{"n":1,"label":"Yes","selected":true},{"n":2,"label":"Yes, and don't ask again for cargo fleet-test"},{"n":3,"label":"No, tell Claude what to do differently"}]},"needs_attention":{"reason":"waiting","since":${ago(7)}},"context_pct":63.0,"created_at":${ago(300)},"last_activity_at":${ago(7)},"branch":"claude/quiet-hours","work":$WORK_150,"org_id":1},
        {"id":103,"tmux_name":"pr-118","friendly_name":"Hub client split","host_alias":"hetzner-1","project_id":1,"status":"running","claude_status":"idle","created_at":${ago(1440)},"last_activity_at":${ago(95)},"last_stop_at":${ago(95)},"branch":"claude/hub-client-split","pr_url":"https://github.com/martin-janci/claude-fleet/pull/118","ci_status":"passing","org_id":1,"context_pct":22.0},
        {"id":104,"tmux_name":"sal-api","friendly_name":"Api tenant resolution","host_alias":"oci-arm","project_id":4,"status":"running","claude_status":"failed","needs_attention":{"reason":"failed","since":${ago(40)}},"created_at":${ago(2000)},"last_activity_at":${ago(40)},"branch":"feature/tenant-resolution","work":$WORK_SAL,"org_id":2},
        {"id":105,"tmux_name":"pm-dispatchers","friendly_name":"Dispatchers","host_alias":"hetzner-1","project_id":3,"status":"running","claude_status":"blocked","stuck_kind":"trust_prompt","created_at":${ago(20)},"last_activity_at":${ago(18)},"org_id":1},
        {"id":106,"tmux_name":"bg:7f3a2c","host_alias":"mercury","project_id":1,"status":"running","claude_status":"working","kind":"background","last_prompt":"Review the HostsScreen diff and list nits","current_activity":"Reading HostsScreen.kt","created_at":${ago(4)},"last_activity_at":${ago(0)},"parent_session_id":101,"org_id":1},
        {"id":107,"tmux_name":"verify-email","friendly_name":"verify email lang defaulting SAL-519","host_alias":"nas","project_id":4,"status":"running","claude_status":"idle","created_at":${ago(4000)},"last_activity_at":${ago(600)},"org_id":2,"work":$WORK_SAL},
        {"id":108,"tmux_name":"orbit-tokens","friendly_name":"Orbit tokens","host_alias":"mercury","project_id":2,"status":"running","claude_status":"idle","created_at":${ago(2)},"last_activity_at":${ago(2)},"branch":"claude/orbit-tokens","org_id":1},
        {"id":109,"tmux_name":"sales-twins","friendly_name":"Sales twins app","host_alias":"oci-arm","project_id":4,"status":"running","claude_status":"unknown","created_at":${ago(9000)},"last_activity_at":${ago(3000)},"org_id":2},
        {"id":110,"tmux_name":"shell-1","host_alias":"mercury","status":"running","kind":"external","created_at":${ago(60)},"last_activity_at":${ago(30)}}
        ]""",
    )
    }

    val suggestedWork: WorkSummary by lazy {j(WORK_150.replace("\"state\":\"active\"", "\"state\":\"suggested\",\"strength\":\"strong\",\"rule\":\"branch\""))
    }

    val card: TicketCard by lazy {j(
        """{"key":"FLEET-142","title":"Hosts screen: show last ping and transport","url":"https://github.com/martin-janci/claude-fleet/issues/142","cached":true,
        "acceptance":["Each host row shows when it was last pinged","Agent-transport hosts are labelled","Unreachable hosts sort last"],
        "excerpt":"The phone's Hosts tab shows reachable/unreachable only. Add the last ping time and the transport so a host on the agent is recognisable."}""",
    )
    }

    val quickReplies by lazy {listOf(
        QuickReply("Continue", "continue"),
        QuickReply("Run tests", "Run the tests and fix what fails."),
        QuickReply("Commit & push", "Commit and push."),
        QuickReply("Open PR", "Open a draft PR."),
    )
    }

    val conversation: Conversation by lazy {j(
        """{"context":{"tokens":82000,"window":200000,"pct":41.0},"turns":[
        {"prompt":"The Hosts tab should show when each host was last pinged and whether it's on the agent. See FLEET-142.","at":"2026-10-08T14:20:00Z","ended_at":"2026-10-08T14:31:00Z","items":[
          {"kind":"text","text":"I'll look at how the Hosts screen builds its rows first."},
          {"kind":"tool","name":"Read","summary":"Read(HostsScreen.kt)","target":"shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/HostsScreen.kt","id":"tu_1"},
          {"kind":"tool","name":"Grep","summary":"Grep(lastPingedAt)","target":"lastPingedAt","id":"tu_2"},
          {"kind":"subagent","id":"tu_3","name":"Task","agent_type":"Explore","description":"Find every place a host's ping time is shown on the desktop","result":"Two: HostCard.svelte and the host inspector. Both use relative time (\"2 min ago\").","done":true},
          {"kind":"tool","name":"Edit","summary":"Edit(HostsScreen.kt)","target":"shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/HostsScreen.kt","id":"tu_4"},
          {"kind":"tool","name":"Edit","summary":"Edit(HostsViewModel.kt)","target":"shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/HostsViewModel.kt","id":"tu_5"},
          {"kind":"text","text":"Each row now shows **pinged 2 min ago** next to the versions, and hosts on the agent get an `agent` label.\n\n- Unreachable hosts sort last\n- A never-pinged host shows nothing rather than `null`\n\nI haven't run the tests yet."}
        ]},
        {"prompt":"Run the tests and fix what fails.","at":"2026-10-08T14:40:00Z","items":[
          {"kind":"tool","name":"Bash","summary":"Bash(./gradlew :shared:jvmTest)","target":"./gradlew :shared:jvmTest","id":"tu_6"},
          {"kind":"text","text":"One failure: `HostsViewModelTest.unreachable_hosts_sort_last` expected the old order. Updating the test to match the new rule."},
          {"kind":"tool","name":"Edit","summary":"Edit(HostsViewModelTest.kt)","target":"shared/src/commonTest/kotlin/dev/claudefleet/mobile/ui/HostsViewModelTest.kt","id":"tu_7"},
          {"kind":"tool","name":"Bash","summary":"Bash(./gradlew :shared:jvmTest)","target":"./gradlew :shared:jvmTest","id":"tu_8","done":false}
        ]}
        ]}""",
    )
    }

    val blockedConversation: Conversation by lazy {j(
        """{"turns":[
        {"prompt":"Add quiet hours to needs-you notifications: nothing between 22:00 and 07:00 unless it's been waiting over an hour.","at":"2026-10-08T14:30:00Z","items":[
          {"kind":"text","text":"I'll add a `quiet_hours` setting and check it where notifications are sent."},
          {"kind":"tool","name":"Edit","summary":"Edit(notify/quiet.rs)","target":"crates/fleet-core/src/notify/quiet.rs","id":"tu_1"},
          {"kind":"tool","name":"Bash","summary":"Bash(cargo fleet-test -- notify::quiet)","target":"cargo fleet-test -- notify::quiet","id":"tu_2","done":false}
        ]}]}""",
    )
    }

    val failedConversation: Conversation by lazy {j(
        """{"turns":[
        {"prompt":"Resolve the tenant from the subdomain before auth runs.","at":"2026-10-08T13:00:00Z","items":[
          {"kind":"tool","name":"Bash","summary":"Bash(npm test)","target":"npm test","error":true,"id":"tu_1"},
          {"kind":"text","text":"API Error: 529 Overloaded. The request could not be completed."}
        ]}]}""",
    )
    }

    val terminal by lazy {"""
╭───────────────────────────────────────────────╮
│ ✻ Welcome to Claude Code!                     │
│   cwd: ~/src/fleet-mobile                     │
╰───────────────────────────────────────────────╯

> Run the tests and fix what fails.

● Bash(./gradlew :shared:jvmTest)
  ⎿  > Task :shared:compileTestKotlinJvm
     > Task :shared:jvmTest
     HostsViewModelTest > unreachable_hosts_sort_last FAILED
     312 tests completed, 1 failed

● Updating the test to match the new rule.

● Update(HostsViewModelTest.kt)
  ⎿  Updated with 3 additions and 2 removals

✻ Running… (42s · ↓ 1.2k tokens · esc to interrupt)
""".trimIndent()
    }

    val links: List<WorkTaskLink> by lazy {j(
        """[
        {"link_id":11,"state":"active","primary":true,"session_id":101,"name":"Hosts screen polish","host":"mercury","source":"tracker","claude_status":"working","branch":"claude/hosts-polish","task":{"task_id":"item:142","key":"FLEET-142","title":"Hosts screen: show last ping and transport","kind":"tracker","status_category":"in_progress","status_name":"In Progress","org_id":1,"tracker_name":"GitHub issues"}},
        {"link_id":14,"state":"active","session_id":106,"name":"Background review","host":"mercury","source":"manual","claude_status":"working","task":{"task_id":"item:142","key":"FLEET-142","title":"Hosts screen: show last ping and transport","kind":"tracker"}},
        {"link_id":15,"state":"suggested","session_id":103,"name":"Hub client split","host":"hetzner-1","source":"rule","strength":"medium","rule":"repo","why":"Same repository and the branch mentions hosts","pr_url":"https://github.com/martin-janci/claude-fleet/pull/118","task":{"task_id":"item:142","key":"FLEET-142","title":"Hosts screen: show last ping and transport","kind":"tracker"}},
        {"link_id":9,"state":"ended","name":"hosts-first-pass","host":"hetzner-1","source":"tracker","ended_at":${ago(4000)},"end_reason":"killed","archived":true,"resumable":true,"branch":"claude/hosts-first-pass","pr_url":"https://github.com/martin-janci/claude-fleet/pull/101","task":{"task_id":"item:142","key":"FLEET-142","title":"Hosts screen: show last ping and transport","kind":"tracker"}},
        {"link_id":8,"state":"ended","name":"hosts-spike","host":"mercury","source":"manual","ended_at":${ago(9000)},"end_reason":"merged","archived":true,"task":{"task_id":"item:142","key":"FLEET-142","title":"Hosts screen: show last ping and transport","kind":"tracker"}}
        ]""",
    )
    }

    private fun task(id: Long, key: String, title: String, status: String, cat: String, group: String, org: Long, mine: Boolean, sessions: String = "[]", active: Int = 0, review: Boolean = false) =
        """{"task_id":"item:$id","item_id":$id,"key":"$key","title":"$title","kind":"tracker","provider":"github","status_name":"$status","status_category":"$cat","mine":$mine,"assignees":${if (mine) "[\"martin-janci\"]" else "[]"},"group":{"id":"label:$group","label":"$group","source":"rule"},"counts":{"active":$active,"ended":1,"suggested":0},"review":$review,"org_id":$org,"needs_you":${active > 0 && review},"last_activity_at":${ago(id % 300)},"sessions":$sessions}"""

    private val workTasks: List<WorkTask> by lazy {j(
        "[" + listOf(
            task(142, "FLEET-142", "Hosts screen: show last ping and transport", "In Progress", "in_progress", "Mobile", 1, true, """[{"link_id":11,"state":"active","primary":true,"session_id":101,"name":"Hosts screen polish","host":"mercury","claude_status":"working"}]""", 2),
            task(150, "FLEET-150", "Quiet hours for needs-you notifications", "To Do", "todo", "Mobile", 1, true, """[{"link_id":12,"state":"active","session_id":102,"name":"Quiet hours","host":"mercury","claude_status":"blocked","needs_you":true}]""", 1, true),
            task(151, "FLEET-151", "Orbit Fleet: apply redesign tokens to the phone", "To Do", "todo", "Mobile", 1, true),
            task(118, "FLEET-118", "Split HubClient into read and write halves", "In Review", "in_progress", "Fleet", 1, true, """[{"link_id":16,"state":"active","session_id":103,"name":"Hub client split","host":"hetzner-1","claude_status":"idle","pr_url":"https://github.com/martin-janci/claude-fleet/pull/118"}]""", 1),
            task(160, "FLEET-160", "Hub: migrate work graph to contract 12", "To Do", "todo", "Fleet", 1, false),
            task(519, "SAL-519", "Verify email language defaulting", "In Review", "in_progress", "Sales twins", 2, true, """[{"link_id":13,"state":"active","session_id":107,"name":"verify email lang","host":"nas","claude_status":"idle"}]""", 1),
            task(522, "SAL-522", "Tenant resolution from subdomain", "In Progress", "in_progress", "Sales twins", 2, true, """[{"link_id":17,"state":"active","session_id":104,"name":"Api tenant resolution","host":"oci-arm","claude_status":"failed","needs_you":true}]""", 1, true),
        ).joinToString(",") + "]",
    )
    }

    private fun groupRef(label: String): GroupRef = j("""{"id":"label:$label","label":"$label","source":"rule"}""")

    val workOrgs by lazy {listOf(
        WorkOrgSection(
            key = "org:1", orgId = 1, name = "Personal", color = "#6750A4", count = 5,
            groups = listOf(
                WorkGroupSection("org:1/Mobile", 1, groupRef("Mobile"), 3, tasks = workTasks.filter { it.group.label == "Mobile" }),
                WorkGroupSection("org:1/Fleet", 1, groupRef("Fleet"), 2, tasks = workTasks.filter { it.group.label == "Fleet" }),
            ),
        ),
        WorkOrgSection(
            key = "org:2", orgId = 2, name = "Sefcik & co", color = "#2E7D32", count = 2,
            groups = listOf(WorkGroupSection("org:2/Sales twins", 2, groupRef("Sales twins"), 2, tasks = workTasks.filter { it.group.label == "Sales twins" })),
        ),
    )
    }

    val taskDetail: TaskDetail by lazy {j(
        """{"task":${task(142, "FLEET-142", "Hosts screen: show last ping and transport", "In Progress", "in_progress", "Mobile", 1, true, "[]", 2)},
        "aliases":["#142"],
        "description":"The phone's Hosts tab shows reachable/unreachable only.\n\n**Acceptance**\n- Each host row shows when it was last pinged\n- Agent-transport hosts are labelled\n- Unreachable hosts sort last",
        "placement":{"group":"Mobile","note":"by rule","version":1},
        "last_outcome":{"at":${ago(4000)},"name":"hosts-first-pass","host":"hetzner-1","branch":"claude/hosts-first-pass","summary":"First pass merged as #101; ping time deferred.","pr_url":"https://github.com/martin-janci/claude-fleet/pull/101"}}""",
    )
    }

    val files by lazy {listOf(
        FileLine(1, "coverage-report.html", "412 KB", "mercury · Hosts screen polish", "3 min ago", FileState.Ready, note = "Coverage after the hosts change", fromAgent = true),
        FileLine(2, "screens.zip", "18.4 MB", "mercury · Orbit tokens", "1 h ago", FileState.Ready),
        FileLine(3, "release-0.9.4.apk", "31 MB", "hetzner-1 · release", "just now", FileState.Fetching),
        FileLine(4, "tenant-trace.log", "96 KB", "oci-arm · Api tenant resolution", "2 h ago", FileState.Failed, error = "The host went offline mid-transfer"),
        FileLine(5, "invoice-2026-09.pdf", "220 KB", "nas · verify email lang", "yesterday", FileState.Ready),
    )
    }

    val restorePlan: RestoreReport by lazy {j(
        """{"plan":[{"session_id":109,"tmux_name":"sales-twins","friendly_name":"Sales twins app","cwd":"~/src/sales-twins-app","action":"resume","reason":"conversation found"},{"session_id":104,"tmux_name":"sal-api","friendly_name":"Api tenant resolution","cwd":"~/src/sales-twins-api","action":"recreate","reason":"no conversation; fresh session in the same worktree"},{"session_id":111,"tmux_name":"scratch","cwd":"~/scratch","action":"skip","reason":"not a git worktree"}]}""",
    )
    }

    @Serializable
    private data class Registry(val pages: List<Page>, val descriptors: List<SettingDescriptor>)

    val fleetSettings: FleetSettingsUiState by lazy {
        val reg = json.decodeFromString(Registry.serializer(), dev.claudefleet.mobile.model.PAGES_REGISTRY_FIXTURE)
        FleetSettingsUiState(
            loaded = true,
            pages = reg.pages,
            descriptors = reg.descriptors.associateBy { it.key },
            values = reg.descriptors.associate { it.key to it.value.ifEmpty { it.default } },
            proposals = listOf(SettingProposal(id = 1, at = ago(30), key = reg.descriptors.first().key, value = reg.descriptors.first().default, why = "The fleet agent suggests this after three missed notifications")),
            canWrite = true,
            historyAvailable = true,
        )
    }

    val usage: UsageReport by lazy {j(
        """{"since":${NOW - 7 * 86400},"total":{"input_tokens":4100000,"output_tokens":910000,"cache_write_tokens":2200000,"cache_read_tokens":38000000,"cost_micros":61420000},
        "by_host":{"mercury":{"cost_micros":34100000,"input_tokens":2000000},"hetzner-1":{"cost_micros":15800000,"input_tokens":1200000},"nas":{"cost_micros":6020000},"oci-arm":{"cost_micros":5500000}},
        "by_day":[
          {"day":"2026-10-02","cost_micros":6100000},{"day":"2026-10-03","cost_micros":3900000},{"day":"2026-10-04","cost_micros":1200000},
          {"day":"2026-10-05","cost_micros":800000},{"day":"2026-10-06","cost_micros":12400000},{"day":"2026-10-07","cost_micros":19900000},{"day":"2026-10-08","cost_micros":17120000}],
        "sessions":[
          {"session_id":101,"host_alias":"mercury","tmux_name":"hosts-polish","friendly_name":"Hosts screen polish","model":"claude-opus","cost_micros":12310000,"input_tokens":900000},
          {"session_id":102,"host_alias":"mercury","tmux_name":"quiet-hours","friendly_name":"Quiet hours","model":"claude-opus","cost_micros":9800000},
          {"session_id":103,"host_alias":"hetzner-1","tmux_name":"pr-118","friendly_name":"Hub client split","model":"claude-sonnet","cost_micros":7400000},
          {"session_id":104,"host_alias":"oci-arm","tmux_name":"sal-api","friendly_name":"Api tenant resolution","model":"claude-sonnet","cost_micros":5500000},
          {"session_id":107,"host_alias":"nas","tmux_name":"verify-email","model":"claude-haiku","cost_micros":610000}]}""",
    )
    }

    val accounts: List<AccountRow> by lazy {j(
        """[{"uuid":"a1b2c3d4-0000","email":"martin@example.com","display_name":"Martin","organization_name":"Personal","seat_tier":"max","last_seen_at":${ago(1)},"nickname":"Personal Max"},
        {"uuid":"e5f6a7b8-0000","email":"martin@sefcik.example","organization_name":"Sefcik & co","seat_tier":"team","last_seen_at":${ago(300)},"has_extra_usage":true}]""",
    )
    }

    val orgs: List<OrgDetail> by lazy {j(
        """[{"id":1,"name":"Personal","color":"#6750A4","my_role":"owner","owns_hub":true,"session_count":7,"needs_you":2,"hosts":["mercury","hetzner-1","nas"],"trackers":[{"id":1,"name":"GitHub issues"}],
          "devices":[{"name":"Pixel 9 Pro","mode":"full","trusted":true},{"name":"MacBook Pro","mode":"full","trusted":true}],"members":[{"person_id":1,"name":"Martin","role":"owner"}],
          "spent_today_micros":17120000,"spent_week_micros":61420000,"spent_month_micros":210000000,"budget_daily_usd":40,"budget_monthly_usd":600},
         {"id":2,"name":"Sefcik & co","color":"#2E7D32","my_role":"member","session_count":3,"needs_you":1,"hosts":["oci-arm","nas"],"trackers":[{"id":2,"name":"Sefcik Jira"}],
          "devices":[{"name":"Pixel 9 Pro","mode":"bound","trusted":false}],"members":[{"person_id":2,"name":"František","role":"owner"},{"person_id":1,"name":"Martin","role":"member"}],
          "spent_today_micros":0,"spent_week_micros":6110000,"budget_monthly_usd":150}]""",
    )
    }

    val changes: List<ChangedFile> by lazy {j(
        """[{"path":"shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/HostsScreen.kt","status":"M","staged":false},
        {"path":"shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/HostsViewModel.kt","status":"M","staged":true},
        {"path":"shared/src/commonTest/kotlin/dev/claudefleet/mobile/ui/HostsViewModelTest.kt","status":"M","staged":false},
        {"path":"shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/components/PingLabel.kt","status":"A","staged":false},
        {"path":"docs/hosts.md","status":"D","staged":false}]""",
    )
    }

    val commits: List<Commit> by lazy {j(
        """[{"hash":"9f3c2a17e0b4","shortHash":"9f3c2a1","short_hash":"9f3c2a1","author":"Claude","date":"2026-10-08T14:31:00Z","subject":"feat(hosts): show last ping and agent transport","refs":[{"name":"HEAD","kind":"head"},{"name":"claude/hosts-polish","kind":"branch"}]},
        {"hash":"6c1d8e0a2f55","shortHash":"6c1d8e0","short_hash":"6c1d8e0","author":"Martin Janči","date":"2026-10-08T09:12:00Z","subject":"Merge pull request #112 from martin-janci/claude/project-thread-jux1s4","refs":[{"name":"origin/main","kind":"remote"}]},
        {"hash":"6860d90aa1c2","shortHash":"6860d90","short_hash":"6860d90","author":"Claude","date":"2026-10-08T08:55:00Z","subject":"feat: Orbit Fleet name and launcher icon"},
        {"hash":"f3ba4fa0c9d1","shortHash":"f3ba4fa","short_hash":"f3ba4fa","author":"Martin Janči","date":"2026-10-07T21:40:00Z","subject":"Merge pull request #111 from martin-janci/claude/chat-rich-ui-blocks-on2kup"},
        {"hash":"1a2b3c4d5e6f","shortHash":"1a2b3c4","short_hash":"1a2b3c4","author":"Claude","date":"2026-10-07T20:02:00Z","subject":"feat(chat): rich UI blocks in replies"}]""",
    )
    }

    val tree: RepoTree by lazy {RepoTree(
        entries = listOf(
            "README.md", "build.gradle.kts", "settings.gradle.kts", "androidApp/", "iosApp/", "shared/", "shared/build.gradle.kts",
            "shared/src/", "docs/", "docs/2026-09-18-fleet-mobile-design.md", "scripts/", "skills/",
        ),
    )
    }

    val diff: FileDiff by lazy {FileDiff(
        path = "shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/HostsScreen.kt",
        diff = """@@ -88,12 +88,17 @@ private fun HostRow(line: HostLine, now: Long) {
         Column(modifier = Modifier.weight(1f)) {
             Text(line.alias, style = MaterialTheme.typography.titleMedium)
-            Text(versions(line), style = MaterialTheme.typography.bodySmall)
+            Text(
+                listOfNotNull(versions(line), relativeAgo(line.lastPingedAt, now)?.let { "pinged ${'$'}it" })
+                    .joinToString(" · "),
+                style = MaterialTheme.typography.bodySmall,
+            )
         }
-        StatusChip(if (line.reachable) "reachable" else "unreachable")
+        if (line.transport != "ssh") WorkChip(line.transport)
+        StatusChip(if (line.reachable) "reachable" else "unreachable")
     }
 }""",
    )
    }

    val commitDetail by lazy {CommitDetail(
        hash = "9f3c2a17e0b4",
        subject = "feat(hosts): show last ping and agent transport",
        body = "Each host row shows when it was last pinged, and hosts on the agent are labelled.\nUnreachable hosts sort last.\n\nFixes FLEET-142.",
        author = "Claude",
        date = "2026-10-08T14:31:00Z",
        files = changes.take(4),
    )
    }

    val fileContent by lazy {FileContent(
        path = "README.md",
        content = "# fleet-mobile\n\nThe phone client for claude-fleet: Android and iOS from one Compose Multiplatform codebase.\n\n## Build\n\n```bash\n./gradlew build\n```\n\n## Pairing\n\nOpen *Settings → Phones* on the desktop and scan the QR code.\n",
        size = 412,
    )
    }

    val todayView: TodayView by lazy {
        val today: Today = j(
            """{"since":${NOW - 15 * 3600},"now":$NOW,"groups":[
            {"bucket":"waiting","key":"FLEET-150","title":"Quiet hours for needs-you notifications","sessions":[{"id":102,"name":"Quiet hours","host_alias":"mercury","attention":"waiting","claude_status":"blocked","last_activity_at":${ago(7)}}]},
            {"bucket":"waiting","key":"SAL-522","title":"Tenant resolution from subdomain","sessions":[{"id":104,"name":"Api tenant resolution","host_alias":"oci-arm","attention":"failed","claude_status":"failed","last_activity_at":${ago(40)}}]},
            {"bucket":"in_progress","key":"FLEET-142","title":"Hosts screen: show last ping and transport","sessions":[{"id":101,"name":"Hosts screen polish","host_alias":"mercury","claude_status":"working","last_activity_at":${ago(0)}},{"id":106,"name":"Background review","host_alias":"mercury","claude_status":"working","last_activity_at":${ago(0)}}]},
            {"bucket":"in_progress","key":"FLEET-118","title":"Split HubClient into read and write halves","sessions":[{"id":103,"name":"Hub client split","host_alias":"hetzner-1","claude_status":"idle","pr_url":"https://github.com/martin-janci/claude-fleet/pull/118","last_activity_at":${ago(95)}}]},
            {"bucket":"stale","key":"SAL-519","title":"Verify email language defaulting","sessions":[{"id":107,"name":"verify email lang","host_alias":"nas","stale":"idle","claude_status":"idle","last_activity_at":${ago(600)}}]}
            ],"shipped":[
            {"how":"pr","key":"FLEET-131","title":"Rich UI blocks in chat replies","url":"https://github.com/martin-janci/claude-fleet/issues/131","at":${ago(300)}},
            {"how":"done","key":"FLEET-128","title":"Orbit Fleet name and launcher icon","at":${ago(400)}}
            ]}""",
        )
        scopeToday(today, null) { null }
    }

    val tidy: List<TidyCandidate> by lazy {j(
        """[{"session_id":201,"host_alias":"hetzner-1","tmux_name":"hosts-first-pass","kind":"merged","reason":"PR #101 merged 2 days ago","secondary":["clean worktree","no unpushed commits"],"action":"safe_kill","since":${ago(2900)},"label":"hosts-first-pass","key":"FLEET-142","branch":"claude/hosts-first-pass","pr_url":"https://github.com/martin-janci/claude-fleet/pull/101"},
        {"session_id":202,"host_alias":"nas","tmux_name":"verify-email","kind":"idle","reason":"Idle for 10 h; ticket In Review","secondary":["2 uncommitted files"],"action":"archive","since":${ago(600)},"label":"verify email lang defaulting","key":"SAL-519","idle_secs":36000},
        {"session_id":203,"host_alias":"oci-arm","tmux_name":"sales-twins","kind":"done","reason":"Ticket closed upstream","action":"safe_kill","since":${ago(3000)},"label":"Sales twins app","item_status":"Done"}]""",
    )
    }

    val ticketSections: List<TicketSection> by lazy {
        val tickets: List<Ticket> = j(
            """[{"id":142,"key":"FLEET-142","title":"Hosts screen: show last ping and transport","url":"https://github.com/martin-janci/claude-fleet/issues/142","status_category":"in_progress","status_name":"In Progress","tracker_id":1,"live_session_ids":[101,106],"description":"The phone's Hosts tab shows reachable/unreachable only."},
            {"id":150,"key":"FLEET-150","title":"Quiet hours for needs-you notifications","status_category":"todo","status_name":"To Do","tracker_id":1,"live_session_ids":[102]},
            {"id":151,"key":"FLEET-151","title":"Orbit Fleet: apply redesign tokens to the phone","status_category":"todo","status_name":"To Do","tracker_id":1},
            {"id":160,"key":"FLEET-160","title":"Hub: migrate work graph to contract 12","status_category":"todo","status_name":"To Do","tracker_id":1,"iteration":"Sprint 41"},
            {"id":522,"key":"SAL-522","title":"Tenant resolution from subdomain","status_category":"in_progress","status_name":"In Progress","tracker_id":2,"live_session_ids":[104]},
            {"id":530,"key":"SAL-530","title":"Export twins to CSV","status_category":"todo","status_name":"Backlog","tracker_id":2,"unavailable_reason":"tracker not reachable"}]""",
        )
        listOf(
            TicketSection("assigned", "Assigned to me", tickets.take(4)),
            TicketSection("sprint", "Current sprint", tickets.drop(4), total = 9),
        )
    }

    val missions: List<Mission> by lazy {j(
        """[{"id":1,"name":"Orbit redesign rollout","goal":"Ship the Orbit Fleet tokens and new navigation to desktop and phone","mode":"finite","state":"running","level":2,"total":14,"done":5},
        {"id":2,"name":"Nightly dependency bumps","goal":"Keep every repo's dependencies current","mode":"standing","state":"paused","level":1,"total":0,"done":0},
        {"id":3,"name":"SAL tenant hardening","goal":"Tenant isolation across the sales twins API","mode":"finite","state":"draft","level":0,"total":6,"done":0}]""",
    )
    }

    val missionDetail: MissionDetail by lazy {j(
        """{"mission":{"id":1,"name":"Orbit redesign rollout","goal":"Ship the Orbit Fleet tokens and new navigation to desktop and phone","mode":"finite","state":"running","level":2,"total":14,"done":5},
        "items":[{"id":151,"key":"FLEET-151","title":"Orbit Fleet: apply redesign tokens to the phone","status_category":"todo"},{"id":152,"key":"FLEET-152","title":"Desktop rail and inspector layout","status_category":"in_progress"}],
        "phase":"build","graph":{"nodes":[{"item_id":151,"state":"ready","wave":1},{"item_id":152,"state":"running","wave":1}]},
        "plan":{"steps":[{"kind":"start","item_id":151,"role":"builder","reason":"Ready: tokens PR #501 merged","auto":false},{"kind":"review","item_id":152,"role":"reviewer","reason":"PR open with CI green","auto":true}],
          "cards":[{"id":1,"source":"planner","kind":"approve_spend","state":"open","note":"Starting FLEET-151 on mercury will cost about $4"}],
          "autonomy":{"asked":3,"ceiling":2,"effective":2,"why":"Level 3 needs a grant from the desktop","enabled":true},"cost_micros":18400000}}""",
    )
    }

    val review: List<ReviewItem> by lazy {j(
        """[{"review_id":"r1","kind":"suggestion","session_id":103,"session_name":"Hub client split","host":"hetzner-1","link_id":15,"task":{"task_id":"item:142","key":"FLEET-142","title":"Hosts screen: show last ping and transport"},"why":["Same repository","Branch mentions hosts"],"strength":"medium","rule":"repo","alternatives":[{"link_id":16,"task_id":"item:118","key":"FLEET-118","title":"Split HubClient into read and write halves"}]},
        {"review_id":"r2","kind":"cross_org","session_id":107,"session_name":"verify email lang","host":"nas","link_id":13,"task":{"task_id":"item:519","key":"SAL-519","title":"Verify email language defaulting"},"why":["The session runs on a Personal host but the ticket is Sefcik & co's"]},
        {"review_id":"r3","kind":"no_primary","session_id":101,"session_name":"Hosts screen polish","host":"mercury","link_id":11,"task":{"task_id":"item:142","key":"FLEET-142","title":"Hosts screen: show last ping and transport"},"why":["Two active tasks and neither is primary"],"preselected":true}]""",
    )
    }

    val events: List<SessionEvent> by lazy {j(
        """[{"id":1,"at":${ago(180)},"kind":"created","detail":"on mercury in ~/src/fleet-mobile"},
        {"id":2,"at":${ago(170)},"kind":"prompt_sent","detail":"The Hosts tab should show when each host was last pinged"},
        {"id":3,"at":${ago(159)},"kind":"turn_done","detail":"6 tools · 11 min"},
        {"id":4,"at":${ago(150)},"kind":"status_change","detail":"working → idle"},
        {"id":5,"at":${ago(140)},"kind":"prompt_sent","detail":"Run the tests and fix what fails."},
        {"id":6,"at":${ago(120)},"kind":"compact_done","detail":"82k → 31k tokens"},
        {"id":7,"at":${ago(60)},"kind":"stuck","detail":"press_enter"},
        {"id":8,"at":${ago(59)},"kind":"keys_sent","detail":"Enter"},
        {"id":9,"at":${ago(4)},"kind":"message_sent","detail":"Started background review 7f3a2c"}]""",
    )
    }

    val movePreview: MovePreview by lazy {j(
        """{"branch":"claude/hosts-polish","dirty":[{"path":"shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/HostsScreen.kt","bytes":14200},{"path":"shared/src/commonTest/kotlin/dev/claudefleet/mobile/ui/HostsViewModelTest.kt","bytes":6100},{"path":"local.properties","bytes":120,"reason":"git-ignored, small"}],
        "target":{"state":"behind","head":"6c1d8e0"},"unknowns":["Whether hetzner-1 has the Android SDK"]}""",
    )
    }

}
