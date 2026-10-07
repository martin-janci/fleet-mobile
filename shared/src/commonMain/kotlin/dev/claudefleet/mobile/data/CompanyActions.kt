package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.OrgDetail

/**
 * The Company screen's one read: `work { action: orgs }`, the same read the
 * org directory makes, re-read on opening so spend and members are current.
 * There is no write here — company administration stays on the desktop.
 */
interface CompanyActions {
    suspend fun orgs(): List<OrgDetail>
}

class HubCompanyActions(private val session: AppSession) : CompanyActions {
    override suspend fun orgs(): List<OrgDetail> = session.withClient { it.workOrgs() }
}
