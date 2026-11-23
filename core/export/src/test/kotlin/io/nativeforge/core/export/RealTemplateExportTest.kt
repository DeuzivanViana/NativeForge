package io.nativeforge.core.export

import io.nativeforge.core.common.Outcome
import io.nativeforge.core.export.signing.ApksigSigningEngine
import io.nativeforge.core.export.signing.CertificateGenerator
import io.nativeforge.core.export.signing.DistinguishedName
import io.nativeforge.core.project.model.ExportSettings
import io.nativeforge.core.project.model.RuntimeKind
import io.nativeforge.core.toolchain.Abi
import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * Exports the real runtime template as an APK and an app bundle, for checks with
 * apksigner and bundletool. Runs only when NF_TEMPLATE_DIR points at the app's generated
 * `runtime/` assets folder and NF_PROGRAM at a built program; writes to NF_EXPORT_OUT.
 */
class RealTemplateExportTest {
    @Test
    fun `exports the real template in both formats`() {
        val templateDir = System.getenv("NF_TEMPLATE_DIR")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val program = File(requireNotNull(System.getenv("NF_PROGRAM")))
        val out = File(System.getenv("NF_EXPORT_OUT") ?: "build/real-export").apply { mkdirs() }
        val key = CertificateGenerator.generate(DistinguishedName("Test Upload Key"))
        for (format in ExportSettings.Format.entries) {
            val result = ApkExporter(ApksigSigningEngine()).export(
                ExportRequest(
                    templateApk = File(templateDir, "template.apk"),
                    bundleSkeleton = File(templateDir, "bundle-skeleton.zip"),
                    outputApk = File(out, "game.${format.extension}"),
                    settings = ExportSettings(appName = "Space Game", packageId = "com.example.space", versionCode = 3, versionName = "1.2", format = format),
                    binaries = listOf(AbiBinaries(Abi.ARM64_V8A, program, emptyList()), AbiBinaries(Abi.X86_64, program, emptyList())),
                    runtime = RuntimeKind.NATIVE_ACTIVITY,
                    signingKey = key,
                ),
            )
            assertIs<Outcome.Success<File>>(result, result.errorOrNull()?.message)
        }
    }
}
