package io.nativeforge.app.crash

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nativeforge.core.common.crash.CrashReportStore
import io.nativeforge.core.ui.component.DialogPrimaryButton
import io.nativeforge.core.ui.component.DialogSecondaryButton
import io.nativeforge.core.ui.component.NfConfirmDialog
import io.nativeforge.core.ui.component.NfDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shown once when the app starts after a crash: the newest new report, with Share and Copy.
 * Closing it marks every new report as seen; all of them stay in Settings › Crash reports.
 */
@Composable
fun NewCrashReportsDialog() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reports by remember { mutableStateOf<List<CrashReportStore.Report>>(emptyList()) }
    LaunchedEffect(Unit) {
        reports = withContext(Dispatchers.IO) { runCatching { CrashReporter.collectUnseen(context) }.getOrDefault(emptyList()) }
    }
    val newest = reports.firstOrNull() ?: return
    val more = reports.size - 1
    CrashReportViewer(
        report = newest,
        title = "NativeForge closed unexpectedly",
        subtitle = newest.kind.label + " · " + CrashReportStore.timestamp(newest.time) +
            (if (more > 0) " · $more more in Settings › Crash reports" else ""),
        onDismiss = {
            reports = emptyList()
            scope.launch(Dispatchers.IO) { runCatching { CrashReporter.store(context).markAllSeen() } }
        },
    )
}

/** A report's text, selectable, with Share (text and file) and Copy. */
@Composable
fun CrashReportViewer(
    report: CrashReportStore.Report,
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val text by produceState("", report) { value = withContext(Dispatchers.IO) { report.text() } }
    NfDialog(
        title = title,
        subtitle = subtitle,
        onDismiss = onDismiss,
        maxWidth = 720.dp,
        actions = {
            if (onDelete != null) DialogSecondaryButton("Delete", onDelete)
            DialogSecondaryButton("Copy") { clipboard.setText(AnnotatedString(text)) }
            DialogPrimaryButton("Share", onClick = { runCatching { CrashReporter.share(context, report) } })
        },
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Share this report when you describe what you were doing. It stays on this phone unless you share it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

/** Settings › Crash reports: every kept report, newest first. */
@Composable
fun CrashReportList() {
    val context = LocalContext.current
    val store = remember { CrashReporter.store(context) }
    var version by remember { mutableIntStateOf(0) }
    val reports by produceState(emptyList<CrashReportStore.Report>(), version) {
        value = withContext(Dispatchers.IO) { store.reports() }
    }
    var open by remember { mutableStateOf<CrashReportStore.Report?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    if (reports.isEmpty()) {
        Text("No crashes recorded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        reports.forEach { report ->
            Row(
                Modifier.fillMaxWidth().clickable { open = report }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(report.kind.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(CrashReportStore.timestamp(report.time).substringBeforeLast('.'), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DialogSecondaryButton("Delete all") { confirmClear = true }
        }
    }
    Text(
        "Kotlin and native crashes of the IDE are written here (the newest ${CrashReportStore.DEFAULT_KEEP}). Nothing is sent anywhere.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    open?.let { report ->
        CrashReportViewer(
            report = report,
            title = report.kind.label,
            subtitle = CrashReportStore.timestamp(report.time),
            onDismiss = { open = null },
            onDelete = {
                store.delete(report)
                open = null
                version++
            },
        )
    }
    if (confirmClear) {
        NfConfirmDialog(
            title = "Delete all crash reports?",
            message = "The ${reports.size} saved reports are removed from this phone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                store.clear()
                confirmClear = false
                version++
            },
            onDismiss = { confirmClear = false },
        )
    }
}
