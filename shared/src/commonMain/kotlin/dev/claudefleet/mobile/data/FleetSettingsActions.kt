package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.PagesBundle
import dev.claudefleet.mobile.model.SettingDescriptor
import dev.claudefleet.mobile.model.SettingsDecided
import dev.claudefleet.mobile.model.SettingsPending

/**
 * The calls the fleet's settings pages make (claude-fleet declarative pages
 * P6): read the page specs and the settings, change one, and review what an
 * agent proposed.
 *
 * Its own interface for the same reason [QuickReplyActions] is one: a 401 goes
 * through [AppSession.withClient] once, in one place, and the view model is
 * testable without a transport.
 */
interface FleetSettingsActions {
    suspend fun pages(): PagesBundle
    suspend fun describe(): List<SettingDescriptor>
    suspend fun set(key: String, value: String): Map<String, String>
    suspend fun pending(): SettingsPending
    suspend fun decide(accept: List<Long>, reject: List<Long>): SettingsDecided
}

/** [FleetSettingsActions] against the paired hub. */
class HubFleetSettingsActions(private val session: AppSession) : FleetSettingsActions {
    override suspend fun pages(): PagesBundle = session.withClient { it.listPages() }
    override suspend fun describe(): List<SettingDescriptor> = session.withClient { it.describeSettings() }
    override suspend fun set(key: String, value: String): Map<String, String> =
        session.withClient { it.setSetting(key, value) }
    override suspend fun pending(): SettingsPending = session.withClient { it.settingProposals() }
    override suspend fun decide(accept: List<Long>, reject: List<Long>): SettingsDecided =
        session.withClient { it.decideSettingProposals(accept, reject) }
}
