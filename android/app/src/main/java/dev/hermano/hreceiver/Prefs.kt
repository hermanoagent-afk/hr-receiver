package dev.hermano.hreceiver

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Настройки приёмника. Ключ устройства храним в EncryptedSharedPreferences
 * (на базе Android Keystore). В production — только так.
 *
 * Синглтон: создание MasterKey + EncryptedSharedPreferences дорогое (~100 мс)
 * и на некоторых прошивках (в т.ч. MIUI/HyperOS) может бросить исключение
 * из-за испорченного keystore — тогда пересоздаём файл, а не падаем.
 */
class Prefs private constructor(private val sp: SharedPreferences) {

    companion object {
        private const val TAG = "Prefs"
        private const val FILE = "hr_receiver_prefs"

        @Volatile private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(open(context.applicationContext)).also { instance = it }
            }

        private fun open(ctx: Context): SharedPreferences {
            return try {
                createEncrypted(ctx)
            } catch (e: Exception) {
                // Испорченный keystore/файл (известная проблема после восстановления/обновления
                // прошивки). Настройки придётся ввести заново, но приложение не упадёт.
                Log.e(TAG, "EncryptedSharedPreferences сломаны, пересоздаю", e)
                try {
                    ctx.deleteSharedPreferences(FILE)
                    createEncrypted(ctx)
                } catch (e2: Exception) {
                    Log.e(TAG, "не удалось пересоздать, работаю в памяти", e2)
                    InMemoryPrefs()
                }
            }
        }

        private fun createEncrypted(ctx: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                ctx,
                FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }

    private fun str(key: String) = try { sp.getString(key, "") ?: "" } catch (_: Exception) { "" }
    private fun putStr(key: String, v: String) = try { sp.edit().putString(key, v).apply() } catch (_: Exception) {}
    private fun bool(key: String, def: Boolean) = try { sp.getBoolean(key, def) } catch (_: Exception) { def }
    private fun putBool(key: String, v: Boolean) = try { sp.edit().putBoolean(key, v).apply() } catch (_: Exception) {}

    var serverUrl: String
        get() = str("server_url")
        set(v) = putStr("server_url", v)

    var deviceKey: String
        get() = str("device_key")
        set(v) = putStr("device_key", v)

    /** Имя того, кто держит телефон: Сева / папа / мама. */
    var operatorName: String
        get() = str("operator_name")
        set(v) = putStr("operator_name", v)

    var deviceId: String
        get() = str("device_id")
        set(v) = putStr("device_id", v)

    /** MAC источника пульса, выбранного пользователем или последнего успешного. */
    var preferredAddress: String
        get() = str("preferred_address")
        set(v) = putStr("preferred_address", v)

    var preferredName: String
        get() = str("preferred_name")
        set(v) = putStr("preferred_name", v)

    /** Пользователь хочет, чтобы приёмник работал (для автозапуска после перезагрузки). */
    var shouldRun: Boolean
        get() = bool("should_run", false)
        set(v) = putBool("should_run", v)

    /** Первый запуск пройден (хотя бы раз получили пульс или закрыли приветствие). */
    var onboardingDone: Boolean
        get() = bool("onboarding_done", false)
        set(v) = putBool("onboarding_done", v)

    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && deviceKey.isNotBlank() && deviceId.isNotBlank()
}

/** Аварийная замена, если Keystore недоступен совсем: живём до перезапуска процесса. */
private class InMemoryPrefs : SharedPreferences {
    private val map = java.util.concurrent.ConcurrentHashMap<String, Any>()

    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        map[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = key != null && map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) map.clear()
            pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
        }
    }
}
