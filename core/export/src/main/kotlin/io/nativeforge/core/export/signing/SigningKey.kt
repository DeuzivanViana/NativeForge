package io.nativeforge.core.export.signing

import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate

/** A private key and its certificate chain (signing certificate first), used to sign exported apps. */
class SigningKey(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
    val certificateChain: List<X509Certificate> = listOf(certificate),
) {
    /** SHA-256 of the signing certificate, as `apksigner` and the Play Console show it ("AB:CD:..."). */
    val sha256Fingerprint: String get() = fingerprint(certificate)

    companion object {
        fun fingerprint(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it) }
    }
}

/**
 * The IDE's own signing key: a PKCS#12 keystore generated on first use. Keeping the
 * same key across exports lets users install updates of their exported apps without
 * uninstalling them first.
 */
class SigningKeyStore(
    private val file: File,
    private val password: CharArray,
    private val alias: String = "nativeforge",
) {
    fun loadOrCreate(commonName: String = "NativeForge User"): SigningKey {
        if (file.isFile) load()?.let { return it }
        val key = CertificateGenerator.generate(DistinguishedName(commonName = commonName, organization = "NativeForge"))
        file.parentFile?.mkdirs()
        file.outputStream().use { Keystores.writePkcs12(it, key, alias, password) }
        return key
    }

    private fun load(): SigningKey? {
        val store = KeyStore.getInstance("PKCS12")
        file.inputStream().use { store.load(it, password) }
        val key = store.getKey(alias, password) as? PrivateKey ?: return null
        val certificate = store.getCertificate(alias) as? X509Certificate ?: return null
        return SigningKey(key, certificate)
    }
}
