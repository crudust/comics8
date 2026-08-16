package com.comics8.core.model

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class Catalog(
    val label: String,
    val paginated: Boolean,
) {
    LATEST("최신", paginated = true),
    FAVORITE("즐겨찾기", paginated = false),
    POPULAR("인기", paginated = false),
    COMPLETE("완결", paginated = false),
    TODAY("오늘", paginated = false);

    fun listingPath(page: Int): String {
        val p = page.coerceAtLeast(1)
        return when (this) {
            LATEST ->
                "/bbs/board.php?bo_table=toon_c&type=upd&tablename=${enc("최신만화")}&page=$p"
            FAVORITE ->
                error("즐겨찾기는 로컬 목록입니다.")
            POPULAR ->
                "/bbs/board.php?bo_table=toon_c&tablename=${enc("인기만화")}"
            COMPLETE ->
                "/bbs/board.php?bo_table=toon_c&is_over=1&tablename=${enc("완결만화")}"
            TODAY ->
                "/bbs/board.php?bo_table=toon_c&type=today&tablename=${enc("매일 추천 100")}"
        }
    }

    companion object {
        fun enc(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }
}
