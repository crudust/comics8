package com.comics8.core.source

data class SourceCatalog(
    val id: String,
    val label: String,
    val paginated: Boolean,
)

data class RequestPolicy(
    val userAgent: String,
    val referer: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
)

data class FetchSpec(
    val url: String,
    val policy: RequestPolicy,
    val headers: Map<String, String> = emptyMap(),
)

data class SearchQuery(
    val text: String,
    val language: String? = null,
    val type: String? = null,
)

data class SourceConfig(
    val language: String? = null,
)

enum class NotificationMode {
    LATEST_INTERSECTION,
    PER_FAVORITE,
    NONE,
}
