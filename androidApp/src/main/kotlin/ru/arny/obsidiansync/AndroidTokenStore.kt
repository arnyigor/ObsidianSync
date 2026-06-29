package ru.arny.obsidiansync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidTokenStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("obsidelta_secure_credentials", Context.MODE_PRIVATE)

    @Synchronized
    fun save(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(token.encodeToByteArray())
        val value = "${cipher.iv.toBase64()}:${encrypted.toBase64()}"
        preferences.edit().putString(KEY_TOKEN, value).commit()
    }

    @Synchronized
    fun load(): String? {
        val value = preferences.getString(KEY_TOKEN, null) ?: return null
        return runCatching {
            val (iv, encrypted) = value.split(':', limit = 2).map(::fromBase64)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(encrypted).decodeToString()
        }.getOrElse {
            preferences.edit().remove(KEY_TOKEN).commit()
            null
        }
    }

    @Synchronized
    fun clear() {
        preferences.edit().remove(KEY_TOKEN).commit()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun fromBase64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        const val KEY_ALIAS = "obsidelta_yandex_oauth"
        const val KEY_TOKEN = "yandex_oauth_ciphertext"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
