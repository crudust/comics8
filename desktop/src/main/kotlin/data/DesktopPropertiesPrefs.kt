package com.comics8.desktop.data

import com.comics8.core.storage.KeyValuePrefs
import java.io.File
import java.util.Properties

class DesktopPropertiesPrefs(
    private val configFile: File = File(System.getProperty("user.home"), ".comics8/sync.properties"),
) : KeyValuePrefs {
    private val properties = Properties().apply {
        if (configFile.exists()) {
            try {
                configFile.inputStream().use { load(it) }
            } catch (_: Exception) {
            }
        }
    }

    private fun saveProperties() {
        try {
            configFile.parentFile?.mkdirs()
            configFile.outputStream().use { properties.store(it, "Comics8 Sync Configuration") }
        } catch (_: Exception) {
        }
    }

    override fun contains(key: String): Boolean = properties.containsKey(key)

    override fun getString(key: String, defaultValue: String?): String? =
        properties.getProperty(key, defaultValue)

    override fun putString(key: String, value: String?) {
        if (value == null) {
            properties.remove(key)
        } else {
            properties.setProperty(key, value)
        }
        saveProperties()
    }
}
