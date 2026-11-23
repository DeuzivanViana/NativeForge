package io.nativeforge.app.signing

import io.nativeforge.core.export.signing.DistinguishedName
import io.nativeforge.core.export.signing.KeystoreException
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SigningKeyRepositoryTest {
    private val dir = Files.createTempDirectory("nf-keys").toFile()

    @Test
    fun `created keys survive a restart, back up and import elsewhere`() {
        val repo = SigningKeyRepository(dir, vault = null)
        val created = repo.create("Game upload key", "upload", "hunter22".toCharArray(), DistinguishedName("Ana"), 30, rememberPassword = false)
        assertEquals(false, created.backedUp)
        assertFailsWith<PasswordRequiredException> { repo.load(created.id) }
        assertEquals(created.fingerprint, repo.load(created.id, KeystorePasswords("hunter22".toCharArray())).sha256Fingerprint)

        val reopened = SigningKeyRepository(dir, vault = null)
        assertEquals(listOf(created.id), reopened.keystores.value.map { it.id })
        val backup = ByteArrayOutputStream().also { reopened.backUp(created.id, it) }.toByteArray()
        assertTrue(reopened.find(created.id)!!.backedUp)

        val other = SigningKeyRepository(Files.createTempDirectory("nf-keys2").toFile(), vault = null)
        assertEquals(listOf("upload"), other.aliases(backup, "hunter22".toCharArray()).map { it.alias })
        val imported = other.import("Copy", backup, "upload", KeystorePasswords("hunter22".toCharArray()), rememberPassword = false)
        assertEquals(created.fingerprint, imported.fingerprint)
        assertFailsWith<KeystoreException> { other.import("Again", backup, "upload", KeystorePasswords("hunter22".toCharArray()), false) }

        reopened.delete(created.id)
        assertTrue(reopened.keystores.value.isEmpty())
        assertFailsWith<KeystoreException> { reopened.load(created.id, KeystorePasswords("hunter22".toCharArray())) }
    }

    @Test
    fun `weak passwords and bad aliases are refused`() {
        val repo = SigningKeyRepository(dir, vault = null)
        assertFailsWith<KeystoreException> { repo.create("k", "upload", "123".toCharArray(), DistinguishedName("A"), 30, false) }
        assertFailsWith<KeystoreException> { repo.create("k", "bad alias", "123456".toCharArray(), DistinguishedName("A"), 30, false) }
    }
}
