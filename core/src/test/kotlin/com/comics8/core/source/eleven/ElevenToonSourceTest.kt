package com.comics8.core.source.eleven

import com.comics8.core.source.FetchSpec
import com.comics8.core.source.HttpResult
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SourceHttp
import com.comics8.core.source.WorkId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ElevenToonSourceTest {
    @Test
    fun catalogsExcludeFavorite() {
        val catalogs = ElevenToonSource().catalogs
        assertThat(catalogs.map { it.id }).containsExactly(
            ElevenCatalog.LATEST.name,
            ElevenCatalog.POPULAR.name,
            ElevenCatalog.COMPLETE.name,
            ElevenCatalog.TODAY.name,
        ).inOrder()
        assertThat(catalogs.none { it.id == ElevenCatalog.FAVORITE.name }).isTrue()
    }

    @Test
    fun loadListingUsesPrivateListingSpec() = runBlocking {
        val html = readFixture("listing_latest.html")
        val http = RecordingHttp(html)
        val page = ElevenToonSource().loadListing(ElevenCatalog.LATEST.name, 1, http)
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
