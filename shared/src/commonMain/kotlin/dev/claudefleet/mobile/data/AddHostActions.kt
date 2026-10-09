package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.SshHost

/**
 * Adding a host from the phone (redesign 14.12 Radar, claude-fleet contract
 * 13's owner's-phone grant): the hub's SSH config as candidates, and
 * `add_host`, which probes a host before keeping it.
 */
interface AddHostActions {
    suspend fun candidates(): List<SshHost>

    suspend fun add(alias: String, sshAlias: String): HostRow
}

class HubAddHostActions(private val session: AppSession) : AddHostActions {
    override suspend fun candidates(): List<SshHost> = session.withClient { it.discoverHosts() }

    override suspend fun add(alias: String, sshAlias: String): HostRow = session.withClient { it.addHost(alias, sshAlias) }
}
