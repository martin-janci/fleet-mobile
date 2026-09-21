package dev.claudefleet.mobile.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Task 5 review's S2 and N6, and the fourth attempt at `isLoopbackUrl`.
 *
 * The previous three attempts each added the spelling that had just been found,
 * and each left its neighbours open. These tests are written as *sets of
 * spellings of the same address* rather than as individual cases, so a fix that
 * closes one and not its twin fails here rather than in six weeks.
 */
class ThisMachineTest {

    /**
     * Every spelling that reaches the device the app runs on. A `false` here
     * fails **open**: the hub's echoed address wins, is stored, and the phone
     * dials itself for good — re-pairing cannot recover, because the hub echoes
     * the same thing again.
     */
    @Test
    fun every_spelling_of_this_machine_is_recognised() {
        val thisMachine = listOf(
            // The plain ones.
            "http://127.0.0.1:8899",
            "http://localhost:8899",
            "http://LOCALHOST:8899",
            "http://[::1]:8899",
            "http://0.0.0.0:8899",
            "http://[::]:8899",
            "http://[0:0:0:0:0:0:0:1]:8899",
            "http://[0000:0000:0000:0000:0000:0000:0000:0001]:8899",
            "http://sub.localhost:8899",
            // `inet_aton` notations. All of these are 127.0.0.1 to
            // `InetAddress.getByName` and to every HTTP client built on it.
            "http://2130706433:8899",
            "http://0177.0.0.1:8899",
            "http://0x7f.0.0.1:8899",
            "http://0x7f000001:8899",
            "http://127.1:8899",
            "http://127.000.000.001:8899",
            "http://0:8899",
            // IPv4 embedded in IPv6, **mapped** (`::ffff:`) — dotted and hex,
            // compressed and not.
            "http://[::ffff:127.0.0.1]:8899",
            "http://[::ffff:7f00:1]:8899",
            "http://[::ffff:7F00:0001]:8899",
            "http://[0:0:0:0:0:ffff:127.0.0.1]:8899",
            "http://[0:0:0:0:0:FFFF:7F00:1]:8899",
            "http://[::ffff:0.0.0.0]:8899",
            // …and **compatible** (`::a.b.c.d`), which RFC 4291 deprecates and
            // Java's `InetAddress` still folds to a plain `Inet4Address`, so
            // OkHttp really does reach this machine through it.
            "http://[::127.0.0.1]:8899",
            "http://[::0.0.0.0]:8899",
            "http://[0:0:0:0:0:0:127.0.0.1]:8899",
            // The fully-qualified forms. A trailing dot is the root label; every
            // resolver accepts it and so does the hub's own `HubBase::public`.
            "http://localhost.:8899",
            "http://LOCALHOST.:8899",
            "http://127.0.0.1.:8899",
            "http://0.0.0.0.:8899",
            "http://2130706433.:8899",
        )
        val failedOpen = thisMachine.filterNot { isLoopbackUrl(it) }
        assertEquals(emptyList(), failedOpen, "these name this machine and were not recognised")
    }

    /**
     * The transport rule normalises its host too — on the branches that are its
     * own, not `isThisMachine`'s.
     *
     * `isLoopbackUrl` and `permitsCleartext` each have to get from a URL to a
     * bare, normalised host, and each used to write the same four-step
     * normalisation out at its own call site. Two copies of a rule are two
     * things that can drift, and drift in *this* rule is the documented root
     * cause of the four separate occasions [isThisMachine] was wrong, so they
     * now share one `hostOfUrl`.
     *
     * The spellings below are chosen to make that shared step load-bearing.
     * Asserting it with loopback spellings proves nothing — `permitsCleartext`
     * asks `isThisMachine` first, and that function normalises again on its own
     * account, so it would paper over a missing step. These four get past that
     * question and land on `permitsCleartext`'s own branches — `.local`, the
     * private-range parse, and the single-label name — where nothing else will
     * fold the case or drop the root label.
     *
     * Each one fails **closed** if the normalisation is missing: an operator
     * with a perfectly good LAN hub is told to use HTTPS for no reason they can
     * see.
     */
    @Test
    fun the_cleartext_rule_normalises_the_host_on_its_own_branches_too() {
        for (url in listOf(
            // A root label on a private address: `inet_aton` sees a fifth,
            // empty part and gives up, and the address is refused.
            "http://192.168.1.5.:8899",
            "http://10.0.0.7.:8899",
            // `.local` is reserved for mDNS by RFC 6762 whatever case it is
            // typed in, and an operator dictating a hostname over the phone is
            // exactly who types it in the wrong one.
            "http://FLEETHUB.LOCAL:8899",
            "http://Fleethub.Local:8899",
            // A single-label LAN name with a root label.
            "http://fleethub.:8899",
        )) {
            assertTrue(
                permitsCleartext(url),
                "$url is on this network however it is spelled — the transport rule must " +
                    "normalise the host on its own branches, not only inside isThisMachine",
            )
        }
    }

    /**
     * The rule must not become "always ignore what the hub says". A `true` here
     * fails **safe** — the address the phone reached is kept — but a rule that
     * matched everything would throw away the hub's own public URL, which is
     * the one thing it knows and the phone does not.
     */
    @Test
    fun a_real_address_still_wins() {
        val real = listOf(
            "https://10.0.0.4:8899",
            "http://hub.example.com:8899",
            "http://notlocalhost:8899",
            "http://localhostx:8899",
            "http://[2001:db8::1]:8899",
            "http://[::2]:8899",
            // Mapped and compatible embeddings of addresses that are not this
            // machine: the fix must not swallow the whole embedded range.
            "http://[::ffff:8f00:1]:8899",
            "http://[::ffff:143.0.0.1]:8899",
            "http://[::ffff:10.0.0.4]:8899",
            "http://[::10.0.0.4]:8899",
            "http://[::ffff:0.0.0.1]:8899",
            // A real IPv6 address whose LAST 32 BITS happen to spell 127.0.0.1.
            // Only the leading five zero groups make an embedding an embedding;
            // without that check these read as loopback and the hub's real
            // address would lose. Found by a surviving mutation, not by design.
            "http://[2001:db8::7f00:1]:8899",
            "http://[fe80::ffff:7f00:1]:8899",
            "http://[2001:db8::127.0.0.1]:8899",
            // The boundaries of the 127/8 block, in the notation that hides them.
            "http://2130706431:8899", // 126.255.255.255
            "http://167772161:8899", // 10.0.0.1
            "http://0x0a000001:8899",
            // Not addresses at all, and not over-matched by the numeric parser.
            "http://0xdeadbeef.example.com:8899",
            "http://4294967296:8899",
            "http://0900.0.0.1:8899", // 9 is not an octal digit
            "http://1.2.3.4.5:8899",
        )
        val overMatched = real.filter { isLoopbackUrl(it) }
        assertEquals(emptyList(), overMatched, "these are real addresses and must win")
    }

    /**
     * Malformed input must neither throw nor claim to be this machine. A scan
     * or a hub can produce anything, and this function is called on both.
     */
    @Test
    fun malformed_input_is_refused_rather_than_thrown() {
        val junk = listOf(
            "http://[:::1]:8899",
            "http://[::ffff:]:8899",
            "http://[::ffff:999.0.0.1]:8899",
            "http://[::ffff:127.0.0.1.evil.com]:8899",
            "http://...:8899",
            "http://:8899",
            "",
        )
        for (url in junk) {
            // The assertion is that this returns at all, and returns "not this
            // machine" rather than guessing.
            assertTrue(!isLoopbackUrl(url), "$url should not be taken for this machine")
        }
    }

    /**
     * Deliberate and documented: a *hostname* that begins `127.` is matched,
     * which is wrong and harmless. It fails safe — the echo loses and the
     * address that demonstrably worked is kept — and the alternative is a
     * DNS lookup the app cannot do.
     */
    @Test
    fun a_hostname_that_merely_looks_like_loopback_fails_safe() {
        assertTrue(isLoopbackUrl("http://127.0.0.1.evil.com:8899"))
    }
}

/**
 * N6 — `hubBase` accepted several things the hub's own `HubBase::public`
 * (`crates/fleet-core/src/service/hub.rs`) refuses. None was a security hole;
 * each was a silent dead end, where a person pastes something plausible and the
 * app simply never connects rather than saying what is wrong.
 */
class HubBaseTest {

    @Test
    fun a_normal_address_is_kept_verbatim_without_its_trailing_slash() {
        assertEquals("https://fleet.example.com", hubBase("https://fleet.example.com/"))
        assertEquals("https://fleet.example.com:8899", hubBase("https://fleet.example.com:8899"))
        // A path prefix is a deliberate feature: the hub can sit behind one.
        assertEquals("https://example.com/fleet", hubBase("https://example.com/fleet"))
    }

    @Test
    fun the_scheme_is_normalised_rather_than_echoed_back_in_whatever_case_it_arrived() {
        assertEquals("https://fleet.example.com", hubBase("HTTPS://fleet.example.com"))
        // A LAN address for the http case: the cleartext policy refuses plain
        // http to a public name, and this test is about the SCHEME's case.
        assertEquals("http://192.168.1.5:8899", hubBase("Http://192.168.1.5:8899"))
    }

    /**
     * The app's own transport policy: plain `http` only to this machine or this
     * network. Previously the app imposed none and relied on two platforms'
     * defaults — Android's cleartext block and iOS's ATS — which it does not
     * control and never stated.
     *
     * This is the one rule in this file that fails CLOSED. A hub over plain http
     * at a public-looking name is refused with a reason, which is recoverable;
     * a bearer token sent over the open internet in the clear is not.
     */
    @Test
    fun plain_http_is_refused_to_a_public_host() {
        assertNull(hubBase("http://fleet.example.com"))
        assertNull(hubBase("http://fleet.example.com:8899"))
        assertNull(hubBase("http://8.8.8.8"))
        assertNull(hubBase("http://[2001:db8::1]:8899"))
        // 126.255.255.255, one below the loopback block — public, so http is out.
        assertNull(hubBase("http://2130706431:8899"))
        // https to the same hosts is fine.
        assertEquals("https://fleet.example.com", hubBase("https://fleet.example.com"))
        assertEquals("https://8.8.8.8", hubBase("https://8.8.8.8"))
    }

    @Test
    fun plain_http_is_permitted_to_this_machine_and_this_network() {
        for (url in listOf(
            "http://127.0.0.1:8899",
            "http://localhost:8899",
            "http://[::1]:8899",
            "http://10.0.0.7:8899",
            "http://192.168.1.5:8899",
            "http://172.16.0.1:8899",
            "http://172.31.255.254:8899",
            "http://169.254.1.1:8899",   // link-local
            "http://100.64.0.1:8899",    // carrier-grade NAT
            "http://[fd00::1]:8899",     // IPv6 unique-local
            "http://[fe80::1]:8899",     // IPv6 link-local
            "http://fleethub:8899",      // a single-label LAN name
            "http://fleethub.local:8899",
            // The numeric spellings of 10.0.0.1 the address parser already knows.
            "http://167772161:8899",
            "http://0x0a000001:8899",
        )) {
            assertEquals(url, hubBase(url), "http should be permitted to $url")
        }
    }

    /** 172.16/12 has edges, and neither of them is 172.anything. */
    @Test
    fun the_private_ranges_have_the_right_edges() {
        assertNull(hubBase("http://172.15.0.1:8899"))
        assertNull(hubBase("http://172.32.0.1:8899"))
        assertNull(hubBase("http://100.63.0.1:8899"))
        assertNull(hubBase("http://100.128.0.1:8899"))
        assertNull(hubBase("http://11.0.0.1:8899"))
        assertNull(hubBase("http://192.169.1.5:8899"))
        // And an IPv6 neighbour of the unique-local block.
        assertNull(hubBase("http://[fb00::1]:8899"))
    }

    @Test
    fun a_query_or_a_fragment_is_refused_rather_than_silently_dialled() {
        // The hub refuses both. Keeping them would build every later request
        // URL by concatenation onto a base that already has a `?` in it.
        assertNull(hubBase("https://hub.example.com?x=1"))
        assertNull(hubBase("https://hub.example.com/fleet?x=1"))
        assertNull(hubBase("https://hub.example.com#frag"))
    }

    @Test
    fun a_port_that_is_not_a_port_is_refused() {
        assertNull(hubBase("https://hub.example.com:99999"))
        assertNull(hubBase("https://hub.example.com:0"))
        assertNull(hubBase("https://hub.example.com:http"))
        assertEquals("https://hub.example.com:65535", hubBase("https://hub.example.com:65535"))
    }

    @Test
    fun an_unclosed_bracket_is_refused() {
        assertNull(hubBase("https://[::1"))
        assertEquals("https://[::1]:8899", hubBase("https://[::1]:8899"))
    }

    /**
     * An IPv6 literal that has lost its brackets is not an authority.
     *
     * Called out by the Task 6 review as load-bearing and unprotected — the
     * comment in `hasUsablePort` was there, but nothing pinned the branch, so
     * deleting `if (colons > 1) return false` changed no test. It is not a
     * tidiness rule: without it `substringAfterLast(':')` on `::1:8899` reads
     * `8899`, calls it a valid port, and `hubBase` returns the address. It then
     * reaches `isLoopbackUrl`, whose normalisation folds a *bracketed* IPv4 tail
     * and does not recognise this shape — so a loopback echo would be accepted
     * and stored, which is the fail-open case this file has now been fixed for
     * four times. The bracketed spelling of the same address is still accepted.
     */
    @Test
    fun a_bracketless_ipv6_literal_is_refused() {
        assertNull(hubBase("http://::1:8899"))
        assertNull(hubBase("http://::1"))
        assertNull(hubBase("https://fe80::1"))
        assertNull(hubBase("https://2001:db8::7f00:1:8899"))
        // One colon is a port, and stays one.
        assertEquals("https://hub.example.com:8899", hubBase("https://hub.example.com:8899"))
        // And the same addresses, spelled correctly, are still addresses.
        assertEquals("http://[::1]:8899", hubBase("http://[::1]:8899"))
        assertEquals("https://[fe80::1]", hubBase("https://[fe80::1]"))
    }

    @Test
    fun a_control_character_anywhere_is_refused() {
        assertNull(hubBase("https://evil.com\u0000.good.com"))
        // CR/LF in an address is a request-smuggling primitive, not a typo.
        assertNull(hubBase("https://evil.com\r\nHost: x"))
        assertNull(hubBase("https://evil.com\u0007.good.com"))
    }

    /** Unchanged from before, and re-pinned so the rewrite cannot lose them. */
    @Test
    fun the_rules_that_were_already_there_still_hold() {
        assertNull(hubBase("https://someone:secret@evil.example.com"))
        assertNull(hubBase("https://user@evil.example.com"))
        assertNull(hubBase("ftp://hub.example.com"))
        assertNull(hubBase("javascript://x"))
        assertNull(hubBase("hub.example.com"))
        assertNull(hubBase("https://"))
        assertNull(hubBase("   "))
        assertNull(hubBase("https:///pair"))
        assertNull(hubBase("https://a b"))
    }
}


/**
 * A zone-scoped IPv6 literal, and the careless fix for it.
 *
 * `HubAddress.kt` carried this as a written-down "known gap": `[::1%25eth0]` —
 * the RFC 6874 percent-encoded form of an RFC 4007 zone id — was not recognised
 * as this machine, and failed **open**, so a hub echoing one would have had it
 * stored and dialled forever with re-pairing unable to recover. The comment
 * also said where the fix belonged: "the next change here should be the
 * normalisation, not another branch."
 *
 * The second half of this class is the reason it was worth writing carefully.
 * Cutting at the first `%` unconditionally closes the gap and opens a worse
 * one, because `%` outside an IPv6 literal is the start of a percent-encoding
 * rather than a zone id.
 */
class ZoneIdTest {

    /** Both spellings of a scoped loopback name this machine. */
    @Test
    fun a_zone_scoped_loopback_is_this_machine() {
        for (url in listOf(
            "http://[::1%eth0]:8899",     // RFC 4007, as a person types it
            "http://[::1%25eth0]:8899",   // RFC 6874, percent-encoded for a URL
            "http://[::1%25lo0]:8899",
            "http://[::%25eth0]:8899",    // the unspecified address, scoped
        )) {
            assertTrue(isLoopbackUrl(url), "$url names this machine and must lose to the address reached")
        }
        // NOT here, and the first draft of this test had it wrong: `fe80::1` is
        // link-local, which is this *network* and not this *machine*. A hub
        // genuinely reachable at a link-local address is a hub whose echoed
        // base should win, so `isThisMachine` is right to refuse it. Where a
        // scoped link-local address does matter is the transport rule, below.
        assertFalse(isLoopbackUrl("http://[fe80::1%25en0]:8899"), "link-local is not loopback")
    }

    /**
     * And a zone does not turn a real address into this machine.
     *
     * Over-matching is the safe direction here, but not infinitely so: a rule
     * that swallowed everything after a `%` would throw away the hub's own
     * public URL, which is the one thing it knows and the phone does not.
     */
    @Test
    fun a_zone_on_a_real_address_is_still_a_real_address() {
        assertFalse(isLoopbackUrl("https://[2001:db8::1%25eth0]:8899"))
        assertFalse(isLoopbackUrl("https://hub.example.com:8899"))
    }

    /**
     * **The careless version of this fix opens cleartext to a public name.**
     *
     * A zone id is defined for IPv6 and nothing else. In any other host a `%`
     * begins a percent-encoding, so cutting at the first one would read
     * `ev%il.com` as the single label `ev` — and a single label is exactly what
     * [permitsCleartext] permits plain `http` to, on the grounds that it cannot
     * be a public name. The colon test is what keeps the strip to the addresses
     * a zone id can legally appear on.
     */
    @Test
    fun a_percent_in_a_name_is_not_a_zone_id() {
        assertNull(hubBase("http://ev%il.com:8899"), "still a dotted public name, so still refused http")
        assertFalse(permitsCleartext("http://ev%il.com:8899"))
        assertFalse(permitsCleartext("http://%2e%2eevil.com:8899"))
        // …and it is not this machine either.
        assertFalse(isLoopbackUrl("http://127%2e0%2e0%2e1.evil.com:8899"))
    }

    /** A scoped literal is still a usable base, not something the parser refuses. */
    @Test
    fun a_scoped_literal_survives_hub_base() {
        assertEquals("http://[fe80::1%25en0]:8899", hubBase("http://[fe80::1%25en0]:8899"))
        assertTrue(permitsCleartext("http://[fe80::1%25en0]:8899"), "link-local is this network")
    }

    // ---- the five branches a sweep of `permitsCleartext` found unguarded ----

    /**
     * `.local` has to be a **suffix**, and the difference fails open.
     *
     * `endsWith` refuses `evil.local.attacker.com`; `contains` permits it, and
     * `attacker.com` is a perfectly ordinary public name that anyone can put a
     * `.local.` label in front of. That is the whole exploit: one relaxation
     * from suffix to substring and the bearer token goes to a public host over
     * plain http.
     */
    @Test
    fun a_local_label_in_the_middle_is_not_a_local_name() {
        assertFalse(permitsCleartext("http://evil.local.attacker.com:8899"))
        assertFalse(permitsCleartext("http://.local.evil.com:8899"))
        assertTrue(permitsCleartext("http://fleethub.local:8899"), "a real mDNS name still passes")
    }

    /**
     * The scheme is compared lower-cased, and that also fails open.
     *
     * `HTTP://evil.com` is the same URL to every client that will fetch it. If
     * the comparison were case-sensitive, `scheme != "http"` would be true for
     * this spelling and the function would take its *`https` is always fine*
     * branch — permitting cleartext to anywhere, for the price of a shift key.
     *
     * `hubBase` happens to lower-case the scheme before calling, so today this
     * is reached only directly. `permitsCleartext` is `internal` and is the
     * app's transport policy; it does not get to assume its one caller.
     */
    @Test
    fun the_scheme_is_matched_without_regard_to_case() {
        assertFalse(permitsCleartext("HTTP://evil.com:8899"))
        assertFalse(permitsCleartext("Http://evil.com:8899"))
        assertTrue(permitsCleartext("HTTPS://evil.com:8899"), "https is fine in any spelling")
    }

    /** No host is not a host, and an `http` URL without one is refused. */
    @Test
    fun a_url_with_no_host_is_refused_cleartext() {
        assertFalse(permitsCleartext("http://:8899"))
        assertFalse(permitsCleartext("http://"))
    }

    /**
     * `fe80::/10` is a /10, not a /16.
     *
     * The mask is `0xFFC0`, so the range is `fe80::`–`febf::` — every address a
     * phone's own interface can hold. Tightening it to an exact `fe80` match
     * would refuse the rest of the range, which fails closed rather than open
     * but still breaks a hub reached over link-local on an interface that
     * numbered itself higher.
     */
    @Test
    fun the_whole_link_local_range_is_this_network() {
        for (address in listOf("fe80::1", "fe81::1", "febf::1")) {
            assertTrue(permitsCleartext("http://[$address]:8899"), "$address is within fe80::/10")
        }
        assertFalse(permitsCleartext("http://[fec0::1]:8899"), "fec0:: is outside it")
    }

    /**
     * An IPv4-mapped address is read as the IPv4 address it carries.
     *
     * `::ffff:192.168.1.1` is how a dual-stack client writes a v4 LAN hub, and
     * it has to reach the same verdict as `192.168.1.1` — otherwise the same
     * machine is permitted or refused depending on which stack resolved it.
     */
    @Test
    fun an_ipv4_mapped_address_is_judged_as_its_ipv4() {
        assertTrue(permitsCleartext("http://[::ffff:192.168.1.1]:8899"), "a private v4 address")
        assertFalse(permitsCleartext("http://[::ffff:8.8.8.8]:8899"), "a public one is still public")
    }
}

/**
 * The address rules at the edges mutation found unguarded.
 *
 * Every case here is one where the code was right and no test said so. Two of
 * them decide whether a bearer token may cross a plain `http` connection, which
 * is the one decision in this file worth being pedantic about.
 */
class AddressEdgesTest {

    /**
     * Link-local is `169.254/16` — **both** octets, not either.
     *
     * `a == 169L && b == 254L` survived as `||`, which would have read every
     * `169.x.x.x` and every `x.254.x.x` as this network and let plain http
     * reach them. `169.1.1.1` is ordinary public space; `8.254.0.1` is Level 3.
     * The existing range test covers the `172.16/12` and `100.64/10` edges and
     * stops short of this one.
     */
    @Test
    fun link_local_needs_both_octets() {
        assertEquals("http://169.254.1.1:8899", hubBase("http://169.254.1.1:8899"), "169.254/16 is link-local")
        assertNull(hubBase("http://169.1.1.1:8899"), "169.anything is not")
        assertNull(hubBase("http://8.254.0.1:8899"), "anything.254 is not either")
        assertNull(hubBase("http://169.253.1.1:8899"))
        assertNull(hubBase("http://169.255.1.1:8899"))
    }

    /**
     * A bracketed IPv6 literal still has to carry a *port*, not merely a colon.
     *
     * `after.startsWith(":") && after.drop(1).isPort()` survived as `||`, which
     * accepts `[::1]:notaport` — the two halves only disagree when there is a
     * colon followed by something that is not a number. The existing
     * port test uses an unbracketed host, so the bracket branch was never
     * exercised with a bad port.
     */
    @Test
    fun a_bracketed_address_with_a_bad_port_is_refused() {
        assertNull(hubBase("http://[::1]:notaport"))
        assertNull(hubBase("http://[::1]:99999"))
        assertNull(hubBase("http://[::1]:"))
        assertEquals("http://[::1]:8899", hubBase("http://[::1]:8899"), "and a good one is kept")
        assertEquals("http://[::1]", hubBase("http://[::1]"), "as is no port at all")
    }

    /**
     * The last part of an `inet_aton` address has to fit the bits it covers.
     *
     * `value >= (1L shl tailBits)` survived as `>`, admitting exactly one value
     * too many — `256` where the tail covers eight bits.
     *
     * The first draft of this test asserted it through [isLoopbackUrl] and
     * failed, for a reason worth keeping: `127.0.0.256` is caught by the
     * deliberate `startsWith("127.")` over-match long before the numeric
     * parser, and that over-match fails *safe*. Where the ceiling actually
     * decides something is [permitsCleartext], where the same parser answers a
     * question that fails **open**: accept the oversized part and
     * `10.0.0.256` parses as `0x0A000100`, whose first octet is 10 — so a
     * bearer token would be allowed over plain http to a name that is not an
     * address at all.
     */
    @Test
    fun an_oversized_part_is_not_a_private_address() {
        assertEquals(
            "http://10.0.0.255:8899",
            hubBase("http://10.0.0.255:8899"),
            "255 fits the last octet, so this is 10/8 and cleartext is fine",
        )
        assertNull(hubBase("http://10.0.0.256:8899"), "256 does not fit, so this is not an address")
        assertFalse(permitsCleartext("http://10.0.0.256:8899"))
        assertFalse(permitsCleartext("http://192.168.1.256:8899"))
        // Deliberately NOT asserted here: `http://4294967296` IS permitted, and
        // correctly so — it has no dots, so it is a single label, and the rule
        // is that a single label cannot be a public name. The ceiling decides
        // dotted forms; the label rule decides that one.
    }

    /**
     * A hub mounted under a path prefix is judged on its host, not its path.
     *
     * `substringBefore('/')` survived as `substringAfter('/')` because every
     * test address stops at the port. The two agree exactly when there is no
     * path — which was every case — and disagree the moment a hub is mounted
     * behind a reverse proxy at `/fleet`, which the design explicitly supports.
     */
    @Test
    fun a_path_prefix_does_not_change_which_host_is_judged() {
        assertTrue(isLoopbackUrl("http://127.0.0.1:8899/fleet"), "the host is still loopback")
        assertTrue(permitsCleartext("http://192.168.1.5:8899/fleet"), "and still this network")
        assertFalse(isLoopbackUrl("https://hub.example.com/127.0.0.1"), "a path is not a host")
        assertFalse(permitsCleartext("http://hub.example.com/fleethub"), "nor is it a single label")
    }

    /** An IPv6 literal with an empty group is not an address. */
    @Test
    fun an_ipv6_literal_with_an_empty_group_is_refused() {
        assertFalse(isLoopbackUrl("http://[1:::1]:8899"))
        assertFalse(isLoopbackUrl("http://[:1:2:3:4:5:6:7]:8899"))
    }
}
