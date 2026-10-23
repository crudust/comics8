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

/**
 * JS-plugin-facing mirror of [com.comics8.core.model.ProgressDisplayMode] for the two legacy modes.
 * It exists only so `source.progressDisplay` in a JS pack keeps parsing. The value is mapped to
 * [com.comics8.core.model.ProgressDisplayMode] at the source boundary; no formatting or comparison
 * happens on this enum.
 */
enum class ProgressDisplay {
    LAST_READ_ORDER,
    READ_COUNT,
    ;

    val mode: com.comics8.core.model.ProgressDisplayMode
        get() = when (this) {
            LAST_READ_ORDER -> com.comics8.core.model.ProgressDisplayMode.LATEST_EPISODE
            READ_COUNT -> com.comics8.core.model.ProgressDisplayMode.READ_COUNT
        }

    companion object {
        fun fromMode(mode: com.comics8.core.model.ProgressDisplayMode): ProgressDisplay =
            when (mode) {
                com.comics8.core.model.ProgressDisplayMode.READ_COUNT -> READ_COUNT
                else -> LAST_READ_ORDER
            }
    }
}
