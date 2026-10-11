package dev.claudefleet.mobile.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A version as the app and the hub print it: `0.9.5`, `v0.9.5`, `0.9.5-rc.1`,
 * `0.6.3-dev.131.desktop.g65f0edd`.
 *
 * Ordered as SemVer 2.0 §11 orders pre-releases. A suffix still sorts below
 * the same numbers without one, as a release candidate comes before its
 * release. Two suffixes on the same numbers compare identifier by identifier,
 * split on dots: numbers numerically and below words, words in ASCII order,
 * and the shorter list first when all before it are equal. It used to stop
 * at "has a suffix", which made every dev build of one X.Y.Z equal: a phone
 * on 0.6.3-dev.131 never saw 0.6.3-dev.144 as newer, and claude-fleet cuts a
 * dev build after every green main push. `+build` metadata is not parsed at
 * all ([parse] refuses it), so it can never take part in the order.
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int, val suffix: String = "") : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        val numbers = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
        if (numbers != 0) return numbers
        return when {
            suffix == other.suffix -> 0
            suffix.isEmpty() -> 1
            other.suffix.isEmpty() -> -1
            else -> comparePreRelease(suffix.split('.'), other.suffix.split('.'))
        }
    }

    override fun toString(): String = "$major.$minor.$patch" + if (suffix.isEmpty()) "" else "-$suffix"

    companion object {
        private val SHAPE = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$""")

        /** Null for anything that is not a version, so "dev" or a blank label never reads as older. */
        fun parse(text: String): AppVersion? {
            val m = SHAPE.matchEntire(text.trim()) ?: return null
            val (a, b, c, s) = m.destructured
            return AppVersion(a.toInt(), b.toInt(), c.toInt(), s)
        }
    }
}

/** SemVer 2.0 §11.4 over two pre-release identifier lists. */
private fun comparePreRelease(a: List<String>, b: List<String>): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val x = a[i]
        val y = b[i]
        // All digits is numeric. Compared as text by length first, then
        // lexically, so an identifier too long for a Long still orders.
        val xn = x.isNotEmpty() && x.all { it in '0'..'9' }
        val yn = y.isNotEmpty() && y.all { it in '0'..'9' }
        val c = when {
            xn && yn -> {
                val xs = x.trimStart('0')
                val ys = y.trimStart('0')
                if (xs.length != ys.length) xs.length.compareTo(ys.length) else xs.compareTo(ys)
            }
            xn -> -1
            yn -> 1
            else -> x.compareTo(y)
        }
        if (c != 0) return c
    }
    return a.size.compareTo(b.size)
}

/** One published release of the phone app: the signed APK and what changed. */
data class ReleaseInfo(
    val version: String,
    val apkUrl: String,
    val sizeBytes: Long,
    /** GitHub's own SHA-256 of the asset, hex, when the release lists one. */
    val sha256: String?,
    /** What changed, in plain words, most notable first. */
    val notes: List<String>,
    val pageUrl: String,
    /**
     * sha256 of the certificate the APK must be signed with, from the signed
     * release manifest (the hub's decision); null from GitHub, where only the
     * installed app's own certificate is compared.
     */
    val signerSha256: String? = null,
    /** The hub requires this update: this build is withdrawn, below a minimum, or past a mandatory deadline. */
    val required: Boolean = false,
    /** The hub's sentence for why, when it gave one. */
    val reason: String? = null,
    /**
     * The same APK on the paired hub's mirror (`/update/artifact/<sha256>`),
     * absolute; tried before [apkUrl]. The token for it comes from the
     * session at download time, never from here.
     */
    val mirrorUrl: String? = null,
)

/** Where the phone learns that a newer release exists. */
fun interface ReleaseSource {
    /** The newest release, or null when there is none or it could not be read. Never throws. */
    suspend fun latest(): ReleaseInfo?

    /**
     * The same, with [refresh] asking the source to read its release channel
     * again first: a person's "Check now". Only the hub can ([HubReleases]);
     * every other source has nothing to re-read and answers as [latest].
     * A member with a body, not a default argument: a `fun interface`'s one
     * abstract function may not carry one.
     */
    suspend fun latest(refresh: Boolean): ReleaseInfo? = latest()
}

/**
 * fleet-mobile's GitHub releases: the release workflow publishes one signed
 * `androidApp-release.apk` per `vX.Y.Z` tag, and `/releases/latest` skips
 * drafts and prereleases. The repository is public, so no token is sent.
 */
class GitHubReleases(
    private val http: HttpClient,
    private val repo: String = "martin-janci/fleet-mobile",
) : ReleaseSource {
    override suspend fun latest(): ReleaseInfo? = try {
        val response = http.get("https://api.github.com/repos/$repo/releases/latest") {
            header("Accept", "application/vnd.github+json")
        }
        if (response.status.isSuccess()) parseRelease(response.bodyAsText()) else null
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        // No network, GitHub rate-limited, a body that will not decode: the
        // update card simply does not appear. Nothing here is worth a banner.
        null
    }
}

/** The release asset the phone installs. */
internal const val APK_ASSET = "androidApp-release.apk"

private val releaseJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

@Serializable
private data class GhRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String = "",
    val body: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
private data class GhAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0,
    val digest: String? = null,
)

/** A GitHub release body as a [ReleaseInfo]; null without a version tag or an APK. */
internal fun parseRelease(body: String): ReleaseInfo? {
    val release = releaseJson.decodeFromString(GhRelease.serializer(), body)
    val version = AppVersion.parse(release.tagName) ?: return null
    val apk = release.assets.firstOrNull { it.name == APK_ASSET } ?: return null
    return ReleaseInfo(
        version = version.toString(),
        apkUrl = apk.url,
        sizeBytes = apk.size,
        sha256 = apk.digest?.removePrefix("sha256:")?.lowercase()?.takeIf { it.length == 64 },
        notes = releaseNotes(release.body.orEmpty()),
        pageUrl = release.htmlUrl,
    )
}

private val CONVENTIONAL = Regex("""^[a-z]+(\([^)]*\))?!?:\s*""")
private val CREDIT = Regex("""\s+by @\S+ in \S+$""")

/**
 * The bullet lines of a generated release body, as a person reads them:
 * "* feat(missions): a Missions sheet by @x in https://…" becomes
 * "A Missions sheet". Chores and contract bumps are left out; they changed
 * nothing anyone sees.
 */
internal fun releaseNotes(body: String): List<String> = body.lines()
    .map { it.trim() }
    .filter { it.startsWith("* ") || it.startsWith("- ") }
    .map { it.drop(2).replace(CREDIT, "").trim() }
    .filterNot { it.startsWith("chore") || it.startsWith("ci") || it.startsWith("build") || it.startsWith("test") }
    .map { it.replace(CONVENTIONAL, "").replaceFirstChar { c -> c.uppercaseChar() } }
    .filter { it.isNotBlank() }
