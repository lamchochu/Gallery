package org.fossify.gallery.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the bot token, chat id and the derived master key encrypted with a non-exportable
 * Android Keystore key. The passphrase itself is never stored.
 */
class CloudSecrets(context: Context) {
    class Credentials(val token: String, val chatId: String, val master: ByteArray)

    private val prefs = context.applicationContext.getSharedPreferences("cloud_secrets", Context.MODE_PRIVATE)

    companion object {
        private const val ALIAS = "gallery_cloud_secrets"
        private const val K_TOKEN = "token"
        private const val K_CHAT = "chat"
        private const val K_MASTER = "master"
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun put(name: String, value: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = cipher.iv + cipher.doFinal(value)
        prefs.edit().putString(name, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    private fun get(name: String): ByteArray? {
        val raw = prefs.getString(name, null) ?: return null
        return try {
            val packed = Base64.decode(raw, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed, 0, 12))
            cipher.doFinal(packed, 12, packed.size - 12)
        } catch (e: Exception) {
            null // e.g. restored on another device, Keystore key is gone
        }
    }

    fun save(token: String, chatId: String, master: ByteArray) {
        put(K_TOKEN, token.toByteArray())
        put(K_CHAT, chatId.toByteArray())
        put(K_MASTER, master)
    }

    fun load(): Credentials? {
        val token = get(K_TOKEN) ?: return null
        val chat = get(K_CHAT) ?: return null
        val master = get(K_MASTER) ?: return null
        return Credentials(String(token), String(chat), master)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
