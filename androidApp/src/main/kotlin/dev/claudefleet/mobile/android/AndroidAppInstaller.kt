package dev.claudefleet.mobile.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.claudefleet.mobile.update.AppInstaller
import dev.claudefleet.mobile.update.Downloaded
import dev.claudefleet.mobile.update.ReleaseInfo
import dev.claudefleet.mobile.update.SignatureCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * The phone's own update on Android (redesign 14.18, MobileUpdate).
 *
 * Downloads the release APK into the cache, resuming a paused download with
 * a `Range` request; checks that it is this app, at the release's version,
 * signed with a certificate the installed app is signed with; and hands it
 * to Android's `PackageInstaller`, which asks the person to confirm. A debug
 * build has its own application id, so a release never passes the check on
 * one: the refusal says so.
 */
class AndroidAppInstaller(private val context: Context) : AppInstaller {

    private val dir: File get() = File(context.cacheDir, "updates")

    private fun file(release: ReleaseInfo) = File(dir, "orbit-fleet-${release.version}.apk")

    override suspend fun download(release: ReleaseInfo, onProgress: (Long, Long) -> Unit): Downloaded =
        withContext(Dispatchers.IO) {
            val target = file(release)
            dir.mkdirs()
            // One update at a time: whatever an older offer left goes.
            dir.listFiles()?.filter { it != target }?.forEach { it.delete() }
            var have = if (target.exists()) target.length() else 0L
            if (release.sizeBytes in 1 until have) {
                target.delete()
                have = 0L
            }
            if (release.sizeBytes <= 0 || have < release.sizeBytes) {
                val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                }
                try {
                    when (val code = conn.responseCode) {
                        HttpURLConnection.HTTP_OK -> have = 0L // the server ignored the range: start over
                        HttpURLConnection.HTTP_PARTIAL -> Unit
                        else -> throw IOException("GitHub answered $code for the download.")
                    }
                    val total = if (release.sizeBytes > 0) release.sizeBytes else have + conn.contentLengthLong
                    FileOutputStream(target, have > 0).use { out ->
                        conn.inputStream.use { input ->
                            val buf = ByteArray(64 * 1024)
                            var reported = have
                            onProgress(have, total)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                have += n
                                if (have - reported >= 256 * 1024) {
                                    reported = have
                                    onProgress(have, total)
                                }
                            }
                            onProgress(have, total)
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }
            if (release.sizeBytes > 0 && target.length() != release.sizeBytes) {
                throw IOException("The download ended at ${target.length()} of ${release.sizeBytes} bytes.")
            }
            Downloaded(target.length(), sha256(target))
        }

    override suspend fun checkSignature(release: ReleaseInfo): SignatureCheck = withContext(Dispatchers.IO) {
        val apk = file(release)
        if (!apk.exists()) return@withContext SignatureCheck.Refused("The download is missing.")
        val pm = context.packageManager
        val archive = archiveInfo(pm, apk.path)
            ?: return@withContext SignatureCheck.Refused("Android cannot read the download as an app.")
        if (archive.packageName != context.packageName) {
            return@withContext SignatureCheck.Refused(
                "The download is ${archive.packageName}, not this app (${context.packageName}). " +
                    "A debug build does not update from releases.",
            )
        }
        if (archive.versionName != release.version) {
            return@withContext SignatureCheck.Refused("The download says it is ${archive.versionName}, not ${release.version}.")
        }
        val theirs = signers(archive)
        // From the hub's decision, which carries the signed release
        // manifest's signer: the APK must be signed with that certificate as
        // well as the installed app's own.
        val named = release.signerSha256?.lowercase()
        if (named != null && named !in theirs.keys) {
            return@withContext SignatureCheck.Refused("It is not signed with the key its release names.")
        }
        val mine = signers(installedInfo(pm))
        val shared = theirs.keys.intersect(mine.keys)
        if (theirs.isEmpty() || shared.isEmpty()) {
            SignatureCheck.Refused("It is not signed with the key this app was installed with.")
        } else {
            SignatureCheck.Matches(theirs.getValue(shared.first()))
        }
    }

    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun openInstallPermission() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override suspend fun install(release: ReleaseInfo): Unit = withContext(Dispatchers.IO) {
        val apk = file(release)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, UpdateInstallReceiver::class.java),
                // Mutable: the installer fills in the status and the confirm intent.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    override fun discard(release: ReleaseInfo) {
        file(release).delete()
    }

    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(SIGNING_FLAGS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, SIGNING_FLAGS)
        }

    private fun installedInfo(pm: PackageManager): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(SIGNING_FLAGS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, SIGNING_FLAGS)
        }

    /** Each signing certificate, by its SHA-256, with the name it was issued to. */
    private fun signers(info: PackageInfo): Map<String, String> {
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return emptyMap()
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: return emptyMap()
        return certs.associate { sig ->
            val bytes = sig.toByteArray()
            hex(MessageDigest.getInstance("SHA-256").digest(bytes)) to commonName(bytes)
        }
    }

    private fun commonName(der: ByteArray): String = try {
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        cert.subjectX500Principal.name.split(',').map { it.trim() }
            .firstOrNull { it.startsWith("CN=") }?.removePrefix("CN=")
            ?: "the release"
    } catch (e: Exception) {
        "the release"
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        @Suppress("DEPRECATION")
        val SIGNING_FLAGS: Int =
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    }
}

/**
 * Where Android reports on the install session. The one status this acts on
 * is "the person has to confirm": it opens Android's own confirm screen. A
 * success restarts the app, so there is nothing to say; a failure leaves the
 * Updating screen where it was, with Install still there to tap.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
        }
    }
}
