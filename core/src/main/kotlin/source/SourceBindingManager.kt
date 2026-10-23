package com.comics8.core.source

import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages local-to-canonical source ID bindings.
 * Maps client JS sourceId (e.g. 'my_custom_eleven') to server canonical sourceId (e.g. 'eleven')
 * to enable zero-overhead crowdsourced catalog lookups after one-time resolution.
 */
class SourceBindingManager(private val storageFile: File? = null) {
    private val bindings = ConcurrentHashMap<String, String>()

    init {
        loadFromFile()
    }

    fun getCanonicalSourceId(localSourceId: String): String {
        return bindings[localSourceId] ?: localSourceId
    }

    fun getLocalSourceId(canonicalSourceId: String): String {
        return bindings.entries.firstOrNull { it.value == canonicalSourceId }?.key ?: canonicalSourceId
    }

    fun hasBinding(localSourceId: String): Boolean = bindings.containsKey(localSourceId)

    fun bind(localSourceId: String, canonicalSourceId: String) {
        if (localSourceId.isBlank() || canonicalSourceId.isBlank()) return
        val prev = bindings.put(localSourceId, canonicalSourceId)
        if (prev != canonicalSourceId) {
            saveToFile()
        }
    }

    private fun loadFromFile() {
        val file = storageFile ?: return
        if (!file.exists()) return
        try {
            val text = file.readText()
            val json = JSONObject(text)
            for (key in json.keys()) {
                val value = json.optString(key)
                if (value.isNotBlank()) {
                    bindings[key] = value
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun saveToFile() {
        val file = storageFile ?: return
        try {
            val json = JSONObject()
            for ((k, v) in bindings) {
                json.put(k, v)
            }
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        val global = SourceBindingManager()
        val desktopGlobal by lazy { desktopDefault() }

        fun desktopDefault(home: File = File(System.getProperty("user.home"))): SourceBindingManager =
            SourceBindingManager(File(home, ".comics8/source_bindings.json"))
    }
}
