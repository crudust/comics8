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

data class SearchSuggestion(
    val ns: String,
    val tag: String,
    val count: Int = 0,
) {
    val queryToken: String
        get() = "$ns:${tag.trim().replace(' ', '_')}"

    fun applyTo(input: String): String {
        val trimmed = input.trimEnd()
        val cut = trimmed.lastIndexOf(' ')
        val prefix = if (cut >= 0) trimmed.substring(0, cut + 1) else ""
        return "$prefix$queryToken "
    }
}

data class SourceConfig(
    val language: String? = null,
)

enum class NotificationMode {
    LATEST_INTERSECTION,
    PER_FAVORITE,
    NONE,
}

enum class SourceKind { LOCAL, REMOTE }

object HostApi {
    const val LEVEL: Int = 1
}

enum class ProgressDisplay {
    LAST_READ_ORDER,
    READ_COUNT,
    ;

    fun format(lastReadOrder: Int, totalEpisodes: Int, readCount: Int): String = when (this) {
        LAST_READ_ORDER -> "$lastReadOrder/$totalEpisodes"
        READ_COUNT -> "$readCount/$totalEpisodes"
    }
}
