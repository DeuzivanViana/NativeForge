package io.nativeforge.app.ui.export

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.nativeforge.app.signing.SavedKeystore
import io.nativeforge.core.ui.component.DenseRow
import io.nativeforge.core.ui.component.NfConfirmDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.nativeforge.app.di.appContainer
import io.nativeforge.core.export.ExportValidator
import io.nativeforge.core.project.model.AppPermission
import io.nativeforge.core.project.model.ExportSettings
import io.nativeforge.core.project.model.RuntimeKind
import io.nativeforge.core.ui.component.CompactButtonPadding
import io.nativeforge.core.ui.component.CompactTopBar
import io.nativeforge.core.ui.component.FieldLabel
import io.nativeforge.core.ui.component.FormSection
import io.nativeforge.core.ui.component.NfTextField
import io.nativeforge.core.ui.component.SegmentedChoice
import io.nativeforge.core.ui.component.SwitchRow
import io.nativeforge.core.ui.component.ToolbarIconButton
import java.io.File

@Composable
fun ExportScreen(projectName: String, onBack: () -> Unit) {
    val container = appContainer()
    val viewModel: ExportViewModel = viewModel(
        key = "export-$projectName",
        factory = viewModelFactory { initializer { ExportViewModel(container, projectName) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importIcon { context.contentResolver.openInputStream(uri) }
    }
    var creatingKey by remember { mutableStateOf(false) }
    var backupTarget by remember { mutableStateOf<SavedKeystore?>(null) }
    var deleteTarget by remember { mutableStateOf<SavedKeystore?>(null) }
    var saveSource by remember { mutableStateOf<File?>(null) }
    var previewingWeb by remember { mutableStateOf(false) }
    val pickKeystore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    // Keystores are a few kilobytes; refuse anything that is obviously something else.
                    input.readNBytesCompat(MAX_KEYSTORE_BYTES + 1)
                }
            }.getOrNull()
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "keystore"
            when {
                bytes == null -> Unit
                bytes.size > MAX_KEYSTORE_BYTES -> viewModel.startImport(ByteArray(0), name)
                else -> viewModel.startImport(bytes, name)
            }
        }
    }
    val backUpKey = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val target = backupTarget
        if (uri != null && target != null) viewModel.backUpKey(target.id) { context.contentResolver.openOutputStream(uri) }
        backupTarget = null
    }
    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = saveSource
        if (uri != null && source != null) viewModel.saveCopy(source) { context.contentResolver.openOutputStream(uri) }
        saveSource = null
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompactTopBar(
            title = "Export",
            subtitle = java.io.File(projectName).name,
            navigation = { ToolbarIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) },
        )
        val settings = state.settings ?: return@Column
        val graphical = state.project?.config?.runtime != RuntimeKind.CONSOLE
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Creates a standalone app that runs only this program. Settings are saved in the project.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FormSection("App") {
                NfTextField(
                    value = settings.appName,
                    onValueChange = { value -> viewModel.update { it.copy(appName = value) } },
                    label = "App name",
                    error = "Required".takeIf { settings.appName.isBlank() },
                )
                NfTextField(
                    value = settings.packageId,
                    onValueChange = { value -> viewModel.update { it.copy(packageId = value.trim()) } },
                    label = "Package ID",
                    monospace = true,
                    error = ExportValidator.packageIdProblem(settings.packageId),
                    supportingText = "Unique id, e.g. com.yourname.game. Keep it the same for updates.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NfTextField(
                        value = settings.versionName,
                        onValueChange = { value -> viewModel.update { it.copy(versionName = value) } },
                        label = "Version name",
                        modifier = Modifier.weight(1f),
                    )
                    NfTextField(
                        value = settings.versionCode.toString(),
                        onValueChange = { value -> value.toIntOrNull()?.let { code -> viewModel.update { it.copy(versionCode = code) } } },
                        label = "Version code",
                        keyboardType = KeyboardType.Number,
                        supportingText = "Raise it for every update",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            FormSection("Icon") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconPreview(viewModel.iconFile(), state.iconVersion, settings.iconFill)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (settings.iconPath == null) "Default NativeForge icon" else "Custom: ${settings.iconPath}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            CompactButton("Choose image") { pickIcon.launch("image/*") }
                            if (settings.iconPath != null) CompactButton("Use default", outlined = true, onClick = viewModel::clearIcon)
                        }
                    }
                }
                if (settings.iconPath != null) {
                    SwitchRow(
                        title = "Fill the whole icon",
                        subtitle = "Off: the image fits inside the shape on a black background",
                        checked = settings.iconFill,
                        onCheckedChange = { value -> viewModel.update { it.copy(iconFill = value) } },
                    )
                }
            }

            FormSection("Display") {
                Column {
                    FieldLabel("Orientation")
                    val rows = ExportSettings.Orientation.entries.let { all ->
                        listOf(
                            listOf(all[0], all[5]), // Auto, any rotation
                            listOf(all[1], all[2]), // portrait, landscape
                            listOf(all[3], all[4]), // both-ways variants
                        )
                    }
                    rows.forEach { row ->
                        SegmentedChoice(
                            options = row,
                            selected = settings.orientation,
                            onSelect = { value -> viewModel.update { it.copy(orientation = value) } },
                            label = { it.displayName },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        )
                    }
                }
                SwitchRow(
                    title = "Full screen",
                    subtitle = "Hide the status and navigation bars",
                    checked = settings.immersive,
                    enabled = graphical,
                    onCheckedChange = { value -> viewModel.update { it.copy(immersive = value) } },
                )
                SwitchRow(
                    title = "Keep the screen on",
                    subtitle = "Useful for games and demos",
                    checked = settings.keepScreenOn,
                    enabled = graphical,
                    onCheckedChange = { value -> viewModel.update { it.copy(keepScreenOn = value) } },
                )
                if (!graphical) {
                    Text("Console apps always run in the terminal.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            FormSection("Build") {
                Column {
                    FieldLabel("Optimization")
                    SegmentedChoice(
                        options = listOf(true, false),
                        selected = settings.releaseBuild,
                        onSelect = { value -> viewModel.update { it.copy(releaseBuild = value) } },
                        label = { if (it) "Release (-O2)" else "Debug (-O0 -g)" },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                NfTextField(
                    value = state.arguments,
                    onValueChange = viewModel::setArguments,
                    label = "Program arguments",
                    placeholder = "--level 1",
                    monospace = true,
                    supportingText = "Passed to main(argc, argv); also used when you run from the editor.",
                )
                SwitchRow(
                    title = "Include the assets/ folder",
                    subtitle = if (state.assetCount == 0) {
                        "No assets/ folder yet. Put images, sounds and fonts there."
                    } else {
                        "${state.assetCount} file(s). Open them as \"assets/<file>\" in your code."
                    },
                    checked = settings.bundleAssets,
                    onCheckedChange = { value -> viewModel.update { it.copy(bundleAssets = value) } },
                )
            }

            PermissionsSection(
                permissions = settings.permissions,
                onChange = { value -> viewModel.update { it.copy(permissions = value) } },
            )

            FormSection("Package") {
                SegmentedChoice(
                    options = ExportSettings.Format.entries,
                    selected = settings.format,
                    onSelect = { value -> viewModel.update { it.copy(format = value) } },
                    label = { it.displayName },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    when (settings.format) {
                        ExportSettings.Format.APK -> "An APK installs directly on phones and can be shared or published on F-Droid and other stores. Built for ${appAbi(state)}."
                        ExportSettings.Format.AAB -> "An app bundle is what Google Play asks for; Play builds the APKs for each device. " +
                            "It cannot be installed directly. Sign it with your upload key, and use the same key for every update."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FormSection("Signing key") {
                Column {
                    DenseRow(
                        title = "NativeForge device key",
                        subtitle = "Created on this device. Good for testing and sharing.",
                        icon = Icons.Filled.PhoneAndroid,
                        selected = settings.signingKeyId == null,
                        onClick = { viewModel.selectKey(null) },
                    )
                    state.keystores.forEach { key ->
                        KeyRow(
                            key = key,
                            selected = key.id == settings.signingKeyId,
                            onSelect = { viewModel.selectKey(key.id) },
                            onBackUp = { backupTarget = key; backUpKey.launch(viewModel.keyFileName(key)) },
                            onForgetPassword = { viewModel.forgetPasswords(key.id) },
                            onDelete = { deleteTarget = key },
                        )
                    }
                }
                val selectedKey = state.keystores.firstOrNull { it.id == settings.signingKeyId }
                if (settings.signingKeyId != null && selectedKey == null) {
                    Text("The key chosen for this project was deleted. Pick another one.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
                if (selectedKey != null && !selectedKey.backedUp) {
                    Text(
                        "This key is only on this device. Back it up (⋮ › Back up) before publishing: updates must be signed with the same key.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompactButton("New key") { creatingKey = true }
                    CompactButton("Import keystore", outlined = true) { pickKeystore.launch(arrayOf("*/*")) }
                }
                state.keyMessage?.let { message ->
                    Text(message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            state.problems.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            when (val status = state.status) {
                is ExportStatus.Working -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(status.step, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is ExportStatus.Failed -> Text(status.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                is ExportStatus.Done -> FormSection("Result") {
                    val isBundle = status.apk.extension == ExportSettings.Format.AAB.extension
                    Text("${if (isBundle) "App bundle" else "APK"} ready: ${status.apk.name}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "%.1f MB".format(status.apk.length() / 1_048_576.0),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!isBundle) CompactButton("Install") { installApk(context, status.apk) }
                        CompactButton("Save to…", outlined = !isBundle) { saveSource = status.apk; saveCopy.launch(status.apk.name) }
                        CompactButton("Share", outlined = true) { shareApk(context, status.apk) }
                    }
                }
                ExportStatus.Idle -> Unit
            }
            Button(
                onClick = viewModel::export,
                enabled = state.status !is ExportStatus.Working,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().height(40.dp),
            ) { Text(if (settings.format == ExportSettings.Format.AAB) "Build and export app bundle" else "Build and export APK") }

            WebExportSection(
                state = state,
                consoleProgram = !graphical,
                onExport = { viewModel.exportWeb() },
                onPreview = { previewingWeb = true },
                onSave = { zip -> saveSource = zip; saveCopy.launch(zip.name) },
            )
        }
    }

    state.webDownloadPrompt?.let { prompt ->
        WebDownloadDialog(prompt, onAccept = { viewModel.exportWeb(downloadAccepted = true) }, onDecline = viewModel::dismissWebDownload)
    }
    val webPage = state.webPage
    if (previewingWeb && webPage != null) {
        WebPreviewDialog(webPage, state.settings?.appName.orEmpty(), onDismiss = { previewingWeb = false })
    }

    if (creatingKey) CreateKeyDialog(viewModel, state.settings?.appName.orEmpty().ifBlank { java.io.File(projectName).name }) { creatingKey = false }
    state.pendingImport?.let { pending -> ImportKeyDialog(viewModel, pending, onDismiss = viewModel::cancelImport) }
    state.passwordPrompt?.let { key ->
        KeyPasswordDialog(
            keystore = key,
            error = (state.status as? ExportStatus.Failed)?.message,
            onExport = { passwords, remember -> viewModel.export(passwords, remember) },
            onDismiss = viewModel::dismissPasswordPrompt,
        )
    }
    deleteTarget?.let { key ->
        NfConfirmDialog(
            title = "Delete '${key.name}'?",
            message = "The keystore file is removed from NativeForge. Apps signed with it can only be updated with a backup of this key.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { viewModel.deleteKey(key.id); deleteTarget = null },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun KeyRow(
    key: SavedKeystore,
    selected: Boolean,
    onSelect: () -> Unit,
    onBackUp: () -> Unit,
    onForgetPassword: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val year = java.util.Calendar.getInstance().apply { timeInMillis = key.validUntil }.get(java.util.Calendar.YEAR)
    DenseRow(
        title = key.name,
        subtitle = "${key.alias} · ${key.type.displayName} · valid until $year\nSHA-256 ${shortFingerprint(key.fingerprint)}" +
            if (key.passwordsSaved) " · password saved" else "",
        icon = Icons.Filled.Key,
        selected = selected,
        onClick = onSelect,
    ) {
        Box {
            ToolbarIconButton(Icons.Filled.MoreVert, "Key actions", onClick = { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Back up…") }, onClick = { menu = false; onBackUp() })
                if (key.passwordsSaved) DropdownMenuItem(text = { Text("Forget password") }, onClick = { menu = false; onForgetPassword() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

private fun appAbi(state: ExportUiState): String = state.abi

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private const val MAX_KEYSTORE_BYTES = 1 shl 20

@Composable
private fun CompactButton(text: String, outlined: Boolean = false, onClick: () -> Unit) {
    if (outlined) {
        OutlinedButton(onClick = onClick, shape = MaterialTheme.shapes.small, contentPadding = CompactButtonPadding, modifier = Modifier.height(32.dp)) {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
        }
    } else {
        Button(onClick = onClick, shape = MaterialTheme.shapes.small, contentPadding = CompactButtonPadding, modifier = Modifier.height(32.dp)) {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
        }
    }
}

/** Approximates the launcher's round mask over the black icon background. */
@Composable
private fun IconPreview(file: File?, version: Int, fill: Boolean) {
    val bitmap = remember(file, version) { file?.let { BitmapFactory.decodeFile(it.path) }?.asImageBitmap() }
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap,
                contentDescription = "App icon",
                contentScale = if (fill) ContentScale.Crop else ContentScale.Fit,
                modifier = if (fill) Modifier.fillMaxSize() else Modifier.size(38.dp),
            )
        } else {
            Icon(Icons.Filled.Code, contentDescription = "Default icon", tint = Color(0xFF61AFEF), modifier = Modifier.size(28.dp))
        }
    }
}

private fun apkUri(context: Context, apk: File) =
    FileProvider.getUriForFile(context, "${context.packageName}.files", apk)

private fun installApk(context: Context, apk: File) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(apkUri(context, apk), "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun shareApk(context: Context, apk: File) {
    val type = if (apk.extension == "apk") "application/vnd.android.package-archive" else "application/octet-stream"
    val intent = Intent(Intent.ACTION_SEND)
        .setType(type)
        .putExtra(Intent.EXTRA_STREAM, apkUri(context, apk))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share ${apk.name}"))
}

/** Checkboxes for the common permissions plus free-form ones (full names, e.g. android.permission.NFC). */
@Composable
private fun PermissionsSection(permissions: List<String>, onChange: (List<String>) -> Unit) {
    var custom by remember { mutableStateOf("") }
    fun addCustom() {
        val name = custom.trim()
        if (name.isNotEmpty()) onChange((permissions + name).distinct())
        custom = ""
    }
    FormSection("Permissions") {
        AppPermission.entries.forEach { permission ->
            SwitchRow(
                title = permission.displayName,
                subtitle = if (permission.runtime) "${permission.description}. Asked when the app starts." else permission.description,
                checked = permission.isIn(permissions),
                onCheckedChange = { enabled -> onChange(AppPermission.toggle(permissions, permission, enabled)) },
            )
        }
        AppPermission.custom(permissions).forEach { name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                ToolbarIconButton(Icons.Filled.Close, "Remove $name", onClick = { onChange(permissions - name) })
            }
        }
        // The field has its label above it: align the button with the 36 dp input box, not the whole column.
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NfTextField(
                value = custom,
                onValueChange = { custom = it },
                label = "Other permission",
                placeholder = "android.permission.NFC",
                monospace = true,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { addCustom() }),
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.height(36.dp), contentAlignment = Alignment.Center) {
                CompactButton("Add", onClick = ::addCustom)
            }
        }
        Text(
            "None are declared by default. Camera, microphone and location are asked from the user when the app opens.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
