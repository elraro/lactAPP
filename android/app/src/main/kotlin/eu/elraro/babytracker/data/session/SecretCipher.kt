package eu.elraro.babytracker.data.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject

/** Encrypts secrets before they are written to disk. An interface only so tests can run off-device. */
interface SecretCipher {
    fun encrypt(plain: String): String

    /** Returns null when the value cannot be decrypted (key lost after a restore, corrupted data). */
    fun decrypt(encrypted: String): String?
}

/** AES-256-GCM with a key that never leaves the Android Keystore. */
class KeystoreCipher
    @Inject
    constructor() : SecretCipher {
        private val key: SecretKey by lazy { loadOrCreateKey() }

        override fun encrypt(plain: String): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val payload = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(payload, Base64.NO_WRAP)
        }

        override fun decrypt(encrypted: String): String? =
            try {
                val payload = Base64.decode(encrypted, Base64.NO_WRAP)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES))
                String(cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES), Charsets.UTF_8)
            } catch (_: GeneralSecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }

        private fun loadOrCreateKey(): SecretKey {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val spec =
                KeyGenParameterSpec
                    .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
                init(spec)
                generateKey()
            }
        }

        private companion object {
            const val KEYSTORE = "AndroidKeyStore"
            const val ALIAS = "babytracker.session"
            const val TRANSFORMATION = "AES/GCM/NoPadding"
            const val KEY_BITS = 256
            const val TAG_BITS = 128
            const val IV_BYTES = 12
        }
    }
