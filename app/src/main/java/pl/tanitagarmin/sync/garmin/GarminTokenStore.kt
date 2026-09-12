package pl.tanitagarmin.sync.garmin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class GarminTokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("garmin_tokens_secure", Context.MODE_PRIVATE)

    data class Tokens(
        val clientId: String,
        val accessToken: String,
        val refreshToken: String,
        val expiresAtEpochSeconds: Long,
        val refreshExpiresAtEpochSeconds: Long?
    )

    fun save(tokens: Tokens) {
        val json = JSONObject()
            .put("clientId", tokens.clientId)
            .put("accessToken", tokens.accessToken)
            .put("refreshToken", tokens.refreshToken)
            .put("expiresAt", tokens.expiresAtEpochSeconds)
            .put("refreshExpiresAt", tokens.refreshExpiresAtEpochSeconds)
            .toString()
        prefs.edit().putString(KEY_BLOB, encrypt(json)).apply()
    }

    fun load(): Tokens? = try {
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        val j = JSONObject(decrypt(blob))
        Tokens(
            clientId = j.getString("clientId"),
            accessToken = j.getString("accessToken"),
            refreshToken = j.getString("refreshToken"),
            expiresAtEpochSeconds = j.getLong("expiresAt"),
            refreshExpiresAtEpochSeconds = if (j.has("refreshExpiresAt") && !j.isNull("refreshExpiresAt")) {
                j.getLong("refreshExpiresAt")
            } else null
        )
    } catch (_: Exception) {
        // v0.1/v0.2-preview stored a different token schema. Drop it instead of
        // trying to use an obsolete OAuth1 session.
        clear()
        null
    }

    fun clear() {
        prefs.edit().remove(KEY_BLOB).apply()
    }

    fun isAuthenticated(): Boolean = load() != null

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val p = blob.split(':', limit = 2)
        require(p.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, Base64.decode(p[0], Base64.NO_WRAP))
        )
        return cipher.doFinal(Base64.decode(p[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private companion object {
        const val KEY_BLOB = "tokens"
        const val KEY_ALIAS = "tanita_garmin_sync_garmin_tokens_v1"
    }
}
