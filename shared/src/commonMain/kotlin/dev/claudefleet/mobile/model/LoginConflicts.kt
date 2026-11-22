package dev.claudefleet.mobile.model

import kotlinx.serialization.Serializable

/*
 * Login conflicts (claude-fleet `service::login_conflicts`, review w12-4):
 * two copies of one Claude login, which log each other out — every OAuth
 * refresh revokes the other copy's token, so signing in again only starts
 * the next round. The hub computes them and sends each host's in
 * `fleet_health.hosts[].login_conflicts`; the phone shows them read-only,
 * since which copy goes is the person's call. An older hub sends none.
 */

/** One login in the fleet: a host's own (`profile` null) or one of its profiles. */
@Serializable
data class LoginAt(val host: String = "", val profile: String? = null)

/**
 * One conflict on a host, the hub's tagged `LoginConflict`: `kind` is
 * `keychain_and_file`, `shared_account` (with [logins] and [email]) or
 * `copied` (with [profile] and [copies]). A kind this build does not know is
 * kept and described as nothing ([describeLoginConflict] answers null).
 */
@Serializable
data class LoginConflict(
    val kind: String = "",
    /** `shared_account`: the logins on one account, null for the host's own. */
    val logins: List<String?> = emptyList(),
    val email: String? = null,
    /** `copied`: the host's login that is a copy (null: its own), and the logins it is a copy of. */
    val profile: String? = null,
    val copies: List<LoginAt> = emptyList(),
)

private fun LoginAt.name(): String = profile?.let { "profile $it on $host" } ?: "the host login on $host"

/** One sentence for a person: what is wrong on [alias] and what to do (the hub's `LoginConflict::describe`). */
fun describeLoginConflict(c: LoginConflict, alias: String): String? = when (c.kind) {
    "keychain_and_file" ->
        "$alias: the default Claude login is kept twice, in the macOS Keychain (the desktop app, a local terminal) " +
            "and in ~/.claude/.credentials.json (sessions started over SSH). Each refresh revokes the other copy, so one " +
            "side keeps hitting \"Login expired\". Run fleet's sessions there under a login profile, then delete " +
            "~/.claude/.credentials.json: the mark stays while both copies exist."
    "shared_account" -> {
        val names = c.logins.map { l -> l?.let { "profile $it" } ?: "the host login" }
        val all = if (names.size == 2) "both" else "all"
        "$alias: ${names.joinToString(" and ")} are $all signed into ${c.email ?: "one account"}. Each refresh revokes " +
            "the others' copy, so they keep hitting \"Login expired\". Keep one login per account on a host: sign the " +
            "others into other accounts."
    }
    "copied" -> {
        val self = c.profile?.let { "profile $it" } ?: "the host login"
        "$alias: $self is a copy of one Claude login that is also ${c.copies.joinToString(" and ") { it.name() }}: " +
            "they share one refresh token, so each refresh revokes the others' copy and they take turns hitting " +
            "\"Login expired\". Sign each host in on its own (Sign in again), and stop copying .credentials.json " +
            "between hosts (a dotfiles or ~/.claude sync, a copied profile dir)."
    }
    else -> null
}
