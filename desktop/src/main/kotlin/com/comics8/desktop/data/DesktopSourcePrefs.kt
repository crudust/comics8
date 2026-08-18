package com.comics8.desktop.data

import com.comics8.core.source.WorkId
import com.comics8.core.source.hitomi.HitomiSource
import java.util.prefs.Preferences

object DesktopSourcePrefs {
    private val prefs: Preferences = Preferences.userRoot().node("com.comics8.desktop")

    var hitomiEnabled: Boolean
        get() = prefs.getBoolean(HitomiSource.ENABLED_PREF_KEY, HitomiSource.ENABLED_BY_DEFAULT)
        set(value) {
            prefs.putBoolean(HitomiSource.ENABLED_PREF_KEY, value)
        }

    var hitomiLanguage: String
        get() = prefs.get(HitomiSource.LANGUAGE_PREF_KEY, HitomiSource.DEFAULT_LANGUAGE)
            .ifBlank { HitomiSource.DEFAULT_LANGUAGE }
        set(value) {
            prefs.put(HitomiSource.LANGUAGE_PREF_KEY, value.ifBlank { HitomiSource.DEFAULT_LANGUAGE })
        }

    var activeSourceId: String
        get() {
            val stored = prefs.get(HitomiSource.ACTIVE_SOURCE_PREF_KEY, WorkId.DEFAULT_SOURCE)
            return stored.ifBlank { WorkId.DEFAULT_SOURCE }
        }
        set(value) {
            prefs.put(HitomiSource.ACTIVE_SOURCE_PREF_KEY, value.ifBlank { WorkId.DEFAULT_SOURCE })
        }
}
