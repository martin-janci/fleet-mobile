package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Sign in again on the phone (reviews w02-8 / w12-6, option a): the
 * desktop's `SignInAgain.svelte` + `AddAccountLogin.svelte` over the hub's
 * `add_account { again: true }` login actions. The hub opens a login pane
 * over the login that is there (`start_login`), the phone polls it
 * (`login_status`), the person opens the sign-in link in the phone's
 * browser and pastes the code back (`login_code`), and the pane closes
 * (`end_login`). The helpers here port claude-fleet's `src/lib/add_account.ts`
 * word for word where the desktop shows the same thing.
 */

/** One login a person can sign in again: [profile] null is the host's own. */
data class LoginTarget(
    val host: String,
    val profile: String?,
    /** A setup-token profile: renewed with `claude setup-token`, never with the OAuth flow (review w12-3). */
    val token: Boolean = false,
) {
    /** "work" or, for the host's own login, "the host login" (the desktop's `loginName`). */
    val name: String get() = profile ?: "the host login"

    /** The command to run in a terminal instead ([loginShellCommand]): the fallback the sheet always keeps. */
    val command: String get() = loginShellCommand(host, profile, token)
}

/** The [LoginTarget] for [profile]'s login on [host]. */
fun loginTarget(host: HostRow, profile: String?): LoginTarget = LoginTarget(host.alias, profile, host.isSetupToken(profile))

/** `add_account { login_status }`'s answer (the hub's `LoginStatus`). */
@Serializable
data class LoginStatus(
    @SerialName("logged_in") val loggedIn: Boolean = false,
    @SerialName("account_uuid") val accountUuid: String? = null,
    val email: String? = null,
    /** The login pane's last lines while not signed in; null when there is no pane. */
    val pane: String? = null,
    /** The sign-in link on screen, once the CLI printed one. */
    @SerialName("sign_in_url") val signInUrl: String? = null,
    /** "Run it there": the command for a terminal on the host. */
    val command: String = "",
)

/** One numbered choice on the login pane's screen ("❯ 1. Claude account …"): its key and its words. */
data class PaneChoice(val key: String, val label: String)

private val CHOICE = Regex("""^\s*(?:[❯›>]\s*)?([1-9])\.\s+(.+?)\s*$""")

/** The numbered choices on the pane, at most nine, in screen order (the desktop's `paneChoices`). */
fun paneChoices(pane: String?): List<PaneChoice> {
    if (pane == null) return emptyList()
    val out = mutableListOf<PaneChoice>()
    for (line in pane.split('\n')) {
        val m = CHOICE.find(line) ?: continue
        val key = m.groupValues[1]
        if (out.none { it.key == key }) out += PaneChoice(key, m.groupValues[2])
    }
    return out.take(9)
}

/** The keys the login pane takes besides a choice's digit (the hub's `LOGIN_KEYS`). */
val LOGIN_KEYS = listOf("Enter", "Up", "Down", "Escape")

/** The pane waits for the code the sign-in page shows (the desktop's `asksForCode`). */
fun asksForCode(pane: String?): Boolean = pane != null && Regex("paste code", RegexOption.IGNORE_CASE).containsMatchIn(pane)

private val DEVICE_CODE = Regex("""\bcode\b[^\n]*?\b([A-Z0-9]{4,5}-[A-Z0-9]{4,5})\b""", RegexOption.IGNORE_CASE)

/** A one-time device code on the pane ("Enter code ABCD-1234"), for Copy code; null when none. */
fun deviceCode(pane: String?): String? = pane?.let { DEVICE_CODE.find(it)?.groupValues?.get(1)?.uppercase() }

/** How long Claude's sign-in page gives a link when the pane does not say. */
const val LOGIN_LINK_MINUTES = 10

private val LINK_MINUTES = Regex("""expires?\s+in\s+(\d{1,3})\s*min""", RegexOption.IGNORE_CASE)

/** How long the sign-in link lasts: what the pane says ("expires in 15 minutes"), else [LOGIN_LINK_MINUTES]. */
fun linkMinutes(pane: String?): Int = pane?.let { LINK_MINUTES.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: LOGIN_LINK_MINUTES

/** The line under the link: minutes left, or that it has run out. */
data class LinkExpiry(val text: String, val expired: Boolean)

/** [LinkExpiry] for a link first seen at [seenAtMs] that lasts [minutes], at [nowMs] (the desktop's `expiryLine`). */
fun expiryLine(seenAtMs: Long, minutes: Int, nowMs: Long): LinkExpiry {
    val leftMs = seenAtMs + minutes * 60_000L - nowMs
    // Math.ceil of the minutes left, in whole numbers: 1 ms left is "1 min".
    val left = if (leftMs <= 0) 0L else (leftMs + 59_999L) / 60_000L
    return if (left <= 0) {
        LinkExpiry("This link has expired. Start over for a new one.", expired = true)
    } else {
        LinkExpiry("The link expires in $left min.", expired = false)
    }
}

/** What a finished sign-in says: "work on htz is signed in again as a@b.c." */
fun signedInAgainLine(target: LoginTarget, email: String?): String {
    val who = target.profile ?: "The host login"
    return "$who on ${target.host} is signed in again${email?.let { " as $it" }.orEmpty()}."
}

/**
 * The signed-out logins whose account is known, by account uuid (for the
 * Accounts rows): the host's own login bills [HostRow.accountUuid], a
 * profile the account its [HostProfile] names. A login whose account fleet
 * never read is left out here; the host sheet and Control's card still show it.
 */
fun signedOutByAccount(hosts: List<HostRow>): Map<String, List<LoginTarget>> {
    val out = LinkedHashMap<String, MutableList<LoginTarget>>()
    for (h in hosts) {
        if (h.hidden) continue
        for (l in h.signedOutLogins) {
            val uuid = if (l.profile == null) h.accountUuid else h.claudeProfiles.orEmpty().firstOrNull { it.name == l.profile }?.accountUuid
            if (uuid.isNullOrBlank()) continue
            out.getOrPut(uuid) { mutableListOf() } += loginTarget(h, l.profile)
        }
    }
    return out
}
