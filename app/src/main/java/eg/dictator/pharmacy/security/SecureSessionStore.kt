package eg.dictator.pharmacy.security

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
 * Stores the pharmacy device token encrypted with an Android Keystore AES-GCM key.
 * The key is non-exportable and is never included in the APK or source control.
 */
object SecureSessionStore {
    private const val PREFS = "dictator_secure_session"
    private const val TOKEN = "encrypted_device_token"
    private const val PHARMACY_ID = "pharmacy_id"
    private const val PHARMACY_NAME = "pharmacy_name"
    private const val DEVICE_ID = "device_id"
    private const val ROLE = "role"
    private const val KEY_ALIAS = "dictator_pharmacy_session_v1"
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128

    data class Session(
        val token: String,
        val pharmacyId: String,
        val pharmacyName: String,
        val deviceId: String,
        val role: String
    )

    @Synchronized
    fun save(context: Context, session: Session) {
        require(session.token.length >= 32) { "Invalid device token" }
        require(session.pharmacyId.isNotBlank() && session.deviceId.isNotBlank()) {
            "Invalid pharmacy session"
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(session.token.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(TOKEN, Base64.encodeToString(packed, Base64.NO_WRAP))
            .putString(PHARMACY_ID, session.pharmacyId)
            .putString(PHARMACY_NAME, session.pharmacyName)
            .putString(DEVICE_ID, session.deviceId)
            .putString(ROLE, session.role)
            .apply()
    }

    @Synchronized
    fun read(context: Context): Session? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val packed = try {
            Base64.decode(prefs.getString(TOKEN, null) ?: return null, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            clear(context)
            return null
        }
        if (packed.size <= IV_LENGTH) {
            clear(context)
            return null
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, packed.copyOfRange(0, IV_LENGTH))
            )
            val token = cipher.doFinal(packed.copyOfRange(IV_LENGTH, packed.size))
                .toString(Charsets.UTF_8)
            val pharmacyId = prefs.getString(PHARMACY_ID, null) ?: return null
            val deviceId = prefs.getString(DEVICE_ID, null) ?: return null
            Session(
                token = token,
                pharmacyId = pharmacyId,
                pharmacyName = prefs.getString(PHARMACY_NAME, "").orEmpty(),
                deviceId = deviceId,
                role = prefs.getString(ROLE, "staff").orEmpty()
            )
        } catch (_: Exception) {
            clear(context)
            null
        }
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {
            // A missing or temporarily unavailable key must not crash sign-out.
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
