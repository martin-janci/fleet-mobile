package dev.claudefleet.mobile.update

import dev.claudefleet.mobile.net.CLIENT_HEADER
import dev.claudefleet.mobile.net.ClientPlatform
import dev.claudefleet.mobile.net.FleetClient
import dev.claudefleet.mobile.net.HubClient
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.MAX_HUB_CONTRACT
import dev.claudefleet.mobile.net.MIN_HUB_CONTRACT
import dev.claudefleet.mobile.net.clientHeaderValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The phone's update as its hub decides it (claude-fleet update design S8). */
class HubReleasesTest {
    private val android = ClientPlatform("android", "aarch64", "1a2b3c4")
    private val sha = "ab".repeat(32)
    private val signer = "cd".repeat(32)

    private fun decision(status: String, artifact: String? = apk(), reason: String = "", mandatory: Boolean = false, deadline: String? = null) = """
        {"update_proto":1,"component":"android","status":"$status","source":"hub","track":"stable",
         "mode":"notify","installed":"0.9.4",
         ${if (artifact == null) "" else """"target":{"version":"0.9.5","mandatory":$mandatory,${deadline?.let { "\"deadline\":\"$it\"," } ?: ""}
           "manifest":{"url":"https://x/m.json","sha256":"$sha"},"channel":{"sequence":3},
           "artifact":$artifact,"url":"https://github.com/martin-janci/fleet-mobile/releases/download/v0.9.5/fleet-mobile-0.9.5.apk"},"""}
         "reason":{"code":"newer_recommended","text":"$reason"},"next_check_secs":21600,"later_field":1}
    """.trimIndent()

    private fun apk(sha256: String = sha, signerSha: String = signer) =
        """{"kind":"apk","url":"https://github.com/martin-janci/fleet-mobile/releases/download/v0.9.5/fleet-mobile-0.9.5.apk","sha256":"$sha256","size":1234,"version_code":77,"signer_sha256":"$signerSha"}"""

    @Test
    fun the_header_names_the_build_and_the_contract_window() {
        assertEquals(
            "android/0.9.5 (android-aarch64; build 1a2b3c4; contract $MIN_HUB_CONTRACT-$MAX_HUB_CONTRACT)",
            clientHeaderValue(android, "0.9.5"),
        )
        assertEquals(
            "ios/0.9.5 (ios-aarch64; contract $MIN_HUB_CONTRACT-$MAX_HUB_CONTRACT)",
            clientHeaderValue(ClientPlatform("ios", "aarch64", "unknown"), "v0.9.5"),
        )
        // A local build the hub could not parse sends nothing.
        assertNull(clientHeaderValue(android, "dev"))
        assertNull(clientHeaderValue(ClientPlatform("android", "arm (64)"), "0.9.5"))
    }

    @Test
    fun the_check_says_what_runs_and_what_it_speaks() {
        val body = Json.parseToJsonElement(assertNotNull(checkRequest(android, "v0.9.4"))).jsonObject
        assertEquals("1", body["update_proto"]!!.jsonPrimitive.content)
        assertEquals("android", body["component"]!!.jsonPrimitive.content)
        val platform = body["platform"]!!.jsonObject
        assertEquals("android", platform["os"]!!.jsonPrimitive.content)
        assertEquals("apk", platform["variant"]!!.jsonPrimitive.content)
        assertEquals("0.9.4", body["installed"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        assertEquals("1a2b3c4", body["installed"]!!.jsonObject["commit"]!!.jsonPrimitive.content)
        val window = body["speaks"]!!.jsonObject["contract_accepts"]!!.jsonObject
        assertEquals(MAX_HUB_CONTRACT.toString(), window["max"]!!.jsonPrimitive.content)
        assertNull(checkRequest(android, "dev"))
    }

    @Test
    fun an_offered_apk_becomes_the_card_with_its_signer() {
        val r = assertNotNull(releaseFromDecision(decision("update_available")))
        assertEquals("0.9.5", r.version)
        assertEquals(sha, r.sha256)
        assertEquals(signer, r.signerSha256)
        assertEquals(1234L, r.sizeBytes)
        assertEquals(false, r.required)
        assertTrue(r.pageUrl.endsWith("/releases/tag/v0.9.5"))
    }

    @Test
    fun a_required_update_says_why() {
        val r = assertNotNull(releaseFromDecision(decision("update_required", reason = "0.9.4 was withdrawn.")))
        assertTrue(r.required)
        assertEquals("0.9.4 was withdrawn.", r.reason)
        val m = assertNotNull(releaseFromDecision(decision("update_available", mandatory = true, deadline = "2026-11-01T00:00:00Z")))
        assertEquals("Required by 2026-11-01.", m.reason)
        assertEquals("Update required: Orbit Fleet 0.9.5", dev.claudefleet.mobile.ui.updateTitle(r))
    }

    @Test
    fun nothing_the_phone_installs_is_offered() {
        for (status in listOf("up_to_date", "hold", "rollback", "client_too_new", "unknown", "quarantined")) {
            assertNull(releaseFromDecision(decision(status)), status)
        }
        assertNull(releaseFromDecision(decision("update_available", artifact = null)))
        assertNull(releaseFromDecision(decision("update_available", artifact = """{"kind":"notify"}""")))
        assertNull(releaseFromDecision(decision("update_available", artifact = apk(sha256 = "nope"))))
        assertNull(releaseFromDecision(decision("update_available", artifact = apk(signerSha = ""))))
    }

    private val gitHub = ReleaseSource { ReleaseInfo("0.9.9", "https://gh/apk", 1, null, emptyList(), "") }

    @Test
    fun the_hub_decides_and_github_is_asked_only_when_the_hub_cannot() = runTest {
        val hub = HubReleases(android, "0.9.4", { decision("update_available") }, gitHub)
        assertEquals("0.9.5", hub.latest()?.version)
        // A hub too old to have /update/check.
        assertEquals("0.9.9", HubReleases(android, "0.9.4", { null }, gitHub).latest()?.version)
        // A build that cannot describe itself.
        assertEquals("0.9.9", HubReleases(null, "0.9.4", { error("not asked") }, gitHub).latest()?.version)
        // The hub said nothing to install: no card, and no going around it.
        assertNull(HubReleases(android, "0.9.4", { decision("up_to_date") }, gitHub).latest())
        assertNull(HubReleases(android, "0.9.4", { throw HubError.Unauthorized() }, gitHub).latest())
    }

    private fun client(handler: suspend (HttpRequestData) -> Pair<HttpStatusCode, String>): Pair<HubClient, MutableList<HttpRequestData>> {
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { req ->
            seen += req
            val (status, body) = handler(req)
            if (status.value in 200..299) respond(body, status, headersOf("Content-Type", "application/json"))
            else respondError(status, body)
        }
        return HubClient(HttpClient(engine), "https://hub.example", "tok") to seen
    }

    @Test
    fun the_check_posts_to_the_update_route_with_the_token() = runTest {
        val (hub, seen) = client { HttpStatusCode.OK to decision("update_available") }
        val body = checkRequest(android, "0.9.4")!!
        assertNotNull(hub.updateCheck(body))
        val req = seen.single()
        assertEquals("/update/check", req.url.encodedPath)
        assertEquals("Bearer tok", req.headers["Authorization"])
        assertEquals(body, (req.body as TextContent).text)
    }

    @Test
    fun an_old_hub_is_null_and_a_refusal_throws() = runTest {
        assertNull(client { HttpStatusCode.NotFound to "" }.first.updateCheck("{}"))
        assertFailsWith<HubError.Unauthorized> { client { HttpStatusCode.Unauthorized to "" }.first.updateCheck("{}") }
    }

    @Test
    fun the_header_goes_to_the_hub_and_never_to_github() = runTest {
        FleetClient.header = "android/0.9.5 (android-aarch64)"
        try {
            val (hub, seen) = client { HttpStatusCode.OK to decision("up_to_date") }
            hub.updateCheck("{}")
            assertEquals("android/0.9.5 (android-aarch64)", seen.single().headers[CLIENT_HEADER])
            val gh = mutableListOf<HttpRequestData>()
            GitHubReleases(HttpClient(MockEngine { req -> gh += req; respondError(HttpStatusCode.NotFound) })).latest()
            assertNull(gh.single().headers[CLIENT_HEADER])
        } finally {
            FleetClient.header = null
        }
    }
}
