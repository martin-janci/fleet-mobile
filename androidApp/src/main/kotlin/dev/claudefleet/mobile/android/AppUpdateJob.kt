package dev.claudefleet.mobile.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.claudefleet.mobile.update.UpdateMode
import dev.claudefleet.mobile.update.UpdatePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The phone's automatic update while the app is away (redesign 14.18): a
 * periodic platform job that asks the paired hub, downloads what it decides,
 * checks it and installs it — the same [dev.claudefleet.mobile.update.UpdateViewModel]
 * the screens show, held by the process's container. JobScheduler rather
 * than WorkManager: one job needs no extra library, and a persisted job comes
 * back after a restart on its own.
 */
class AppUpdateJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val updates = (application as FleetApplication).container.updates
        if (updates.state.value.mode != UpdateMode.AUTO) return false
        running = scope.launch {
            try {
                updates.runInBackground(onScreen = AppVisibility.foreground)
                val s = updates.state.value
                val release = s.available
                // Android has not allowed this app to install yet: that one
                // tap is the person's, so say an update is waiting for it.
                if (release != null && (s.phase as? UpdatePhase.Ready)?.needsPermission == true) {
                    UpdateNotices.waiting(this@AppUpdateJob, release.version)
                }
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        // Run again later: the download resumes from what it has.
        return true
    }

    companion object {
        private const val JOB_ID = 14_18
        private const val PERIOD_MS = 6 * 60 * 60 * 1000L

        /** Schedule the job unless it already is; at every start and after an update. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, AppUpdateJob::class.java))
                .setPeriodic(PERIOD_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            try {
                scheduler.schedule(job)
            } catch (e: RuntimeException) {
                // Too many jobs or a missing boot permission: the checks at
                // every app start still run.
            }
        }
    }
}

/**
 * After this app was updated (by itself, or by anything else): Android does
 * not start it again, so the background watch and the update job are put
 * back here.
 */
class AppReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        AppUpdateJob.schedule(context)
        UpdateNotices.clear(context)
        try {
            (context.applicationContext as FleetApplication).notifier.apply()
        } catch (e: RuntimeException) {
            // The system refused to start the service from here; the next
            // launch starts it.
        }
    }
}

/** The update's own notifications: Android wants a tap, or Android has not allowed installs yet. */
internal object UpdateNotices {
    private const val CHANNEL = "updates"
    private const val ID = 14_180
    private const val PREFS = "update_notices"
    private const val SEEN = "waiting_for"

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    private fun channel(context: Context) {
        manager(context).createNotificationChannel(
            NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "An update of this app is ready and needs a tap."
            },
        )
    }

    /** Android asks the person to confirm the install: [confirm] is its screen. */
    fun confirm(context: Context, confirm: Intent) {
        post(
            context,
            "Update ready to install",
            "Tap to finish updating Orbit Fleet.",
            PendingIntent.getActivity(context, ID, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )
    }

    /** [version] is downloaded and checked, and Android has not allowed this app to install it. Once per version. */
    fun waiting(context: Context, version: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(SEEN, null) == version) return
        prefs.edit().putString(SEEN, version).apply()
        post(
            context,
            "Update $version is ready",
            "Open the app and allow it to install updates, once.",
            PendingIntent.getActivity(context, ID, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
        )
    }

    fun clear(context: Context) {
        manager(context).cancel(ID)
    }

    private fun post(context: Context, title: String, text: String, tap: PendingIntent) {
        channel(context)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try {
            manager(context).notify(ID, n)
        } catch (e: SecurityException) {
            // Notifications are off for this app: the card in More says it.
        }
    }
}
