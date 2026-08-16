package com.comics8.monitor.data

import org.json.JSONObject

object SearchParser {
    private val dateRegex = Regex("""\d{4}-(\d{2})-(\d{2})""")

    fun parse(json: String): List<ToonItem> {
        val root = JSONObject(json)
        if (root.optString("status") != "success") return emptyList()
        val list = root.optJSONArray("list") ?: return emptyList()
        val items = ArrayList<ToonItem>(list.length())
        for (i in 0 until list.length()) {
            val row = list.optJSONObject(i) ?: continue
            val id = row.optString("wr_id").trim()
            val title = row.optString("wr_subject").trim()
            if (id.isEmpty() || title.isEmpty()) continue
            items += ToonItem(
                id = id,
                title = title,
                thumbUrl = ComicSite.coverUrl(id),
                href = ComicSite.seriesUrl(id, title),
                genre = row.optString("ca_name").trim(),
                updatedAt = formatDate(row.optString("wr_datetime")),
            )
        }
        return items.distinctBy { it.id }
    }

    internal fun formatDate(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val match = dateRegex.find(raw) ?: return null
        return "${match.groupValues[1]}.${match.groupValues[2]}"
    }
}
