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
import dev.claudefleet.mobile.notify.BackgroundNotifier
import dev.claudefleet.mobile.notify.NeedsYouAlert
import dev.claudefleet.mobile.notify.needsYouAlerts
import dev.claudefleet.mobile.store.AndroidPrefs
import dev.claudefleet.mobile.store.AndroidSecrets
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channels(this)
        ServiceCompat.startForeground(
            this,
            ONGOING_ID,
            ongoing(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (watching == null) watching = scope.launch { watch() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun watch() {
        val container = AppContainer(
            secrets = AndroidSecrets(applicationContext),
            prefs = AndroidPrefs(getSharedPreferences("quick_replies", MODE_PRIVATE)),
            http = HttpClient(OkHttp),
            appVersion = BuildConfig.VERSION_NAME,
        )
        val credentials = (container.session.restore() as? AuthState.Paired)?.credentials
        if (credentials == null) {
            stopSelf()
            return
        }
        val fleet = container.repository(credentials, scope)
        fleet.start()
        needsYouAlerts(fleet).collect { alert ->
            // On screen, the app already shows it: no second word for it.
            if (!AppVisibility.foreground) post(alert)
        }
    }

    private fun post(alert: NeedsYouAlert) {
        val open = Intent(this, MainActivity::class.java)
            .putExtra(EXTRA_SESSION_ID, alert.sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(
            this,
            alert.sessionId.toInt(),
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, ALERTS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        // One per session: a session that needs you again replaces its own.
        manager(this).notify(ALERT_BASE + (alert.sessionId % 100_000).toInt(), n)
    }

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
        private const val WATCHING = "watching"
        private const val ALERTS = "needs_you"
        private const val ONGOING_ID = 1
        private const val ALERT_BASE = 1_000

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
    private val prefs = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)
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

    private companion object {
        const val KEY = "needs_you"
    }
}
