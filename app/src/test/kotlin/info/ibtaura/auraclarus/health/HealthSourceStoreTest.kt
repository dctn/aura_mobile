package info.ibtaura.auraclarus.health

import android.content.SharedPreferences
import info.ibtaura.aura.core.HealthSourceGroup
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthSourceStoreTest {
    @Test
    fun signOutClearForgetsSourcesOnly() {
        val prefs = MemoryPrefs()
        prefs.edit().putLong("account-retryAt", 5L).apply()
        val store = HealthSourceStore(prefs) { LocalDate.of(2026, 11, 15) }
        store.setOverride(HealthSourceGroup.sleep, "com.sec.android.app.shealth")
        store.saveTotals(mapOf("com.sec.android.app.shealth" to LocalDate.of(2026, 11, 14)))
        assertEquals("com.sec.android.app.shealth", store.overrides()[HealthSourceGroup.sleep])

        store.clear()

        assertTrue(store.overrides().isEmpty())
        assertTrue(store.totals().isEmpty())
        // Keys outside the store (the day port's retry wait) stay.
        assertEquals(setOf("account-retryAt"), prefs.all.keys)
    }
}

/** A plain in-memory SharedPreferences for unit tests (no Android runtime). */
private class MemoryPrefs : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): Map<String, *> = values.toMap()
    override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?) = values[key] as Set<String>? ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val puts = mutableMapOf<String, Any?>()
        private val removes = mutableSetOf<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { puts[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { puts[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { puts[key] = value }
        override fun putLong(key: String, value: Long) = apply { puts[key] = value }
        override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
        override fun remove(key: String) = apply { removes += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            removes.forEach(values::remove)
            values.putAll(puts)
            return true
        }
        override fun apply() {
            commit()
        }
    }
}
