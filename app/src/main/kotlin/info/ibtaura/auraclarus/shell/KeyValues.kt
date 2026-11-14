package info.ibtaura.auraclarus.shell

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Small settings kept on the phone (iOS `UserDefaults`): pins, today's flags. Never tokens or health data. */
interface KeyValues {
    fun getBoolean(key: String): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

class SharedPrefsKeyValues(private val prefs: SharedPreferences) : KeyValues {
    override fun getBoolean(key: String) = prefs.getBoolean(key, false)
    override fun putBoolean(key: String, value: Boolean) = prefs.edit { putBoolean(key, value) }
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) = prefs.edit { putString(key, value) }
    override fun remove(key: String) = prefs.edit { remove(key) }

    companion object {
        /** "aura_app" for the app, "uitesting" for UI tests, so a walk never sees the app's own settings. */
        fun forApp(context: Context, flags: LaunchFlags): SharedPrefsKeyValues {
            val name = if (flags.isUiTesting) "uitesting" else "aura_app"
            return SharedPrefsKeyValues(context.getSharedPreferences(name, Context.MODE_PRIVATE))
        }
    }
}

class InMemoryKeyValues : KeyValues {
    private val values = mutableMapOf<String, Any>()
    override fun getBoolean(key: String) = values[key] as? Boolean ?: false
    override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    override fun getString(key: String) = values[key] as? String
    override fun putString(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}
