package com.comics8.core.source.js

import com.comics8.core.source.FetchSpec
import com.comics8.core.source.HttpResult
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SourceHttp
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

class RawkumaDebugLiveTest {
    @Test
    fun debugHomeParsing() {
        val html = File("/tmp/debug_home.html").readText()
        val engine = JsEngine()
        val file = File(workspaceRoot(), "examples/sources/rawkuma.js")
        val handle = engine.load(file.readText(), "rawkuma.js")
        val source = JsComicSource(engine, handle)

        val http = object : SourceHttp {
            override fun fetch(spec: FetchSpec): HttpResult = HttpResult(200, emptyMap(), html.toByteArray())
            override fun fetchText(spec: FetchSpec): String = html
            override fun isAccessible(url: String, policy: RequestPolicy): Boolean = true
        }

        val page = runBlocking { source.loadListing("LATEST", 1, http) }
        println("=== Extracted ${page.items.size} items ===")
        page.items.forEachIndexed { i, it ->
            println("[$i] id=${it.id}, title=${it.title}, thumb=${it.thumbUrl}")
        }
    }

    private fun workspaceRoot(): File {
        val cwd = File(System.getProperty("user.dir")).canonicalFile
        var dir: File? = cwd
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("workspace root not found")
    }
}
