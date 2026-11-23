package io.nativeforge.core.export.signing

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.UnrecoverableKeyException
import java.security.cert.X509Certificate

/** Keystore file formats the IDE reads. Keys it creates are always PKCS#12. */
enum class KeystoreType(val displayName: String) {
    PKCS12("PKCS#12"),
    JKS("JKS"),
    BKS("BKS"),
}

/** A key entry found in a keystore, before its key password is known. */
data class KeystoreAlias(val alias: String, val certificate: X509Certificate?) {
    val fingerprint: String? get() = certificate?.let(SigningKey::fingerprint)
}

/** Why a keystore could not be opened; [message] is shown to the user. */
open class KeystoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Reads keystores made by keytool, Android Studio or other tools, and writes PKCS#12 ones. */
object Keystores {

    fun detectType(bytes: ByteArray): KeystoreType? = when {
        bytes.size < 4 -> null
        bytes.startsWith(JKS_MAGIC) -> KeystoreType.JKS
        bytes.startsWith(JCEKS_MAGIC) -> throw KeystoreException("JCEKS keystores are not supported. Convert it with: keytool -importkeystore -deststoretype PKCS12")
        // PKCS#12 is a DER SEQUENCE; BKS starts with a 4-byte big-endian version (1 or 2).
        bytes[0] == 0x30.toByte() -> KeystoreType.PKCS12
        bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 0.toByte() && bytes[3].toInt() in 1..2 -> KeystoreType.BKS
        else -> null
    }

    /** Like [detectType], but null instead of an exception for unsupported formats. */
    fun detectTypeOrNull(bytes: ByteArray): KeystoreType? = runCatching { detectType(bytes) }.getOrNull()

    /** Lists the key entries of a keystore. Throws [KeystoreException] with a readable message. */
    fun aliases(bytes: ByteArray, storePassword: CharArray): List<KeystoreAlias> = when (type(bytes)) {
        KeystoreType.JKS -> JksKeystore.read(bytes, storePassword).entries.map { KeystoreAlias(it.alias, it.chain.firstOrNull()) }
        else -> {
            val store = openJca(bytes, storePassword)
            store.aliases().toList().filter { store.isKeyEntry(it) }.map { KeystoreAlias(it, store.getCertificate(it) as? X509Certificate) }
        }
    }

    /** Loads [alias] from a keystore. A null [keyPassword] means the same as [storePassword]. */
    fun load(bytes: ByteArray, storePassword: CharArray, alias: String, keyPassword: CharArray? = null): SigningKey {
        val password = keyPassword ?: storePassword
        return when (type(bytes)) {
            KeystoreType.JKS -> {
                val entry = JksKeystore.read(bytes, storePassword).entries.firstOrNull { it.alias.equals(alias, ignoreCase = true) }
                    ?: throw KeystoreException("The keystore has no key named '$alias'")
                val key = JksKeystore.decryptKey(entry, password)
                SigningKey(key, entry.chain.first(), entry.chain)
            }
            else -> {
                val store = openJca(bytes, storePassword)
                val key = try {
                    store.getKey(alias, password) as? PrivateKey
                } catch (e: UnrecoverableKeyException) {
                    throw KeystoreException("Wrong key password for '$alias'", e)
                } ?: throw KeystoreException("The keystore has no private key named '$alias'")
                val chain = store.getCertificateChain(alias)?.mapNotNull { it as? X509Certificate }.orEmpty()
                if (chain.isEmpty()) throw KeystoreException("The key '$alias' has no certificate")
                SigningKey(key, chain.first(), chain)
            }
        }
    }

    /** Writes [key] as a PKCS#12 keystore, readable by keytool, Android Studio and Gradle. */
    fun writePkcs12(out: OutputStream, key: SigningKey, alias: String, password: CharArray) {
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        store.setKeyEntry(alias, key.privateKey, password, key.certificateChain.toTypedArray())
        store.store(out, password)
    }

    private fun type(bytes: ByteArray): KeystoreType =
        detectType(bytes) ?: throw KeystoreException("This file is not a keystore (PKCS#12, JKS or BKS)")

    /**
     * PKCS#12 and BKS through the platform provider first, then Bouncy Castle, which reads
     * newer PKCS#12 files (AES encryption, SHA-256 MAC) that older Android versions cannot.
     */
    private fun openJca(bytes: ByteArray, password: CharArray): KeyStore {
        val type = type(bytes)
        val attempts = buildList<() -> KeyStore> {
            if (type == KeystoreType.PKCS12) add { KeyStore.getInstance("PKCS12") }
            add { KeyStore.getInstance(type.name, BouncyCastleProvider()) }
        }
        var failure: Exception? = null
        for (create in attempts) {
            try {
                return create().apply { load(ByteArrayInputStream(bytes), password) }
            } catch (e: IOException) {
                // Wrong password shows up as an IOException caused by UnrecoverableKeyException.
                if (e.cause is UnrecoverableKeyException || e.message?.contains("password", ignoreCase = true) == true) {
                    throw KeystoreException("Wrong keystore password", e)
                }
                failure = e
            } catch (e: Exception) {
                failure = e
            }
        }
        throw KeystoreException("Could not open the keystore: ${failure?.message ?: "unknown error"}", failure)
    }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private val JKS_MAGIC = byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFE.toByte(), 0xED.toByte())
    private val JCEKS_MAGIC = byteArrayOf(0xCE.toByte(), 0xCE.toByte(), 0xCE.toByte(), 0xCE.toByte())
}
