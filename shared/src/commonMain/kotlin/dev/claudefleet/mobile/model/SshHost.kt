package dev.claudefleet.mobile.model

import kotlinx.serialization.Serializable

/**
 * One entry of the hub's `~/.ssh/config`, as `discover_hosts` lists it: a
 * candidate for `add_host`. On a Windows hub a WSL distribution comes back
 * the same way, with `hostname` "WSL: <distro>".
 */
@Serializable
data class SshHost(
    val alias: String,
    val hostname: String? = null,
    val user: String? = null,
    val port: Int? = null,
)
