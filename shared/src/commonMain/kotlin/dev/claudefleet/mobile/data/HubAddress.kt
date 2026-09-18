package dev.claudefleet.mobile.data

/**
 * Does this URL name the machine it is read on?
 *
 * The question matters exactly once, and it is not academic. After pairing, the
 * hub echoes where to come back to: its configured `hub.public_url` or, when
 * none is set, its own loopback base. Stored verbatim on a phone,
 * `http://127.0.0.1:8899` means *the phone*, and the app can never reach the hub
 * again — so an address that means "this machine" loses to the one the phone
 * demonstrably just reached.
 *
 * The hub's own `HubBase::loopback` only ever emits `http://127.0.0.1:{port}`,
 * but `HubBase::public` validates scheme, userinfo, whitespace, path and port
 * and then **accepts `0.0.0.0` and `[::]`** — and an operator who pastes the
 * bind address into `hub.public_url` is precisely the operator this rule exists
 * to protect. Re-pairing never recovers, because the echo wins every time.
 */
internal fun isLoopbackUrl(url: String): Boolean {
    val authority = url.substringAfter("://", "").substringBefore('/')
    val host = when {
        authority.startsWith("[") -> authority.substringAfter('[').substringBefore(']')
        // An IPv6 literal without brackets has more than one colon; a host:port
        // has exactly one.
        authority.count { it == ':' } > 1 -> authority
        else -> authority.substringBefore(':')
    }
    return isThisMachine(host)
}

/** [isLoopbackUrl] for a bare host, which is where all the shapes live. */
private fun isThisMachine(rawHost: String): Boolean {
    val host = rawHost.trim().trim('[', ']').lowercase()
    if (host.isEmpty()) return false
    // `localhost` and, per RFC 6761, anything under it.
    if (host == "localhost" || host.endsWith(".localhost")) return true
    // IPv4: the whole 127/8 loopback block, and the unspecified address, which
    // is what an operator who pasted a bind address will have.
    if (host.startsWith("127.") || host == "0.0.0.0") return true
    // `::ffff:127.0.0.1` — the same IPv4 addresses, wearing an IPv6 hat.
    if (host.startsWith("::ffff:") && '.' in host) return isThisMachine(host.removePrefix("::ffff:"))
    val groups = expandIpv6(host) ?: return false
    return groups == IPV6_LOOPBACK || groups == IPV6_UNSPECIFIED
}

private val IPV6_LOOPBACK = listOf(0, 0, 0, 0, 0, 0, 0, 1)
private val IPV6_UNSPECIFIED = listOf(0, 0, 0, 0, 0, 0, 0, 0)

/**
 * An IPv6 literal as its eight groups, with `::` expanded and leading zeros
 * dropped, or null if it is not one.
 *
 * Written out rather than matched against a handful of spellings because `::1`,
 * `0:0:0:0:0:0:0:1` and `0000:...:0001` are the same address and a set of
 * strings would catch whichever ones someone thought of.
 */
private fun expandIpv6(host: String): List<Int>? {
    if (':' !in host) return null
    val halves = host.split("::")
    if (halves.size > 2) return null
    fun groups(part: String): List<String>? =
        if (part.isEmpty()) emptyList() else part.split(":").also { if (it.any { g -> g.isEmpty() }) return null }
    val head = groups(halves[0]) ?: return null
    val tail = if (halves.size == 2) groups(halves[1]) ?: return null else emptyList()
    val padding = 8 - head.size - tail.size
    // Without `::` every group has to be written out; with it, at least one is elided.
    if (halves.size == 1 && head.size != 8) return null
    if (padding < 0) return null
    val all = head + List(padding) { "0" } + tail
    if (all.size != 8) return null
    return all.map { group ->
        if (group.length > 4) return null
        group.toIntOrNull(16) ?: return null
    }
}
