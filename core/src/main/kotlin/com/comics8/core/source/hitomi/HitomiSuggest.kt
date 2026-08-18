package com.comics8.core.source.hitomi

import com.comics8.core.source.SearchSuggestion
import org.json.JSONArray

object HitomiSuggest {
    const val ORIGIN = "https://tagindex.hitomi.la"

    fun lastTerm(query: String): Pair<String, String> {
        val last = query.trimEnd().split(Regex("\\s+")).lastOrNull().orEmpty().removePrefix("-")
        val idx = last.indexOf(':')
        if (idx > 0) {
            val ns = last.substring(0, idx).lowercase()
            val term = last.substring(idx + 1).replace('_', ' ')
            if (ns in HitomiSearch.namespaces) return ns to term
        }
        return "artist" to last.replace('_', ' ')
    }

    fun url(query: String): String? {
        if (query.isBlank()) return null
        val (field, term) = lastTerm(query)
        if (term.isBlank()) return null
        val path = term.lowercase().map { ch ->
            when (ch) {
                ' ' -> "_"
                '/' -> "slash"
                '.' -> "dot"
                else -> ch.toString()
            }
        }.joinToString("/")
        return "$ORIGIN/$field/$path.json"
    }

    fun parse(body: String): List<SearchSuggestion> {
        val root = try {
            JSONArray(body)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<SearchSuggestion>(root.length().coerceAtMost(10))
        for (i in 0 until root.length()) {
            if (out.size >= 10) break
            val row = root.optJSONArray(i) ?: continue
            val tag = row.optString(0).trim()
            val ns = row.optString(2).trim().ifBlank { "artist" }
            if (tag.isEmpty()) continue
            out += SearchSuggestion(ns = ns, tag = tag, count = row.optInt(1))
        }
        return out
    }

    fun apply(input: String, suggestion: SearchSuggestion): String = suggestion.applyTo(input)
}
