package info.ibtaura.auraclarus.store

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import info.ibtaura.aura.core.AuthTokens
import info.ibtaura.aura.core.TokenStore
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The session between launches (iOS Keychain): the tokens as JSON, sealed with AES-256-GCM under a key that never
 * leaves the Android Keystore, kept in the private prefs file `aura_tokens.xml` (excluded from backup and device
 * transfer, see res/xml). Never log what's in it. A store that can't be opened (key gone after a restore, damaged
 * file) reads as signed out and is cleared.
 */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): AuthTokens? = synchronized(lock) {
        val sealed = prefs.getString(ENTRY, null) ?: return null
        runCatching {
            val bytes = Base64.decode(sealed, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_BYTES)
            val body = bytes.copyOfRange(IV_BYTES, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            json.decodeFromString(AuthTokens.serializer(), cipher.doFinal(body).toString(Charsets.UTF_8))
        }.getOrElse {
            prefs.edit { remove(ENTRY) }
            null
        }
    }

    override fun save(tokens: AuthTokens) = synchronized(lock) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val body = cipher.doFinal(json.encodeToString(AuthTokens.serializer(), tokens).toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected IV length" }
        prefs.edit(commit = true) { putString(ENTRY, Base64.encodeToString(iv + body, Base64.NO_WRAP)) }
    }

    override fun clear() = synchronized(lock) {
        prefs.edit(commit = true) { remove(ENTRY) }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val FILE = "aura_tokens"
        const val ENTRY = "session"
        const val ALIAS = "aura_session_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
