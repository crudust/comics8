package com.comics8.core.source

import com.comics8.core.model.Catalog
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ElevenToonSourceTest {
    @Test
    fun catalogsExcludeFavorite() {
        val catalogs = ElevenToonSource().catalogs
        assertThat(catalogs.map { it.id }).containsExactly(
            Catalog.LATEST.name,
            Catalog.POPULAR.name,
            Catalog.COMPLETE.name,
            Catalog.TODAY.name,
        ).inOrder()
        assertThat(catalogs.none { it.id == Catalog.FAVORITE.name }).isTrue()
    }

    @Test
    fun loadListingUsesPrivateListingSpec() = runBlocking {
        val html = readFixture("listing_latest.html")
        val http = RecordingHttp(html)
        val page = ElevenToonSource().loadListing(Catalog.LATEST.name, 1, http)
        assertThat(http.urls).hasSize(1)
        assertThat(http.urls[0]).contains("bo_table=toon_c")
        assertThat(http.urls[0]).contains("type=upd")
        assertThat(page.items).isNotEmpty()
        assertThat(page.items[0].sourceId).isEqualTo(WorkId.DEFAULT_SOURCE)
        assertThat(page.items[0].id).isEqualTo("7883")
    }

    private fun readFixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().readText()

    private class RecordingHttp(private val body: String) : SourceHttp {
        val urls = mutableListOf<String>()

        override fun fetch(spec: FetchSpec): HttpResult {
            urls += spec.url
            return HttpResult(200, emptyMap(), body.toByteArray())
        }

        override fun fetchText(spec: FetchSpec): String {
            urls += spec.url
            return body
        }

        override fun isAccessible(url: String, policy: RequestPolicy): Boolean = true
    }
}
