package io.nativeforge.app.remote

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import io.nativeforge.app.MainActivity
import io.nativeforge.app.NativeForgeApplication
import io.nativeforge.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the remote web IDE running while the screen is off: a foreground service (so Android
 * does not kill the process), a partial wake lock (the CPU keeps compiling) and a Wi-Fi lock
 * (the radio does not doze between requests). Its notification shows the address and has a
 * Stop button; the server also stops itself after the idle time set by the user.
 */
class RemoteAccessService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var started = false

    private val manager get() = (application as NativeForgeApplication).container.remoteAccess

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification())
        if (started) return START_NOT_STICKY
        started = true
        acquireLocks()
        scope.launch {
            try {
                manager.startServer(
                    onIdle = { main.post { stopSelf() } },
                    onStatus = { main.post(::updateNotification) },
                )
                main.post(::updateNotification)
            } catch (e: Exception) {
                main.post { stopSelf() }
            }
        }
        // Browsers follow the app's theme, editor and terminal settings live.
        val container = (application as NativeForgeApplication).container
        scope.launch {
            container.settings.settings.map { listOf(it.appTheme, it.editor, it.terminalFontSizeSp, it.formatOnSave) }.distinctUntilChanged().drop(1).collect { manager.themeChanged() }
        }
        scope.launch { container.thermal.level.collect { manager.thermalChanged(it) } }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        // Stopping closes sockets and terminals; do it off the main thread.
        Thread { manager.stopServer() }.start()
        releaseLocks()
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NativeForge:remote-access").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION") // HIGH_PERF is the mode that keeps working with the screen off.
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "NativeForge:remote-access").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun updateNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notifications.getNotificationChannel(CHANNEL) == null) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL, "Remote access", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while browsers can connect to NativeForge"
                    setShowBadge(false)
                },
            )
        }
        val state = manager.state.value
        val immutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_REMOTE, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or immutable,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RemoteAccessService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or immutable,
        )
        val text = when {
            state.error != null -> "Could not start: ${state.error}"
            !state.running -> "Starting…"
            state.primaryUrl == null -> "Connect the phone to Wi-Fi or a VPN"
            state.connectedBrowsers > 0 -> "${state.primaryUrl} · ${state.connectedBrowsers} browser${if (state.connectedBrowsers == 1) "" else "s"} connected"
            else -> state.primaryUrl!!
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_remote_access)
            .setContentTitle("Remote access is on")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "io.nativeforge.app.remote.STOP"
        private const val CHANNEL = "remote_access"
        private const val NOTIFICATION_ID = 4431
    }
}
