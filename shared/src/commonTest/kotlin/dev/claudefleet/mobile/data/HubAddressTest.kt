package dev.claudefleet.mobile.data

import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals("http://fleet.example.com", hubBase("Http://fleet.example.com"))
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
