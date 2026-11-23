package io.nativeforge.core.export

import io.nativeforge.core.export.signing.CertificateGenerator
import io.nativeforge.core.export.signing.DistinguishedName
import io.nativeforge.core.export.signing.KeystoreException
import io.nativeforge.core.export.signing.KeystoreType
import io.nativeforge.core.export.signing.Keystores
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KeystoresTest {
    private val key = CertificateGenerator.generate(DistinguishedName("Ana Dev", organization = "Example", country = "br"))

    private fun write(type: String, storePassword: String, keyPassword: String, provider: java.security.Provider? = null): ByteArray {
        val store = (if (provider != null) KeyStore.getInstance(type, provider) else KeyStore.getInstance(type)).apply { load(null, null) }
        store.setKeyEntry("upload", key.privateKey, keyPassword.toCharArray(), arrayOf(key.certificate))
        return ByteArrayOutputStream().also { store.store(it, storePassword.toCharArray()) }.toByteArray()
    }

    @Test
    fun `generated certificate carries the owner and a long validity`() {
        val subject = key.certificate.subjectX500Principal.name
        assertTrue("CN=Ana Dev" in subject && "O=Example" in subject && "C=BR" in subject, subject)
        val years = (key.certificate.notAfter.time - key.certificate.notBefore.time) / (365.25 * 24 * 3600 * 1000)
        assertTrue(years >= 29.9, "validity $years")
        assertEquals(95, key.sha256Fingerprint.length)
    }

    @Test
    fun `reads JKS keystores with separate store and key passwords`() {
        val bytes = write("JKS", "storepass", "keypass")
        assertEquals(KeystoreType.JKS, Keystores.detectType(bytes))
        assertEquals(listOf("upload"), Keystores.aliases(bytes, "storepass".toCharArray()).map { it.alias })
        val loaded = Keystores.load(bytes, "storepass".toCharArray(), "upload", "keypass".toCharArray())
        assertEquals(key.privateKey, loaded.privateKey)
        assertEquals(key.sha256Fingerprint, loaded.sha256Fingerprint)
        assertFailsWith<KeystoreException> { Keystores.aliases(bytes, "wrong".toCharArray()) }
        assertFailsWith<KeystoreException> { Keystores.load(bytes, "storepass".toCharArray(), "upload", "wrong".toCharArray()) }
    }

    @Test
    fun `reads PKCS12 from keytool and from Bouncy Castle, and BKS`() {
        for (bytes in listOf(write("PKCS12", "pw123456", "pw123456"), write("PKCS12", "pw123456", "pw123456", BouncyCastleProvider()))) {
            assertEquals(KeystoreType.PKCS12, Keystores.detectType(bytes))
            assertEquals(key.privateKey, Keystores.load(bytes, "pw123456".toCharArray(), "upload").privateKey)
            assertFailsWith<KeystoreException> { Keystores.aliases(bytes, "nope".toCharArray()) }
        }
        val bks = write("BKS", "bkspass", "bkspass", BouncyCastleProvider())
        assertEquals(KeystoreType.BKS, Keystores.detectType(bks))
        assertEquals(key.privateKey, Keystores.load(bks, "bkspass".toCharArray(), "upload").privateKey)
    }

    @Test
    fun `written PKCS12 keystores load again`() {
        val bytes = ByteArrayOutputStream().also { Keystores.writePkcs12(it, key, "release", "secret1".toCharArray()) }.toByteArray()
        assertEquals(listOf("release"), Keystores.aliases(bytes, "secret1".toCharArray()).map { it.alias })
        assertEquals(key.certificate, Keystores.load(bytes, "secret1".toCharArray(), "release").certificate)
    }

    @Test
    fun `other files are rejected`() {
        assertFailsWith<KeystoreException> { Keystores.aliases("hello world".toByteArray(), "x".toCharArray()) }
    }
}
