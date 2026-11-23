package io.nativeforge.app.signing

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts saved keystore passwords with an AES key held by the Android Keystore, so the
 * passwords on disk are useless without this app on this device (the key never leaves the
 * secure hardware where there is one, and is not part of backups).
 */
class PasswordVault(private val keyAlias: String = "nativeforge-signing-passwords") {

    fun encrypt(secret: CharArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val plain = String(secret).toByteArray(Charsets.UTF_8)
        try {
            val sealed = cipher.iv + cipher.doFinal(plain)
            return Base64.encodeToString(sealed, Base64.NO_WRAP)
        } finally {
            plain.fill(0)
        }
    }

    /** Null when the value cannot be decrypted (for example after the app's keys were reset). */
    fun decrypt(value: String): CharArray? = runCatching {
        val sealed = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        val plain = cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        String(plain, Charsets.UTF_8).toCharArray().also { plain.fill(0) }
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
