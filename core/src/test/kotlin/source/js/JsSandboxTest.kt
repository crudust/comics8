package com.comics8.core.source.js

import com.comics8.core.source.FetchSpec
import com.comics8.core.source.HttpResult
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SourceHttp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class JsSandboxTest {
    @Test
    fun runtimeJavaNioFilesAndPackagesAreHidden() {
        val engine = JsEngine()
        val handle = engine.load(JsTestResources.read("js/hello.js"), "hello.js")
        assertFails(engine, handle, "java.lang.Runtime.getRuntime()")
        assertFails(engine, handle, "java.nio.file.Files")
        assertFails(engine, handle, "Packages")
        assertFails(engine, handle, "Packages.java.io.File")
        assertFails(engine, handle, "new java.io.File('/')")
        assertFails(engine, handle, "org.mozilla.javascript.Context.getCurrentContext()")
    }

    @Test
    fun classShutterAllowsOnlyJsHostPackage() {
        val shutter = JsSandbox.classShutter()
        assertThat(shutter.visibleToScripts("com.comics8.core.source.js.HostObject")).isTrue()
        assertThat(shutter.visibleToScripts("com.comics8.core.source.js.HostApiV1Impl")).isTrue()
        assertThat(shutter.visibleToScripts("java.lang.Runtime")).isFalse()
        assertThat(shutter.visibleToScripts("java.nio.file.Files")).isFalse()
        assertThat(shutter.visibleToScripts("java.io.File")).isFalse()
        assertThat(shutter.visibleToScripts("org.mozilla.javascript.Context")).isFalse()
        assertThat(shutter.visibleToScripts("com.comics8.core.source.ComicSource")).isFalse()
    }

    @Test
    fun apiLevelAboveHostIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            JsEngine().load(
                """
                var source = {
                  id: "future",
                  displayName: "Future",
                  apiLevel: 99,
                  origin: "https://x.test",
                  catalogs: [],
                  loadListing: function() { return { items: [], currentPage: 1, lastPage: 1 }; },
                  loadEpisodes: function() { return { items: [], currentPage: 1, lastPage: 1 }; },
                  resolveImages: function() { return []; }
                };
                """.trimIndent(),
                "future.js",
            )
        }
        assertThat(error.message).contains("앱 업데이트가 필요합니다")
    }

    @Test
    fun apiLevelBelowOneIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            JsEngine().load(
                """
                var source = {
                  id: "old",
                  displayName: "Old",
                  apiLevel: 0,
                  origin: "https://x.test",
                  catalogs: [],
                  loadListing: function() { return { items: [], currentPage: 1, lastPage: 1 }; },
                  loadEpisodes: function() { return { items: [], currentPage: 1, lastPage: 1 }; },
                  resolveImages: function() { return []; }
                };
                """.trimIndent(),
                "old.js",
            )
        }
    }

    @Test
    fun missingSourceObjectIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            JsEngine().load("var foo = 1;", "missing.js")
        }
        assertThat(error.message).contains("source가 없음")
    }

    @Test
    fun sourceCallTimesOut() {
        val engine = JsEngine(callTimeoutMs = 400)
        val handle = engine.load(
            """
            var source = {
              id: "slow",
              displayName: "Slow",
              apiLevel: 1,
              origin: "https://slow.test",
              catalogs: [],
              loadListing: function() { for (;;) {} },
              loadEpisodes: function() { return { items: [], currentPage: 1, lastPage: 1 }; },
              resolveImages: function() { return []; }
            };
            """.trimIndent(),
            "slow.js",
        )
        val source = JsComicSource(engine, handle)
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                source.loadListing("LATEST", 1, NoopHttp)
            }
        }
        assertThat(error.message).contains("timed out")
    }

    private fun assertFails(engine: JsEngine, handle: JsSourceHandle, expression: String) {
        val error = runCatching { engine.eval(handle, expression) }.exceptionOrNull()
        assertThat(error).isNotNull()
    }

    private object NoopHttp : SourceHttp {
        override fun fetch(spec: FetchSpec): HttpResult = HttpResult(200, emptyMap(), ByteArray(0))
        override fun fetchText(spec: FetchSpec): String = ""
        override fun isAccessible(url: String, policy: RequestPolicy): Boolean = false
    }
}
