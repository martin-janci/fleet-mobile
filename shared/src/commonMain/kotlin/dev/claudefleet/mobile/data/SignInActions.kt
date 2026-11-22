package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.LoginStatus
import dev.claudefleet.mobile.model.LoginTarget

/**
 * Sign in again (reviews w02-8 / w12-6): `add_account`'s login actions over
 * a login that exists (`again: true`). Each takes the login by host and
 * profile; the host's own login has no profile.
 */
interface SignInActions {
    /** `start_login`: the hub opens the login pane (`claude /login`) on the host. */
    suspend fun start(target: LoginTarget)

    /** `login_status`: the pane, its sign-in link, and whether the sign-in went through. */
    suspend fun status(target: LoginTarget): LoginStatus

    /** `login_key`: a numbered choice's digit, or Enter / Up / Down / Escape. */
    suspend fun key(target: LoginTarget, key: String)

    /** `login_code`: the code the sign-in page showed. */
    suspend fun code(target: LoginTarget, code: String)

    /** `end_login`: close the login pane. */
    suspend fun end(target: LoginTarget)
}

class HubSignInActions(private val session: AppSession) : SignInActions {
    override suspend fun start(target: LoginTarget) = session.withClient { it.loginAgain("start_login", target.host, target.profile) }

    override suspend fun status(target: LoginTarget): LoginStatus = session.withClient { it.loginAgainStatus(target.host, target.profile) }

    override suspend fun key(target: LoginTarget, key: String) = session.withClient { it.loginAgain("login_key", target.host, target.profile, key = key) }

    override suspend fun code(target: LoginTarget, code: String) = session.withClient { it.loginAgain("login_code", target.host, target.profile, code = code) }

    override suspend fun end(target: LoginTarget) = session.withClient { it.loginAgain("end_login", target.host, target.profile) }
}
