package com.comics8.monitor.data

import android.content.SharedPreferences
import com.comics8.core.source.SourcePrefs
import com.comics8.core.source.SourcePreferenceStore
import com.comics8.core.source.SourceSettings
import com.comics8.core.source.StoredSourceSettings

class AndroidSourcePrefs private constructor(
    private val prefs: SharedPreferences,
    private val stored: StoredSourceSettings,
) : SourceSettings by stored {
    constructor(prefs: SharedPreferences) : this(prefs, StoredSourceSettings(AndroidPreferenceStore(prefs)))

    fun migrateIfNeeded() = stored.migrateIfNeeded()

    fun globalNotificationEnabled(): Boolean {
        return if (prefs.contains(GLOBAL_NOTIFICATION_ENABLED_KEY)) {
            prefs.getBoolean(GLOBAL_NOTIFICATION_ENABLED_KEY, true)
        } else {
            true
        }
    }

    fun setGlobalNotificationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(GLOBAL_NOTIFICATION_ENABLED_KEY, enabled).apply()
    }

    fun notificationIntervalMinutes(): Long {
        return if (prefs.contains(NOTIFICATION_INTERVAL_MINUTES_KEY)) {
            prefs.getLong(NOTIFICATION_INTERVAL_MINUTES_KEY, DEFAULT_INTERVAL_MINUTES)
        } else {
            DEFAULT_INTERVAL_MINUTES
        }
    }

    fun setNotificationIntervalMinutes(minutes: Long) {
        val valid = minutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
        prefs.edit().putLong(NOTIFICATION_INTERVAL_MINUTES_KEY, valid).apply()
    }

    companion object {
        const val GLOBAL_NOTIFICATION_ENABLED_KEY = "pref_notification_enabled"
        const val NOTIFICATION_INTERVAL_MINUTES_KEY = "pref_notification_interval_minutes"
        const val DEFAULT_INTERVAL_MINUTES = 60L
        const val MIN_INTERVAL_MINUTES = 15L
    }

}

private class AndroidPreferenceStore(private val prefs: SharedPreferences) : SourcePreferenceStore {
    override fun contains(key: String) = prefs.contains(key)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
}
