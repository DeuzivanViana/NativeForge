package io.nativeforge.app.signing

import io.nativeforge.core.export.signing.CertificateGenerator
import io.nativeforge.core.export.signing.DistinguishedName
import io.nativeforge.core.export.signing.KeystoreException
import io.nativeforge.core.export.signing.KeystoreType
import io.nativeforge.core.export.signing.Keystores
import io.nativeforge.core.export.signing.SigningKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.UUID

/** A keystore saved in the IDE. The file itself is kept byte for byte as created or imported. */
@Serializable
data class SavedKeystore(
    val id: String,
    /** Name shown in the IDE, e.g. "Play upload key". */
    val name: String,
    val alias: String,
    val type: KeystoreType,
    /** SHA-256 of the signing certificate ("AB:CD:..."). */
    val fingerprint: String,
    val subject: String,
    /** Certificate expiry, milliseconds since the epoch. */
    val validUntil: Long,
    val createdAt: Long,
    /** Passwords encrypted by [PasswordVault], when the user chose to remember them. */
    val storePassword: String? = null,
    val keyPassword: String? = null,
    /** False until the user backed the file up (or imported it, so a copy exists elsewhere). */
    val backedUp: Boolean = false,
) {
    val passwordsSaved: Boolean get() = storePassword != null
}

/** Passwords typed by the user. A null [key] means the same as [store]. */
class KeystorePasswords(val store: CharArray, val key: CharArray? = null)

/**
 * Keystores the user created or imported, stored in private app storage
 * (`files/signing/keystores/`), with an index in `keystores.json`.
 */
class SigningKeyRepository(
    private val directory: File,
    private val vault: PasswordVault?,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val indexFile = File(directory, "keystores.json")
    private val _keystores = MutableStateFlow(readIndex())
    val keystores: StateFlow<List<SavedKeystore>> = _keystores.asStateFlow()

    fun find(id: String?): SavedKeystore? = id?.let { wanted -> _keystores.value.firstOrNull { it.id == wanted } }

    /** Creates a new PKCS#12 keystore with one RSA key, like Android Studio's "Create new key store". */
    @Synchronized
    fun create(
        name: String,
        alias: String,
        password: CharArray,
        owner: DistinguishedName,
        validityYears: Int,
        rememberPassword: Boolean,
    ): SavedKeystore {
        validate(name, alias, password)
        val key = CertificateGenerator.generate(owner, validityYears = validityYears)
        val bytes = ByteArrayOutputStream().also { Keystores.writePkcs12(it, key, alias, password) }.toByteArray()
        return save(name, alias, KeystoreType.PKCS12, bytes, key, KeystorePasswords(password), rememberPassword, backedUp = false)
    }

    /** Lists the keys in a keystore file the user picked, to choose the alias. */
    fun aliases(bytes: ByteArray, storePassword: CharArray) = Keystores.aliases(bytes, storePassword)

    @Synchronized
    fun import(name: String, bytes: ByteArray, alias: String, passwords: KeystorePasswords, rememberPassword: Boolean): SavedKeystore {
        if (name.isBlank()) throw KeystoreException("Give the key a name")
        val type = Keystores.detectType(bytes) ?: throw KeystoreException("This file is not a keystore (PKCS#12, JKS or BKS)")
        val key = Keystores.load(bytes, passwords.store, alias, passwords.key)
        _keystores.value.firstOrNull { it.fingerprint == key.sha256Fingerprint }?.let {
            throw KeystoreException("This key is already saved as '${it.name}'")
        }
        return save(name, alias, type, bytes, key, passwords, rememberPassword, backedUp = true)
    }

    /** Opens a saved key, with the remembered passwords when [passwords] is null. */
    fun load(id: String, passwords: KeystorePasswords? = null): SigningKey {
        val entry = find(id) ?: throw KeystoreException("The signing key was deleted. Choose another key.")
        val actual = passwords ?: savedPasswords(entry) ?: throw PasswordRequiredException(entry)
        return Keystores.load(file(entry).readBytes(), actual.store, entry.alias, actual.key)
    }

    fun savedPasswords(entry: SavedKeystore): KeystorePasswords? {
        val store = entry.storePassword?.let { vault?.decrypt(it) } ?: return null
        return KeystorePasswords(store, entry.keyPassword?.let { vault?.decrypt(it) })
    }

    @Synchronized
    fun rememberPasswords(id: String, passwords: KeystorePasswords) {
        val vault = vault ?: return
        update(id) { it.copy(storePassword = vault.encrypt(passwords.store), keyPassword = passwords.key?.let(vault::encrypt)) }
    }

    @Synchronized
    fun forgetPasswords(id: String) = update(id) { it.copy(storePassword = null, keyPassword = null) }

    @Synchronized
    fun rename(id: String, name: String) {
        if (name.isNotBlank()) update(id) { it.copy(name = name.trim()) }
    }

    /** Writes the keystore file to [out] (a document the user picked) and marks it as backed up. */
    @Synchronized
    fun backUp(id: String, out: OutputStream) {
        val entry = find(id) ?: return
        file(entry).inputStream().use { it.copyTo(out) }
        update(id) { it.copy(backedUp = true) }
    }

    @Synchronized
    fun delete(id: String) {
        val entry = find(id) ?: return
        file(entry).delete()
        writeIndex(_keystores.value.filterNot { it.id == id })
    }

    fun fileName(entry: SavedKeystore): String =
        entry.name.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifEmpty { "key" } +
            if (entry.type == KeystoreType.JKS) ".jks" else if (entry.type == KeystoreType.BKS) ".bks" else ".p12"

    private fun save(
        name: String,
        alias: String,
        type: KeystoreType,
        bytes: ByteArray,
        key: SigningKey,
        passwords: KeystorePasswords,
        rememberPassword: Boolean,
        backedUp: Boolean,
    ): SavedKeystore {
        val vault = vault.takeIf { rememberPassword }
        val entry = SavedKeystore(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            alias = alias,
            type = type,
            fingerprint = key.sha256Fingerprint,
            subject = key.certificate.subjectX500Principal.name,
            validUntil = key.certificate.notAfter.time,
            createdAt = System.currentTimeMillis(),
            storePassword = vault?.encrypt(passwords.store),
            keyPassword = passwords.key?.let { vault?.encrypt(it) },
            backedUp = backedUp,
        )
        directory.mkdirs()
        file(entry).writeBytes(bytes)
        writeIndex(_keystores.value + entry)
        return entry
    }

    private fun validate(name: String, alias: String, password: CharArray) {
        if (name.isBlank()) throw KeystoreException("Give the key a name")
        if (!Regex("[A-Za-z0-9_.-]{1,64}").matches(alias)) throw KeystoreException("The alias can use letters, digits, '.', '_' and '-'")
        if (password.size < MIN_PASSWORD) throw KeystoreException("The password needs at least $MIN_PASSWORD characters")
    }

    private fun update(id: String, transform: (SavedKeystore) -> SavedKeystore) {
        writeIndex(_keystores.value.map { if (it.id == id) transform(it) else it })
    }

    private fun file(entry: SavedKeystore) = File(directory, "${entry.id}.keystore")

    private fun readIndex(): List<SavedKeystore> =
        runCatching { json.decodeFromString<List<SavedKeystore>>(indexFile.readText()) }.getOrDefault(emptyList())

    private fun writeIndex(entries: List<SavedKeystore>) {
        directory.mkdirs()
        val temp = File(directory, "keystores.json.tmp")
        temp.writeText(json.encodeToString(entries))
        if (!temp.renameTo(indexFile)) {
            indexFile.writeText(temp.readText())
            temp.delete()
        }
        _keystores.value = entries
    }

    companion object {
        /** Same minimum as keytool. */
        const val MIN_PASSWORD = 6
    }
}

/** The key's passwords are not saved; the user has to type them. */
class PasswordRequiredException(val keystore: SavedKeystore) : KeystoreException("Enter the password for '${keystore.name}'")
