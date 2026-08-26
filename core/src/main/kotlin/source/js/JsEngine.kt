package com.comics8.core.source.js

import com.comics8.core.model.ArtistRef
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodePage
import com.comics8.core.model.ListingPage
import com.comics8.core.model.ToonItem
import com.comics8.core.network.ToonClient
import com.comics8.core.source.HostApi
import com.comics8.core.source.NotificationMode
import com.comics8.core.source.ProgressDisplay
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SearchQuery
import com.comics8.core.source.SearchSuggestion
import com.comics8.core.source.SourceCatalog
import com.comics8.core.source.SourceConfig
import com.comics8.core.source.SourceHttp
import com.comics8.core.source.SourceRegistry
import org.mozilla.javascript.Context
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.Function
import org.mozilla.javascript.JavaScriptException
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.WrappedException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class JsEngine(
    private val callTimeoutMs: Long = JsSandbox.DEFAULT_CALL_TIMEOUT_MS,
) {
    fun load(
        script: String,
        fileName: String = "<source.js>",
        http: SourceHttp? = null,
    ): JsSourceHandle {
        val api = HostApiV1Impl(
            policy = RequestPolicy(userAgent = ToonClient.USER_AGENT),
            language = null,
        )
        val cx = JsSandbox.enter()
        val deadline = System.currentTimeMillis() + callTimeoutMs
        api.deadlineEpochMs = deadline
        api.http = http
        cx.putThreadLocal(JsSandbox.DEADLINE_KEY, deadline)
        val interrupter = scheduleInterrupt(deadline)
        try {
            val scope = JsSandbox.createScope(cx)
            api.scope = scope
            HostObject(api).install(scope)
            cx.evaluateString(scope, script, fileName, 1, null)
            val sourceObj = ScriptableObject.getProperty(scope, "source") as? Scriptable
                ?: throw IllegalArgumentException("source가 없음")
            return readHandle(api, scope, sourceObj)
        } catch (e: Exception) {
            throw translate(e)
        } finally {
            interrupter.cancel(false)
            Thread.interrupted()
            api.http = null
            Context.exit()
        }
    }

    fun callListing(
        handle: JsSourceHandle,
        http: SourceHttp,
        catalogId: String,
        page: Int,
    ): ListingPage {
        val result = invoke(handle, "loadListing", http, arrayOf(catalogId, Integer.valueOf(page)))
        return listingFromJs(result, handle.id)
    }

    fun callSearch(handle: JsSourceHandle, http: SourceHttp, query: SearchQuery): List<ToonItem> {
        val result = invoke(handle, "search", http, arrayOf(query))
        return JsValues.asList(result).map { toonFromJs(it, handle.id) }
    }

    fun callSuggest(handle: JsSourceHandle, http: SourceHttp, query: SearchQuery): List<SearchSuggestion> {
        if (!handle.hasFunction("suggest")) return emptyList()
        val result = invoke(handle, "suggest", http, arrayOf(query))
        return JsValues.asList(result).map { suggestionFromJs(it) }
    }

    fun callEpisodes(
        handle: JsSourceHandle,
        http: SourceHttp,
        item: ToonItem,
        page: Int,
    ): EpisodePage {
        val result = invoke(handle, "loadEpisodes", http, arrayOf(item, Integer.valueOf(page)))
        return episodePageFromJs(result)
    }

    fun callImages(
        handle: JsSourceHandle,
        http: SourceHttp,
        episode: EpisodeItem,
        item: ToonItem,
    ): List<String> {
        val result = invoke(handle, "resolveImages", http, arrayOf(episode, item))
        val images = JsValues.asList(result).map { Context.toString(it) }
        if (images.isEmpty()) {
            error("만화 이미지를 불러오지 못했습니다.")
        }
        return images
    }

    fun callStringList(handle: JsSourceHandle, name: String, arg: Any?): List<String> {
        if (!handle.hasFunction(name)) return emptyList()
        val result = invoke(handle, name, http = null, args = arrayOf(arg))
        return JsValues.asList(result).map { Context.toString(it) }
    }

    fun callString(handle: JsSourceHandle, name: String, arg: Any?): String? {
        if (!handle.hasFunction(name)) return null
        val result = invoke(handle, name, http = null, args = arrayOf(arg))
        return JsValues.asString(result)
    }

    fun callBoolean(handle: JsSourceHandle, name: String, arg: Any?, default: Boolean): Boolean {
        if (!handle.hasFunction(name)) return default
        val result = invoke(handle, name, http = null, args = arrayOf(arg))
        return JsValues.asBoolean(result, default)
    }

    fun callResolveParent(
        handle: JsSourceHandle,
        item: ToonItem,
        choice: ArtistRef,
        entryEpisodeId: String?,
    ): ToonItem? {
        if (handle.hasFunction("resolveParent")) {
            val result = invoke(handle, "resolveParent", http = null, args = arrayOf(item, choice, entryEpisodeId))
            if (!JsValues.isNullish(result)) return toonFromJs(result, handle.id)
        }
        val entry = entryEpisodeId?.takeIf { it.isNotBlank() } ?: item.entryEpisodeId
        return ToonItem(
            id = "artist:${choice.slug}",
            title = choice.displayName,
            thumbUrl = item.thumbUrl,
            href = item.href,
            genre = item.genre,
            updatedAt = item.updatedAt,
            sourceId = handle.id,
            entryEpisodeId = entry,
        )
    }

    fun callNotificationCandidates(handle: JsSourceHandle, favorites: List<ToonItem>): List<ToonItem> {
        if (handle.hasFunction("notificationCandidates")) {
            val result = invoke(handle, "notificationCandidates", http = null, args = arrayOf(favorites))
            return JsValues.asList(result).map { toonFromJs(it, handle.id) }
        }
        if (handle.notificationMode == NotificationMode.PER_FAVORITE) {
            return favorites.filter { it.id.startsWith("artist:") }
        }
        return favorites
    }

    fun callSupportsChapterNotifications(handle: JsSourceHandle, item: ToonItem, default: Boolean): Boolean {
        if (handle.hasFunction("supportsChapterNotifications")) {
            val result = invoke(handle, "supportsChapterNotifications", http = null, args = arrayOf(item))
            return JsValues.asBoolean(result, default)
        }
        if (handle.notificationMode == NotificationMode.PER_FAVORITE) {
            return item.id.startsWith("artist:")
        }
        return default
    }

    fun applyConfig(handle: JsSourceHandle, config: SourceConfig) {
        handle.api.language = config.language
        if (!handle.hasFunction("applyConfig")) return
        invoke(handle, "applyConfig", http = null, args = arrayOf(config))
    }

    fun eval(handle: JsSourceHandle, script: String, http: SourceHttp? = null): Any? {
        return invokeRaw(handle, http) { cx ->
            cx.evaluateString(handle.scope, script, "<eval>", 1, null)
        }
    }

    private fun invoke(
        handle: JsSourceHandle,
        name: String,
        http: SourceHttp?,
        args: Array<Any?>,
    ): Any? {
        return invokeRaw(handle, http) { cx ->
            val fn = ScriptableObject.getProperty(handle.sourceObj, name) as? Function
                ?: error("source.$name is not a function")
            val jsArgs = args.map { javaToJs(cx, handle.scope, it) }.toTypedArray()
            fn.call(cx, handle.scope, handle.sourceObj, jsArgs)
        }
    }

    private fun invokeRaw(
        handle: JsSourceHandle,
        http: SourceHttp?,
        block: (Context) -> Any?,
    ): Any? {
        synchronized(handle.lock) {
            val cx = JsSandbox.enter()
            val deadline = System.currentTimeMillis() + callTimeoutMs
            handle.api.deadlineEpochMs = deadline
            handle.api.http = http
            cx.putThreadLocal(JsSandbox.DEADLINE_KEY, deadline)
            val interrupter = scheduleInterrupt(deadline)
            try {
                return block(cx)
            } catch (e: Exception) {
                throw translate(e)
            } finally {
                interrupter.cancel(false)
                Thread.interrupted()
                handle.api.http = null
                Context.exit()
            }
        }
    }

    private fun scheduleInterrupt(deadline: Long) =
        JsSandbox.timeoutScheduler.schedule(
            interruptTask(Thread.currentThread()),
            (deadline - System.currentTimeMillis()).coerceAtLeast(0L),
            TimeUnit.MILLISECONDS,
        )

    private fun interruptTask(thread: Thread) = Runnable { thread.interrupt() }

    private fun readHandle(
        api: HostApiV1Impl,
        scope: Scriptable,
        sourceObj: Scriptable,
    ): JsSourceHandle {
        val id = requiredString(sourceObj, "id")
        val displayName = requiredString(sourceObj, "displayName")
        val apiLevel = JsValues.asInt(ScriptableObject.getProperty(sourceObj, "apiLevel"), 1)
        if (apiLevel < 1) throw IllegalArgumentException("apiLevel")
        if (apiLevel > HostApi.LEVEL) throw IllegalArgumentException("앱 업데이트가 필요합니다")
        requireFunction(sourceObj, "loadListing")
        requireFunction(sourceObj, "loadEpisodes")
        requireFunction(sourceObj, "resolveImages")
        val origin = JsValues.asString(ScriptableObject.getProperty(sourceObj, "origin")).orEmpty()
        val userAgent = JsValues.asString(ScriptableObject.getProperty(sourceObj, "userAgent"))
            ?: ToonClient.USER_AGENT
        val referer = JsValues.asString(ScriptableObject.getProperty(sourceObj, "referer"))
            ?: origin.takeIf { it.isNotBlank() }
        val extraHeaders = readStringMap(ScriptableObject.getProperty(sourceObj, "extraHeaders"))
        val policy = RequestPolicy(userAgent = userAgent, referer = referer, extraHeaders = extraHeaders)
        api.policy = policy
        api.sourceId = id
        api.sourceObj = sourceObj
        api.language = JsValues.asString(ScriptableObject.getProperty(sourceObj, "defaultLanguage"))
        val functions = SOURCE_FUNCTIONS.filter { isFunction(sourceObj, it) }.toSet()
        val catalogs = catalogsFromJs(ScriptableObject.getProperty(sourceObj, "catalogs"))
        return JsSourceHandle(
            id = id,
            displayName = displayName,
            apiLevel = apiLevel,
            origin = origin,
            catalogs = if (catalogs.isEmpty()) listOf(SourceCatalog("LATEST", "최신", true)) else catalogs,
            defaultPolicy = policy,
            searchPlaceholder = JsValues.asString(ScriptableObject.getProperty(sourceObj, "searchPlaceholder"))
                ?: "제목 검색",
            notificationMode = parseNotificationMode(
                JsValues.asString(ScriptableObject.getProperty(sourceObj, "notificationMode")),
            ),
            episodePageSize = JsValues.asInt(ScriptableObject.getProperty(sourceObj, "episodePageSize"), 100),
            emptyListingOk = JsValues.asBoolean(ScriptableObject.getProperty(sourceObj, "emptyListingOk"), false),
            emptyEpisodesOk = JsValues.asBoolean(ScriptableObject.getProperty(sourceObj, "emptyEpisodesOk"), false),
            defaultLanguage = JsValues.asString(ScriptableObject.getProperty(sourceObj, "defaultLanguage")),
            progressDisplay = parseProgressDisplay(
                JsValues.asString(ScriptableObject.getProperty(sourceObj, "progressDisplay")),
            ),
            functions = functions,
            scope = scope,
            sourceObj = sourceObj,
            api = api,
        )
    }

    companion object {
        private val SOURCE_FUNCTIONS = listOf(
            "loadListing",
            "search",
            "suggest",
            "loadEpisodes",
            "resolveImages",
            "imageFallbacks",
            "imageReferer",
            "coverUrl",
            "ownsHost",
            "useProxy",
            "resolveParent",
            "supportsChapterNotifications",
            "notificationCandidates",
            "applyConfig",
        )
    }
}

class JsSourceHandle internal constructor(
    val id: String,
    val displayName: String,
    val apiLevel: Int,
    val origin: String,
    val catalogs: List<SourceCatalog>,
    val defaultPolicy: RequestPolicy,
    val searchPlaceholder: String,
    val notificationMode: NotificationMode,
    val episodePageSize: Int,
    val emptyListingOk: Boolean,
    val emptyEpisodesOk: Boolean,
    val defaultLanguage: String?,
    val progressDisplay: ProgressDisplay,
    internal val functions: Set<String>,
    internal val scope: Scriptable,
    internal val sourceObj: Scriptable,
    internal val api: HostApiV1Impl,
    internal val lock: Any = Any(),
) {
    val originHost: String? = SourceRegistry.hostOf(origin)

    fun hasFunction(name: String): Boolean = functions.contains(name)
}

internal fun translate(error: Throwable): RuntimeException {
    val unwrapped = unwrapRhino(error)
    val message = unwrapped.message ?: error.message.orEmpty()
    if (unwrapped is InterruptedException ||
        message.contains("timed out") ||
        (unwrapped is EvaluatorException && message.contains("timed out"))
    ) {
        return IllegalStateException("source call timed out", unwrapped)
    }
    return when (unwrapped) {
        is RuntimeException -> unwrapped
        else -> IllegalStateException(message.ifBlank { unwrapped.javaClass.simpleName }, unwrapped)
    }
}

private fun unwrapRhino(error: Throwable): Throwable {
    var cur: Throwable = error
    val seen = HashSet<Throwable>()
    while (seen.add(cur)) {
        cur = when (cur) {
            is WrappedException -> cur.wrappedException ?: return cur
            is JavaScriptException -> (cur.value as? Throwable) ?: return cur
            is InvocationTargetException -> cur.targetException ?: return cur
            is ExecutionException -> cur.cause ?: return cur
            is RhinoException -> return cur
            else -> return cur
        }
    }
    return cur
}

private fun requiredString(obj: Scriptable, name: String): String =
    JsValues.asString(ScriptableObject.getProperty(obj, name))?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("source.$name is required")

private fun requireFunction(obj: Scriptable, name: String) {
    if (!isFunction(obj, name)) {
        throw IllegalArgumentException("source.$name function is required")
    }
}

internal fun isFunction(scope: Scriptable, name: String): Boolean {
    val prop = ScriptableObject.getProperty(scope, name)
    return prop is Function
}

private fun parseNotificationMode(value: String?): NotificationMode =
    when (value?.uppercase()) {
        "LATEST_INTERSECTION" -> NotificationMode.LATEST_INTERSECTION
        "PER_FAVORITE" -> NotificationMode.PER_FAVORITE
        else -> NotificationMode.NONE
    }

private fun parseProgressDisplay(value: String?): ProgressDisplay =
    when (value?.uppercase()) {
        "READ_COUNT" -> ProgressDisplay.READ_COUNT
        else -> ProgressDisplay.LAST_READ_ORDER
    }

private fun catalogsFromJs(value: Any?): List<SourceCatalog> {
    return JsValues.asList(value).mapNotNull { entry ->
        val obj = entry as? Scriptable ?: return@mapNotNull null
        val id = JsValues.asString(ScriptableObject.getProperty(obj, "id")) ?: return@mapNotNull null
        val label = JsValues.asString(ScriptableObject.getProperty(obj, "label")) ?: id
        val paginated = JsValues.asBoolean(ScriptableObject.getProperty(obj, "paginated"), false)
        SourceCatalog(id = id, label = label, paginated = paginated)
    }
}

private fun extractPageInfo(obj: Scriptable): PageInfoResult {
    val pageInfo = ScriptableObject.getProperty(obj, "pageInfo") as? Scriptable
    val target = pageInfo ?: obj
    val current = JsValues.asInt(ScriptableObject.getProperty(target, "currentPage"), 1)
    val last = JsValues.asInt(ScriptableObject.getProperty(target, "lastPage"), current)
    return PageInfoResult(currentPage = current, lastPage = last)
}

private fun listingFromJs(value: Any?, sourceId: String): ListingPage {
    val obj = value as? Scriptable ?: error("expected listing page")
    val items = JsValues.asList(ScriptableObject.getProperty(obj, "items")).map { toonFromJs(it, sourceId) }
    val pages = extractPageInfo(obj)
    return ListingPage(
        items = items,
        currentPage = pages.currentPage,
        lastPage = pages.lastPage,
    )
}

private fun episodePageFromJs(value: Any?): EpisodePage {
    val obj = value as? Scriptable ?: error("expected episode page")
    val items = JsValues.asList(ScriptableObject.getProperty(obj, "items")).map { episodeFromJs(it) }
    val pages = extractPageInfo(obj)
    return EpisodePage(
        items = items,
        currentPage = pages.currentPage,
        lastPage = pages.lastPage,
    )
}

private fun toonFromJs(value: Any?, sourceId: String): ToonItem {
    val obj = value as? Scriptable ?: error("expected toon item")
    val id = JsValues.asString(ScriptableObject.getProperty(obj, "id")) ?: error("item.id")
    return ToonItem(
        id = id,
        title = JsValues.asString(ScriptableObject.getProperty(obj, "title")).orEmpty(),
        thumbUrl = JsValues.asString(ScriptableObject.getProperty(obj, "thumbUrl")).orEmpty(),
        href = JsValues.asString(ScriptableObject.getProperty(obj, "href")).orEmpty(),
        genre = JsValues.asString(ScriptableObject.getProperty(obj, "genre")).orEmpty(),
        updatedAt = JsValues.asString(ScriptableObject.getProperty(obj, "updatedAt")),
        ranking = JsValues.asString(ScriptableObject.getProperty(obj, "ranking")),
        isNew = JsValues.asBoolean(ScriptableObject.getProperty(obj, "isNew"), false),
        sourceId = sourceId,
        entryEpisodeId = JsValues.asString(ScriptableObject.getProperty(obj, "entryEpisodeId")),
        artistChoices = JsValues.asList(ScriptableObject.getProperty(obj, "artistChoices")).map { artistFromJs(it) },
    )
}

private fun episodeFromJs(value: Any?): EpisodeItem {
    val obj = value as? Scriptable ?: error("expected episode item")
    return EpisodeItem(
        wrId = JsValues.asString(ScriptableObject.getProperty(obj, "wrId")) ?: error("episode.wrId"),
        title = JsValues.asString(ScriptableObject.getProperty(obj, "title")).orEmpty(),
        date = JsValues.asString(ScriptableObject.getProperty(obj, "date")),
        thumbUrl = JsValues.asString(ScriptableObject.getProperty(obj, "thumbUrl")),
        href = JsValues.asString(ScriptableObject.getProperty(obj, "href")).orEmpty(),
        artistChoices = JsValues.asList(ScriptableObject.getProperty(obj, "artistChoices")).map { artistFromJs(it) },
    )
}

private fun artistFromJs(value: Any?): ArtistRef {
    val obj = value as? Scriptable ?: error("expected artist")
    return ArtistRef(
        slug = JsValues.asString(ScriptableObject.getProperty(obj, "slug")).orEmpty(),
        displayName = JsValues.asString(ScriptableObject.getProperty(obj, "displayName")).orEmpty(),
    )
}

private fun suggestionFromJs(value: Any?): SearchSuggestion {
    val obj = value as? Scriptable ?: error("expected suggestion")
    return SearchSuggestion(
        ns = JsValues.asString(ScriptableObject.getProperty(obj, "ns")).orEmpty(),
        tag = JsValues.asString(ScriptableObject.getProperty(obj, "tag")).orEmpty(),
        count = JsValues.asInt(ScriptableObject.getProperty(obj, "count"), 0),
    )
}

private fun javaToJs(cx: Context, scope: Scriptable, value: Any?): Any? {
    return when (value) {
        null -> null
        is String, is Number, is Boolean -> value
        is ToonItem -> toonToJs(cx, scope, value)
        is EpisodeItem -> episodeToJs(cx, scope, value)
        is ArtistRef -> artistToJs(cx, scope, value)
        is SearchQuery -> queryToJs(cx, scope, value)
        is SourceConfig -> configToJs(cx, scope, value)
        is List<*> -> cx.jsArray(scope, value.map { javaToJs(cx, scope, it) })
        else -> Context.toString(value)
    }
}

private fun toonToJs(cx: Context, scope: Scriptable, item: ToonItem): Scriptable {
    val obj = cx.newObject(scope)
    putJs(obj, "id", item.id)
    putJs(obj, "title", item.title)
    putJs(obj, "thumbUrl", item.thumbUrl)
    putJs(obj, "href", item.href)
    putJs(obj, "genre", item.genre)
    putJs(obj, "updatedAt", item.updatedAt)
    putJs(obj, "ranking", item.ranking)
    putJs(obj, "isNew", item.isNew)
    putJs(obj, "sourceId", item.sourceId)
    putJs(obj, "entryEpisodeId", item.entryEpisodeId)
    putJs(obj, "artistChoices", javaToJs(cx, scope, item.artistChoices))
    return obj
}

private fun episodeToJs(cx: Context, scope: Scriptable, item: EpisodeItem): Scriptable {
    val obj = cx.newObject(scope)
    putJs(obj, "wrId", item.wrId)
    putJs(obj, "title", item.title)
    putJs(obj, "date", item.date)
    putJs(obj, "thumbUrl", item.thumbUrl)
    putJs(obj, "href", item.href)
    putJs(obj, "artistChoices", javaToJs(cx, scope, item.artistChoices))
    return obj
}

private fun artistToJs(cx: Context, scope: Scriptable, artist: ArtistRef): Scriptable {
    val obj = cx.newObject(scope)
    putJs(obj, "slug", artist.slug)
    putJs(obj, "displayName", artist.displayName)
    return obj
}

private fun queryToJs(cx: Context, scope: Scriptable, query: SearchQuery): Scriptable {
    val obj = cx.newObject(scope)
    putJs(obj, "text", query.text)
    putJs(obj, "language", query.language)
    putJs(obj, "type", query.type)
    return obj
}

private fun configToJs(cx: Context, scope: Scriptable, config: SourceConfig): Scriptable {
    val obj = cx.newObject(scope)
    putJs(obj, "language", config.language)
    return obj
}

private fun putJs(obj: Scriptable, name: String, value: Any?) {
    ScriptableObject.putProperty(obj, name, value ?: Undefined.instance)
}

private fun readStringMap(value: Any?): Map<String, String> {
    if (JsValues.isNullish(value)) return emptyMap()
    val obj = value as? Scriptable ?: return emptyMap()
    val out = linkedMapOf<String, String>()
    for (id in obj.ids) {
        val raw = when (id) {
            is Int -> obj.get(id, obj)
            is String -> ScriptableObject.getProperty(obj, id)
            else -> ScriptableObject.getProperty(obj, id.toString())
        }
        if (JsValues.isNullish(raw)) continue
        out[id.toString()] = Context.toString(raw)
    }
    return out
}
