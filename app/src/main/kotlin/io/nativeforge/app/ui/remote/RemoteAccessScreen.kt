package io.nativeforge.app.ui.remote

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.nativeforge.app.di.appContainer
import io.nativeforge.app.remote.RemoteAccessSettings
import io.nativeforge.app.remote.RemoteAccessState
import io.nativeforge.core.remote.security.AccessControl
import io.nativeforge.core.ui.component.CompactTopBar
import io.nativeforge.core.ui.component.FormSection
import io.nativeforge.core.ui.component.NfConfirmDialog
import io.nativeforge.core.ui.component.SegmentedChoice
import io.nativeforge.core.ui.component.ToolbarIconButton
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Remote access: the phone serves the IDE to a browser on a computer on the same network.
 * Start/stop, the address and pairing code, the certificate fingerprint, trusted browsers,
 * the idle timeout and the battery setting that keeps it running with the screen off.
 */
@Composable
fun RemoteAccessScreen(onBack: () -> Unit) {
    val manager = appContainer().remoteAccess
    val state by manager.state.collectAsStateWithLifecycle()
    val settings by manager.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var batteryExempt by remember { mutableStateOf(manager.isIgnoringBatteryOptimizations) }
    var confirmRevokeAll by remember { mutableStateOf(false) }
    var confirmNewCertificate by remember { mutableStateOf(false) }

    // Addresses and the battery exemption can change while the screen is away (Wi-Fi, Settings).
    LifecycleResumeEffect(Unit) {
        manager.refresh()
        batteryExempt = manager.isIgnoringBatteryOptimizations
        onPauseOrDispose {}
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompactTopBar(
            title = "Remote access",
            subtitle = "Use NativeForge from a computer's browser",
            navigation = { ToolbarIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) },
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(12.dp)
                .widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FormSection("Server") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                state.running -> "On"
                                state.starting -> "Starting…"
                                else -> "Off"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = if (state.running) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (state.running) "${state.connectedBrowsers} browser${if (state.connectedBrowsers == 1) "" else "s"} connected"
                            else "Compiling, files and terminals stay on this phone; the browser is only the screen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    if (state.running) {
                        OutlinedButton(onClick = manager::stop) { Text("Stop") }
                    } else {
                        Button(onClick = manager::start, enabled = !state.starting) { Text("Start") }
                    }
                }
                state.error?.let { Text("Could not start: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }

            if (state.running) {
                ConnectSection(state, onCopy = { clipboard.setText(AnnotatedString(it)) }, onNewCode = manager::newPairingCode)
            }

            FormSection("Screen off") {
                Text(
                    "While remote access is on, a notification keeps NativeForge running and the phone keeps the CPU and Wi-Fi awake, " +
                        "so you can lock the screen and keep working from the computer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Battery optimization", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (batteryExempt) "Not restricted: Android will not pause NativeForge" else "Restricted: some phones pause apps with the screen off",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (batteryExempt) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!batteryExempt) {
                        TextButton(onClick = { requestBatteryExemption(context) }) { Text("Allow") }
                    }
                }
                Text("Stop when idle", style = MaterialTheme.typography.bodyMedium)
                SegmentedChoice(
                    options = RemoteAccessSettings.IDLE_CHOICES,
                    selected = settings.idleMinutes,
                    onSelect = { minutes -> manager.updateSettings { it.copy(idleMinutes = minutes) } },
                    label = { if (it == 0) "Never" else if (it < 60) "$it min" else "${it / 60} h" },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Stops after this long without anything done from a browser (builds and typing count). Applies on the next start.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FormSection("Trusted browsers") {
                if (state.browsers.isEmpty()) {
                    Text("No browser is paired yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
                state.browsers.forEach { browser ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(browser.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Paired ${format.format(Date(browser.createdAtMillis))} · last used ${format.format(Date(browser.lastSeenAtMillis))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { manager.revoke(browser) }) { Text("Revoke", color = MaterialTheme.colorScheme.error) }
                    }
                }
                if (state.browsers.size > 1) {
                    TextButton(onClick = { confirmRevokeAll = true }) { Text("Revoke all", color = MaterialTheme.colorScheme.error) }
                }
                Text(
                    "A paired browser stays signed in for 30 days after its last use. Revoking signs it out at once.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FormSection("Security") {
                Text(
                    "• Only devices on your local network (or a VPN such as Tailscale) can connect; the phone is never exposed to the internet.\n" +
                        "• The connection is encrypted (HTTPS) with a certificate made on this phone. The browser warns once because no " +
                        "authority signed it: compare its SHA-256 fingerprint with the one shown above before continuing.\n" +
                        "• A browser needs a one-time pairing code; after 5 wrong codes the code is destroyed.\n" +
                        "• To work away from home, install a VPN such as Tailscale or WireGuard on both devices instead of forwarding ports.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { confirmNewCertificate = true }, enabled = !state.running) { Text("Make a new certificate") }
            }
        }
    }

    if (confirmRevokeAll) {
        NfConfirmDialog(
            title = "Revoke all browsers",
            message = "Every browser will need a new pairing code to connect again.",
            confirmLabel = "Revoke all",
            onConfirm = { confirmRevokeAll = false; manager.revokeAll() },
            onDismiss = { confirmRevokeAll = false },
            destructive = true,
        )
    }
    if (confirmNewCertificate) {
        NfConfirmDialog(
            title = "New certificate",
            message = "The fingerprint changes; browsers will warn again and you should compare the new fingerprint.",
            confirmLabel = "Make new",
            onConfirm = { confirmNewCertificate = false; manager.resetCertificate() },
            onDismiss = { confirmNewCertificate = false },
        )
    }
}

@Composable
private fun ConnectSection(state: RemoteAccessState, onCopy: (String) -> Unit, onNewCode: () -> Unit) {
    FormSection("Connect a browser") {
        val url = state.primaryUrl
        if (url == null) {
            Text(
                "No local network found. Connect the phone to Wi-Fi (or turn on its hotspot or a VPN), then come back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            return@FormSection
        }
        Text("1. On the computer, open:", style = MaterialTheme.typography.bodyMedium)
        state.addresses.forEachIndexed { index, address ->
            val full = "https://${RemoteAccessState.hostPart(address)}:${state.port}"
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(Modifier.weight(1f)) {
                    Text(
                        full,
                        fontFamily = FontFamily.Monospace,
                        fontSize = if (index == 0) 16.sp else 13.sp,
                        fontWeight = if (index == 0) FontWeight.Medium else FontWeight.Normal,
                        color = if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ToolbarIconButton(Icons.Filled.ContentCopy, "Copy address", { onCopy(full) })
            }
        }
        Text("2. The browser warns about the certificate. Check that its SHA-256 fingerprint starts with:", style = MaterialTheme.typography.bodyMedium)
        SelectionContainer {
            Text(
                state.fingerprint.split(':').take(8).joinToString(":"),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Text("then continue to the site.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("3. Type this pairing code:", style = MaterialTheme.typography.bodyMedium)
        val code = state.pairingCode
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(code) {
            while (code != null) {
                now = System.currentTimeMillis()
                delay(1000)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (code != null && code.expiresAtMillis > now) {
                Column(Modifier.weight(1f)) {
                    Text(
                        AccessControl.formatCode(code.code),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 3.sp,
                    )
                    val left = (code.expiresAtMillis - now) / 1000
                    Text(
                        "Works once · expires in ${left / 60}:${"%02d".format(left % 60)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ToolbarIconButton(Icons.Filled.Refresh, "New code", onNewCode)
            } else {
                Button(onClick = onNewCode) { Text("Show a pairing code") }
            }
        }
        if (code != null && code.expiresAtMillis > now) {
            Text(
                "Or scan this with a tablet or a laptop camera (it opens the address and fills the code):",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                QrCode("$url/#pair=${code.code}", Modifier.size(180.dp))
            }
        }
    }
}

/** A QR code drawn with ZXing (black on white, as scanners expect). */
@Composable
private fun QrCode(text: String, modifier: Modifier = Modifier) {
    val bitmap = remember(text) {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix.get(i % matrix.width, i / matrix.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Box(modifier.clip(RoundedCornerShape(8.dp))) {
        Image(bitmap, contentDescription = "QR code of the address and pairing code", modifier = Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
    }
}

@SuppressLint("BatteryLife") // F-Droid build; the user asks for it explicitly with the "Allow" button.
private fun requestBatteryExemption(context: android.content.Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    try {
        context.startActivity(direct)
    } catch (e: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}
