package com.comics8.core.source

data class WorkId(val sourceId: String, val toonId: String) {
    init {
        require(':' !in sourceId) { "sourceId must not contain ':'" }
    }

    fun storageKey(): String = "$sourceId:$toonId"

    companion object {
        const val DEFAULT_SOURCE = "eleven"

        fun eleven(toonId: String): WorkId = WorkId(DEFAULT_SOURCE, toonId)

        fun writable(sourceId: String, toonId: String): WorkId? {
            val sid = sourceId.ifBlank { DEFAULT_SOURCE }
            if (sid != DEFAULT_SOURCE) return null
            return WorkId(sid, toonId)
        }

        fun parse(raw: String): WorkId {
            val idx = raw.indexOf(':')
            if (idx < 0) {
                return WorkId(DEFAULT_SOURCE, raw)
            }
            val source = raw.substring(0, idx)
            val toonId = raw.substring(idx + 1)
            return WorkId(source.ifBlank { DEFAULT_SOURCE }, toonId)
        }
    }
}
