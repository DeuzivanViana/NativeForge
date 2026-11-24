package io.nativeforge.core.remote.security

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/**
 * The server's TLS key: an ECDSA P-256 key with a self-signed certificate, created on the
 * device on first use and kept in private storage. Browsers cannot verify it against a CA,
 * so the user compares [fingerprint] (shown by the app) with the one the browser shows.
 */
class TlsIdentity private constructor(val privateKey: PrivateKey, val certificate: X509Certificate) {

    /** SHA-256 of the certificate, as colon-separated upper-case hex (what browsers display). */
    val fingerprint: String get() = fingerprintOf(certificate)

    /** First 8 bytes of [fingerprint]: enough to spot a substitute certificate at a glance. */
    val shortFingerprint: String get() = fingerprint.split(':').take(8).joinToString(":")

    /** An HTTPS server socket on [port] (0 = any) that only speaks TLS 1.2 and 1.3. */
    fun serverSocket(port: Int, bindAddress: InetAddress?, backlog: Int = 50): ServerSocket {
        val store = KeyStore.getInstance(KeyStore.getDefaultType().let { if (it.equals("jks", true)) "PKCS12" else it }).apply {
            load(null, null)
            setKeyEntry(ALIAS, privateKey, PASSWORD, arrayOf(certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, PASSWORD) }.keyManagers
        val context = SSLContext.getInstance("TLS").apply { init(keyManagers, null, SecureRandom()) }
        val socket = context.serverSocketFactory.createServerSocket(port, backlog, bindAddress) as SSLServerSocket
        socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        socket.needClientAuth = false
        socket.reuseAddress = true
        return socket
    }

    companion object {
        private const val ALIAS = "nativeforge-remote"
        private val PASSWORD = "nativeforge-remote".toCharArray()

        /** Validity of a new certificate; a new one is made when less than 30 days remain. */
        private const val VALIDITY_DAYS = 825L

        /**
         * Loads the identity stored in [file] (PKCS#12), or creates and stores a new one when the
         * file is missing, unreadable or the certificate is about to expire.
         */
        fun loadOrCreate(file: File): TlsIdentity {
            load(file)?.takeIf { it.certificate.notAfter.time - System.currentTimeMillis() > TimeUnit.DAYS.toMillis(30) }?.let { return it }
            val identity = generate()
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { out ->
                KeyStore.getInstance("PKCS12").apply {
                    load(null, null)
                    setKeyEntry(ALIAS, identity.privateKey, PASSWORD, arrayOf(identity.certificate))
                }.store(out, PASSWORD)
            }
            if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
            return identity
        }

        /** Deletes the stored key, so the next start creates a new certificate (and fingerprint). */
        fun reset(file: File) { file.delete() }

        private fun load(file: File): TlsIdentity? = runCatching {
            if (!file.isFile) return null
            val store = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, PASSWORD) } }
            val key = store.getKey(ALIAS, PASSWORD) as PrivateKey
            val cert = store.getCertificate(ALIAS) as X509Certificate
            TlsIdentity(key, cert)
        }.getOrNull()

        fun generate(): TlsIdentity {
            val random = SecureRandom()
            val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
            // An explicit provider instance: Android ships a trimmed "BC" provider under the same name.
            val provider = BouncyCastleProvider()
            val subject = X500Name("CN=NativeForge on this device")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger(127, random).add(BigInteger.ONE),
                Date(now - TimeUnit.DAYS.toMillis(1)),
                Date(now + TimeUnit.DAYS.toMillis(VALIDITY_DAYS)),
                subject,
                keyPair.public,
            )
                .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
                .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
                .addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
            val holder = builder.build(JcaContentSignerBuilder("SHA256withECDSA").setProvider(provider).build(keyPair.private))
            val certificate = JcaX509CertificateConverter().setProvider(provider).getCertificate(holder)
            return TlsIdentity(keyPair.private, certificate)
        }

        fun fingerprintOf(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it) }
    }
}
