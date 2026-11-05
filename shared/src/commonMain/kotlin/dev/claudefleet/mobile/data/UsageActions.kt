package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.AccountRow
import dev.claudefleet.mobile.model.AutoSwitchView
import dev.claudefleet.mobile.model.OrgDetail
import dev.claudefleet.mobile.model.UsageReport

/** The Usage screen's reads, the account name and the automatic-switching answer. */
interface UsageActions {
    suspend fun report(sinceSecs: Long?): UsageReport
    suspend fun accounts(): List<AccountRow>
    suspend fun setNickname(uuid: String, nickname: String?): AccountRow
    /** Replace the projects [uuid] is forbidden for (`set_account_projects`). */
    suspend fun setForbiddenProjects(uuid: String, projectIds: List<Long>): AccountRow =
        error("set_account_projects is not offered")
    /** Replace the orgs [uuid] is forbidden for (`set_account_projects`, the org list only). */
    suspend fun setForbiddenOrgs(uuid: String, orgIds: List<Long>): AccountRow =
        error("set_account_projects is not offered")
    /** The fleet's orgs, for an account's "Not for these orgs" list. */
    suspend fun orgs(): List<OrgDetail> = emptyList()
    /** Read (no arguments) or change the person's automatic-switching answer. */
    suspend fun autoSwitch(enabled: Boolean? = null, continueAfter: Boolean? = null): AutoSwitchView =
        error("auto_switch_account is not offered")
}

class HubUsageActions(private val session: AppSession) : UsageActions {
    override suspend fun report(sinceSecs: Long?): UsageReport = session.withClient { it.usageReport(sinceSecs) }
    override suspend fun accounts(): List<AccountRow> = session.withClient { it.listAccounts() }
    override suspend fun setNickname(uuid: String, nickname: String?): AccountRow =
        session.withClient { it.setAccountNickname(uuid, nickname) }
    override suspend fun setForbiddenProjects(uuid: String, projectIds: List<Long>): AccountRow =
        session.withClient { it.setAccountProjects(uuid, projectIds) }
    override suspend fun setForbiddenOrgs(uuid: String, orgIds: List<Long>): AccountRow =
        session.withClient { it.setAccountOrgs(uuid, orgIds) }
    override suspend fun orgs(): List<OrgDetail> = session.withClient { it.workOrgs() }
    override suspend fun autoSwitch(enabled: Boolean?, continueAfter: Boolean?): AutoSwitchView =
        session.withClient { it.autoSwitchAccount(enabled, continueAfter) }
}
