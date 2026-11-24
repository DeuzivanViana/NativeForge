package io.nativeforge.core.export

import io.nativeforge.core.common.SafeFiles
import io.nativeforge.core.common.NfError
import io.nativeforge.core.common.Outcome
import io.nativeforge.core.common.outcomeOf
import io.nativeforge.core.export.axml.ManifestPatch
import io.nativeforge.core.export.axml.ManifestPatcher
import io.nativeforge.core.export.signing.ApkSigningEngine
import io.nativeforge.core.export.signing.SigningKey
import io.nativeforge.core.export.signing.SignatureScheme
import io.nativeforge.core.export.bundle.ProtoField
import io.nativeforge.core.export.bundle.ProtoManifestPatcher
import io.nativeforge.core.export.bundle.ProtoMessage
import io.nativeforge.core.project.ProjectJson
import io.nativeforge.core.project.model.ExportSettings
import io.nativeforge.core.project.model.RuntimeKind
import io.nativeforge.core.project.runtime.LaunchSpec
import io.nativeforge.core.toolchain.Abi
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** The program for one ABI: the executable or shared library and the libraries it loads. */
data class AbiBinaries(
    val abi: Abi,
    /** Executable (console) or shared library (graphical) produced by the build. */
    val program: File,
    /** Shared libraries to bundle and preload, in load order. */
    val runtimeLibraries: List<File>,
)

/** Everything needed to turn a built program into a standalone APK or app bundle. */
data class ExportRequest(
    /** Prebuilt runtime APK shipped inside the IDE (see the runtime-template module). */
    val templateApk: File,
    val outputApk: File,
    val settings: ExportSettings,
    /** One entry per ABI to include; the first one's library list is used for the launch spec. */
    val binaries: List<AbiBinaries>,
    val runtime: RuntimeKind,
    val arguments: List<String> = emptyList(),
    val signingKey: SigningKey,
    /** PNG for the adaptive icon foreground (432×432, artwork inside the central 66%), or null to keep the default. */
    val iconForegroundPng: ByteArray? = null,
    /** Project `assets/` folder bundled as APK assets under `assets/`, or null. */
    val assetsDirectory: File? = null,
    /**
     * For [ExportSettings.Format.AAB]: the runtime template's app bundle without `base/dex/` and
     * `base/lib/` (those are taken from [templateApk], where they are identical).
     */
    val bundleSkeleton: File? = null,
)

/**
 * Produces a standalone APK, or an Android App Bundle, from the runtime template:
 * 1. patches the manifest (package id, label, version...), binary XML for APKs and
 *    protocol buffer XML for bundles,
 * 2. replaces native libraries with the user's program and the dependencies it loads, per ABI,
 * 3. embeds a [LaunchSpec] telling the runtime what to start,
 * 4. signs the result (APK signature schemes, or a JAR signature for bundles).
 */
class ApkExporter(
    private val signingEngine: ApkSigningEngine,
    private val manifestPatcher: ManifestPatcher = ManifestPatcher(),
    private val protoManifestPatcher: ProtoManifestPatcher = ProtoManifestPatcher(),
) {

    fun export(request: ExportRequest): Outcome<File> {
        val problems = ExportValidator.validate(request.settings)
        if (problems.isNotEmpty()) return Outcome.Failure(NfError.Validation(problems.joinToString("\n")))
        if (request.binaries.isEmpty() || request.binaries.any { !it.program.isFile }) {
            return Outcome.Failure(NfError.NotFound("Build the project before exporting"))
        }
        if (request.binaries.map { it.abi }.distinct().size != request.binaries.size) {
            return Outcome.Failure(NfError.Validation("Each ABI can only be included once"))
        }
        val bundle = request.settings.format == ExportSettings.Format.AAB
        if (bundle && request.bundleSkeleton?.isFile != true) {
            return Outcome.Failure(NfError.NotFound("This NativeForge build has no app bundle template"))
        }

        return outcomeOf(errorFactory = { NfError.Io("Export failed: ${it.message}", it) }) {
            val trimmed = trimLibraries(request)
            request.outputApk.parentFile?.mkdirs()
            val unsigned = File(request.outputApk.path + ".unsigned")
            try {
                if (bundle) assembleBundle(trimmed, unsigned) else assemble(trimmed, unsigned)
                signingEngine.sign(
                    unsigned, request.outputApk, request.signingKey, MIN_SDK,
                    if (bundle) SignatureScheme.JAR else SignatureScheme.APK,
                )
            } finally {
                unsigned.delete()
            }
            request.outputApk
        }
    }

    /** Keeps only the libraries each program loads (see [RuntimeLibraries]). */
    private fun trimLibraries(request: ExportRequest): ExportRequest = request.copy(
        binaries = request.binaries.map { it.copy(runtimeLibraries = RuntimeLibraries.required(it.program, it.runtimeLibraries)) },
    )

    internal fun assemble(request: ExportRequest, destination: File) {
        val abiDirs = request.binaries.map { "lib/${it.abi.androidName}/" }
        ZipFile(request.templateApk).use { template ->
            ZipOutputStream(destination.outputStream().buffered()).use { out ->
                val written = HashSet<String>()
                template.entries().asSequence().forEach { entry ->
                    val name = entry.name
                    when {
                        name.startsWith("META-INF/") && isSignatureFile(name) -> Unit
                        name == "AndroidManifest.xml" ->
                            out.putBytes(name, manifestPatcher.patch(template.getInputStream(entry).readBytes(), manifestPatch(request)), false, written)
                        // Keep only the chosen ABIs' native libraries (the runtime shim).
                        name.startsWith("lib/") && abiDirs.none(name::startsWith) -> Unit
                        else -> out.copyTemplateEntry(template, entry, name, request, written)
                    }
                }
                writeProgramFiles(out, request, libraryPrefix = "lib/", assetsPrefix = "assets/", written)
            }
        }
    }

    /**
     * App bundle layout: the module's files live under `base/` (`manifest/`, `dex/`, `lib/`,
     * `assets/`, `res/`, `root/`, `resources.pb`), next to `BundleConfig.pb`.
     */
    internal fun assembleBundle(request: ExportRequest, destination: File) {
        val skeletonFile = requireNotNull(request.bundleSkeleton)
        val abiDirs = request.binaries.map { "lib/${it.abi.androidName}/" }
        ZipFile(skeletonFile).use { skeleton ->
            ZipFile(request.templateApk).use { template ->
                ZipOutputStream(destination.outputStream().buffered()).use { out ->
                    val written = HashSet<String>()
                    skeleton.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                        val name = entry.name
                        when {
                            name.startsWith("META-INF/") -> Unit
                            name == BUNDLE_MANIFEST -> out.putBytes(
                                name,
                                protoManifestPatcher.patch(skeleton.getInputStream(entry).readBytes(), manifestPatch(request)),
                                false,
                                written,
                            )
                            name.startsWith("base/dex/") || name.startsWith("base/lib/") -> Unit
                            name == BUNDLE_NATIVE_TARGETING -> out.putBytes(
                                name,
                                nativeTargeting(skeleton.getInputStream(entry).readBytes(), request.binaries.map { it.abi }),
                                false,
                                written,
                            )
                            else -> out.copyTemplateEntry(skeleton, entry, name, request, written)
                        }
                    }
                    // Dex files and the runtime shim are the same in the APK and the bundle.
                    template.entries().asSequence().forEach { entry ->
                        val name = entry.name
                        when {
                            DEX_FILE.matches(name) -> out.putBytes("base/dex/$name", template.getInputStream(entry).readBytes(), false, written)
                            name.startsWith("lib/") && abiDirs.any(name::startsWith) ->
                                out.putBytes("base/$name", template.getInputStream(entry).readBytes(), false, written)
                        }
                    }
                    writeProgramFiles(out, request, libraryPrefix = "base/lib/", assetsPrefix = "base/assets/", written)
                }
            }
        }
    }

    /**
     * `base/native.pb` lists each `lib/<abi>` directory of the bundle (NativeLibraries.directory = 1,
     * TargetedNativeDirectory.path = 1). bundletool rejects entries for ABIs that have no files.
     */
    private fun nativeTargeting(bytes: ByteArray, abis: List<Abi>): ByteArray {
        val keep = abis.map { "lib/${it.androidName}" }.toSet()
        val message = ProtoMessage.decode(bytes)
        message.fields.removeAll { field ->
            field.number == 1 && field is ProtoField.Bytes && ProtoMessage.decode(field.value).string(1) !in keep
        }
        return message.encode()
    }

    private fun writeProgramFiles(out: ZipOutputStream, request: ExportRequest, libraryPrefix: String, assetsPrefix: String, written: MutableSet<String>) {
        val stored = libraryPrefix == "lib/"
        request.binaries.forEach { binaries ->
            val dir = "$libraryPrefix${binaries.abi.androidName}/"
            out.putFile(dir + PROGRAM_LIBRARY, binaries.program, stored, written)
            binaries.runtimeLibraries.forEach { out.putFile(dir + it.name, it, stored, written) }
        }
        request.assetsDirectory?.takeIf { it.isDirectory }?.let { root ->
            // Links are skipped: a link could pull private app files into a shared APK.
            SafeFiles.regularFiles(root).forEach { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                out.putBytes("$assetsPrefix$BUNDLED_ASSETS_DIR/$relative", file.readBytes(), false, written)
            }
        }
        out.putBytes(
            "$assetsPrefix${LaunchSpec.ASSET_PATH}",
            ProjectJson.encodeToString(LaunchSpec.serializer(), launchSpec(request)).toByteArray(),
            false,
            written,
        )
    }

    private fun launchSpec(request: ExportRequest) = LaunchSpec(
        runtime = request.runtime,
        program = PROGRAM_LIBRARY,
        preloadLibraries = request.binaries.first().runtimeLibraries.map { it.name },
        arguments = request.arguments,
        relativeToNativeLibraryDir = true,
        orientation = request.settings.orientation,
        immersive = request.settings.immersive,
        keepScreenOn = request.settings.keepScreenOn,
    )

    private fun ZipOutputStream.copyTemplateEntry(source: ZipFile, entry: ZipEntry, name: String, request: ExportRequest, written: MutableSet<String>) {
        val icon = request.iconForegroundPng
        val bytes = if (icon != null && name.endsWith("/$ICON_FOREGROUND")) icon else source.getInputStream(entry).readBytes()
        putBytes(name, bytes, entry.method == ZipEntry.STORED, written)
    }

    private fun manifestPatch(request: ExportRequest) = ManifestPatch(
        packageName = request.settings.packageId,
        versionCode = request.settings.versionCode,
        versionName = request.settings.versionName,
        label = request.settings.appName,
        screenOrientation = request.settings.orientation.androidValue,
        oldPackageName = TEMPLATE_PACKAGE,
        permissions = request.settings.permissions,
    )

    private fun isSignatureFile(name: String): Boolean {
        val upper = name.uppercase()
        return upper == "META-INF/MANIFEST.MF" || upper.endsWith(".SF") || upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") || upper.endsWith(".EC")
    }

    private fun ZipOutputStream.putFile(name: String, file: File, stored: Boolean, written: MutableSet<String>) =
        putBytes(name, file.readBytes(), stored, written)

    /** Writes an entry unless one with that name was already written (first one wins). */
    private fun ZipOutputStream.putBytes(name: String, bytes: ByteArray, stored: Boolean, written: MutableSet<String>) {
        if (!written.add(name)) return
        val entry = ZipEntry(name)
        if (stored) {
            entry.method = ZipEntry.STORED
            entry.size = bytes.size.toLong()
            entry.compressedSize = bytes.size.toLong()
            entry.crc = CRC32().apply { update(bytes) }.value
        }
        putNextEntry(entry)
        write(bytes)
        closeEntry()
    }

    companion object {
        /** Application id of the runtime-template module; replaced on export. */
        const val TEMPLATE_PACKAGE = "io.nativeforge.runtime.template"

        /** Name under which the user's program is bundled in `lib/<abi>/`. */
        const val PROGRAM_LIBRARY = "libnf_program.so"

        const val MIN_SDK = 26

        /** File name of the launcher icon foreground in the runtime template (`res/drawable-nodpi`). */
        const val ICON_FOREGROUND = "nf_app_icon.png"

        /** Project assets land in `assets/assets/` (see [LaunchSpec.BUNDLED_ASSETS_DIR]). */
        const val BUNDLED_ASSETS_DIR = LaunchSpec.BUNDLED_ASSETS_DIR

        /** Manifest of the base module inside an app bundle (protocol buffer XML). */
        const val BUNDLE_MANIFEST = "base/manifest/AndroidManifest.xml"

        /** Native library targeting of the base module. */
        const val BUNDLE_NATIVE_TARGETING = "base/native.pb"

        private val DEX_FILE = Regex("classes\\d*\\.dex")
    }
}
