package io.nativeforge.core.export.signing

import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Date
import java.util.concurrent.TimeUnit

/** Certificate owner, as keytool asks for it. Blank fields are left out. */
data class DistinguishedName(
    val commonName: String,
    val organizationalUnit: String = "",
    val organization: String = "",
    val locality: String = "",
    val state: String = "",
    /** Two-letter country code. */
    val country: String = "",
)

/** Creates RSA keys with a self-signed certificate, like `keytool -genkeypair`. */
object CertificateGenerator {
    const val DEFAULT_KEY_SIZE = 2048

    /** Google Play requires certificates valid until at least 2033; 25+ years is the usual advice. */
    const val DEFAULT_VALIDITY_YEARS = 30

    fun generate(
        owner: DistinguishedName,
        keySize: Int = DEFAULT_KEY_SIZE,
        validityYears: Int = DEFAULT_VALIDITY_YEARS,
    ): SigningKey {
        require(owner.commonName.isNotBlank()) { "The certificate needs a name" }
        require(keySize in setOf(2048, 3072, 4096)) { "Key size must be 2048, 3072 or 4096 bits" }
        require(validityYears in 1..100) { "Validity must be between 1 and 100 years" }
        require(owner.country.isEmpty() || Regex("[A-Za-z]{2}").matches(owner.country)) { "Country must be a two-letter code" }

        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(keySize, SecureRandom()) }.generateKeyPair()
        // An explicit provider instance: Android ships a trimmed "BC" provider under the same name.
        val provider = BouncyCastleProvider()
        val subject = X500NameBuilder(BCStyle.INSTANCE).apply {
            fun add(oid: org.bouncycastle.asn1.ASN1ObjectIdentifier, value: String) {
                if (value.isNotBlank()) addRDN(oid, value.trim())
            }
            add(BCStyle.CN, owner.commonName)
            add(BCStyle.OU, owner.organizationalUnit)
            add(BCStyle.O, owner.organization)
            add(BCStyle.L, owner.locality)
            add(BCStyle.ST, owner.state)
            add(BCStyle.C, owner.country.uppercase())
        }.build()
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE),
            Date(now - TimeUnit.DAYS.toMillis(1)),
            Date(now + TimeUnit.DAYS.toMillis(365L * validityYears + validityYears / 4)),
            subject,
            keyPair.public,
        ).build(JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(keyPair.private))
        val certificate = JcaX509CertificateConverter().setProvider(provider).getCertificate(holder)
        return SigningKey(keyPair.private, certificate)
    }
}
