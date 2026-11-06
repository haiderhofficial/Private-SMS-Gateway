package org.privatesms.gateway

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/** Persists [Config]. The token lives in EncryptedSharedPreferences; everything else in plain prefs. */
class SettingsStore private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("gateway", Context.MODE_PRIVATE)

    private val secure: SharedPreferences by lazy {
        try {
            val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                app, "gateway_secure", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // Keystore problems exist on some devices; fall back rather than lose the setting.
            app.getSharedPreferences("gateway_secure_fallback", Context.MODE_PRIVATE)
        }
    }

    /** Everything except the token (cheap; safe to call on the main thread). */
    fun loadPublic(): Config = runCatching {
        Config.fromJson(JSONObject(prefs.getString("config", "{}") ?: "{}"))
    }.getOrDefault(Config())

    fun load(): Config = loadPublic().copy(token = secure.getString("token", "") ?: "")

    fun save(c: Config) {
        prefs.edit().putString("config", c.toJson(includeToken = false).toString()).apply()
        secure.edit().putString("token", c.token).apply()
    }

    /** Whether the user wants the gateway running (so it can resume after reboot or a crash). */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) { prefs.edit().putBoolean("enabled", v).apply() }

    companion object {
        @Volatile private var instance: SettingsStore? = null
        fun get(ctx: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(ctx.applicationContext).also { instance = it }
            }
    }
}
