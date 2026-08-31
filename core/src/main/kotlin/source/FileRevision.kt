package com.comics8.core.source

/** Metadata that identifies one observable version of a file. */
data class FileRevision(
    val sizeBytes: Long,
    val modifiedAtEpochMs: Long,
    val entityTag: String? = null,
) {
    companion object {
        val UNKNOWN = FileRevision(-1L, 0L, null)
    }
}
