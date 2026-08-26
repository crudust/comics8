package com.comics8.core.source

object SourceAccess {
    fun isEnabled(sourceId: String, storedEnabled: (String) -> Boolean): Boolean {
        if (sourceId.isBlank()) return false
        return storedEnabled(sourceId)
    }

    fun writable(
        sourceId: String,
        toonId: String,
        storedEnabled: (String) -> Boolean,
        installedIds: Set<String>,
    ): WorkId? = WorkId.writable(sourceId, toonId, isEnabled(sourceId, storedEnabled), installedIds)

    fun writable(
        workId: WorkId,
        storedEnabled: (String) -> Boolean,
        installedIds: Set<String>,
    ): WorkId? = writable(workId.sourceId, workId.toonId, storedEnabled, installedIds)
}
