package dev.claudefleet.mobile.update

import dev.claudefleet.mobile.store.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import dev.claudefleet.mobile.ui.explain

/**
 * What a platform does with a release (MobileUpdate). Android downloads the
 * APK, compares its signing certificate with the installed app's and hands it
 * to the system installer; iOS updates through TestFlight and has none.
 */
interface AppInstaller {
    /**
     * Fetch [release]'s APK, carrying on from what a paused download left.
     * Reports bytes so far against the total; cancellable, keeping what it has.
     */
    suspend fun download(release: ReleaseInfo, onProgress: (done: Long, total: Long) -> Unit): Downloaded

    /** Whether the downloaded APK is this app, signed with the key the installed app was signed with. */
    suspend fun checkSignature(release: ReleaseInfo): SignatureCheck

    /** Whether Android lets this app install updates; the first time it does not. */
    fun canInstall(): Boolean

    /** Open Android's "install unknown apps" page for this app. */
    fun openInstallPermission()

    /** Hand the checked APK to Android, which asks the person to confirm. */
    suspend fun install(release: ReleaseInfo)

    /** Forget the downloaded file. */
    fun discard(release: ReleaseInfo)
}

/** What [AppInstaller.download] wrote. */
data class Downloaded(val bytes: Long, val sha256: String)

sealed interface SignatureCheck {
    /** The APK is this app, signed by [signer]'s key, the installed app's own. */
    data class Matches(val signer: String) : SignatureCheck

    /** Anything else, in words. Nothing is installed. */
    data class Refused(val reason: String) : SignatureCheck
}

/** "Updates" under This phone. Never "install on my own": the phone offers, a person installs. */
enum class UpdateMode(val label: String) {
    TELL("Tell me, never install on my own"),
    OFF("Don't check"),
}

/** Where one update stands, from the card to Android's own confirm. */
sealed interface UpdatePhase {
    data object Idle : UpdatePhase
    data class Downloading(val done: Long, val total: Long) : UpdatePhase
    data class Paused(val done: Long, val total: Long) : UpdatePhase
    data object Checking : UpdatePhase
    data class Ready(val signer: String, val needsPermission: Boolean) : UpdatePhase

    /** The download is not the release it claims to be. Nothing was installed and the file is gone. */
    data class Refused(val reason: String) : UpdatePhase
    /**
     * The download (or Android's install) stopped. [message] is a sentence
     * for a person; [details] is the technical half (the failure's kind),
     * drawn only behind Details.
     */
    data class Failed(val message: String, val details: String? = null) : UpdatePhase

    /** Android has it; the app closes for the install and opens again. */
    data object Handed : UpdatePhase
}

data class UpdateState(
    val mode: UpdateMode = UpdateMode.TELL,
    /** The release newer than this app, once [UpdateViewModel.check] has found one. */
    val available: ReleaseInfo? = null,
    val phase: UpdatePhase = UpdatePhase.Idle,
    /** Whether this platform can update itself at all. */
    val supported: Boolean = false,
)

internal const val UPDATE_MODE_PREF = "update.mode"
internal const val LAST_VERSION_PREF = "update.lastVersion"
internal const val PENDING_NOTES_PREF = "update.pendingNotes"

/**
 * The phone's own update: found on GitHub releases, offered, never forced.
 * Every step to the install is a tap, and the install only ever follows a
 * signature that matched: [install] does nothing from any other phase. The
 * hub is never touched from here; its owner updates it from the desktop.
 */
class UpdateViewModel(
    private val source: ReleaseSource,
    private val installer: AppInstaller?,
    private val prefs: Prefs,
    private val appVersion: String,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(UpdateState(mode = readMode(), supported = installer != null))
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null
    private var pausing = false

    /** Look for a newer release, quietly. Nothing to do when the setting is off or the platform cannot update. */
    suspend fun check() {
        if (installer == null || _state.value.mode == UpdateMode.OFF) return
        val mine = AppVersion.parse(appVersion) ?: return
        val latest = source.latest() ?: return
        val theirs = AppVersion.parse(latest.version) ?: return
        _state.update { it.copy(available = latest.takeIf { theirs > mine }) }
    }

    fun setMode(mode: UpdateMode) {
        prefs.putStringList(UPDATE_MODE_PREF, listOf(mode.name.lowercase()))
        _state.update { it.copy(mode = mode, available = if (mode == UpdateMode.OFF) null else it.available) }
    }

    /** Download, then check the signature. Resumes a paused download. */
    fun download() {
        val release = _state.value.available ?: return
        val installer = installer ?: return
        if (job?.isActive == true) return
        pausing = false
        val start = _state.value.phase.let { if (it is UpdatePhase.Paused) it.done else 0L }
        _state.update { it.copy(phase = UpdatePhase.Downloading(start, release.sizeBytes)) }
        job = scope.launch {
            try {
                val got = installer.download(release) { done, total ->
                    _state.update { s -> if (s.phase is UpdatePhase.Downloading) s.copy(phase = UpdatePhase.Downloading(done, total)) else s }
                }
                _state.update { it.copy(phase = UpdatePhase.Checking) }
                val expected = release.sha256
                val check = if (expected != null && !expected.equals(got.sha256, ignoreCase = true)) {
                    SignatureCheck.Refused("The download does not match the checksum GitHub published for it.")
                } else {
                    installer.checkSignature(release)
                }
                when (check) {
                    is SignatureCheck.Matches ->
                        _state.update { it.copy(phase = UpdatePhase.Ready(check.signer, !installer.canInstall())) }
                    is SignatureCheck.Refused -> {
                        installer.discard(release)
                        _state.update { it.copy(phase = UpdatePhase.Refused(check.reason)) }
                    }
                }
            } catch (e: CancellationException) {
                _state.update { s ->
                    val p = s.phase
                    if (pausing && p is UpdatePhase.Downloading) s.copy(phase = UpdatePhase.Paused(p.done, p.total)) else s
                }
                throw e
            } catch (t: Throwable) {
                // Not `t.message`: an engine's exception text is not a sentence
                // for a person, and may quote the URL it failed on.
                _state.update { it.copy(phase = UpdatePhase.Failed(DOWNLOAD_STOPPED, explain(t))) }
            }
        }
    }

    /** Stop the download and keep what it has; [download] carries on from there. */
    fun pause() {
        if (_state.value.phase !is UpdatePhase.Downloading) return
        pausing = true
        job?.cancel()
    }

    /** Stop and forget the download. The card stays, for later. */
    fun cancel() {
        pausing = false
        job?.cancel()
        _state.value.available?.let { installer?.discard(it) }
        _state.update { it.copy(phase = UpdatePhase.Idle) }
    }

    /** The person came back from Android's settings page: ask again whether installing is allowed. */
    fun recheckPermission() {
        val installer = installer ?: return
        _state.update { s ->
            val p = s.phase
            if (p is UpdatePhase.Ready) s.copy(phase = p.copy(needsPermission = !installer.canInstall())) else s
        }
    }

    /**
     * Install, from Ready only: a release whose signature did not match never
     * gets here. The first time, Android has to allow it, so this opens that
     * page instead and the person taps Install again on return.
     */
    fun install() {
        val release = _state.value.available ?: return
        val installer = installer ?: return
        if (_state.value.phase !is UpdatePhase.Ready) return
        if (!installer.canInstall()) {
            _state.update { it.copy(phase = UpdatePhase.Ready((it.phase as UpdatePhase.Ready).signer, needsPermission = true)) }
            installer.openInstallPermission()
            return
        }
        // What changed, kept for the screen the updated app shows once.
        prefs.putStringList(PENDING_NOTES_PREF, listOf(release.version) + release.notes)
        _state.update { it.copy(phase = UpdatePhase.Handed) }
        scope.launch {
            try {
                installer.install(release)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.update { it.copy(phase = UpdatePhase.Failed(INSTALL_REFUSED, explain(t))) }
            }
        }
    }

    /** Clear a refusal or a failure so the card offers the download again. */
    fun reset() {
        if (job?.isActive == true) return
        _state.update { it.copy(phase = UpdatePhase.Idle) }
    }

    /** Try again after a failure: runs the download (and the checks after it) again, not just a reset. */
    fun retry() {
        if (_state.value.phase !is UpdatePhase.Failed) return
        reset()
        download()
    }

    private fun readMode(): UpdateMode {
        val stored = prefs.getStringList(UPDATE_MODE_PREF).firstOrNull()
        return UpdateMode.entries.firstOrNull { it.name.lowercase() == stored } ?: UpdateMode.TELL
    }
}

internal const val DOWNLOAD_STOPPED = "The download stopped before it finished. Check the connection, then try again."
internal const val INSTALL_REFUSED = "Android did not take the update. Try again to download and check it once more."

/** The screen the app shows once after an update: the wordmark, then what changed. */
data class WhatsNew(val version: String, val notes: List<String>)

/**
 * Whether this launch is the first after an update, and what changed. True
 * once: the version is recorded as seen on the same call. A fresh install
 * has nothing to compare with and shows nothing.
 */
fun takeWhatsNew(prefs: Prefs, appVersion: String): WhatsNew? {
    val last = prefs.getStringList(LAST_VERSION_PREF).firstOrNull()
    if (last == appVersion) return null
    prefs.putStringList(LAST_VERSION_PREF, listOf(appVersion))
    val before = last?.let(AppVersion::parse) ?: return null
    val now = AppVersion.parse(appVersion) ?: return null
    if (now <= before) return null
    val pending = prefs.getStringList(PENDING_NOTES_PREF)
    prefs.putStringList(PENDING_NOTES_PREF, emptyList())
    val notes = if (pending.firstOrNull() == appVersion) pending.drop(1) else emptyList()
    return WhatsNew(appVersion, notes.take(3))
}

/** The hub and the app are released together under one version; when they differ, one of them is behind. */
sealed interface HubMismatch {
    val hub: String
    val app: String

    /** The hub's owner updates it on the desktop; until then what needs it stays off. */
    data class HubOlder(override val hub: String, override val app: String) : HubMismatch

    /** This app is behind: Update ready. */
    data class HubNewer(override val hub: String, override val app: String) : HubMismatch
}

/** Compares the released numbers only; a version either side cannot read is no mismatch. */
fun hubMismatch(appVersion: String, hubVersion: String?): HubMismatch? {
    val app = AppVersion.parse(appVersion) ?: return null
    val hub = hubVersion?.let(AppVersion::parse) ?: return null
    val a = Triple(app.major, app.minor, app.patch)
    val h = Triple(hub.major, hub.minor, hub.patch)
    return when {
        h == a -> null
        compareValuesBy(h, a, { it.first }, { it.second }, { it.third }) < 0 -> HubMismatch.HubOlder(hub.toString(), app.toString())
        else -> HubMismatch.HubNewer(hub.toString(), app.toString())
    }
}
