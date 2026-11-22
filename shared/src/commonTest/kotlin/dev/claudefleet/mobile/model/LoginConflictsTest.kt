package dev.claudefleet.mobile.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Review w12-4: the hub's login conflicts (`fleet_health.hosts[].login_conflicts`), read and said. */
class LoginConflictsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun the_hubs_tagged_conflicts_decode_from_fleet_health() {
        val health = json.decodeFromString(
            HubHealth.serializer(),
            """{"db_ready":true,"hosts":[
                {"alias":"mac","reachable":true,"login_conflicts":[
                  {"kind":"keychain_and_file"},
                  {"kind":"shared_account","logins":[null,"work"],"email":"me@x.io"},
                  {"kind":"copied","profile":"work","copies":[{"host":"htz","profile":null}]}
                ]},
                {"alias":"htz","reachable":true}
            ]}""",
        )
        val mac = health.hosts.single { it.alias == "mac" }.loginConflicts
        assertEquals(listOf("keychain_and_file", "shared_account", "copied"), mac.map { it.kind })
        assertEquals(listOf(null, "work"), mac[1].logins)
        assertEquals(listOf(LoginAt("htz", null)), mac[2].copies)
        assertTrue(health.hosts.single { it.alias == "htz" }.loginConflicts.isEmpty(), "absent is none")
        assertTrue(json.decodeFromString(HubHealth.serializer(), """{"db_ready":true}""").hosts.isEmpty(), "an older hub sends none")
    }

    @Test
    fun each_conflict_reads_as_the_hubs_sentence() {
        val copied = describeLoginConflict(LoginConflict(kind = "copied", profile = "work", copies = listOf(LoginAt("htz", null), LoginAt("trn", "work"))), "mac")!!
        assertTrue(copied.startsWith("mac: profile work is a copy of one Claude login that is also the host login on htz and profile work on trn: "))
        assertTrue("stop copying .credentials.json" in copied)
        val shared = describeLoginConflict(LoginConflict(kind = "shared_account", logins = listOf(null, "work")), "mac")!!
        assertTrue(shared.startsWith("mac: the host login and profile work are both signed into one account."))
        assertTrue("macOS Keychain" in describeLoginConflict(LoginConflict(kind = "keychain_and_file"), "mac")!!)
        assertNull(describeLoginConflict(LoginConflict(kind = "something_new"), "mac"))
    }
}
