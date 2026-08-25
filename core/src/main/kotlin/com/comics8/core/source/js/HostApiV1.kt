package com.comics8.core.source.js

import com.comics8.core.source.ComicSource
import com.comics8.core.source.FetchSpec
import com.comics8.core.source.HostApi
import com.comics8.core.source.HttpResult
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SourceHttp
import com.comics8.core.source.SourceRegistry
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.json.JsonParser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

data class PageInfoResult(
    val currentPage: Int,
    val lastPage: Int,
)

data class IndexPageResult(
    val ids: List<Long>,
    val currentPage: Int,
    val lastPage: Int,
)

interface HostApiV1 {
    val apiLevel: Int get() = HostApi.LEVEL
    val language: String?

    // Network & Serialization
    fun fetch(spec: HostFetchSpec): HostFetchResult
    fun fetchText(spec: HostFetchSpec): String
    fun fetchJson(spec: HostFetchSpec): Any?
    fun fetchAll(specs: List<HostFetchSpec>, concurrency: Int = 6): List<HostFetchResult>
    fun isAccessible(url: String): Boolean
    fun json(text: String): Any?
    fun jsonFromBody(body: ByteArray): Any?
    fun utf8(body: ByteArray): String
    fun evalSiteJs(kind: String, text: String): Any?
    fun log(level: String, message: String)

    // Parsing & Convenience Helpers
    fun parseHtml(text: String, baseUrl: String? = null): HostHtmlDoc
    fun absUrl(url: String, baseUrl: String? = null): String
    fun digits(text: String?): String
    fun match(text: String?, regex: String, groupIndex: Int = 1): String?
    fun slug(text: String?): String
    fun extractImages(htmlOrText: String, baseUrl: String? = null): List<String>
    fun parsePageInfo(doc: HostHtmlDoc): PageInfoResult
    fun fetchInt32Index(url: String, page: Int = 1, pageSize: Int = 25): IndexPageResult
    fun intersectIndexUrls(urls: List<String>, pageSize: Int = 25): List<Long>
    fun cacheGet(key: String): Any?
    fun cachePut(key: String, value: Any?, ttlMs: Long = 600000L)
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
    fun selectFirst(css: String): HostHtmlEl? = document.selectFirst(css)?.let { HostHtmlEl(it) }
    fun textOf(css: String): String = document.selectFirst(css)?.text().orEmpty()
    fun attrOf(css: String, attr: String): String = document.selectFirst(css)?.attr(attr).orEmpty()
    fun absUrlOf(css: String, attr: String): String = document.selectFirst(css)?.absUrl(attr).orEmpty()
}

class HostHtmlEl(internal val element: Element) {
    fun text(): String = element.text()
    fun html(): String = element.html()
    fun attr(name: String): String = element.attr(name)
    fun absUrl(attr: String): String = element.absUrl(attr)
    fun select(css: String): List<HostHtmlEl> = element.select(css).map { HostHtmlEl(it) }
    fun selectFirst(css: String): HostHtmlEl? = element.selectFirst(css)?.let { HostHtmlEl(it) }
    fun textOf(css: String): String = element.selectFirst(css)?.text().orEmpty()
    fun attrOf(css: String, attr: String): String = element.selectFirst(css)?.attr(attr).orEmpty()
    fun absUrlOf(css: String, attr: String): String = element.selectFirst(css)?.absUrl(attr).orEmpty()
    fun bgUrl(): String? = HostApiV1Impl.extractBackgroundUrl(element.attr("style"))
}

fun toFetchSpec(src: ComicSource, spec: HostFetchSpec): FetchSpec =
    toFetchSpec(src.defaultPolicy, spec)

internal fun toFetchSpec(policy: RequestPolicy, spec: HostFetchSpec): FetchSpec =
    FetchSpec(
        url = spec.url,
        policy = policy,
        headers = spec.headers,
    )

internal class NestedHostQuery(
    val ownsHost: Map<String, Boolean>,
    val useProxy: Map<String, Boolean>,
)

internal class HostApiV1Impl(
    @Volatile var policy: RequestPolicy,
    @Volatile override var language: String?,
    @Volatile var sourceId: String = "?",
) : HostApiV1 {
    @Volatile var http: SourceHttp? = null
    @Volatile var deadlineEpochMs: Long = Long.MAX_VALUE
    @Volatile var nestedHostQuery: NestedHostQuery? = null
    lateinit var scope: Scriptable
    lateinit var sourceObj: Scriptable

    private data class CacheEntry(val value: Any?, val expiresAt: Long)
    private val lruCache = ConcurrentHashMap<String, CacheEntry>()

    override fun fetch(spec: HostFetchSpec): HostFetchResult =
        withHostQuery(listOf(spec.url)) { fetchOnce(spec) }

    override fun fetchText(spec: HostFetchSpec): String {
        val result = fetch(spec)
        if (result.code !in 200..299) {
            error("HTTP ${result.code}")
        }
        return utf8(result.body)
    }

    override fun fetchJson(spec: HostFetchSpec): Any? {
        val text = fetchText(spec)
        return json(text)
    }

    override fun fetchAll(specs: List<HostFetchSpec>, concurrency: Int): List<HostFetchResult> {
        if (specs.isEmpty()) return emptyList()
        return withHostQuery(specs.map { it.url }) {
            val conc = concurrency.coerceAtLeast(1).coerceAtMost(specs.size)
            if (conc == 1 || specs.size == 1) {
                return@withHostQuery specs.map { fetchOnce(it) }
            }
            val pool = Executors.newFixedThreadPool(conc)
            try {
                val futures = specs.map { spec -> pool.submit<HostFetchResult> { fetchOnce(spec) } }
                futures.map { it.get() }
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("source call timed out", e)
            } finally {
                pool.shutdownNow()
            }
        }
    }

    override fun isAccessible(url: String): Boolean {
        checkAborted()
        if (url.isBlank()) return false
        return withHostQuery(listOf(url)) { requireHttp().isAccessible(url, policy) }
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

    override fun log(level: String, message: String) {
        System.err.println("[js:$sourceId] $level: $message")
    }

    override fun absUrl(url: String, baseUrl: String?): String {
        val value = url.trim().trim('\'', '"')
        if (value.isEmpty()) return value
        if (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) return value
        if (value.startsWith("//")) return "https:$value"
        val root = baseUrl?.trimEnd('/') ?: ""
        if (root.isEmpty()) return value
        if (value.startsWith("/")) return "$root$value"
        return "$root/$value"
    }

    override fun digits(text: String?): String =
        text?.replace(Regex("""\D"""), "").orEmpty()

    override fun match(text: String?, regex: String, groupIndex: Int): String? {
        if (text == null) return null
        return runCatching {
            Regex(regex).find(text)?.groups?.get(groupIndex)?.value
        }.getOrNull()
    }

    override fun slug(text: String?): String =
        text?.trim()?.lowercase()?.replace(Regex("""\s+"""), "_")?.replace(Regex("""^_+|_\+$"""), "").orEmpty()

    override fun extractImages(htmlOrText: String, baseUrl: String?): List<String> {
        val base = baseUrl.orEmpty()
        val candidateLists = parseAllImageCandidateLists(htmlOrText, base)
        if (candidateLists.isNotEmpty()) {
            if (candidateLists.size == 1) return candidateLists[0]
            val primary = candidateLists[0]
            if (primary.isNotEmpty() && isAccessible(primary[0])) return primary
            for (i in 1 until candidateLists.size) {
                val cand = candidateLists[i]
                if (cand.isNotEmpty() && isAccessible(cand[0])) return cand
            }
            return primary
        }
        return parseImagesFromDom(htmlOrText, base)
    }

    override fun parsePageInfo(doc: HostHtmlDoc): PageInfoResult {
        val d = doc.document
        var current = 1
        val curEl = d.selectFirst("strong.pg_current, .pagination .active, .page-item.active, .current-page")
        if (curEl != null) {
            val parsed = curEl.text().replace(Regex("""\D"""), "").toIntOrNull()
            if (parsed != null && parsed > 0) current = parsed
        }
        var last = current
        val pageRegex = Regex("""(?:[?&]|&amp;)page=(\d+)""")
        fun extractPage(href: String?): Int? {
            if (href.isNullOrBlank()) return null
            return pageRegex.find(href)?.groupValues?.get(1)?.toIntOrNull()
        }
        val endEl = d.selectFirst("a.pg_end, a.page-end, a[title*='끝']")
        if (endEl != null) {
            val fromEnd = extractPage(endEl.attr("href"))
            if (fromEnd != null && fromEnd > last) last = fromEnd
        }
        val pageEls = d.select("a.pg_page, .pagination a, .page-link")
        for (el in pageEls) {
            val fromPage = extractPage(el.attr("href")) ?: el.text().replace(Regex("""\D"""), "").toIntOrNull()
            if (fromPage != null && fromPage > last) last = fromPage
        }
        return PageInfoResult(currentPage = current, lastPage = last)
    }

    override fun fetchInt32Index(url: String, page: Int, pageSize: Int): IndexPageResult {
        val p = page.coerceAtLeast(1)
        val ps = pageSize.coerceAtLeast(1)
        val idBytes = 4
        val start = (p - 1) * ps * idBytes
        val end = p * ps * idBytes - 1
        val result = fetch(HostFetchSpec(url = url, headers = mapOf("Range" to "bytes=$start-$end")))
        if (result.code !in 200..299) {
            error("HTTP ${result.code}")
        }
        val ids = parseIdsU32be(result.body)
        val totalLength = result.totalLength()
        val last = if (totalLength != null && totalLength > 0) {
            val count = (totalLength / idBytes).toInt()
            val totalPages = (count + ps - 1) / ps
            totalPages.coerceAtLeast(1)
        } else {
            if (ids.size < ps) p else p + 1
        }
        return IndexPageResult(ids = ids, currentPage = p, lastPage = last)
    }

    override fun intersectIndexUrls(urls: List<String>, pageSize: Int): List<Long> {
        if (urls.isEmpty()) return emptyList()
        val limit = pageSize.coerceAtLeast(1)
        if (urls.size == 1) {
            return fetchInt32Index(urls[0], 1, limit).ids
        }

        class NozomiCursor(val url: String) {
            var page = 1
            var lastPage = Int.MAX_VALUE
            val buffer = ArrayDeque<Long>()

            fun peek(): Long? {
                while (buffer.isEmpty() && page <= lastPage) {
                    val res = fetchInt32Index(url, page, 25)
                    lastPage = res.lastPage
                    buffer.addAll(res.ids)
                    page++
                    if (res.ids.isEmpty()) break
                }
                return buffer.firstOrNull()
            }

            fun pop() {
                if (buffer.isNotEmpty()) buffer.removeFirst()
            }
        }

        val cursors = urls.map { NozomiCursor(it) }
        val out = mutableListOf<Long>()
        while (out.size < limit) {
            val heads = cursors.map { it.peek() }
            if (heads.any { it == null }) break
            val target = heads.filterNotNull().minOrNull() ?: break
            val allMatch = heads.all { it == target }
            if (allMatch) {
                out.add(target)
                cursors.forEach { it.pop() }
            } else {
                for (cursor in cursors) {
                    val head = cursor.peek() ?: return out
                    if (head > target) cursor.pop()
                }
            }
        }
        return out
    }

    override fun cacheGet(key: String): Any? {
        val entry = lruCache[key] ?: return null
        if (System.currentTimeMillis() >= entry.expiresAt) {
            lruCache.remove(key)
            return null
        }
        return entry.value
    }

    override fun cachePut(key: String, value: Any?, ttlMs: Long) {
        val expiresAt = System.currentTimeMillis() + ttlMs.coerceAtLeast(1000L)
        lruCache[key] = CacheEntry(value, expiresAt)
    }

    private fun u32be(body: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 4 <= body.size) { "u32be offset out of range" }
        return ((body[offset].toLong() and 0xFF) shl 24) or
            ((body[offset + 1].toLong() and 0xFF) shl 16) or
            ((body[offset + 2].toLong() and 0xFF) shl 8) or
            (body[offset + 3].toLong() and 0xFF)
    }

    private fun parseIdsU32be(body: ByteArray): List<Long> {
        val list = ArrayList<Long>(body.size / 4)
        var offset = 0
        while (offset + 4 <= body.size) {
            list.add(u32be(body, offset))
            offset += 4
        }
        return list
    }

    private fun parseAllImageCandidateLists(html: String, baseUri: String): List<List<String>> {
        val result = mutableListOf<List<String>>()
        val matches = JS_IMG_ARRAY_RE.findAll(html)
        for (m in matches) {
            val body = m.groupValues[1]
            val urls = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            val urlMatches = JS_STRING_URL_RE.findAll(body)
            for (um in urlMatches) {
                val rawUrl = um.groupValues[1].trim()
                if (rawUrl.isNotBlank() && isComicImageUrl(rawUrl)) {
                    val full = absUrl(rawUrl, baseUri)
                    if (seen.add(full)) urls.add(full)
                }
            }
            if (urls.isNotEmpty()) result.add(urls)
        }
        return result
    }

    private fun parseImagesFromDom(html: String, baseUri: String): List<String> {
        val doc = if (baseUri.isBlank()) Jsoup.parse(html) else Jsoup.parse(html, baseUri)
        val containers = doc.select("#scroll-list, .view-content, .scroll-viewer, #page-list, #contents-wrapper, #toon_view, .comic-view")
        val useDoc = containers.isEmpty()
        val imgNodes = if (useDoc) doc.select("img") else containers[0].select("img")
        val bgNodes = if (useDoc) doc.select("[style*='background']") else containers[0].select("[style*='background']")
        val urls = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (img in imgNodes) {
            val raw = when {
                img.hasAttr("data-src") -> img.absUrl("data-src").ifBlank { img.attr("data-src") }
                img.hasAttr("data-original") -> img.absUrl("data-original").ifBlank { img.attr("data-original") }
                img.hasAttr("data-lazy") -> img.absUrl("data-lazy").ifBlank { img.attr("data-lazy") }
                else -> img.absUrl("src").ifBlank { img.attr("src") }
            }
            if (raw.isNotBlank() && isComicImageUrl(raw)) {
                val full = absUrl(raw, baseUri)
                if (seen.add(full)) urls.add(full)
            }
        }
        for (bg in bgNodes) {
            val extracted = extractBackgroundUrl(bg.attr("style")) ?: continue
            if (isComicImageUrl(extracted)) {
                val full = absUrl(extracted, baseUri)
                if (seen.add(full)) urls.add(full)
            }
        }
        return urls
    }

    private fun isImageUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") ||
            lower.contains(".webp") || lower.contains(".avif") || lower.contains(".gif")
    }

    private fun isComicImageUrl(url: String): Boolean {
        if (!isImageUrl(url)) return false
        val lower = url.lowercase()
        return IGNORE_IMAGE_PATTERNS.none { lower.contains(it) }
    }

    internal fun checkAborted() {
        if (Thread.currentThread().isInterrupted) {
            throw IllegalStateException("source call timed out")
        }
        if (System.currentTimeMillis() > deadlineEpochMs) {
            throw IllegalStateException("source call timed out")
        }
    }

    private fun fetchOnce(spec: HostFetchSpec): HostFetchResult {
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

    private fun <T> withHostQuery(urls: List<String>, block: () -> T): T {
        val prev = nestedHostQuery
        nestedHostQuery = mergeHostQuery(urls, prev)
        try {
            return block()
        } finally {
            nestedHostQuery = prev
        }
    }

    private fun mergeHostQuery(urls: List<String>, prev: NestedHostQuery?): NestedHostQuery {
        val owns = LinkedHashMap<String, Boolean>()
        if (prev != null) owns.putAll(prev.ownsHost)
        val proxy = LinkedHashMap<String, Boolean>()
        if (prev != null) proxy.putAll(prev.useProxy)
        val cx = Context.getCurrentContext()
        val src = sourceObjOrNull()
        for (url in urls) {
            val host = SourceRegistry.hostOf(url)
            if (host != null && host !in owns) {
                owns[host] = if (cx != null && src != null) {
                    callSourceBoolean(cx, src, "ownsHost", host, false)
                } else {
                    false
                }
            }
            if (url !in proxy) {
                proxy[url] = if (cx != null && src != null) {
                    callSourceBoolean(cx, src, "useProxy", url, true)
                } else {
                    true
                }
            }
        }
        return NestedHostQuery(owns, proxy)
    }

    private fun sourceObjOrNull(): Scriptable? = if (this::sourceObj.isInitialized) sourceObj else null

    private fun callSourceBoolean(
        cx: Context,
        src: Scriptable,
        name: String,
        arg: String,
        default: Boolean,
    ): Boolean {
        val fn = ScriptableObject.getProperty(src, name) as? Function ?: return default
        val result = fn.call(cx, scope, src, arrayOf(arg))
        return JsValues.asBoolean(result, default)
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
        cx.evaluateString(evalScope, "var galleryinfo;", "<init>", 1, null)
        cx.evaluateString(evalScope, text, "galleryinfo", 1, null)
        val value = ScriptableObject.getProperty(evalScope, "galleryinfo")
        if (JsValues.isNullish(value)) error("galleryinfo")
        return value
    }

    private fun evalGg(cx: Context, evalScope: Scriptable, text: String): Any {
        cx.evaluateString(evalScope, "var gg;", "<init>", 1, null)
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
        private val JS_IMG_ARRAY_RE = Regex("""(?:var|let|const)\s+(?:img_list|img_list_2|data_list|arr_img|images|toon_images)\s*=\s*\[([\s\S]*?)\]""", RegexOption.IGNORE_CASE)
        private val JS_STRING_URL_RE = Regex("""["']([^"']+\.(?:jpg|jpeg|png|webp|gif|avif)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE)
        private val BG_URL_RE = Regex("""url\(\s*(['"]?)(.+?)\1\s*\)""", RegexOption.IGNORE_CASE)
        private val IGNORE_IMAGE_PATTERNS = listOf("icon-", "favicon", "192x192", "/toonfile/toonres/", "logo", "banner", "avatar")

        fun extractBackgroundUrl(style: String?): String? {
            if (style.isNullOrBlank()) return null
            return BG_URL_RE.find(style)?.groups?.get(2)?.value?.trim('\'', '"', ' ')
        }

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
