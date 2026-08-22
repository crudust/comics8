package com.comics8.core.source.js

import com.comics8.core.source.ComicSource
import com.comics8.core.source.FetchSpec
import com.comics8.core.source.HostApi
import com.comics8.core.source.HttpResult
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SourceHttp
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.json.JsonParser
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

interface HostApiV1 {
    val apiLevel: Int get() = HostApi.LEVEL
    val language: String?
    fun fetch(spec: HostFetchSpec): HostFetchResult
    fun fetchText(spec: HostFetchSpec): String
    fun fetchAll(specs: List<HostFetchSpec>, concurrency: Int = 6): List<HostFetchResult>
    fun isAccessible(url: String): Boolean
    fun parseHtml(text: String, baseUrl: String?): HostHtmlDoc
    fun json(text: String): Any?
    fun jsonFromBody(body: ByteArray): Any?
    fun utf8(body: ByteArray): String
    fun evalSiteJs(kind: String, text: String): Any?
    fun u32be(body: ByteArray, offset: Int): Long
    fun byteLength(body: ByteArray): Int
    fun slice(body: ByteArray, start: Int, endExclusive: Int): ByteArray
    fun log(level: String, message: String)
}

data class HostFetchSpec(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
)

class HostFetchResult(
    val code: Int,
    private val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun totalLength(): Long? = HttpResult(code, headers, body).totalLength()
}

class HostHtmlDoc(internal val document: Document) {
    fun select(css: String): List<HostHtmlEl> = document.select(css).map { HostHtmlEl(it) }
}

class HostHtmlEl(internal val element: Element) {
    fun text(): String = element.text()
    fun html(): String = element.html()
    fun attr(name: String): String = element.attr(name)
    fun absUrl(attr: String): String = element.absUrl(attr)
    fun select(css: String): List<HostHtmlEl> = element.select(css).map { HostHtmlEl(it) }
}

fun toFetchSpec(src: ComicSource, spec: HostFetchSpec): FetchSpec =
    toFetchSpec(src.defaultPolicy, spec)

internal fun toFetchSpec(policy: RequestPolicy, spec: HostFetchSpec): FetchSpec =
    FetchSpec(
        url = spec.url,
        policy = policy,
        headers = spec.headers,
    )

internal class HostApiV1Impl(
    @Volatile var policy: RequestPolicy,
    @Volatile override var language: String?,
    @Volatile var sourceId: String = "?",
) : HostApiV1 {
    @Volatile var http: SourceHttp? = null
    @Volatile var deadlineEpochMs: Long = Long.MAX_VALUE
    lateinit var scope: Scriptable

    override fun fetch(spec: HostFetchSpec): HostFetchResult {
        val method = spec.method.ifBlank { "GET" }.uppercase()
        require(method == "GET" || method == "HEAD") { "method must be GET or HEAD" }
        val client = requireHttp()
        if (method == "HEAD") {
            checkAborted()
            val ok = client.isAccessible(spec.url, policy)
            return HostFetchResult(if (ok) 200 else 404, emptyMap(), ByteArray(0))
        }
        val fetchSpec = toFetchSpec(policy, spec)
        var last: HttpResult? = null
        for (attempt in 0..BACKOFF_MS.size) {
            checkAborted()
            last = try {
                client.fetch(fetchSpec)
            } catch (e: Exception) {
                if (attempt == BACKOFF_MS.size) throw e
                sleepBackoff(attempt)
                continue
            }
            if (last.code in 200..299) return last.toHost()
            if (!retryableStatus(last.code) || attempt == BACKOFF_MS.size) return last.toHost()
            sleepBackoff(attempt)
        }
        return last!!.toHost()
    }

    override fun fetchText(spec: HostFetchSpec): String {
        val result = fetch(spec)
        if (result.code !in 200..299) {
            error("HTTP ${result.code}")
        }
        return utf8(result.body)
    }

    override fun fetchAll(specs: List<HostFetchSpec>, concurrency: Int): List<HostFetchResult> {
        if (specs.isEmpty()) return emptyList()
        val conc = concurrency.coerceAtLeast(1).coerceAtMost(specs.size)
        if (conc == 1 || specs.size == 1) {
            return specs.map { fetch(it) }
        }
        val pool = Executors.newFixedThreadPool(conc)
        try {
            val futures = specs.map { spec -> pool.submit<HostFetchResult> { fetch(spec) } }
            return futures.map { it.get() }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("source call timed out", e)
        } finally {
            pool.shutdownNow()
        }
    }

    override fun isAccessible(url: String): Boolean {
        checkAborted()
        if (url.isBlank()) return false
        return requireHttp().isAccessible(url, policy)
    }

    override fun parseHtml(text: String, baseUrl: String?): HostHtmlDoc {
        val doc = if (baseUrl.isNullOrBlank()) Jsoup.parse(text) else Jsoup.parse(text, baseUrl)
        return HostHtmlDoc(doc)
    }

    override fun json(text: String): Any? {
        val cx = Context.getCurrentContext() ?: error("no rhino context")
        return try {
            JsonParser(cx, scope).parseValue(text)
        } catch (e: JsonParser.ParseException) {
            error(e.message ?: "json")
        }
    }

    override fun jsonFromBody(body: ByteArray): Any? = json(utf8(body))

    override fun utf8(body: ByteArray): String = String(body, Charsets.UTF_8)

    override fun evalSiteJs(kind: String, text: String): Any? {
        val cx = Context.getCurrentContext() ?: error("no rhino context")
        val evalScope = JsSandbox.createScope(cx)
        return when (kind) {
            "galleryinfo" -> evalGalleryInfo(cx, evalScope, text)
            "gg" -> evalGg(cx, evalScope, text)
            else -> error("evalSiteJs kind must be galleryinfo or gg")
        }
    }

    override fun u32be(body: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 4 <= body.size) { "u32be offset out of range" }
        return ((body[offset].toLong() and 0xFF) shl 24) or
            ((body[offset + 1].toLong() and 0xFF) shl 16) or
            ((body[offset + 2].toLong() and 0xFF) shl 8) or
            (body[offset + 3].toLong() and 0xFF)
    }

    override fun byteLength(body: ByteArray): Int = body.size

    override fun slice(body: ByteArray, start: Int, endExclusive: Int): ByteArray {
        require(start >= 0 && endExclusive >= start && endExclusive <= body.size) { "slice out of range" }
        return body.copyOfRange(start, endExclusive)
    }

    override fun log(level: String, message: String) {
        System.err.println("[js:$sourceId] $level: $message")
    }

    internal fun checkAborted() {
        if (Thread.currentThread().isInterrupted) {
            throw IllegalStateException("source call timed out")
        }
        if (System.currentTimeMillis() > deadlineEpochMs) {
            throw IllegalStateException("source call timed out")
        }
    }

    private fun requireHttp(): SourceHttp = http ?: error("host.fetch requires SourceHttp")

    private fun sleepBackoff(attempt: Int) {
        checkAborted()
        try {
            Thread.sleep(BACKOFF_MS[attempt])
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("source call timed out", e)
        }
        checkAborted()
    }

    private fun evalGalleryInfo(cx: Context, evalScope: Scriptable, text: String): Any? {
        val extracted = extractGalleryInfoJson(text)
        if (extracted != null) {
            try {
                return JsonParser(cx, evalScope).parseValue(extracted)
            } catch (_: Exception) {
                // not JSON — evaluate as site JS
            }
        }
        cx.evaluateString(evalScope, text, "galleryinfo", 1, null)
        val value = ScriptableObject.getProperty(evalScope, "galleryinfo")
        if (JsValues.isNullish(value)) error("galleryinfo")
        return value
    }

    private fun evalGg(cx: Context, evalScope: Scriptable, text: String): Any {
        cx.evaluateString(evalScope, text, "gg", 1, null)
        val gg = ScriptableObject.getProperty(evalScope, "gg") as? Scriptable
            ?: error("gg")
        val out = cx.newObject(evalScope)
        ScriptableObject.putProperty(out, "b", ScriptableObject.getProperty(gg, "b"))
        ScriptableObject.putProperty(out, "m", ScriptableObject.getProperty(gg, "m"))
        ScriptableObject.putProperty(out, "s", ScriptableObject.getProperty(gg, "s"))
        return out
    }

    companion object {
        const val FETCH_CONCURRENCY = 6
        val BACKOFF_MS = listOf(250L, 1_000L, 4_000L)

        fun retryableStatus(code: Int): Boolean =
            code == 403 || code == 408 || code == 425 || code == 429 ||
                code == 500 || code == 502 || code == 503 || code == 504 ||
                code in 520..524

        fun extractGalleryInfoJson(js: String): String? {
            val trimmed = js.trim()
            if (trimmed.isEmpty()) return null
            val eq = trimmed.indexOf('=')
            val body = if (eq >= 0 && trimmed.regionMatches(0, "var galleryinfo", 0, 15, ignoreCase = true)) {
                trimmed.substring(eq + 1).trim()
            } else {
                trimmed
            }
            return body.trim().trimEnd(';').trim().takeIf { it.startsWith("{") }
        }
    }
}

private fun HttpResult.toHost(): HostFetchResult = HostFetchResult(code, headers, body)
