package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.AutofixFinding
import dev.claudefleet.mobile.model.AutofixFindings

/**
 * The calls the Problems & fixes sheet may make (claude-fleet `autofix`).
 * Its own interface, as [DeviceActions] is, so the sheet gets only what it
 * draws.
 */
interface AutofixActions {
    suspend fun list(): AutofixFindings

    /** Check the fleet now; answers the list as it then stands. */
    suspend fun scan(): AutofixFindings

    /** Fix: the row as it now stands (cleared, verifying, or apply_failed). */
    suspend fun apply(id: Long): AutofixFinding

    /** Later: hidden for a day. */
    suspend fun dismiss(id: Long): AutofixFinding
}

/** [AutofixActions] against the paired hub, through [AppSession.withClient]. */
class HubAutofixActions(private val session: AppSession) : AutofixActions {
    override suspend fun list(): AutofixFindings = session.withClient { it.autofixFindings() }

    override suspend fun scan(): AutofixFindings = session.withClient { it.scanAutofix() }

    override suspend fun apply(id: Long): AutofixFinding = session.withClient { it.applyAutofix(id) }

    override suspend fun dismiss(id: Long): AutofixFinding = session.withClient { it.dismissAutofix(id) }
}
