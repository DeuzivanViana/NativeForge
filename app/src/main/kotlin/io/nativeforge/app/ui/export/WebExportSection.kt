package io.nativeforge.app.ui.export

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import io.nativeforge.core.ui.component.CompactTopBar
import io.nativeforge.core.ui.component.DialogPrimaryButton
import io.nativeforge.core.ui.component.DialogSecondaryButton
import io.nativeforge.core.ui.component.FormSection
import io.nativeforge.core.ui.component.NfDialog
import io.nativeforge.core.ui.component.ToolbarIconButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal const val WEB_CONSOLE_ONLY = "Only console programs can be exported for the web. Graphical programs (SDL, raylib, " +
    "OpenGL ES) would need Emscripten, which does not run on Android."

/**
 * "Web (WebAssembly)" on the export screen: builds the console program for WASI and
 * packages it as a page with a terminal, plus a zip to save or share.
 */
@Composable
internal fun WebExportSection(
    state: ExportUiState,
    consoleProgram: Boolean,
    onExport: () -> Unit,
    onPreview: () -> Unit,
    onSave: (File) -> Unit,
) {
    val context = LocalContext.current
    FormSection("Web (WebAssembly)") {
        Text(
            "Runs the program in any browser: a page with a terminal (index.html) and the .wasm module, in a zip. " +
                "Every C/C++ file in the source folder is compiled with clang for WASI; the app name, arguments, " +
                "assets and build type above are used too.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!consoleProgram) {
            Text(WEB_CONSOLE_ONLY, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        when (val status = state.webStatus) {
            is ExportStatus.Working -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(status.step, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is ExportStatus.Failed -> Text(
                status.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            is ExportStatus.Done -> {
                Text("Web page ready: ${status.apk.name}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "%.1f MB · open index.html in a browser, or put the folder on any static web host".format(status.apk.length() / 1_048_576.0),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.webPage != null) WebButton("Preview", onPreview)
                    WebButton("Save zip to…") { onSave(status.apk) }
                    WebButton("Share") { shareZip(context, status.apk) }
                }
            }
            ExportStatus.Idle -> Unit
        }
        OutlinedButton(
            onClick = onExport,
            enabled = consoleProgram && state.webStatus !is ExportStatus.Working,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().height(40.dp),
        ) { Text("Build for the web") }
    }
}

@Composable
private fun WebButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shape = MaterialTheme.shapes.small, modifier = Modifier.height(32.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
    }
}

private fun shareZip(context: Context, zip: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
    val intent = Intent(Intent.ACTION_SEND)
        .setType("application/zip")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share ${zip.name}"))
}

/** Asks before downloading Termux's wasi-libc, like the toolchain's own consent dialog. */
@Composable
internal fun WebDownloadDialog(prompt: WebDownloadPrompt, onAccept: () -> Unit, onDecline: () -> Unit) {
    val size = prompt.sizeBytes?.let { " (about %.0f MB)".format(it / 1_048_576.0) }.orEmpty()
    NfDialog(
        title = "Download the WebAssembly libraries?",
        onDismiss = onDecline,
        maxWidth = 480.dp,
        actions = {
            DialogSecondaryButton("Not now", onDecline)
            DialogPrimaryButton("Download", onAccept)
        },
    ) {
        Text(
            "Building for the web needs wasi-libc$size: the C and C++ standard libraries for WebAssembly (WASI). " +
                "NativeForge downloads it once from the Termux package repository (packages-cf.termux.dev) and uses it " +
                "with the clang you already have.\n\nLike the compiler, it is free software that is not built or checked " +
                "by F-Droid or by the store you installed NativeForge from.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(14.dp),
        )
    }
}

/**
 * Runs the exported page in a WebView. The page is loaded as data with no base URL, so
 * it has no file, content or network access: it only needs its inline script and module.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun WebPreviewDialog(page: File, title: String, onDismiss: () -> Unit) {
    val html by produceState<String?>(null, page) { value = withContext(Dispatchers.IO) { runCatching { page.readText() }.getOrNull() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            CompactTopBar(
                title = "Web preview",
                subtitle = title,
                navigation = { ToolbarIconButton(Icons.Filled.Close, "Close", onDismiss) },
            )
            Box(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                val content = html
                if (content != null) {
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.blockNetworkLoads = true
                                settings.domStorageEnabled = false
                                settings.setGeolocationEnabled(false)
                                // Shows the page's prompt() dialogs (input on WebViews without JSPI).
                                webChromeClient = WebChromeClient()
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                                }
                                loadDataWithBaseURL(null, content, "text/html", "utf-8", null)
                            }
                        },
                        onRelease = { it.destroy() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
