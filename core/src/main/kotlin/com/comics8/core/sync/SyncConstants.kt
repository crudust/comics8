package com.comics8.core.sync

object SyncConstants {
    const val KEY_SYNC_KEY = "sync_key"
    const val KEY_SERVER_URL = "sync_server_url"
    const val KEY_AUTO_SYNC = "sync_auto_enabled"
    const val KEY_LAST_SYNCED_AT = "sync_last_at"
    const val DEFAULT_SERVER_URL = "https://homeassistant.tail1946af.ts.net/api/comics8/sync"

    fun generateSyncKey(): String {
        val chars = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        return (1..4).map { chars.random() }.joinToString("")
    }
}
