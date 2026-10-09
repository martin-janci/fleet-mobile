package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.TrackerAdminRow
import dev.claudefleet.mobile.model.TrackerTestReport

/** The tracker actions of `work_admin` a trusted owner's phone may call (contract 13). */
interface TrackerActions {
    suspend fun list(): List<TrackerAdminRow>
    suspend fun add(siteUrl: String, provider: String?): TrackerAdminRow
    suspend fun setCredential(trackerId: Long, authKind: String, username: String?, secret: String): TrackerAdminRow
    suspend fun test(trackerId: Long): TrackerTestReport
    suspend fun remove(trackerId: Long, confirmNonce: String?)
}

class HubTrackerActions(private val session: AppSession) : TrackerActions {
    override suspend fun list(): List<TrackerAdminRow> = session.withClient { it.listTrackers() }
    override suspend fun add(siteUrl: String, provider: String?): TrackerAdminRow = session.withClient { it.addTracker(siteUrl, provider) }
    override suspend fun setCredential(trackerId: Long, authKind: String, username: String?, secret: String): TrackerAdminRow =
        session.withClient { it.setTrackerCredential(trackerId, authKind, username, secret) }
    override suspend fun test(trackerId: Long): TrackerTestReport = session.withClient { it.testTracker(trackerId) }
    override suspend fun remove(trackerId: Long, confirmNonce: String?) = session.withClient { it.removeTracker(trackerId, confirmNonce) }
}
