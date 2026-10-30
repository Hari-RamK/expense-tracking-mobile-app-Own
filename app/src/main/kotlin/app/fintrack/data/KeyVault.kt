package app.fintrack.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Holds the database passphrase. A random 32-byte key is generated on first run, encrypted with an
 * AES-GCM key that lives in the Android Keystore (never exported), and only the encrypted form is stored.
 * Without this device's Keystore the database cannot be opened, which is why backups use their own passphrase.
 */
class KeyVault(context: Context) {
    private val prefs = context.getSharedPreferences("keyvault", Context.MODE_PRIVATE)

    fun databasePassphrase(): ByteArray {
        prefs.getString(WRAPPED, null)?.let { return unwrap(it) }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(WRAPPED, wrap(key)).apply()
        return key
    }

    fun hasKey(): Boolean = prefs.contains(WRAPPED)

    fun destroy() {
        prefs.edit().remove(WRAPPED).apply()
        runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun wrap(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
        val out = cipher.iv + cipher.doFinal(plain)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun unwrap(encoded: String): ByteArray {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, raw, 0, 12))
        }
        return cipher.doFinal(raw, 12, raw.size - 12)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "fintrack_db_key"
        const val WRAPPED = "wrapped_db_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
