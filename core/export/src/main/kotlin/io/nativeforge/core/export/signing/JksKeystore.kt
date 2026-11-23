package io.nativeforge.core.export.signing

import org.bouncycastle.asn1.pkcs.EncryptedPrivateKeyInfo
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Reader for Java's JKS keystore format (keytool's default before Java 9 and what older
 * Android Studio versions created). Android has no JKS provider, so the format and Sun's
 * key protection algorithm are implemented here.
 */
internal object JksKeystore {

    class Entry(val alias: String, val protectedKey: ByteArray, val chain: List<X509Certificate>)

    class Contents(val entries: List<Entry>)

    fun read(bytes: ByteArray, storePassword: CharArray): Contents {
        try {
            verifyIntegrity(bytes, storePassword)
            val input = DataInputStream(ByteArrayInputStream(bytes, 0, bytes.size - DIGEST_LENGTH))
            require(input.readInt() == MAGIC) { "Not a JKS keystore" }
            val version = input.readInt()
            require(version == 1 || version == 2) { "Unsupported JKS version $version" }
            val count = input.readInt()
            require(count in 0..10_000) { "Corrupt JKS keystore" }
            val certificates = CertificateFactory.getInstance("X.509")
            val entries = mutableListOf<Entry>()
            repeat(count) {
                val tag = input.readInt()
                val alias = input.readUTF()
                input.readLong() // creation date
                when (tag) {
                    TAG_PRIVATE_KEY -> {
                        val key = ByteArray(input.readInt().also { require(it in 1..1_000_000) }).also(input::readFully)
                        val chain = List(input.readInt().also { require(it in 0..100) }) {
                            if (version == 2) input.readUTF()
                            val der = ByteArray(input.readInt().also { size -> require(size in 1..1_000_000) }).also(input::readFully)
                            certificates.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
                        }
                        if (chain.isNotEmpty()) entries += Entry(alias, key, chain)
                    }
                    TAG_TRUSTED_CERTIFICATE -> {
                        if (version == 2) input.readUTF()
                        input.skipBytes(input.readInt())
                    }
                    else -> throw IllegalArgumentException("Corrupt JKS keystore (entry type $tag)")
                }
            }
            return Contents(entries)
        } catch (e: KeystoreException) {
            throw e
        } catch (e: Exception) {
            throw KeystoreException("Could not read the JKS keystore: ${e.message}", e)
        }
    }

    /** Decrypts a key protected with Sun's JKS algorithm (OID 1.3.6.1.4.1.42.2.17.1.1). */
    fun decryptKey(entry: Entry, keyPassword: CharArray): PrivateKey {
        val info = EncryptedPrivateKeyInfo.getInstance(entry.protectedKey)
        require(info.encryptionAlgorithm.algorithm.id == SUN_KEY_PROTECTOR_OID) {
            "Unsupported key protection ${info.encryptionAlgorithm.algorithm.id}"
        }
        val data = info.encryptedData
        require(data.size > 2 * DIGEST_LENGTH) { "Corrupt JKS key" }
        val password = passwordBytes(keyPassword)
        val salt = data.copyOfRange(0, DIGEST_LENGTH)
        val encrypted = data.copyOfRange(DIGEST_LENGTH, data.size - DIGEST_LENGTH)
        val check = data.copyOfRange(data.size - DIGEST_LENGTH, data.size)

        val sha1 = MessageDigest.getInstance("SHA-1")
        val plain = ByteArray(encrypted.size)
        var digest = salt
        var offset = 0
        while (offset < encrypted.size) {
            sha1.update(password)
            sha1.update(digest)
            digest = sha1.digest()
            for (i in digest.indices) {
                if (offset + i >= encrypted.size) break
                plain[offset + i] = (encrypted[offset + i].toInt() xor digest[i].toInt()).toByte()
            }
            offset += digest.size
        }
        sha1.update(password)
        sha1.update(plain)
        if (!MessageDigest.isEqual(sha1.digest(), check)) throw KeystoreException("Wrong key password for '${entry.alias}'")

        val algorithm = when (PrivateKeyInfo.getInstance(plain).privateKeyAlgorithm.algorithm.id) {
            "1.2.840.113549.1.1.1" -> "RSA"
            "1.2.840.10045.2.1" -> "EC"
            "1.2.840.10040.4.1" -> "DSA"
            else -> throw KeystoreException("Unsupported key type in '${entry.alias}'")
        }
        return KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(plain))
    }

    /** The file ends with SHA-1(password as UTF-16BE, "Mighty Aphrodite", contents). */
    private fun verifyIntegrity(bytes: ByteArray, password: CharArray) {
        if (bytes.size < 12 + DIGEST_LENGTH) throw KeystoreException("The JKS keystore is truncated")
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(passwordBytes(password))
        sha1.update("Mighty Aphrodite".toByteArray(Charsets.UTF_8))
        sha1.update(bytes, 0, bytes.size - DIGEST_LENGTH)
        val expected = bytes.copyOfRange(bytes.size - DIGEST_LENGTH, bytes.size)
        if (!MessageDigest.isEqual(sha1.digest(), expected)) throw KeystoreException("Wrong keystore password")
    }

    private fun passwordBytes(password: CharArray): ByteArray =
        ByteArray(password.size * 2).also { out ->
            password.forEachIndexed { i, c ->
                out[i * 2] = (c.code shr 8).toByte()
                out[i * 2 + 1] = c.code.toByte()
            }
        }

    private const val MAGIC = 0xFEEDFEED.toInt()
    private const val TAG_PRIVATE_KEY = 1
    private const val TAG_TRUSTED_CERTIFICATE = 2
    private const val DIGEST_LENGTH = 20
    private const val SUN_KEY_PROTECTOR_OID = "1.3.6.1.4.1.42.2.17.1.1"
}
