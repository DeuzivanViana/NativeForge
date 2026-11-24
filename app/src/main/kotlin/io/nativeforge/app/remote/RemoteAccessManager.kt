package io.nativeforge.app.remote

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import io.nativeforge.app.di.AppContainer
import io.nativeforge.core.remote.RemoteServer
import io.nativeforge.core.remote.RemoteServerConfig
import io.nativeforge.core.remote.security.AccessControl
import io.nativeforge.core.remote.security.NetworkPolicy
import io.nativeforge.core.remote.security.PairingCode
import io.nativeforge.core.remote.security.TlsIdentity
import io.nativeforge.core.remote.security.TrustedBrowser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/** User choices for remote access (Remote access screen). */
data class RemoteAccessSettings(
    val port: Int = DEFAULT_PORT,
    /** Stop after this many minutes without anything done from a browser; 0 = never. */
    val idleMinutes: Int = 30,
    /** Show a black full screen (screen on, OLED pixels off) while remote access is on. */
    val blackScreen: Boolean = true,
) {
    companion object {
        const val DEFAULT_PORT = 8443
        val IDLE_CHOICES = listOf(15, 30, 60, 120, 0)
    }
}

/** What the Remote access screen and the notification show. */
data class RemoteAccessState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val port: Int = 0,
    /** Addresses a browser can use, best first (Wi-Fi, then VPN and hotspot). */
    val addresses: List<String> = emptyList(),
    val fingerprint: String = "",
    val pairingCode: PairingCode? = null,
    val browsers: List<TrustedBrowser> = emptyList(),
    val connectedBrowsers: Int = 0,
    val error: String? = null,
) {
    val primaryUrl: String? get() = addresses.firstOrNull()?.let { "https://${hostPart(it)}:$port" }

    companion object {
        fun hostPart(address: String) = if (':' in address) "[$address]" else address
    }
}

/**
 * Owns the remote web IDE: the trusted browsers, the TLS key and, while
 * [RemoteAccessService] runs, the server itself.
 */
class RemoteAccessManager(private val context: Context, private val container: AppContainer) {
    private val dir = File(context.filesDir, "remote").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("remote_access", Context.MODE_PRIVATE)
    private val tlsFile = File(dir, "tls.p12")

    val access = AccessControl(File(dir, "browsers.json"))

    private val _settings = MutableStateFlow(
        RemoteAccessSettings(
            port = prefs.getInt("port", RemoteAccessSettings.DEFAULT_PORT),
            idleMinutes = prefs.getInt("idle_minutes", 30),
            blackScreen = prefs.getBoolean("black_screen", true),
        ),
    )
    val settings: StateFlow<RemoteAccessSettings> = _settings.asStateFlow()

    private val _state = MutableStateFlow(RemoteAccessState(browsers = access.trustedBrowsers()))
    val state: StateFlow<RemoteAccessState> = _state.asStateFlow()

    @Volatile private var server: RemoteServer? = null

    init {
        access.onChanged = ::refresh
    }

    fun updateSettings(transform: (RemoteAccessSettings) -> RemoteAccessSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        prefs.edit()
            .putInt("port", updated.port)
            .putInt("idle_minutes", updated.idleMinutes)
            .putBoolean("black_screen", updated.blackScreen)
            .apply()
    }

    /** Starts the foreground service, which starts the server. */
    fun start() {
        _state.update { it.copy(starting = true, error = null) }
        val intent = Intent(context, RemoteAccessService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
    }

    fun stop() {
        context.startService(Intent(context, RemoteAccessService::class.java).setAction(RemoteAccessService.ACTION_STOP))
    }

    fun newPairingCode() {
        access.newPairingCode()
        refresh()
    }

    fun revoke(browser: TrustedBrowser) = access.revoke(browser.id)

    fun revokeAll() = access.revokeAll()

    /** Makes a new certificate (and fingerprint); browsers will warn once and must be checked again. */
    fun resetCertificate() {
        TlsIdentity.reset(tlsFile)
        _state.update { it.copy(fingerprint = "") }
    }

    val isIgnoringBatteryOptimizations: Boolean
        get() = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

    /** Called by the service. Blocking: creates the TLS key on first use. */
    internal fun startServer(onIdle: () -> Unit, onStatus: () -> Unit) {
        if (server != null) return
        try {
            val identity = TlsIdentity.loadOrCreate(tlsFile)
            val config = _settings.value
            val socket = openSocket(identity, config.port)
            val created = RemoteServer(
                backend = AndroidRemoteBackend(context, container),
                access = access,
                logger = container.logger,
                config = RemoteServerConfig(idleTimeoutMillis = TimeUnit.MINUTES.toMillis(config.idleMinutes.toLong())),
            )
            created.onIdle = onIdle
            created.onStatusChanged = { refresh(); onStatus() }
            // Set before start(): the server chains its own listener (closing revoked browsers' connections).
            access.onChanged = { refresh(); onStatus() }
            created.start(socket)
            server = created
            if (access.trustedBrowsers().isEmpty() && access.currentPairingCode() == null) access.newPairingCode()
            _state.update {
                it.copy(running = true, starting = false, port = socket.localPort, fingerprint = identity.fingerprint, error = null)
            }
            refresh()
        } catch (e: Exception) {
            container.logger.error(TAG, "Remote access did not start", e)
            _state.update { it.copy(running = false, starting = false, error = e.message ?: e.javaClass.simpleName) }
            throw e
        }
    }

    /** The configured port, or the next free one when another app uses it. */
    private fun openSocket(identity: TlsIdentity, port: Int): java.net.ServerSocket {
        var last: Exception? = null
        for (candidate in port until port + 10) {
            try {
                return identity.serverSocket(candidate, null)
            } catch (e: java.net.BindException) {
                last = e
            }
        }
        throw last ?: IllegalStateException("No free port")
    }

    internal fun stopServer() {
        server?.stop()
        server = null
        access.onChanged = ::refresh
        _state.update { it.copy(running = false, starting = false, connectedBrowsers = 0) }
    }

    /** Pushes the app's theme to connected browsers. */
    internal fun themeChanged() = server?.themeChanged()

    fun refresh() {
        _state.update {
            it.copy(
                addresses = if (it.running) localAddresses() else emptyList(),
                pairingCode = access.currentPairingCode(),
                browsers = access.trustedBrowsers(),
                connectedBrowsers = server?.status?.connectedBrowsers ?: 0,
            )
        }
    }

    companion object {
        private const val TAG = "RemoteAccess"

        /** Addresses of this device a browser on the same network (or VPN) can reach. */
        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { iface ->
                    when {
                        iface.name.startsWith("wlan") -> 0
                        iface.name.startsWith("tun") || iface.name.startsWith("tailscale") -> 1
                        iface.name.startsWith("ap") || iface.name.startsWith("swlan") || iface.name.startsWith("rndis") -> 2
                        else -> 3
                    }
                }
                .flatMap { iface -> iface.inetAddresses.toList().filter { usable(it) } }
                .sortedBy { if (it is Inet4Address) 0 else 1 }
                .map { it.hostAddress!!.substringBefore('%') }
                .distinct()
        }.getOrDefault(emptyList())

        private fun usable(address: InetAddress) =
            !address.isLinkLocalAddress && !address.isLoopbackAddress && NetworkPolicy.isLocalPeer(address)
    }
}
