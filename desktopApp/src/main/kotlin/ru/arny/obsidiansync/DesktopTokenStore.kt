package ru.arny.obsidiansync

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DesktopTokenStore(
    private val root: Path = Path.of(System.getProperty("user.home"), ".obsidelta-sync"),
) {
    @Synchronized
    fun save(token: String) {
        Files.createDirectories(root)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))
        val value = "${cipher.iv.toBase64()}:${encrypted.toBase64()}"
        val temporary = root.resolve("token.tmp")
        Files.writeString(temporary, value, StandardCharsets.UTF_8)
        Files.move(temporary, tokenFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    @Synchronized
    fun load(): String? {
        if (!Files.exists(tokenFile)) return null
        return runCatching {
            val (iv, encrypted) = Files.readString(tokenFile, StandardCharsets.UTF_8)
                .split(':', limit = 2)
                .map(::fromBase64)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    fun clear() {
        Files.deleteIfExists(tokenFile)
        Files.deleteIfExists(root.resolve("token.tmp"))
    }

    private fun getOrCreateKey(): SecretKey {
        Files.createDirectories(root)
        val password = storePassword()
        val keyStore = KeyStore.getInstance("JCEKS")
        if (Files.exists(keyStoreFile)) {
            Files.newInputStream(keyStoreFile).use { keyStore.load(it, password) }
        } else {
            keyStore.load(null, password)
        }
        (keyStore.getKey(KEY_ALIAS, password) as? SecretKey)?.let { return it }

        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        keyStore.setEntry(
            KEY_ALIAS,
            KeyStore.SecretKeyEntry(key),
            KeyStore.PasswordProtection(password),
        )
        Files.newOutputStream(keyStoreFile).use { keyStore.store(it, password) }
        return key
    }

    private fun storePassword(): CharArray {
        val binding = buildString {
            append(System.getProperty("user.name"))
            append('|')
            append(System.getProperty("user.home"))
            append("|ObsiDeltaSync-local-credentials")
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(binding.toByteArray(StandardCharsets.UTF_8))
            .toBase64()
            .toCharArray()
    }

    private val tokenFile get() = root.resolve("token.enc")
    private val keyStoreFile get() = root.resolve("credentials.jceks")

    private fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)
    private fun fromBase64(value: String): ByteArray = Base64.getDecoder().decode(value)

    private companion object {
        const val KEY_ALIAS = "yandex-oauth"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
