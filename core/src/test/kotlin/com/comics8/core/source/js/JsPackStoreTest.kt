package com.comics8.core.source.js

import com.comics8.core.source.HostApi
import com.comics8.core.source.SourceRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class JsPackStoreTest {
    @Test
    fun safeIdStripsIllegalCharacters() {
        assertThat(JsPackStore.safeId("hello")).isEqualTo("hello")
        assertThat(JsPackStore.safeId("ok_id-1.2")).isEqualTo("ok_id-1.2")
        assertThat(JsPackStore.safeId("Hello World!")).isEqualTo("HelloWorld")
        assertThat(JsPackStore.safeId("a/b\\c:d")).isEqualTo("abcd")
        assertThat(JsPackStore.safeId("***")).isEmpty()
    }

    @Test
    fun copyRejectsBlankAndLocalWithoutWriting() {
        withStore { store, dir ->
            assertThrows(IllegalArgumentException::class.java) {
                store.copy("", 1, "x".toByteArray())
            }.also { assertThat(it.message).contains("id 없음") }
            assertThrows(IllegalArgumentException::class.java) {
                store.copy("   ", 1, "x".toByteArray())
            }
            val localError = assertThrows(IllegalArgumentException::class.java) {
                store.copy("local", 1, "x".toByteArray())
            }
            assertThat(localError.message).contains("예약")
            val stripped = assertThrows(IllegalArgumentException::class.java) {
                store.copy("local!", 1, "x".toByteArray())
            }
            assertThat(stripped.message).contains("예약")
            assertThrows(IllegalArgumentException::class.java) {
                store.copy("lo cal", 1, "x".toByteArray())
            }
            assertThat(dir.listFiles().orEmpty()).isEmpty()
        }
    }

    @Test
    fun ingestRejectsLocalAfterSanitizeWithoutWriting() {
        withStore { store, dir ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                store.ingest(minimalSource("local!"), "pack.js")
            }
            assertThat(error.message).contains("예약")
            assertThat(dir.listFiles().orEmpty()).isEmpty()
        }
    }

    @Test
    fun copyRejectsOversizedPayload() {
        withStore { store, dir ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                store.copy("hello", 1, ByteArray(JsPackStore.MAX_SCRIPT_BYTES + 1))
            }
            assertThat(error.message).contains("너무 큽니다")
            assertThat(dir.listFiles().orEmpty()).isEmpty()
        }
    }

    @Test
    fun copyRejectsApiLevelAboveHost() {
        withStore { store, dir ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                store.copy("future", HostApi.LEVEL + 1, "x".toByteArray())
            }
            assertThat(error.message).contains("앱 업데이트가 필요합니다")
            assertThat(dir.listFiles().orEmpty()).isEmpty()
        }
    }

    @Test
    fun copyWritesSafeIdFileAndReplacesSameId() {
        withStore { store, _ ->
            val first = store.copy("Hello World!", 1, "v1".toByteArray())
            assertThat(first.name).isEqualTo("HelloWorld.js")
            assertThat(first.readText()).isEqualTo("v1")
            val replaced = store.copy("Hello World!", 1, "v2".toByteArray())
            assertThat(replaced.canonicalPath).isEqualTo(first.canonicalPath)
            assertThat(first.readText()).isEqualTo("v2")
            assertThat(store.list().map { it.name }).containsExactly("HelloWorld.js")
            assertThat(store.delete("Hello World!")).isTrue()
            assertThat(store.list()).isEmpty()
        }
    }

    @Test
    fun ingestAndLoadAllRoundTripHelloFixture() {
        withStore { store, _ ->
            val script = JsTestResources.read("js/hello.js")
            val ingested = store.ingest(script, "hello.js")
            assertThat(ingested.id).isEqualTo("hello")
            assertThat(store.fileFor("hello").readText()).isEqualTo(script)

            val registry = SourceRegistry()
            val loaded = store.loadInto(registry)
            assertThat(loaded.map { it.id }).containsExactly("hello")
            assertThat(registry.get("hello").displayName).isEqualTo("Hello")
        }
    }

    @Test
    fun loadAllSkipsLocalAndHighApiLevelFiles() {
        withStore { store, dir ->
            File(dir, "hello.js").writeText(JsTestResources.read("js/hello.js"))
            File(dir, "local.js").writeText(minimalSource("local"))
            File(dir, "bang.js").writeText(minimalSource("local!"))
            File(dir, "future.js").writeText(minimalSource("future", apiLevel = 99))
            assertThat(store.loadAll().map { it.id }).containsExactly("hello")
        }
    }

    private fun minimalSource(id: String, apiLevel: Int = 1): String = """
        var source = {
          id: "$id",
          displayName: "$id",
          apiLevel: $apiLevel,
          origin: "https://x.test",
          catalogs: [],
          loadListing: function() { return { items: [], currentPage: 1, lastPage: 1 }; },
          loadEpisodes: function() { return { items: [], currentPage: 1, lastPage: 1 }; },
          resolveImages: function() { return []; }
        };
    """.trimIndent()

    private fun withStore(block: (JsPackStore, File) -> Unit) {
        val dir = File.createTempFile("jspack", "dir").apply {
            delete()
            mkdirs()
        }
        try {
            block(JsPackStore(dir), dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
