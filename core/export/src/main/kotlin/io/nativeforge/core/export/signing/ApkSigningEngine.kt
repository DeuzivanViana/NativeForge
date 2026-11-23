package io.nativeforge.core.export.signing

import com.android.apksig.ApkSigner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** How a package is signed. */
enum class SignatureScheme {
    /** APK signature schemes v1, v2 and v3, for APKs that are installed directly. */
    APK,

    /** JAR signature (v1) only, which is what app bundles uploaded to Google Play use. */
    JAR,
}

/** Signs a package. Abstracted so the export pipeline can be tested without real signing. */
fun interface ApkSigningEngine {
    fun sign(unsignedApk: File, outputApk: File, key: SigningKey, minSdkVersion: Int, scheme: SignatureScheme)
}

/**
 * Signs with Google's apksig. apksig also aligns uncompressed entries (4 bytes, native
 * libraries to page size), so no separate zipalign step is needed.
 */
class ApksigSigningEngine : ApkSigningEngine {
    override fun sign(unsignedApk: File, outputApk: File, key: SigningKey, minSdkVersion: Int, scheme: SignatureScheme) {
        val signer = ApkSigner.SignerConfig.Builder(SIGNER_NAME, key.privateKey, key.certificateChain).build()
        val fullApk = scheme == SignatureScheme.APK
        val signed = if (fullApk) outputApk else File(outputApk.path + ".jar")
        ApkSigner.Builder(listOf(signer))
            .setInputApk(unsignedApk)
            .setOutputApk(signed)
            .setMinSdkVersion(minSdkVersion)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(fullApk)
            .setV3SigningEnabled(fullApk)
            .setV4SigningEnabled(false)
            .build()
            .sign()
        if (!fullApk) {
            try {
                manifestFirst(signed, outputApk)
            } finally {
                signed.delete()
            }
        }
    }

    /**
     * apksig appends the JAR signature files at the end. `JarInputStream` (and so some
     * upload checks) only finds them when META-INF/MANIFEST.MF comes first, as jarsigner
     * writes it. Entry order is not covered by a JAR signature, so this keeps it valid.
     */
    private fun manifestFirst(input: File, output: File) {
        ZipFile(input).use { zip ->
            val entries = zip.entries().asSequence().toList()
            val (signature, rest) = entries.partition { it.name.uppercase().startsWith("META-INF/") && it.name.indexOf('/', 9) < 0 }
            val ordered = signature.sortedBy { if (it.name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) 0 else 1 } + rest
            ZipOutputStream(output.outputStream().buffered()).use { out ->
                ordered.forEach { entry ->
                    val copy = ZipEntry(entry.name)
                    if (entry.method == ZipEntry.STORED) {
                        copy.method = ZipEntry.STORED
                        copy.size = entry.size
                        copy.compressedSize = entry.size
                        copy.crc = entry.crc
                    }
                    out.putNextEntry(copy)
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
    }

    private companion object {
        /** Base name of the META-INF signature files, as jarsigner and Android Studio use. */
        const val SIGNER_NAME = "CERT"
    }
}
