package com.comics8.core.source

data class WorkId(val sourceId: String, val toonId: String) {
    init {
        require(':' !in sourceId) { "sourceId must not contain ':'" }
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
    }

    fun storageKey(): String = "$sourceId:$toonId"

    companion object {
        const val DEFAULT_SOURCE = "eleven"
        const val LOCAL_SOURCE = "local"

        fun eleven(toonId: String): WorkId = WorkId(DEFAULT_SOURCE, toonId)
        fun local(toonId: String): WorkId = WorkId(LOCAL_SOURCE, toonId)

        /**
         * Unprefixed raw / blank sourceId → DEFAULT_SOURCE (legacy entityId).
         * Do not use for UI active source, new favorites, or download enqueue.
         */
        fun parse(raw: String): WorkId {
            val idx = raw.indexOf(':')
            if (idx < 0) return WorkId(DEFAULT_SOURCE, raw)
            val source = raw.substring(0, idx)
            val toonId = raw.substring(idx + 1)
            return WorkId(source.ifBlank { DEFAULT_SOURCE }, toonId)
        }

        /**
         * inbound sync / DB row. Blank sourceId is DEFAULT_SOURCE (old servers).
         * local id is accepted here; SyncWire filters it.
         */
        fun stored(sourceId: String, toonId: String): WorkId? {
            val sid = sourceId.ifBlank { DEFAULT_SOURCE }
            if (toonId.isBlank()) return null
            return try {
                WorkId(sid, toonId)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /**
         * New writes. Blank sourceId is rejected.
         * [installedIds]: currently installed sources.
         */
        fun writable(
            sourceId: String,
            toonId: String,
            sourceEnabled: Boolean,
            installedIds: Set<String>,
        ): WorkId? {
            if (sourceId.isBlank() || toonId.isBlank() || !sourceEnabled) return null
            if (sourceId !in installedIds) return null
            return try {
                WorkId(sourceId, toonId)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
