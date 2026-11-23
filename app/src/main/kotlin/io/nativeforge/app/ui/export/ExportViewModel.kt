package io.nativeforge.app.ui.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.nativeforge.app.di.AppContainer
import io.nativeforge.core.build.model.BuildEvent
import io.nativeforge.core.build.model.BuildRequest
import io.nativeforge.core.common.Outcome
import io.nativeforge.app.signing.KeystorePasswords
import io.nativeforge.app.signing.PasswordRequiredException
import io.nativeforge.app.signing.SavedKeystore
import io.nativeforge.core.export.AbiBinaries
import io.nativeforge.core.export.ApkExporter
import io.nativeforge.core.export.signing.DistinguishedName
import io.nativeforge.core.export.signing.KeystoreAlias
import io.nativeforge.core.export.signing.KeystoreException
import io.nativeforge.core.export.signing.Keystores
import io.nativeforge.core.export.ExportRequest
import io.nativeforge.core.export.ExportValidator
import io.nativeforge.core.project.model.BuildType
import io.nativeforge.core.project.model.ExportSettings
import io.nativeforge.core.project.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

sealed interface ExportStatus {
    data object Idle : ExportStatus
    data class Working(val step: String) : ExportStatus
    data class Done(val apk: File) : ExportStatus
    data class Failed(val message: String) : ExportStatus
}

data class ExportUiState(
    val project: Project? = null,
    val settings: ExportSettings? = null,
    /** Program arguments, space separated (stored in the project, used by IDE runs too). */
    val arguments: String = "",
    /** Files in the project's assets/ folder. */
    val assetCount: Int = 0,
    /** Changes whenever the icon file changes, so previews reload. */
    val iconVersion: Int = 0,
    val problems: List<String> = emptyList(),
    val status: ExportStatus = ExportStatus.Idle,
    /** Keystores saved in the IDE. */
    val keystores: List<SavedKeystore> = emptyList(),
    /** Set when the chosen key's password must be typed before exporting. */
    val passwordPrompt: SavedKeystore? = null,
    /** A keystore file being imported: its bytes and the keys found in it once unlocked. */
    val pendingImport: PendingImport? = null,
    /** Short message about key management (created, imported, backed up...). */
    val keyMessage: String? = null,
    /** ABI the program is built for (this device's). */
    val abi: String = "",
)

/** A picked keystore file waiting for its password and alias. */
class PendingImport(val bytes: ByteArray, val fileName: String, val aliases: List<KeystoreAlias>? = null)

class ExportViewModel(private val container: AppContainer, projectName: String) : ViewModel() {
    private val _state = MutableStateFlow(ExportUiState(abi = container.abi.androidName))
    val state: StateFlow<ExportUiState> = _state.asStateFlow()
    private var saveJob: Job? = null

    private val keys = container.signingKeys

    init {
        viewModelScope.launch { keys.keystores.collect { list -> _state.update { it.copy(keystores = list) } } }
        viewModelScope.launch {
            val project = container.projectRepository.open(projectName).getOrNull()
            val assets = project?.let { assetCount(it) } ?: 0
            _state.update {
                it.copy(
                    project = project,
                    settings = project?.config?.export,
                    arguments = project?.config?.runArguments?.joinToString(" ").orEmpty(),
                    assetCount = assets,
                )
            }
        }
    }

    fun update(transform: (ExportSettings) -> ExportSettings) {
        val settings = _state.value.settings?.let(transform) ?: return
        _state.update { it.copy(settings = settings, problems = ExportValidator.validate(settings)) }
        scheduleSave()
    }

    fun setArguments(text: String) {
        _state.update { it.copy(arguments = text) }
        scheduleSave()
    }

    /** Copies the picked image into the project as icon.png (at most 512 px) and uses it as the launcher icon. */
    fun importIcon(open: () -> InputStream?) {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val source = open()?.use(BitmapFactory::decodeStream) ?: error("Not an image")
                    val scale = minOf(1f, ICON_SOURCE_MAX / maxOf(source.width, source.height).toFloat())
                    val bitmap = if (scale < 1f) {
                        Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true)
                    } else {
                        source
                    }
                    File(project.root, ICON_FILE).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
            if (saved.isSuccess) {
                update { it.copy(iconPath = ICON_FILE) }
                _state.update { it.copy(iconVersion = it.iconVersion + 1) }
            } else {
                _state.update { it.copy(status = ExportStatus.Failed("Could not use that image: ${saved.exceptionOrNull()?.message}")) }
            }
        }
    }

    fun clearIcon() = update { it.copy(iconPath = null) }

    /** The icon file, when the project has a custom one. */
    fun iconFile(): File? {
        val project = _state.value.project ?: return null
        return _state.value.settings?.iconPath
            ?.let { io.nativeforge.core.common.SafeFiles.resolveInside(project.root, it) }
            ?.takeIf { it.isFile && !io.nativeforge.core.common.SafeFiles.isSymlink(it) }
    }

    /**
     * Builds, then assembles and signs the APK or bundle. [passwords] come from the password
     * dialog when the chosen key's passwords are not saved; [remember] saves them.
     */
    fun export(passwords: KeystorePasswords? = null, remember: Boolean = false) {
        val project = _state.value.project ?: return
        val settings = _state.value.settings ?: return
        val problems = ExportValidator.validate(settings)
        if (problems.isNotEmpty()) return _state.update { it.copy(problems = problems) }

        viewModelScope.launch {
            // Open the key first, so a wrong password does not cost a build.
            val signingKey = try {
                withContext(Dispatchers.IO) {
                    val id = settings.signingKeyId
                    if (id == null) {
                        container.signingKeyStore.loadOrCreate()
                    } else {
                        keys.load(id, passwords).also { if (passwords != null && remember) keys.rememberPasswords(id, passwords) }
                    }
                }
            } catch (e: PasswordRequiredException) {
                _state.update { it.copy(passwordPrompt = e.keystore, status = ExportStatus.Idle) }
                return@launch
            } catch (e: KeystoreException) {
                val entry = keys.find(settings.signingKeyId)
                // Saved passwords that no longer work (or a typo in the dialog): ask again.
                _state.update {
                    it.copy(
                        passwordPrompt = entry,
                        status = ExportStatus.Failed(e.message ?: "Could not open the signing key"),
                    )
                }
                return@launch
            }
            _state.update { it.copy(passwordPrompt = null) }
            saveJob?.cancel()
            val current = save() ?: project
            val buildType = if (settings.releaseBuild) BuildType.RELEASE else BuildType.DEBUG

            _state.update { it.copy(status = ExportStatus.Working(if (settings.releaseBuild) "Building release…" else "Building debug…")) }
            val request = BuildRequest(
                project = current,
                toolchain = container.toolchain,
                buildType = buildType,
                dependencies = container.packageManager.nativeDependencies(current.config.packages),
            )
            var finished: BuildEvent.Finished? = null
            container.buildService.build(request).collect { if (it is BuildEvent.Finished) finished = it }
            val artifact = finished?.result?.artifact
            if (artifact == null) {
                _state.update { it.copy(status = ExportStatus.Failed("The build failed. Build the project in the editor to see the errors.")) }
                return@launch
            }

            _state.update { it.copy(status = ExportStatus.Working(if (settings.format == ExportSettings.Format.AAB) "Packaging the app bundle and signing…" else "Packaging and signing…")) }
            val result = withContext(Dispatchers.IO) {
                val icon = iconFile()?.let { iconForeground(it, settings.iconFill) }
                val assets = File(current.root, ApkExporter.BUNDLED_ASSETS_DIR).takeIf { settings.bundleAssets && it.isDirectory }
                val bundle = settings.format == ExportSettings.Format.AAB
                container.apkExporter.export(
                    ExportRequest(
                        templateApk = container.runtimeTemplateApk(),
                        bundleSkeleton = if (bundle) container.runtimeBundleSkeleton() else null,
                        outputApk = File(container.exportsDirectory, "${settings.packageId}-${settings.versionName}.${settings.format.extension}"),
                        settings = settings,
                        binaries = listOf(AbiBinaries(container.abi, artifact.file, artifact.runtimeLibraries)),
                        runtime = current.config.runtime,
                        arguments = current.config.runArguments,
                        signingKey = signingKey,
                        iconForegroundPng = icon,
                        assetsDirectory = assets,
                    ),
                )
            }
            _state.update {
                it.copy(
                    status = when (result) {
                        is Outcome.Success -> ExportStatus.Done(result.value)
                        is Outcome.Failure -> ExportStatus.Failed(result.error.message)
                    },
                )
            }
        }
    }

    fun dismissPasswordPrompt() = _state.update { it.copy(passwordPrompt = null) }

    fun selectKey(id: String?) = update { it.copy(signingKeyId = id) }

    fun clearKeyMessage() = _state.update { it.copy(keyMessage = null) }

    /** Creates a keystore and selects it. Returns an error message, or null on success. */
    suspend fun createKey(
        name: String,
        alias: String,
        password: CharArray,
        owner: DistinguishedName,
        validityYears: Int,
        remember: Boolean,
    ): String? = keyAction {
        val entry = keys.create(name, alias, password, owner, validityYears, remember)
        selectKey(entry.id)
        _state.update { it.copy(keyMessage = "Created '${entry.name}'. Back it up now: without this file you cannot publish updates.") }
    }

    fun startImport(bytes: ByteArray, fileName: String) {
        val type = runCatching { Keystores.detectType(bytes) }
        if (type.getOrNull() == null) {
            val message = type.exceptionOrNull()?.message ?: "That file is not a keystore (.jks, .keystore, .p12, .pfx or .bks)."
            _state.update { it.copy(keyMessage = message) }
            return
        }
        _state.update { it.copy(pendingImport = PendingImport(bytes, fileName)) }
    }

    fun cancelImport() = _state.update { it.copy(pendingImport = null) }

    /** Step 1 of an import: unlock the file and list its keys. */
    suspend fun unlockImport(storePassword: CharArray): String? {
        val pending = _state.value.pendingImport ?: return null
        return keyAction {
            val aliases = keys.aliases(pending.bytes, storePassword)
            if (aliases.isEmpty()) throw KeystoreException("This keystore has no private keys")
            _state.update { it.copy(pendingImport = PendingImport(pending.bytes, pending.fileName, aliases)) }
        }
    }

    /** Step 2: save the chosen key and select it. */
    suspend fun finishImport(name: String, alias: String, passwords: KeystorePasswords, remember: Boolean): String? {
        val pending = _state.value.pendingImport ?: return null
        return keyAction {
            val entry = keys.import(name, pending.bytes, alias, passwords, remember)
            selectKey(entry.id)
            _state.update { it.copy(pendingImport = null, keyMessage = "Imported '${entry.name}'.") }
        }
    }

    fun backUpKey(id: String, open: () -> java.io.OutputStream?) {
        viewModelScope.launch {
            val error = keyAction { open()?.use { keys.backUp(id, it) } ?: error("Could not write the file") }
            _state.update { it.copy(keyMessage = error ?: "Key saved. Keep the file and its password somewhere safe.") }
        }
    }

    fun forgetPasswords(id: String) = keys.forgetPasswords(id)

    fun deleteKey(id: String) {
        keys.delete(id)
        if (_state.value.settings?.signingKeyId == id) selectKey(null)
    }

    fun keyFileName(entry: SavedKeystore) = keys.fileName(entry)

    /** Copies the exported file to a document the user picked (Downloads, Drive...). */
    fun saveCopy(file: File, open: () -> java.io.OutputStream?) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { open()?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("Could not write the file") } }
            _state.update { it.copy(keyMessage = if (result.isSuccess) "Saved ${file.name}." else "Could not save: ${result.exceptionOrNull()?.message}") }
        }
    }

    private suspend fun keyAction(block: () -> Unit): String? = withContext(Dispatchers.IO) {
        try {
            block()
            null
        } catch (e: KeystoreException) {
            e.message
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            save()
        }
    }

    /** Writes the export settings and arguments to nativeforge.json. */
    private suspend fun save(): Project? {
        val state = _state.value
        val project = state.project ?: return null
        val settings = state.settings ?: return null
        val arguments = state.arguments.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val saved = container.projectRepository.saveConfig(project, project.config.copy(export = settings, runArguments = arguments))
        return saved.getOrNull()?.also { updated -> _state.update { it.copy(project = updated) } }
    }

    private fun assetCount(project: Project): Int =
        io.nativeforge.core.common.SafeFiles.regularFiles(File(project.root, ApkExporter.BUNDLED_ASSETS_DIR)).size

    /**
     * Adaptive icon foreground: 432 px (108 dp at xxxhdpi). With [fill] the image covers
     * the whole layer (the launcher's mask crops the corners); otherwise it fits inside
     * the 72 dp area that is always visible.
     */
    private fun iconForeground(file: File, fill: Boolean): ByteArray? {
        val source = BitmapFactory.decodeFile(file.path) ?: return null
        val size = ICON_LAYER_PX
        val target = if (fill) size.toFloat() else size * 72f / 108f
        val scale = if (fill) {
            maxOf(target / source.width, target / source.height)
        } else {
            minOf(target / source.width, target / source.height)
        }
        val width = source.width * scale
        val height = source.height * scale
        val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(layer).drawBitmap(
            source,
            null,
            RectF((size - width) / 2, (size - height) / 2, (size + width) / 2, (size + height) / 2),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        return ByteArrayOutputStream().also { layer.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    override fun onCleared() {
        // Keep edits made just before leaving the screen.
        if (saveJob?.isActive == true) {
            saveJob?.cancel()
            kotlinx.coroutines.runBlocking { save() }
        }
    }

    private companion object {
        const val ICON_FILE = "icon.png"
        const val ICON_SOURCE_MAX = 512f
        const val ICON_LAYER_PX = 432
    }
}
