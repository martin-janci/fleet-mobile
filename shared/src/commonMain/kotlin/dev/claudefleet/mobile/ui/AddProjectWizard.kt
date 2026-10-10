package dev.claudefleet.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.GithubRepo
import dev.claudefleet.mobile.model.OrgDirectory
import dev.claudefleet.mobile.model.OrgRule
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.kit.DataRain
import dev.claudefleet.mobile.ui.kit.SheetOption
import dev.claudefleet.mobile.ui.kit.StepBars
import dev.claudefleet.mobile.ui.kit.rememberLoaderVisible
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.theme.OrbitTokens

/*
 * Add a project as the Orbit Fleet wizard (redesign 14.20, board
 * MobileWizards): Source, then Where, then the clone itself under the Data
 * rain. Drawn from the New session wizard in the New layout; Classic keeps
 * [AddProjectSheet]. The same [ProjectToolsViewModel] and the same calls
 * drive both, so the hub's own checks (a GitHub creation is confirmed first)
 * hold here unchanged.
 *
 * The sources are the desktop's four (6.11): a repository `gh` on the host
 * can see, a URL, a folder already on the host, or a new empty repository
 * (on GitHub too). The hub adopts a folder on its own machine only
 * ([LOCAL_HOST]), so that source is offered only where the fleet lists it.
 */

/** The hub's own machine: the one host `add_project` adopts a folder on. */
internal const val LOCAL_HOST = "local"

/** The wizard's two asking steps; the third is the clone running. */
enum class AddProjectStep(val title: String) {
    Source("Source"),
    Where("Where");

    val number: Int get() = ordinal + 1
}

/** What is being added. */
sealed interface ProjectSource {
    /** One of the repositories `gh` on the host listed. */
    data class Github(val nameWithOwner: String) : ProjectSource

    /** A clone URL typed or pasted. */
    data class Url(val url: String) : ProjectSource

    /** A checkout already on the hub's own machine, by its absolute [path]; it stays where it is. */
    data class Folder(val path: String) : ProjectSource

    /** A new empty repository, on the host, and on GitHub when [onGithub]. */
    data class New(val owner: String, val repo: String, val onGithub: Boolean) : ProjectSource
}

/** "Step 1 of 3 · Source". The clone running is the third. */
internal fun addStepHeading(step: AddProjectStep): String = "Step ${step.number} of 3 · ${step.title}"

/** Why Next (or the last step's action) is off, in words; null when it may go on. */
internal fun sourceBlocker(source: ProjectSource?): String? = when (source) {
    null -> "Pick where the project comes from."
    is ProjectSource.Github -> null
    is ProjectSource.Url -> if (source.url.isBlank()) "Paste the repository's URL." else null
    is ProjectSource.Folder -> when {
        source.path.isBlank() -> "Type the folder's path on the host."
        !source.path.trim().startsWith("/") -> "Give the whole path, from /."
        else -> null
    }
    is ProjectSource.New -> when {
        source.owner.isBlank() -> "Name the owner."
        source.repo.isBlank() -> "Name the repository."
        else -> null
    }
}

/** The last step's button names the action, as the wizard rules ask. */
internal fun addActionLabel(source: ProjectSource?): String = when (source) {
    is ProjectSource.New -> if (source.onGithub) "Create on GitHub" else "Create"
    is ProjectSource.Folder -> "Add"
    else -> "Clone"
}

/**
 * Why the Where step cannot add [source] on [host], beyond reachability: a
 * folder is adopted on the hub's own machine only. Null when it can.
 */
internal fun hostBlocker(source: ProjectSource?, host: String): String? =
    if (source is ProjectSource.Folder && host != LOCAL_HOST) "A folder is added on $LOCAL_HOST, the hub's own machine." else null

/** The name a folder's project is shown by while it is added: the last part of its path. */
internal fun folderName(path: String): String? =
    path.trim().trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }

/**
 * Whether the fleet already holds [repo] as a project: the board lists it
 * anyway and says so, rather than hiding a repository the person may be
 * looking for.
 */
internal fun alreadyAdded(repo: GithubRepo, projects: Collection<String>): Boolean =
    projects.any { it.equals(repo.nameWithOwner, ignoreCase = true) }

/** The Cloning screen's title: "Cloning papaya-pos", "Creating papaya-pos". */
internal fun addingTitle(what: String?, creating: Boolean, adopting: Boolean = false): String =
    (if (adopting) "Adding " else if (creating) "Creating " else "Cloning ") + (what ?: "the project")

/**
 * The steps the hub runs for this add, in order. It answers once, at the
 * end, so none of them is ticked off on the way: they say what is coming,
 * not how far it got.
 */
internal fun addSteps(creating: Boolean, onGithub: Boolean, adopting: Boolean = false): List<String> = buildList {
    if (adopting) {
        add("Check the folder is a git checkout")
        add("Add to Projects")
        return@buildList
    }
    if (creating && onGithub) add("Create the repository on GitHub")
    add(if (creating) "Create the repository on the host" else "Clone onto the host")
    add("Add to Projects")
}

/**
 * The owner and repository a source names, as the hub will store them: a
 * GitHub pick and a new repository say so; a URL's last two path parts
 * (`https://github.com/acme/app.git`, `git@github.com:acme/app`). A folder
 * takes them from its origin remote on the host, which the phone cannot
 * read, so it has none here.
 */
internal fun sourceOwnerRepo(source: ProjectSource?): Pair<String, String>? = when (source) {
    is ProjectSource.Github -> source.nameWithOwner.split('/').takeIf { it.size == 2 && it.all(String::isNotBlank) }?.let { it[0] to it[1] }
    is ProjectSource.New -> (source.owner.trim() to source.repo.trim()).takeIf { it.first.isNotBlank() && it.second.isNotBlank() }
    is ProjectSource.Url -> {
        val raw = source.url.trim().trimEnd('/').removeSuffix(".git")
        // `scheme://host[:port]/path`, else scp-like `user@host:path`.
        val path = if ("://" in raw) raw.substringAfter("://").substringAfter('/', "") else raw.substringAfter(':', "")
        val parts = path.split('/').filter { it.isNotBlank() }
        if (parts.size >= 2) parts[parts.size - 2] to parts.last() else null
    }
    is ProjectSource.Folder, null -> null
}

/** Which org a new project's sessions land in, and why, in words. */
internal data class ProjectOrg(val orgId: Long, val name: String, val why: String)

/**
 * The org the hub will place this project's sessions in (claude-fleet
 * `store::orgs`, session resolution): the most specific matching rule —
 * path, then owner/repo, then owner, then a host-only rule, a rule bound to
 * the host ranking above one that is not, ties to the lower id — else the
 * host's own org. Read from the rules the hub already sends with its orgs.
 *
 * A clone's folder is not known until the hub has made it, so path rules are
 * not weighed for one; a folder's owner is not known at all, so for a folder
 * only a path rule or the host decides, and an owner rule that might outrank
 * the host makes the answer unknown (null) rather than a guess. Null too
 * when nothing places it, or the hub sent no orgs.
 */
internal fun projectOrg(source: ProjectSource?, host: String, orgs: OrgDirectory): ProjectOrg? {
    if (orgs.orgs.isEmpty() || host.isBlank()) return null
    val ownerRepo = sourceOwnerRepo(source)
    val folder = (source as? ProjectSource.Folder)?.path?.trim()?.trimEnd('/')?.takeIf { it.startsWith("/") }
    if (source == null || (ownerRepo == null && folder == null)) return null
    fun underPrefix(prefix: String): Boolean {
        val p = prefix.trimEnd('/')
        return folder != null && (folder == p || folder.startsWith("$p/"))
    }
    fun matches(r: OrgRule): Boolean {
        if (r.hostAlias != null && r.hostAlias != host) return false
        if (r.pathPrefix != null && !underPrefix(r.pathPrefix)) return false
        if (r.owner != null && (ownerRepo == null || !r.owner.equals(ownerRepo.first, ignoreCase = true))) return false
        if (r.repo != null && (ownerRepo == null || !r.repo.equals(ownerRepo.second, ignoreCase = true))) return false
        return true
    }
    fun rank(r: OrgRule): Int = when {
        r.pathPrefix != null -> 3000 + r.pathPrefix.length
        r.repo != null -> 2000
        r.owner != null -> 1000
        else -> 0
    } + if (r.hostAlias != null) 1 else 0
    val best = orgs.rules.filter(::matches).sortedWith(compareByDescending<OrgRule>(::rank).thenBy { it.id }).firstOrNull()
    // A folder's owner is unknown: an owner or repo rule that could apply would outrank the host.
    if (ownerRepo == null && (best == null || best.pathPrefix == null) &&
        orgs.rules.any { (it.owner != null || it.repo != null) && it.pathPrefix == null && (it.hostAlias == null || it.hostAlias == host) }
    ) return null
    if (best != null) {
        val why = when {
            best.pathPrefix != null -> "from the rule for ${best.pathPrefix}"
            best.repo != null && best.owner != null -> "from the rule for ${best.owner}/${best.repo}"
            best.repo != null -> "from the rule for ${best.repo}"
            best.owner != null -> "from the rule for ${best.owner}/*"
            else -> "from the rule for $host"
        }
        return ProjectOrg(best.orgId, orgs.name(best.orgId), why)
    }
    val hostOrg = orgs.hostOrg[host] ?: return null
    return ProjectOrg(hostOrg, orgs.name(hostOrg), "$host belongs to it")
}

data class AddProjectHandlers(
    val onStep: (AddProjectStep) -> Unit = {},
    val onChooseHost: (String) -> Unit = {},
    val onClone: (String) -> Unit = {},
    val onCreate: (String, String, Boolean) -> Unit = { _, _, _ -> },
    /** Add the folder at this path on [LOCAL_HOST] (`add_project` with a `folder` source). */
    val onAdopt: (String) -> Unit = {},
    /** Close the wizard; while a clone runs it keeps running, and the form says so. */
    val onClose: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    /** Whether something is typed, so closing can ask first (the wizard rules). */
    val onTyped: (Boolean) -> Unit = {},
)

@Composable
internal fun AddProjectWizard(
    tools: ProjectToolsUiState,
    step: AddProjectStep,
    hosts: List<HostChoice>,
    /** The fleet's projects as `owner/repo`, to say which repositories are added already. */
    projects: Collection<String>,
    handlers: AddProjectHandlers,
    modifier: Modifier = Modifier,
    /** The orgs and their rules, to say which org the project lands in on the Where step. */
    orgs: OrgDirectory = OrgDirectory.EMPTY,
) {
    var source by remember { mutableStateOf<ProjectSource?>(null) }
    // What was typed stays when the kind changes: Back keeps answers.
    var url by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf("") }
    var onGithub by remember { mutableStateOf(false) }
    val typed = addProjectTyped(url, folder, owner, repo)
    LaunchedEffect(typed) { handlers.onTyped(typed) }
    val creating = source is ProjectSource.New
    val adopting = source is ProjectSource.Folder
    val host = tools.addingOn.orEmpty()

    Column(modifier = modifier.fillMaxSize().background(Fleet.colors.bg)) {
        if (tools.adding) {
            ScreenHeader(
                title = addingTitle(tools.addingWhat, creating, adopting),
                subtitle = "on ${tools.addingWhere ?: host}",
                navigation = {
                    IconButton(onClick = handlers.onClose) { Icon(FleetIcons.ArrowBack, contentDescription = "Continue in the background") }
                },
            )
            AddingPanel(creating, (source as? ProjectSource.New)?.onGithub == true, handlers.onClose, Modifier.weight(1f), adopting)
        } else {
            AskingSteps(
                tools, step, hosts, host, projects, handlers, source, url, owner, repo, onGithub,
                folder = folder,
                orgs = orgs,
                onSource = { source = it },
                onUrl = { url = it },
                onFolder = { folder = it },
                onNew = { o, r, g ->
                    owner = o
                    repo = r
                    onGithub = g
                },
            )
        }
    }
}

@Composable
private fun ColumnScope.AskingSteps(
    tools: ProjectToolsUiState,
    step: AddProjectStep,
    hosts: List<HostChoice>,
    host: String,
    projects: Collection<String>,
    handlers: AddProjectHandlers,
    source: ProjectSource?,
    url: String,
    owner: String,
    repo: String,
    onGithub: Boolean,
    folder: String,
    orgs: OrgDirectory,
    onSource: (ProjectSource) -> Unit,
    onUrl: (String) -> Unit,
    onFolder: (String) -> Unit,
    onNew: (String, String, Boolean) -> Unit,
) {
    // A folder only where the hub has its own machine in the fleet to adopt it on.
    val folderOffered = tools.canAdd && hosts.any { it.alias == LOCAL_HOST }
    ScreenHeader(
        title = "Add a project",
        subtitle = addStepHeading(step),
        navigation = {
            IconButton(onClick = { if (step == AddProjectStep.Where) handlers.onStep(AddProjectStep.Source) else handlers.onClose() }) {
                Icon(FleetIcons.ArrowBack, contentDescription = "Back")
            }
        },
    )
    StepBars(step = step.number, total = 3)
    ErrorBanner(tools.error, onDismiss = handlers.onDismissError)
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        when (step) {
            AddProjectStep.Source -> SourceStep(
                tools = tools,
                host = host,
                projects = projects,
                source = source,
                url = url,
                owner = owner,
                repo = repo,
                onGithub = onGithub,
                folder = folder.takeIf { folderOffered },
                onPick = onSource,
                onUrl = {
                    onUrl(it)
                    onSource(ProjectSource.Url(it))
                },
                onFolder = {
                    onFolder(it)
                    onSource(ProjectSource.Folder(it))
                },
                onNew = { o, r, g ->
                    onNew(o, r, g)
                    onSource(ProjectSource.New(o, r, g))
                },
            )
            AddProjectStep.Where -> WhereToAdd(hosts, host, source, handlers.onChooseHost, projectOrg(source, host, orgs))
        }
    }
    val blocker = sourceBlocker(source) ?: hostBlocker(source, host)
        ?: if (hosts.none { it.alias == host && it.reachable }) "Pick a host the hub can reach." else null
    WizardFooter(
        caption = if (step == AddProjectStep.Source) sourceBlocker(source).takeIf { source != null } else blocker,
        backLabel = if (step == AddProjectStep.Source) "Cancel" else "Back",
        onBackTap = { if (step == AddProjectStep.Source) handlers.onClose() else handlers.onStep(AddProjectStep.Source) },
        primaryLabel = if (step == AddProjectStep.Source) "Next" else addActionLabel(source),
        primaryEnabled = if (step == AddProjectStep.Source) sourceBlocker(source) == null else blocker == null && tools.canAdd,
        onPrimary = {
            when (step) {
                AddProjectStep.Source -> {
                    // A folder has one host it can be added on: Where opens on it.
                    if (source is ProjectSource.Folder) handlers.onChooseHost(LOCAL_HOST)
                    handlers.onStep(AddProjectStep.Where)
                }
                AddProjectStep.Where -> when (val s = source) {
                    is ProjectSource.Github -> handlers.onClone("https://github.com/${s.nameWithOwner}")
                    is ProjectSource.Url -> handlers.onClone(s.url)
                    is ProjectSource.Folder -> handlers.onAdopt(s.path.trim())
                    is ProjectSource.New -> handlers.onCreate(s.owner, s.repo, s.onGithub)
                    null -> Unit
                }
            }
        },
        editable = true,
    )
}

@Composable
private fun SourceStep(
    tools: ProjectToolsUiState,
    host: String,
    projects: Collection<String>,
    source: ProjectSource?,
    url: String,
    owner: String,
    repo: String,
    onGithub: Boolean,
    /** What was typed for a folder; null when the folder source is not offered. */
    folder: String?,
    onPick: (ProjectSource) -> Unit,
    onUrl: (String) -> Unit,
    onFolder: (String) -> Unit,
    onNew: (String, String, Boolean) -> Unit,
) {
    val repos = tools.repos
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = gutterPadding()) {
        if (tools.canListGithub) {
            item(key = "gh-label") {
                StepLabel("Clone from GitHub")
                StepHint(
                    when {
                        repos == null -> "Asking gh on $host for its repositories…"
                        repos.isEmpty() -> "gh on $host lists no repositories."
                        else -> "${repos.size} ${if (repos.size == 1) "repository" else "repositories"} gh on $host can see"
                    },
                )
            }
            items(repos.orEmpty(), key = { "gh-${it.nameWithOwner}" }) { r ->
                SheetOption(
                    title = r.nameWithOwner,
                    selected = source == ProjectSource.Github(r.nameWithOwner),
                    onSelect = { onPick(ProjectSource.Github(r.nameWithOwner)) },
                    sub = repoLine(r, alreadyAdded(r, projects)),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
        item(key = "url") {
            StepLabel("Clone a URL")
            OutlinedTextField(
                value = url,
                onValueChange = onUrl,
                singleLine = true,
                label = { Text("Repository URL") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            )
        }
        if (folder != null) {
            item(key = "folder") {
                StepLabel("A folder already on the host")
                OutlinedTextField(
                    value = folder,
                    onValueChange = onFolder,
                    singleLine = true,
                    label = { Text("Path on $LOCAL_HOST") },
                    placeholder = { Text("/home/me/projects/app") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                )
                StepHint("A git checkout on the hub's own machine. It stays where it is; nothing is cloned.")
            }
        }
        item(key = "new") {
            StepLabel("A new empty repository")
            OutlinedTextField(
                value = owner,
                onValueChange = { onNew(it, repo, onGithub) },
                singleLine = true,
                label = { Text("Owner") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            )
            OutlinedTextField(
                value = repo,
                onValueChange = { onNew(owner, it, onGithub) },
                singleLine = true,
                label = { Text("Repository") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .height(OrbitTokens.spacing("touch-min").dp)
                    .toggleable(value = onGithub, role = Role.Checkbox) { onNew(owner, repo, it) },
            ) {
                Checkbox(checked = onGithub, onCheckedChange = null)
                Text("Create it on GitHub too", color = Fleet.colors.fg, style = Fleet.type.textMd)
            }
            StepHint("Made with the host's own gh login. The hub asks you to confirm before it creates anything on GitHub.")
        }
    }
}

/** A repository's sub-line: private, and whether the fleet has it already. */
internal fun repoLine(r: GithubRepo, added: Boolean): String? = listOfNotNull(
    "already a project".takeIf { added },
    "private".takeIf { r.isPrivate },
    r.description?.takeIf { it.isNotBlank() },
).joinToString(" · ").ifEmpty { null }

@Composable
private fun WhereToAdd(hosts: List<HostChoice>, host: String, source: ProjectSource?, onChooseHost: (String) -> Unit, org: ProjectOrg?) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = gutterPadding()) {
        item(key = "host-label") { StepLabel("Host") }
        if (hosts.isEmpty()) item(key = "no-hosts") { StepHint("No hosts yet. They appear once the hub has listed them.") }
        items(hosts, key = { "host-${it.alias}" }) { h ->
            val elsewhere = hostBlocker(source, h.alias) != null
            SheetOption(
                title = h.alias,
                selected = h.alias == host,
                onSelect = { onChooseHost(h.alias) },
                sub = whereHostLine(h, elsewhere),
                enabled = h.reachable && !elsewhere,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        item(key = "folder") {
            StepLabel("Folder")
            StepHint(
                if (source is ProjectSource.Folder) "It stays where it is. The owner and name come from its origin remote."
                else "The hub puts it where its projects layout says, under the owner and the repository's name.",
            )
            StepLabel("Adding")
            StepHint(sourceSummary(source))
            if (org != null) {
                StepLabel("Organisation")
                StepHint("${org.name}, ${org.why}")
            }
        }
    }
}

/** A host's line on the Where step: why it cannot be picked, else what the hub's last probe found there. */
internal fun whereHostLine(h: HostChoice, elsewhere: Boolean): String? = when {
    !h.reachable -> "Signal lost · cannot add there now"
    elsewhere -> "A folder is added on $LOCAL_HOST only"
    else -> h.facts
}

/** What the Where step says is about to happen. */
internal fun sourceSummary(source: ProjectSource?): String = when (source) {
    null -> "Nothing picked yet."
    is ProjectSource.Github -> "Clone ${source.nameWithOwner}"
    is ProjectSource.Url -> "Clone ${source.url.trim()}"
    is ProjectSource.Folder -> "Add the folder ${source.path.trim()}"
    is ProjectSource.New -> "A new repository ${source.owner.trim()}/${source.repo.trim()}" + if (source.onGithub) ", on GitHub too" else ""
}

/**
 * The clone running: the Data rain (its size is not known; the hub answers
 * once, at the end), the steps it is going through, and Continue in the
 * background. The rain shows only once the wait has passed `loader-delay`.
 */
@Composable
private fun AddingPanel(creating: Boolean, onGithub: Boolean, onBackground: () -> Unit, modifier: Modifier = Modifier, adopting: Boolean = false) {
    val o = Fleet.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = OrbitTokens.spacing("phone-gutter").dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
            if (rememberLoaderVisible(true)) DataRain()
        }
        Text(
            if (adopting) "Checking the folder" else if (creating) "Creating the repository" else "Receiving the repository",
            color = o.fg,
            style = Fleet.type.textLg,
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            for (line in addSteps(creating, onGithub, adopting)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).border(1.5.dp, o.loaderAccent, CircleShape))
                    Text(line, color = o.fg2, style = Fleet.type.textMd, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        Text(
            "The hub answers once it is done, so there is no count to show. You can leave: it keeps going and the project is picked here when it lands.",
            color = o.fgMuted,
            style = Fleet.type.textSm,
        )
        OutlinedButton(
            onClick = onBackground,
            border = BorderStroke(1.dp, o.controlBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = o.fg2),
            shape = RoundedCornerShape(OrbitTokens.radius("radius-md").dp),
            modifier = Modifier.height(OrbitTokens.spacing("touch-min").dp),
        ) { Text("Continue in the background") }
    }
}
