package com.stealth.rootexecutor

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM authenticated storage. AAD binds ciphertext to its preference key. */
internal object PrivateInputStore {
    private const val PREFIX = "enc:v1:"
    private const val ALIAS = "tubing.private.inputs.v1"
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized fun write(prefs: SharedPreferences, name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = PREFIX + Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        check(prefs.edit().putString(name, encoded).commit()) { "输入数据加密保存失败" }
    }
    @Synchronized fun read(prefs: SharedPreferences, name: String, fallback: String = "[]"): String {
        val stored = prefs.getString(name, null) ?: return fallback
        if (!stored.startsWith(PREFIX)) {
            write(prefs, name, stored) // Migrate only after encryption has succeeded.
            return stored
        }
        val bytes = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
        require(bytes.size >= 28) { "输入数据损坏" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
    fun migrate(prefs: SharedPreferences) {
        prefs.all.keys.filter { it.startsWith("scriptInput_presets_") || it.startsWith("scriptInput_history_") }
            .forEach { read(prefs, it) }
    }
}
