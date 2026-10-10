package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.TrackerActions
import dev.claudefleet.mobile.model.TrackerAdminRow
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The first hub contract that lets the owner's trusted phone manage trackers. */
internal const val TRACKERS_ON_PHONE_CONTRACT = 13

enum class TrackerStep { Site, SignIn }

/** What Connect is doing, in order: add the tracker, store its sign-in, ask the tracker. */
enum class TrackerWork { Adding, Saving, Checking }

/**
 * Connect a tracker as a wizard (MobileWizards): the site, then the sign-in.
 * [secret] lives only here, in memory, until Connect sends it to the hub; it
 * is cleared as soon as it is sent and never written anywhere on the phone.
 */
data class TrackerWizard(
    val step: TrackerStep = TrackerStep.Site,
    val siteUrl: String = "",
    /** Picked by hand; null lets the address decide ([inferTrackerProvider]). */
    val chosenProvider: String? = null,
    /** The tracker Connect already added, kept so a retry does not add it twice. */
    val created: TrackerAdminRow? = null,
    val email: String = "",
    val secret: String = "",
    val working: TrackerWork? = null,
    /** Why the last Connect did not end connected, in words. */
    val failure: String? = null,
    /** Close was asked for with something typed: the sheet that asks first. */
    val askingClose: Boolean = false,
) {
    val provider: String? get() = created?.provider ?: chosenProvider ?: inferTrackerProvider(siteUrl)

    /** Never the secret, not even in a log line or a crash report. */
    override fun toString(): String =
        "TrackerWizard(step=$step, siteUrl=$siteUrl, provider=$provider, created=${created?.id}, " +
            "email=$email, secret=${if (secret.isEmpty()) "" else "…"}, working=$working, failure=$failure)"
}

data class TrackersUiState(
    /** A contract-13 hub that lists `work_admin` to this pairing, and a pairing that may write. */
    val available: Boolean = false,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val trackers: List<TrackerAdminRow> = emptyList(),
    val wizard: TrackerWizard? = null,
    /** The tracker whose Test or Remove is on the wire. */
    val busy: Long? = null,
    /** Remove pressed: the sheet that says what disconnecting does. */
    val removing: TrackerAdminRow? = null,
    val notice: String? = null,
    val error: Friendly? = null,
)

/**
 * The hub's trackers on the phone (redesign 14.20, Martin's 2026-10-08
 * decision): the list with each one's state, Test, Sign in again, Remove, and
 * Connect a tracker. Since contract 13 the hub owner's trusted `full` phone
 * may call `work_admin`'s tracker actions; any other device is refused with
 * `E_FORBIDDEN`, and the screen says how the operator trusts it.
 */
class TrackersViewModel(
    private val fleet: FleetState,
    private val actions: TrackerActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
) {
    private data class Local(
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val trackers: List<TrackerAdminRow> = emptyList(),
        val wizard: TrackerWizard? = null,
        val busy: Long? = null,
        val removing: TrackerAdminRow? = null,
        /** The hub's confirmation token for a Remove waiting on a person's yes. */
        val removeNonce: Pair<Long, String>? = null,
        val notice: String? = null,
        val error: Friendly? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<TrackersUiState> = combine(local, fleet.capabilities, fleet.hubContract) { l, caps, contract ->
        TrackersUiState(
            available = trackersAvailable(caps, contract, canWrite),
            loading = l.loading,
            loaded = l.loaded,
            trackers = l.trackers,
            wizard = l.wizard,
            busy = l.busy,
            removing = l.removing,
            notice = l.notice,
            error = l.error,
        )
    }.stateIn(scope, SharingStarted.Eagerly, TrackersUiState())

    fun load(): Job = scope.launch {
        if (!state.value.available) return@launch
        local.update { it.copy(loading = true, error = null) }
        guarded { val rows = actions.list(); local.update { it.copy(trackers = rows, loaded = true) } }
        local.update { it.copy(loading = false) }
    }

    // ── Connect a tracker ──

    fun startConnect() {
        if (!state.value.available) return
        local.update { it.copy(wizard = TrackerWizard(), notice = null, error = null) }
    }

    /** Sign an existing tracker in again: straight to the sign-in step. */
    fun signInAgain(row: TrackerAdminRow) {
        if (!state.value.available) return
        local.update {
            it.copy(
                wizard = TrackerWizard(step = TrackerStep.SignIn, siteUrl = row.siteUrl, created = row, email = row.username.orEmpty()),
                notice = null,
                error = null,
            )
        }
    }

    fun editSite(url: String) = editWizard { if (it.created == null) it.copy(siteUrl = url, failure = null) else it }
    fun chooseProvider(provider: String?) = editWizard { if (it.created == null) it.copy(chosenProvider = provider) else it }
    fun editEmail(email: String) = editWizard { it.copy(email = email, failure = null) }
    fun editSecret(secret: String) = editWizard { it.copy(secret = secret, failure = null) }

    fun next() = editWizard { w -> if (w.step == TrackerStep.Site && siteBlocker(w) == null) w.copy(step = TrackerStep.SignIn) else w }

    /** Back a step, or out of the wizard from its first; nothing while Connect runs. */
    fun back() {
        val w = local.value.wizard ?: return
        if (w.working != null) return
        when {
            w.step == TrackerStep.SignIn && w.created == null -> local.update { it.copy(wizard = w.copy(step = TrackerStep.Site, secret = "")) }
            else -> requestClose()
        }
    }

    /** Close, asking first when something was typed (the wizard rules). */
    fun requestClose() {
        val w = local.value.wizard ?: return
        if (w.working != null) return
        if (trackerTyped(w)) local.update { it.copy(wizard = w.copy(askingClose = true)) } else closeWizard()
    }

    fun keepEditing() = setWizard { it.copy(askingClose = false) }

    fun closeWizard() {
        val w = local.value.wizard ?: return
        if (w.working != null) return
        local.update { it.copy(wizard = null) }
        // A Connect that added the tracker but was not accepted leaves it on the hub: the list shows it.
        if (w.created != null) scope.launch { reload() }
    }

    /** Add the tracker (once), store its sign-in on the hub, then ask the tracker whether it answers. */
    fun connect(): Job = scope.launch {
        val w = local.value.wizard ?: return@launch
        if (w.working != null || w.step != TrackerStep.SignIn || signInBlocker(w) != null) return@launch
        val provider = w.provider ?: return@launch
        val secret = w.secret
        try {
            val row = w.created ?: run {
                setWizard { it.copy(working = TrackerWork.Adding, failure = null) }
                actions.add(w.siteUrl.trim(), w.chosenProvider).also { added -> setWizard { it.copy(created = added) } }
            }
            // The secret leaves the phone here, once, and is dropped from memory.
            setWizard { it.copy(working = TrackerWork.Saving, secret = "") }
            actions.setCredential(row.id, authKindFor(provider), w.email.trim().takeIf { needsEmail(provider) }, secret)
            setWizard { it.copy(working = TrackerWork.Checking) }
            val report = actions.test(row.id)
            if (report.ok) {
                local.update { it.copy(wizard = null, notice = "Connected ${report.tracker.name.ifBlank { providerLabel(provider) }}.") }
                reload()
            } else {
                setWizard { it.copy(working = null, created = report.tracker, failure = testFailure(provider, report.error)) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            setWizard { it.copy(working = null, secret = "") }
            // A refusal that quotes what it was sent must not carry the token
            // onto the screen or into a crash report (14.20).
            local.update { it.copy(error = trackerFriendly(t).without(secret)) }
        }
    }

    // ── One tracker ──

    fun test(row: TrackerAdminRow): Job = scope.launch {
        if (local.value.busy != null) return@launch
        local.update { it.copy(busy = row.id, notice = null, error = null) }
        guarded {
            val report = actions.test(row.id)
            local.update { l ->
                l.copy(
                    trackers = l.trackers.map { if (it.id == row.id) report.tracker else it },
                    notice = if (report.ok) "${row.name} answers." else testFailure(row.provider, report.error),
                )
            }
        }
        local.update { it.copy(busy = null) }
    }

    fun askRemove(row: TrackerAdminRow) = local.update { it.copy(removing = row) }

    fun cancelRemove() = local.update { it.copy(removing = null) }

    fun confirmRemove(): Job = scope.launch {
        val row = local.value.removing ?: return@launch
        if (local.value.busy != null) return@launch
        val nonce = local.value.removeNonce?.takeIf { it.first == row.id }?.second
        local.update { it.copy(busy = row.id, removing = null, error = null) }
        try {
            actions.remove(row.id, nonce)
            local.update { l -> l.copy(removeNonce = null, trackers = l.trackers.filterNot { it.id == row.id }, notice = "Disconnected ${row.name}.") }
            reload()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HubError.Tool) {
            // The hub's confirm gate names its token `confirm_nonce`; `confirm`
            // is `add_project`'s own token. Reading only the latter left the
            // phone with no token, so every Remove asked again forever.
            val details = e.details as? JsonObject
            val token = (details?.get("confirm_nonce") ?: details?.get("confirm"))?.let { (it as? JsonPrimitive)?.content }
            if (e.code == "E_CONFIRM_REQUIRED" && token != null) {
                local.update { it.copy(removeNonce = row.id to token, notice = REMOVE_NEEDS_APPROVAL) }
            } else {
                local.update { it.copy(error = trackerFriendly(e)) }
            }
        } catch (t: Throwable) {
            local.update { it.copy(error = trackerFriendly(t)) }
        } finally {
            local.update { it.copy(busy = null) }
        }
    }

    fun dismissError() = local.update { it.copy(error = null) }

    fun dismissNotice() = local.update { it.copy(notice = null) }

    private suspend fun reload() {
        runCatching { actions.list() }.onSuccess { rows -> local.update { it.copy(trackers = rows, loaded = true) } }
    }

    private fun editWizard(change: (TrackerWizard) -> TrackerWizard) {
        local.update { l -> l.wizard?.takeIf { it.working == null }?.let { l.copy(wizard = change(it)) } ?: l }
    }

    private fun setWizard(change: (TrackerWizard) -> TrackerWizard) {
        local.update { l -> l.wizard?.let { l.copy(wizard = change(it)) } ?: l }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(error = trackerFriendly(t)) }
        }
    }
}

internal fun trackersAvailable(caps: HubCapabilities, contract: Int?, canWrite: Boolean): Boolean =
    canWrite && (contract ?: 0) >= TRACKERS_ON_PHONE_CONTRACT && HubCapabilities.WORK_ADMIN in caps.tools

internal const val REMOVE_NEEDS_APPROVAL = "The hub asks a person to approve this. Approve it on the desktop, then tap Remove again."

/**
 * The phone's version of the hub's `infer_provider`: the tracker a pasted
 * site or ticket URL names, by its host. Null for anything else (a Jira Data
 * Center or GitHub Enterprise host), where the person picks.
 */
internal fun inferTrackerProvider(raw: String): String? {
    val rest = raw.trim().substringAfter("://", "").takeIf { it.isNotEmpty() } ?: return null
    val host = rest.split('/', '?', '#').first().substringAfterLast('@').lowercase().substringBefore(':')
    return when {
        host.endsWith(".atlassian.net") -> "jira"
        host == "github.com" || host == "www.github.com" -> "github"
        host == "app.asana.com" -> "asana"
        host == "linear.app" -> "linear"
        else -> null
    }
}

/** The trackers the phone offers to pick when the address does not say, in the desktop's order. */
internal val TRACKER_PROVIDERS = listOf("jira", "jira_dc", "github", "linear", "asana")

internal fun providerLabel(provider: String?): String = when (provider) {
    "jira" -> "Jira Cloud"
    "jira_dc" -> "Jira Data Center"
    "github" -> "GitHub"
    "linear" -> "Linear"
    "asana" -> "Asana"
    null -> "the tracker"
    else -> provider
}

/** Jira Cloud signs in with an email and an API token; the others with a token alone. */
internal fun needsEmail(provider: String?): Boolean = provider == "jira"

internal fun authKindFor(provider: String): String = if (needsEmail(provider)) "basic" else "bearer"

internal fun tokenLabel(provider: String?): String = when (provider) {
    "jira" -> "API token"
    "linear" -> "API key"
    else -> "Personal access token"
}

internal fun trackerStepHeading(step: TrackerStep): String = when (step) {
    TrackerStep.Site -> "Step 1 of 2 · Site"
    TrackerStep.SignIn -> "Step 2 of 2 · Sign in"
}

/** What Next is waiting for on the site step, or null when it may go on. */
internal fun siteBlocker(w: TrackerWizard): String? = when {
    w.siteUrl.isBlank() -> "Paste the tracker's address, or a link to any ticket on it."
    !w.siteUrl.trim().startsWith("https://") && !w.siteUrl.trim().startsWith("http://") -> "Paste the whole address, starting with https://."
    w.provider == null -> "Pick which tracker this is."
    else -> null
}

/** What Connect is waiting for on the sign-in step, or null. */
internal fun signInBlocker(w: TrackerWizard): String? = when {
    needsEmail(w.provider) && w.email.isBlank() -> "Enter the account's email."
    w.secret.isBlank() -> "Paste the ${tokenLabel(w.provider)}."
    else -> null
}

/** The line beside the loader while Connect runs. */
internal fun connectingLine(work: TrackerWork, provider: String?): String = when (work) {
    TrackerWork.Adding -> "Adding ${providerLabel(provider)} to the hub…"
    TrackerWork.Saving -> "Storing the sign-in on the hub…"
    TrackerWork.Checking -> "Checking the token with ${providerLabel(provider)}…"
}

internal fun testFailure(provider: String?, error: String?): String =
    "${providerLabel(provider)} did not accept the sign-in" + (error?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")

/** A tracker's state in words, never the colour alone. */
internal fun trackerStateWord(state: String): String = when (state) {
    "ok" -> "Connected"
    "auth_failed" -> "Sign-in refused"
    "rate_limited" -> "Rate limited"
    "unreachable" -> "Unreachable"
    "captcha" -> "Needs a captcha on the site"
    "unconfigured" -> "Not signed in"
    else -> "Not syncing"
}

/** A row's second line: site, account, and when it last synced or why it did not. */
internal fun trackerLine(row: TrackerAdminRow, ago: String?): String = listOfNotNull(
    row.siteUrl.substringAfter("://").trimEnd('/').takeIf { it.isNotBlank() },
    row.username?.takeIf { it.isNotBlank() },
    if (row.state != "ok") row.lastError?.takeIf { it.isNotBlank() } else ago?.let { "synced $it" },
).joinToString(" · ")

/** E_FORBIDDEN on this phone means the operator has not trusted it yet; the hub's message names the command. */
/** [this] with every copy of [secret] blanked out of what it shows and keeps. */
internal fun Friendly.without(secret: String): Friendly {
    if (secret.length < 4) return this
    fun String.scrub() = replace(secret, "…")
    return copy(title = title.scrub(), body = body.scrub(), details = details?.scrub())
}

internal fun trackerFriendly(t: Throwable): Friendly =
    if (t is HubError.Tool && t.code == "E_FORBIDDEN") {
        Friendly(
            "This phone may not manage trackers yet",
            if ("client trust" in t.message) t.message else "${t.message} The hub's operator trusts a device with fleet-hub client trust <name>.",
            isError = true,
            details = explain(t),
        )
    } else {
        friendly(t)
    }
