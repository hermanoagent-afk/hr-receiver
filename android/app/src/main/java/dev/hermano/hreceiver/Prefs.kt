package dev.hermano.hreceiver

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Настройки приёмника. Ключ устройства храним в EncryptedSharedPreferences
 * (на базе Android Keystore). В production — только так.
 */
class Prefs(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sp = EncryptedSharedPreferences.create(
        context,
        "hr_receiver_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var serverUrl: String
        get() = sp.getString("server_url", "") ?: ""
        set(v) = sp.edit().putString("server_url", v).apply()

    var deviceKey: String
        get() = sp.getString("device_key", "") ?: ""
        set(v) = sp.edit().putString("device_key", v).apply()

    var deviceId: String
        get() = sp.getString("device_id", "") ?: ""
        set(v) = sp.edit().putString("device_id", v).apply()

    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && deviceKey.isNotBlank() && deviceId.isNotBlank()
}
