package com.comics8.core.source

object SourceAccess {
    fun isEnabled(sourceId: String, storedEnabled: (String) -> Boolean): Boolean {
        val sid = sourceId.ifBlank { WorkId.DEFAULT_SOURCE }
        return sid == WorkId.DEFAULT_SOURCE || storedEnabled(sid)
    }

    fun writable(sourceId: String, toonId: String, storedEnabled: (String) -> Boolean): WorkId? =
        WorkId.writable(sourceId, toonId, isEnabled(sourceId, storedEnabled))

    fun writable(workId: WorkId, storedEnabled: (String) -> Boolean): WorkId? =
        writable(workId.sourceId, workId.toonId, storedEnabled)
}
