package com.comics8.core.source

data class WorkId(val sourceId: String, val toonId: String) {
    init {
        require(':' !in sourceId) { "sourceId must not contain ':'" }
    }

    fun storageKey(): String = "$sourceId:$toonId"

    companion object {
        const val DEFAULT_SOURCE = "eleven"

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
