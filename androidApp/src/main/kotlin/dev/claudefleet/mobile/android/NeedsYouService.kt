package dev.claudefleet.mobile.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.claudefleet.mobile.AppContainer
import dev.claudefleet.mobile.data.AuthState
import dev.claudefleet.mobile.net.HubClient
import dev.claudefleet.mobile.notify.BackgroundNotifier
import dev.claudefleet.mobile.notify.NeedsYouAlert
import dev.claudefleet.mobile.notify.NeedsYouResolved
import dev.claudefleet.mobile.notify.NotifyActionKind
import dev.claudefleet.mobile.notify.MISSION_WAITING_REASON
import dev.claudefleet.mobile.notify.MissionWaitAlert
import dev.claudefleet.mobile.notify.ROUTINE_FAILED_REASON
import dev.claudefleet.mobile.notify.RoutineFailedAlert
import dev.claudefleet.mobile.notify.missionWaitAlerts
import dev.claudefleet.mobile.notify.routineFailedAlerts
import dev.claudefleet.mobile.notify.needsYouContent
import dev.claudefleet.mobile.notify.decodeSeen
import dev.claudefleet.mobile.notify.encodeSeen
import dev.claudefleet.mobile.notify.needsYouEvents
import dev.claudefleet.mobile.notify.notifyAllows
import dev.claudefleet.mobile.store.AndroidPrefs
import dev.claudefleet.mobile.store.AndroidSecrets
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the hub's event stream open while the app is away, and posts a
 * notification when a session comes to need the person (`needsYouAlerts`).
 *
 * A foreground service, so the system keeps it — with its own quiet ongoing
 * notification, which is the honest price of a socket that stays open — of
 * type `specialUse`: none of the named types is "watch a server for a
 * person". It reads the same stored credential the app does through its own
 * [AppContainer], so a revoked token (401) unpairs here as it would there,
 * and it stops itself when there is no credential to watch with.
 */
class NeedsYouService : Service() {
    // Default, not Main: SSE parsing and the row decode run per frame, and this
    // service shares the app's process, so on Main they compete with its UI (review r16).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channels(this)
        // "Later" on a notification: put it away. The session keeps waiting
        // and stays in the Inbox; nothing is answered.
        if (intent?.action == ACTION_LATER) {
            intent.getLongExtra(EXTRA_SESSION_ID, -1L).takeIf { it >= 0 }?.let(::withdraw)
        }
        // Started through `startForegroundService` (Later is a foreground
        // PendingIntent), so the system wants `startForeground` within seconds
        // whatever happens next — even on the way out.
        ServiceCompat.startForeground(
            this,
            ONGOING_ID,
            ongoing(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        // The stored choice, read here and not assumed: a Later tapped after
        // "Notify me" was turned off, or a START_STICKY restart, must not
        // bring the watcher back against it.
        if (!AndroidBackgroundNotifier.isEnabled(this)) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (watching == null) watching = scope.launch { watch() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun watch() {
        val http = HttpClient(OkHttp)
        val container = AppContainer(
            secrets = AndroidSecrets(applicationContext),
            prefs = AndroidPrefs(getSharedPreferences("quick_replies", MODE_PRIVATE)),
            http = http,
            appVersion = BuildConfig.VERSION_NAME,
        )
        val credentials = (container.session.restore() as? AuthState.Paired)?.credentials
        if (credentials == null) {
            stopSelf()
            return
        }
        val fleet = container.repository(credentials, scope)
        fleet.start()
        scope.launch { watchRoutines(HubClient(http, credentials.hub, credentials.token), container) }
        scope.launch { watchMissions(HubClient(http, credentials.hub, credentials.token), container) }
        // What the last run saw, so a session that began waiting while the
        // service was down is still news when it comes back.
        val memory = getSharedPreferences("notifications", MODE_PRIVATE)
        val remembered = memory.getStringSet(SEEN, null)?.let { decodeSeen(it.toList()) }
        needsYouEvents(fleet, remembered) { seen -> memory.edit().putStringSet(SEEN, encodeSeen(seen).toSet()).apply() }
            .collect { event ->
                when (event) {
                    // On screen, the app already shows it: no second word for it.
                    // A kind turned off on This phone is not posted; read
                    // each time, so a switch flipped in the app counts now.
                    is NeedsYouAlert -> if (!AppVisibility.foreground && container.prefs.notifyAllows(event.reason)) post(event)
                    // Answered here or elsewhere: it no longer needs saying.
                    is NeedsYouResolved -> withdraw(event.sessionId)
                }
            }
    }

    /** The sessions with a notification up, for the group's summary. */
    private val shown = linkedMapOf<Long, String>()

    /**
     * Put one alert away. By id, whether or not this instance posted it:
     * [shown] is this instance's memory, and a Later tapped on an alert a
     * previous instance posted (the system restarted the service, or the
     * person turned it off and on) found nothing there and did nothing.
     */
    private fun withdraw(sessionId: Long) {
        shown.remove(sessionId)
        manager(this).cancel(alertId(sessionId))
        summarize()
    }

    /**
     * One summary over the group — "3 sessions need you" — so ten waiting
     * agents are one heads-up and a list, not ten.
     */
    private fun summarize() {
        val m = manager(this)
        // An alert the person tapped (auto-cancel) or swiped away left
        // without telling [shown]: count only what is still showing.
        val showing = m.activeNotifications.map { it.id }.toSet()
        shown.keys.retainAll { alertId(it) in showing }
        if (shown.size < 2) {
            m.cancel(SUMMARY_ID)
            return
        }
        val style = NotificationCompat.InboxStyle()
        shown.values.forEach { style.addLine(it) }
        val n = NotificationCompat.Builder(this, ALERTS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("${shown.size} sessions need you")
            .setStyle(style)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        m.notify(SUMMARY_ID, n)
    }

    /**
     * One "needs you" notification (redesign 14.8): the question, Answer (or
     * Open) and Later — never an action that answers. The lock screen shows
     * only the session and why until the phone is unlocked.
     */
    private fun post(alert: NeedsYouAlert) {
        val c = needsYouContent(alert)
        val open = Intent(this, MainActivity::class.java)
            .putExtra(EXTRA_SESSION_ID, alert.sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(
            this,
            alert.sessionId.toInt(),
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val later = PendingIntent.getForegroundService(
            this,
            alert.sessionId.toInt(),
            Intent(this, NeedsYouService::class.java).setAction(ACTION_LATER).putExtra(EXTRA_SESSION_ID, alert.sessionId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val locked = NotificationCompat.Builder(this, ALERTS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(c.publicTitle)
            .setContentText(c.publicBody)
            .build()
        val builder = NotificationCompat.Builder(this, ALERTS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(c.title)
            .setContentText(alert.question ?: alert.text)
            // The question first, then why and where, then what it is doing:
            // the question is half of whether to pick the phone up.
            .setStyle(NotificationCompat.BigTextStyle().bigText(c.body))
            .setGroup(GROUP)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(locked)
        for (action in c.actions) {
            val intent = when (action.kind) {
                NotifyActionKind.Open -> tap
                NotifyActionKind.Later -> later
            }
            builder.addAction(0, action.label, intent)
        }
        val n = builder.build()
        // One per session: a session that needs you again replaces its own.
        manager(this).notify(alertId(alert.sessionId), n)
        shown[alert.sessionId] = "${c.title} — ${alert.text}"
        summarize()
    }

    /**
     * The matrix's Routine failed row (review r19, R19-5): the hub's event
     * stream carries sessions, not routine runs, so ask `routines { failing }`
     * every [ROUTINE_POLL_MS]. The first answer is the baseline: a failure
     * from before the watcher started is on the Inbox already, not news. A hub
     * that answers with an error (an older hub, a token without routines) is
     * asked again less often.
     */
    private suspend fun watchRoutines(client: HubClient, container: AppContainer) {
        var seen: Set<Long>? = null
        while (true) {
            val failing = try {
                client.failingRoutines()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (failing != null) {
                val (alerts, now) = routineFailedAlerts(seen, failing)
                seen = now
                if (!AppVisibility.foreground && container.prefs.notifyAllows(ROUTINE_FAILED_REASON)) alerts.forEach(::postRoutine)
            }
            delay(if (failing != null) ROUTINE_POLL_MS else ROUTINE_RETRY_MS)
        }
    }

    /**
     * A mission that waits on a person (gap plan G5.7): the event stream
     * carries sessions, not missions, so ask `work { missions }` every
     * [ROUTINE_POLL_MS], as for routines. The first answer is the baseline; a
     * hub that answers with an error (no missions, a token without work) is
     * asked again less often.
     */
    private suspend fun watchMissions(client: HubClient, container: AppContainer) {
        var seen: Map<Long, String>? = null
        while (true) {
            val missions = try {
                client.workMissions()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (missions != null) {
                val (alerts, now) = missionWaitAlerts(seen, missions)
                seen = now
                if (!AppVisibility.foreground && container.prefs.notifyAllows(MISSION_WAITING_REASON)) alerts.forEach(::postMission)
            }
            delay(if (missions != null) ROUTINE_POLL_MS else ROUTINE_RETRY_MS)
        }
    }

    /** One "a mission waits for you" notification; a tap opens the app, where it is answered. */
    private fun postMission(alert: MissionWaitAlert) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, ALERTS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        manager(this).notify(MISSION_BASE + (alert.missionId % 100_000).toInt(), n)
    }

    /** One "a routine run failed" notification; a tap opens the app. */
    private fun postRoutine(alert: RoutineFailedAlert) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, ALERTS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        manager(this).notify(ROUTINE_BASE + (alert.runId % 100_000).toInt(), n)
    }

    private fun alertId(sessionId: Long) = ALERT_BASE + (sessionId % 100_000).toInt()

    private fun ongoing(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, WATCHING)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Watching the fleet")
            .setContentText("You will be told when a session needs you. Turn it off in Settings.")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        const val EXTRA_SESSION_ID = "dev.claudefleet.mobile.SESSION_ID"
        /** The service's own intent for a notification's Later. */
        const val ACTION_LATER = "dev.claudefleet.mobile.NEEDS_YOU_LATER"
        private const val WATCHING = "watching"
        private const val ALERTS = "needs_you"
        private const val ONGOING_ID = 1
        private const val ALERT_BASE = 1_000
        private const val SUMMARY_ID = 2
        private const val ROUTINE_BASE = 200_000
        private const val MISSION_BASE = 300_000
        private const val ROUTINE_POLL_MS = 60_000L
        private const val ROUTINE_RETRY_MS = 5 * 60_000L
        private const val GROUP = "needs_you"
        private const val SEEN = "seen"

        private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

        /** The two channels: the quiet ongoing one, and the one that interrupts. */
        fun channels(context: Context) {
            val m = manager(context)
            m.createNotificationChannel(
                NotificationChannel(WATCHING, "Watching the fleet", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "The ongoing notice while the app keeps a connection to the hub open."
                },
            )
            m.createNotificationChannel(
                NotificationChannel(ALERTS, "A session needs you", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "A session is waiting for you, stuck, failed or needs a decision."
                },
            )
        }
    }
}

/** Whether the app is on screen — set by [MainActivity]; the service says nothing while it is. */
object AppVisibility {
    @Volatile
    var foreground: Boolean = false
}

/**
 * [BackgroundNotifier] on Android: the person's choice, kept on the device,
 * and the [NeedsYouService] started or stopped to match it.
 */
class AndroidBackgroundNotifier(private val context: Context) : BackgroundNotifier {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY, false))

    override val supported: Boolean = true
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY, on).apply()
        _enabled.value = on
        apply()
    }

    /** Start the service if it is wanted, stop it if not — at launch, and after every change. */
    fun apply() {
        val intent = Intent(context, NeedsYouService::class.java)
        if (_enabled.value) context.startForegroundService(intent) else context.stopService(intent)
    }

    companion object {
        private const val PREFS = "notifications"
        private const val KEY = "needs_you"

        /** The stored choice, for the service to check before it watches. */
        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }
}
