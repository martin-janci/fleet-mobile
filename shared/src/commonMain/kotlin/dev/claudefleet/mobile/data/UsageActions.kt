package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.AccountRow
import dev.claudefleet.mobile.model.AutoSwitchView
import dev.claudefleet.mobile.model.UsageReport

/** The Usage screen's reads, the account name and the automatic-switching answer. */
interface UsageActions {
    suspend fun report(sinceSecs: Long?): UsageReport
    suspend fun accounts(): List<AccountRow>
    suspend fun setNickname(uuid: String, nickname: String?): AccountRow
    /** Read (no arguments) or change the person's automatic-switching answer. */
    suspend fun autoSwitch(enabled: Boolean? = null, continueAfter: Boolean? = null): AutoSwitchView =
        error("auto_switch_account is not offered")
}

class HubUsageActions(private val session: AppSession) : UsageActions {
    override suspend fun report(sinceSecs: Long?): UsageReport = session.withClient { it.usageReport(sinceSecs) }
    override suspend fun accounts(): List<AccountRow> = session.withClient { it.listAccounts() }
    override suspend fun setNickname(uuid: String, nickname: String?): AccountRow =
        session.withClient { it.setAccountNickname(uuid, nickname) }
    override suspend fun autoSwitch(enabled: Boolean?, continueAfter: Boolean?): AutoSwitchView =
        session.withClient { it.autoSwitchAccount(enabled, continueAfter) }
}
