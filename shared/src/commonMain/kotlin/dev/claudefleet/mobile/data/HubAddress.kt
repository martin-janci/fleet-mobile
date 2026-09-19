package dev.claudefleet.mobile.data

/**
 * A hub's base URL as the app will store and use it, or null if it is not one.
 *
 * `http` or `https`, an authority, optionally a path prefix, and nothing else.
 * **All three** ways an address arrives go through here — the base carved out of
 * a scanned pair URL, the one a person types beside a dictated code, and the one
 * the hub echoes back after pairing — because all three land in
 * `Credentials.hub`, which is dialled for every later request.
 *
 * The rules mirror the hub's own `HubBase::public`
 * (`crates/fleet-core/src/service/hub.rs`), so that anything a correctly-built
 * hub can be configured with is accepted and nothing else is:
 *
 *  - **userinfo is refused** — the Rust says "credentials (user@) are not
 *    allowed", and this app has a second reason: `hub` is the one field
 *    `Credentials.toString()` prints unredacted, so `user:pw@` would be both a
 *    route through someone else's host and a password in every log line that
 *    prints the auth state;
 *  - **query and fragment are refused.** Not a security hole — they simply fail
 *    to connect — but every later URL is built by concatenating onto this
 *    string, and a base already carrying a `?` produces nonsense rather than an
 *    error. Someone who pastes the address bar of a hub behind an SSO redirect
 *    gets told, instead of a silent dead end;
 *  - **a port must be a port**, 1–65535, for the same reason;
 *  - **whitespace and control characters are refused anywhere.** A CR or LF in
 *    an address is a request-smuggling primitive rather than a typo, and a NUL
 *    is how a name gets read as one thing by a check and another by a dialler;
 *  - **the scheme is lower-cased** rather than echoed back as it arrived, so two
 *    spellings of one hub are one string.
 *
 * A path prefix is deliberately allowed: the hub can be mounted under one, and
 * `pairBase` depends on it.
 */
internal fun hubBase(url: String): String? {
    val trimmed = url.trim()
    val separator = trimmed.indexOf("://")
    if (separator < 0) return null
    val scheme = trimmed.substring(0, separator).lowercase()
    if (scheme != "http" && scheme != "https") return null

    val rest = trimmed.substring(separator + 3).trimEnd('/')
    if (rest.isEmpty()) return null
    // Neither may appear, so neither is somewhere a path could be split.
    if ('?' in rest || '#' in rest) return null
    // Whitespace is a paste accident; the other control characters are not.
    if (rest.any { it.isWhitespace() || it.isControl() }) return null

    val authority = rest.substringBefore('/')
    if (authority.isEmpty()) return null
    if ('@' in authority) return null
    if (!authority.hasUsablePort()) return null

    // The app's own transport policy, rather than whatever the platform happens
    // to default to. See [permitsCleartext].
    val candidate = "$scheme://$rest"
    if (!permitsCleartext(candidate)) return null

    return candidate
}

/**
 * Whether the authority's port, if it has one, is a port.
 *
 * The colon that matters is the one after any bracketed IPv6 literal; inside the
 * brackets every colon belongs to the address. An unclosed bracket is not an
 * authority at all.
 */
private fun String.hasUsablePort(): Boolean {
    if (startsWith("[")) {
        val close = indexOf(']')
        if (close < 0) return false
        val after = substring(close + 1)
        if (after.isEmpty()) return true
        return after.startsWith(":") && after.drop(1).isPort()
    }
    val colons = count { it == ':' }
    // A bracketless authority with more than one colon is an IPv6 literal that
    // has lost its brackets, which is not a URL authority at all.
    if (colons > 1) return false
    if (colons == 0) return true
    return substringAfterLast(':').isPort()
}

private fun String.isPort(): Boolean = (toIntOrNull() ?: return false) in 1..65535

/** The C0 controls, DEL, and the C1 range. */
private fun Char.isControl(): Boolean = this < ' ' || this in ''..''

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
internal fun isLoopbackUrl(url: String): Boolean =
    isThisMachine(hostOf(url.substringAfter("://", "").substringBefore('/')))

/**
 * The bare host inside an authority, with any port and brackets removed.
 *
 * Shared by [isLoopbackUrl] and [permitsCleartext] rather than written twice.
 * Duplicated host parsing in this file is not a hypothetical risk: it is the
 * documented root cause of the four separate occasions [isThisMachine] was
 * wrong.
 */
private fun hostOf(authority: String): String = when {
    authority.startsWith("[") -> authority.substringAfter('[').substringBefore(']')
    // An IPv6 literal without brackets has more than one colon; a host:port
    // has exactly one.
    authority.count { it == ':' } > 1 -> authority
    else -> authority.substringBefore(':')
}

/**
 * May this URL be spoken over plain `http://`?
 *
 * The app's own transport policy, rather than two platforms' defaults. Android
 * at `targetSdk` 35 blocks cleartext unless an app opts in, and iOS's App
 * Transport Security blocks it except to the local network, so today both
 * platforms refuse a plaintext hub on the public internet before this function
 * is consulted. That is exactly why it exists: the app was relying on a default
 * it does not control and never stated, and a single edit to either platform's
 * configuration — the one thing an operator who wants a LAN hub is most likely
 * to reach for — would have exposed it with nothing here objecting.
 *
 * `https` is always fine. `http` is permitted only to a destination that is
 * plausibly this machine or this network:
 *
 *  - anything [isThisMachine] already recognises, in all of its spellings;
 *  - the RFC 1918 private ranges, CGNAT (100.64/10) and link-local (169.254/16);
 *  - IPv6 unique-local (`fc00::/7`) and link-local (`fe80::/10`);
 *  - a single-label name (`fleethub`) or one under `.local`, which is how an
 *    mDNS or short-name LAN host is written and cannot be a public name.
 *
 * **This fails CLOSED, and that is a deliberate trade.** An operator running a
 * hub over plain http at a public-looking DNS name that resolves privately —
 * `http://hub.mycompany.internal` — is refused and has to use https. They see a
 * refusal with a reason, which is recoverable; the alternative is a bearer token
 * on the open internet in the clear, which is not. Every other failure this file
 * has had failed *open*, so this is the first one worth taking in the other
 * direction.
 */
internal fun permitsCleartext(url: String): Boolean {
    val scheme = url.substringBefore("://", "").trim().lowercase()
    if (scheme != "http") return true

    val host = hostOf(url.substringAfter("://", "").substringBefore('/'))
        .trim().trim('[', ']').lowercase().trimEnd('.')
    if (host.isEmpty()) return false
    if (isThisMachine(host)) return true

    // A name rather than an address. A single label cannot be a public FQDN, and
    // `.local` is reserved for mDNS by RFC 6762.
    if (host.endsWith(".local")) return true

    inetAton(host)?.let { return it.isPrivateIpv4() }

    val groups = expandIpv6(host)
    if (groups != null) {
        // `fc00::/7` unique-local, `fe80::/10` link-local.
        if ((groups[0] and 0xFE00) == 0xFC00) return true
        if ((groups[0] and 0xFFC0) == 0xFE80) return true
        embeddedIpv4(groups)?.let { v4 ->
            val packed = (v4[0].toLong() shl 24) or (v4[1].toLong() shl 16) or
                (v4[2].toLong() shl 8) or v4[3].toLong()
            return packed.isPrivateIpv4()
        }
        return false
    }

    // Not an address at all. One label with no dot is a LAN short name.
    return '.' !in host
}

/** RFC 1918, CGNAT and link-local, on a 32-bit address. */
private fun Long.isPrivateIpv4(): Boolean {
    val a = (this ushr 24) and 0xFF
    val b = (this ushr 16) and 0xFF
    return when {
        a == 10L -> true
        a == 172L && b in 16..31 -> true
        a == 192L && b == 168L -> true
        a == 100L && b in 64..127 -> true  // 100.64/10, carrier-grade NAT
        a == 169L && b == 254L -> true     // 169.254/16, link-local
        else -> false
    }
}

/**
 * Does this bare host denote the machine the app is running on?
 *
 * This function has been wrong four times, always in the same direction and
 * always for the same reason: each fix added the spelling that had just been
 * found. So it is now two steps rather than a list.
 *
 * **First normalise, then decide.** Everything that is merely *notation* — the
 * brackets around an IPv6 literal, letter case, a fully-qualified trailing dot,
 * an IPv4 address embedded in an IPv6 one — is removed before anything is
 * compared, so the decision sees one canonical form. The misses were all
 * normalisation failures, not missing cases: `localhost.` is `localhost` with a
 * root label, and `[0:0:0:0:0:ffff:127.0.0.1]` is `127.0.0.1` written the way
 * RFC 4291 §2.2.3 allows. Adding them as strings would have left their
 * neighbours (`2130706433.`, `[::0.0.0.0]`) still open, which is exactly what
 * happened each previous time.
 *
 * **Over-matching is the safe direction and under-matching is not.** A host this
 * says yes to merely loses to the address the phone demonstrably just reached; a
 * host it says no to is stored and dialled forever, and re-pairing never
 * recovers, because the hub echoes the same thing again. That asymmetry is why
 * `127.0.0.1.evil.com` is deliberately left matching: it is a hostname, not an
 * address, and the false positive costs nobody anything.
 *
 * The shapes covered, each verified rather than assumed: `localhost` and
 * anything under it, with or without a root dot; the whole `127/8` block and
 * `0.0.0.0` in all four `inet_aton` notations (dotted, octal, hex, and fewer
 * than four parts); `::1` and `::` however padded; and IPv4 embedded in IPv6
 * both mapped (`::ffff:a.b.c.d`, `::ffff:7f00:1`) and compatible
 * (`::a.b.c.d`) — Java's `InetAddress` folds both to the IPv4 address, so an
 * HTTP client really does reach this machine through them.
 */
private fun isThisMachine(rawHost: String): Boolean {
    // A trailing dot is the root label of a fully-qualified name: `localhost.`
    // and `localhost` are the same name to every resolver, and the hub's own
    // `HubBase::public` accepts it because axum's `Authority` parses it. Not
    // stripping it defeated the numeric parser too — `2130706433.` is the form
    // the previous commit had just fixed, beaten by one character.
    val host = rawHost.trim().trim('[', ']').lowercase().trimEnd('.')
    if (host.isEmpty()) return false
    // `localhost` and, per RFC 6761, anything under it.
    if (host == "localhost" || host.endsWith(".localhost")) return true
    // IPv4: the whole 127/8 loopback block, and the unspecified address, which
    // is what an operator who pasted a bind address will have.
    //
    // Kept as a prefix test as well as the numeric parse below, because it also
    // catches `127.0.0.1.evil.com` — a hostname, not an address. That is a false
    // positive and it fails SAFE: the echo loses and the address the phone
    // actually reached is kept, which is the outcome nobody is harmed by.
    // Leaving that behaviour alone is deliberate.
    if (host.startsWith("127.") || host == "0.0.0.0") return true
    // Every other spelling of the same address. `2130706433`, `0177.0.0.1`,
    // `0x7f.0.0.1` and `127.1` are all 127.0.0.1 to `inet_aton`, and therefore
    // to `InetAddress.getByName` and to the HTTP clients built on it.
    inetAton(host)?.let { return (it ushr 24) == 127L || it == 0L }
    // An IPv6 literal, with any embedded IPv4 already folded into its two
    // groups by `expandIpv6`. There is deliberately no special case for
    // `::ffff:` spelled with dots any more: it is the same address as
    // `::ffff:7f00:1` and now takes the same path.
    val groups = expandIpv6(host) ?: return false
    if (groups == IPV6_LOOPBACK || groups == IPV6_UNSPECIFIED) return true
    val v4 = embeddedIpv4(groups) ?: return false
    return v4[0] == 127 || v4.all { it == 0 }
}

/**
 * The 32-bit address a host spells as IPv4, in every form `inet_aton` accepts,
 * or null when it is not an IPv4 address at all.
 *
 * Written out rather than matched against the spellings someone thought of,
 * which is how `::ffff:7f00:1` was missed in the first place. `inet_aton` takes
 * one to four parts — the last one covering every byte the earlier ones did not
 * name, so `127.1` is 127.0.0.1 and `2130706433` is the whole thing — and reads
 * each part as hex with `0x`, octal with a leading `0`, and decimal otherwise.
 *
 * Over-matching here is the safe direction. A host this says yes to loses to the
 * address the phone actually reached; a host it says no to is stored and dialled.
 */
private fun inetAton(host: String): Long? {
    val parts = host.split('.')
    if (parts.size > 4) return null
    val values = parts.map { numericPart(it) ?: return null }
    // 1 part covers 32 bits, 2 parts 24, 3 parts 16, 4 parts 8.
    val tailBits = (5 - values.size) * 8
    var address = 0L
    for ((index, value) in values.withIndex()) {
        if (index == values.lastIndex) {
            if (value >= (1L shl tailBits)) return null
            address = address or value
        } else {
            if (value > 255) return null
            address = address or (value shl (32 - 8 * (index + 1)))
        }
    }
    return address
}

/** One `inet_aton` part: `0x` hex, leading-zero octal, else decimal. */
private fun numericPart(part: String): Long? = when {
    part.isEmpty() -> null
    part.startsWith("0x") -> part.drop(2).takeIf { it.isNotEmpty() }?.toLongOrNull(16)
    part.length > 1 && part[0] == '0' -> part.drop(1).toLongOrNull(8)
    else -> part.toLongOrNull(10)
}?.takeIf { it >= 0 }

/**
 * The four IPv4 octets carried inside an IPv6 address, or null.
 *
 * Both embeddings RFC 4291 defines: IPv4-**mapped** (`::ffff:a.b.c.d`, group 5
 * is `ffff`) and the deprecated IPv4-**compatible** (`::a.b.c.d`, group 5 is
 * zero). The compatible form is included because Java's `InetAddress` folds it
 * to a plain `Inet4Address` — so `http://[::127.0.0.1]/` really does reach this
 * machine through OkHttp, whatever the RFC says about deprecation.
 *
 * `::1` and `::` reach here too and would come back as `0.0.0.1` and `0.0.0.0`;
 * both are decided by the loopback/unspecified comparison before this is called,
 * so the order at the call site is load-bearing.
 */
private fun embeddedIpv4(groups: List<Int>): List<Int>? {
    if (groups.take(5).any { it != 0 }) return null
    if (groups[5] != 0xffff && groups[5] != 0) return null
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
private fun expandIpv6(raw: String): List<Int>? {
    if (':' !in raw) return null
    // RFC 4291 §2.2.3 lets the last 32 bits be written as an IPv4 address:
    // `::ffff:127.0.0.1` and `[0:0:0:0:0:ffff:127.0.0.1]` are the same address
    // as `::ffff:7f00:1`. Folding it into two hex groups here is what lets the
    // rest of this function stay a pure IPv6 parser, and is what removed the
    // `startsWith("::ffff:")` special case that only ever caught one spelling
    // of it.
    val host = foldIpv4Tail(raw) ?: return null
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

/**
 * An IPv6 literal whose last group is a dotted IPv4 address, rewritten with that
 * address as two hex groups. Unchanged when there is no dotted tail, null when
 * the tail is present but is not an IPv4 address.
 *
 * **Known gap:** a zone-scoped literal — `[::1%25eth0]`, the
 * percent-encoded RFC 6874 form — is not recognised as this machine, and fails
 * OPEN: the hub's echoed base would win. Nothing in `fleet-hub` emits a scoped
 * loopback literal; it would take an operator configuring `hub.public_url` to
 * one by hand. It is written down rather than fixed because the fix is to strip
 * a `%…` suffix before parsing, and this file has been "fixed" four times by
 * people adding one more spelling to a list. The next change here should be the
 * normalisation, not another branch.
 */
private fun foldIpv4Tail(host: String): String? {
    val lastColon = host.lastIndexOf(':')
    val tail = host.substring(lastColon + 1)
    if ('.' !in tail) return host
    val address = inetAton(tail) ?: return null
    val high = ((address ushr 16) and 0xffff).toString(16)
    val low = (address and 0xffff).toString(16)
    return host.substring(0, lastColon + 1) + high + ":" + low
}
