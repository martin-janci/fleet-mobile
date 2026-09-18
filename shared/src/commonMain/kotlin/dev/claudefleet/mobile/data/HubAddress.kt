package dev.claudefleet.mobile.data

/**
 * A hub's base URL as the app will store and use it, or null if it is not one.
 *
 * `http` or `https`, an authority, no userinfo, no trailing slash. One
 * implementation for both ways an address arrives — the base carved out of a
 * scanned pair URL, and the one a person types beside a dictated code — because
 * the typed field is the *easier* of the two to get something wrong into, and it
 * used to be the one that went through `trim()` and nothing else.
 *
 * Userinfo is refused for parity with the hub's own `HubBase::public`
 * ("credentials (user@) are not allowed") and for one reason of this app's own:
 * `Credentials.hub` is the single field `Credentials.toString()` prints
 * unredacted, so `user:pw@` would be both a route through someone else's host
 * and a password in every log line that prints the auth state.
 */
internal fun hubBase(url: String): String? {
    val trimmed = url.trim()
    val scheme = trimmed.substringBefore("://", "").lowercase()
    if (scheme != "http" && scheme != "https") return null
    val base = trimmed.trimEnd('/')
    // Everything after `://` up to the first `/` is the authority; a URL with
    // none ("https:///pair") names no hub.
    val authority = base.substringAfter("://", "").substringBefore('/')
    if (authority.isEmpty()) return null
    if ('@' in authority) return null
    // Whitespace anywhere is a paste accident, and a header built from it is a
    // request smuggling primitive rather than a typo.
    if (base.any { it.isWhitespace() }) return null
    return base
}

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
    if (groups == IPV6_LOOPBACK || groups == IPV6_UNSPECIFIED) return true
    // The *hex* spelling of the same mapped address, which is what every tool
    // that prints an IPv6 address prints: `::ffff:7f00:1`, no dot anywhere, so
    // the dotted branch above never sees it. Missing this failed OPEN — the
    // echoed address won, and an app that stores it never reaches the hub again.
    val v4 = mappedIpv4(groups) ?: return false
    return v4[0] == 127 || v4.all { it == 0 }
}

/** The four IPv4 octets inside an IPv4-mapped IPv6 address (`::ffff:a.b.c.d`), or null. */
private fun mappedIpv4(groups: List<Int>): List<Int>? {
    if (groups.take(5).any { it != 0 } || groups[5] != 0xffff) return null
    return listOf(groups[6] shr 8, groups[6] and 0xff, groups[7] shr 8, groups[7] and 0xff)
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
