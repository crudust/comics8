package com.comics8.core.source.js

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

internal class HostObject(
    val api: HostApiV1Impl,
) : ScriptableObject() {
    override fun getClassName(): String = "Host"

    fun install(scope: ScriptableObject) {
        parentScope = scope
        prototype = getObjectPrototype(scope)
        defineProperty("apiLevel", api.apiLevel, READONLY or PERMANENT)

        // Network & Serialization
        defineHostFn("fetch", 1) { cx, callScope, args ->
            wrapResult(cx, callScope, api.fetch(parseFetchSpec(JsValues.arg(args, 0))))
        }
        defineHostFn("fetchText", 1) { _, _, args ->
            api.fetchText(parseFetchSpec(JsValues.arg(args, 0)))
        }
        defineHostFn("fetchJson", 1) { _, _, args ->
            api.fetchJson(parseFetchSpec(JsValues.arg(args, 0)))
        }
        defineHostFn("fetchAll", 1) { cx, callScope, args ->
            val specs = JsValues.asList(JsValues.arg(args, 0)).map { parseFetchSpec(it) }
            val concurrency = if (args.size >= 2 && !JsValues.isNullish(args[1])) {
                JsValues.asInt(args[1], HostApiV1Impl.FETCH_CONCURRENCY)
            } else {
                HostApiV1Impl.FETCH_CONCURRENCY
            }
            val results = api.fetchAll(specs, concurrency)
            cx.jsArray(callScope, results.map { wrapResult(cx, callScope, it) })
        }
        defineHostFn("isAccessible", 1) { _, _, args ->
            api.isAccessible(Context.toString(JsValues.arg(args, 0)))
        }
        defineHostFn("json", 1) { _, _, args ->
            api.json(Context.toString(JsValues.arg(args, 0)))
        }
        defineHostFn("jsonFromBody", 1) { _, _, args ->
            api.jsonFromBody(unwrapBytes(JsValues.arg(args, 0)))
        }
        defineHostFn("utf8", 1) { _, _, args ->
            api.utf8(unwrapBytes(JsValues.arg(args, 0)))
        }
        defineHostFn("evalSiteJs", 2) { _, _, args ->
            api.evalSiteJs(
                Context.toString(JsValues.arg(args, 0)),
                Context.toString(JsValues.arg(args, 1)),
            )
        }
        defineHostFn("log", 2) { _, _, args ->
            api.log(
                Context.toString(JsValues.arg(args, 0)),
                Context.toString(JsValues.arg(args, 1)),
            )
            Undefined.instance
        }

        // Parsing & Convenience Helpers
        defineHostFn("parseHtml", 1) { _, callScope, args ->
            val text = Context.toString(JsValues.arg(args, 0))
            val baseUrl = if (args.size >= 2 && !JsValues.isNullish(args[1])) Context.toString(args[1]) else null
            JsHtmlDocView(api.parseHtml(text, baseUrl)).also { it.attach(callScope) }
        }
        defineHostFn("absUrl", 1) { _, _, args ->
            val url = Context.toString(JsValues.arg(args, 0))
            val base = if (args.size >= 2 && !JsValues.isNullish(args[1])) Context.toString(args[1]) else null
            api.absUrl(url, base)
        }
        defineHostFn("digits", 1) { _, _, args ->
            val text = if (args.isNotEmpty() && !JsValues.isNullish(args[0])) Context.toString(args[0]) else null
            api.digits(text)
        }
        defineHostFn("match", 2) { _, _, args ->
            val text = if (args.isNotEmpty() && !JsValues.isNullish(args[0])) Context.toString(args[0]) else null
            val regex = Context.toString(JsValues.arg(args, 1))
            val group = if (args.size >= 3 && !JsValues.isNullish(args[2])) JsValues.asInt(args[2], 1) else 1
            api.match(text, regex, group)
        }
        defineHostFn("slug", 1) { _, _, args ->
            val text = if (args.isNotEmpty() && !JsValues.isNullish(args[0])) Context.toString(args[0]) else null
            api.slug(text)
        }
        defineHostFn("extractImages", 1) { cx, callScope, args ->
            val html = Context.toString(JsValues.arg(args, 0))
            val base = if (args.size >= 2 && !JsValues.isNullish(args[1])) Context.toString(args[1]) else null
            val images = api.extractImages(html, base)
            cx.jsArray(callScope, images)
        }
        defineHostFn("parsePageInfo", 1) { cx, callScope, args ->
            val arg0 = JsValues.arg(args, 0)
            val doc = when (arg0) {
                is JsHtmlDocView -> arg0.doc
                is HostHtmlDoc -> arg0
                else -> api.parseHtml(Context.toString(arg0), null)
            }
            val pageInfo = api.parsePageInfo(doc)
            val obj = cx.newObject(callScope)
            obj.put("currentPage", obj, pageInfo.currentPage)
            obj.put("lastPage", obj, pageInfo.lastPage)
            obj
        }
        defineHostFn("fetchInt32Index", 1) { cx, callScope, args ->
            val url = Context.toString(JsValues.arg(args, 0))
            val page = if (args.size >= 2 && !JsValues.isNullish(args[1])) JsValues.asInt(args[1], 1) else 1
            val pageSize = if (args.size >= 3 && !JsValues.isNullish(args[2])) JsValues.asInt(args[2], 25) else 25
            val res = api.fetchInt32Index(url, page, pageSize)
            val obj = cx.newObject(callScope)
            obj.put("ids", obj, cx.jsArray(callScope, res.ids.map { it.toDouble() }))
            obj.put("currentPage", obj, res.currentPage)
            obj.put("lastPage", obj, res.lastPage)
            obj
        }
        defineHostFn("intersectIndexUrls", 1) { cx, callScope, args ->
            val urls = JsValues.asList(JsValues.arg(args, 0)).map { Context.toString(it) }
            val pageSize = if (args.size >= 2 && !JsValues.isNullish(args[1])) JsValues.asInt(args[1], 25) else 25
            val ids = api.intersectIndexUrls(urls, pageSize)
            cx.jsArray(callScope, ids.map { it.toDouble() })
        }
        defineHostFn("cacheGet", 1) { _, _, args ->
            val key = Context.toString(JsValues.arg(args, 0))
            api.cacheGet(key) ?: Undefined.instance
        }
        defineHostFn("cachePut", 2) { _, _, args ->
            val key = Context.toString(JsValues.arg(args, 0))
            val value = JsValues.arg(args, 1)
            val ttlMs = if (args.size >= 3 && !JsValues.isNullish(args[2])) JsValues.asInt(args[2], 600000).toLong() else 600000L
            api.cachePut(key, value, ttlMs)
            Undefined.instance
        }

        scope.defineProperty("host", this, READONLY or PERMANENT)
    }

    override fun get(name: String, start: Scriptable): Any? {
        if (name == "language") return api.language
        return super.get(name, start)
    }

    override fun has(name: String, start: Scriptable): Boolean {
        if (name == "language") return true
        return super.has(name, start)
    }

    override fun put(name: String, start: Scriptable, value: Any?) {
        if (name == "language" || name == "apiLevel") return
        super.put(name, start, value)
    }
}

internal class JsBytes(val bytes: ByteArray) : ScriptableObject() {
    override fun getClassName(): String = "Bytes"

    fun attach(scope: Scriptable) {
        parentScope = scope
        prototype = getObjectPrototype(scope)
    }
}

private class JsFetchResultView(private val result: HostFetchResult) : ScriptableObject() {
    override fun getClassName(): String = "HostFetchResult"

    fun attach(scope: Scriptable) {
        parentScope = scope
        prototype = getObjectPrototype(scope)
        defineProperty("code", result.code, READONLY or PERMANENT)
        defineProperty("body", JsBytes(result.body).also { it.attach(scope) }, READONLY or PERMANENT)
        defineHostFn("header", 1) { _, _, args ->
            result.header(Context.toString(JsValues.arg(args, 0)))
        }
        defineHostFn("totalLength", 0) { _, _, _ ->
            result.totalLength()?.toDouble()
        }
        defineHostFn("text", 0) { _, _, _ ->
            String(result.body, Charsets.UTF_8)
        }
    }
}

private class JsHtmlDocView(val doc: HostHtmlDoc) : ScriptableObject() {
    override fun getClassName(): String = "HostHtmlDoc"

    fun attach(scope: Scriptable) {
        parentScope = scope
        prototype = getObjectPrototype(scope)
        defineHostFn("select", 1) { cx, callScope, args ->
            wrapElements(cx, callScope, doc.select(Context.toString(JsValues.arg(args, 0))))
        }
        defineHostFn("selectFirst", 1) { _, callScope, args ->
            val el = doc.selectFirst(Context.toString(JsValues.arg(args, 0)))
            el?.let { JsHtmlElView(it).also { view -> view.attach(callScope) } }
        }
        defineHostFn("textOf", 1) { _, _, args ->
            doc.textOf(Context.toString(JsValues.arg(args, 0)))
        }
        defineHostFn("attrOf", 2) { _, _, args ->
            doc.attrOf(
                Context.toString(JsValues.arg(args, 0)),
                Context.toString(JsValues.arg(args, 1)),
            )
        }
        defineHostFn("absUrlOf", 2) { _, _, args ->
            doc.absUrlOf(
                Context.toString(JsValues.arg(args, 0)),
                Context.toString(JsValues.arg(args, 1)),
            )
        }
    }
}

private class JsHtmlElView(private val el: HostHtmlEl) : ScriptableObject() {
    override fun getClassName(): String = "HostHtmlEl"

    fun attach(scope: Scriptable) {
        parentScope = scope
        prototype = getObjectPrototype(scope)
        defineHostFn("text", 0) { _, _, _ -> el.text() }
        defineHostFn("html", 0) { _, _, _ -> el.html() }
        defineHostFn("attr", 1) { _, _, args -> el.attr(Context.toString(JsValues.arg(args, 0))) }
        defineHostFn("absUrl", 1) { _, _, args -> el.absUrl(Context.toString(JsValues.arg(args, 0))) }
        defineHostFn("select", 1) { cx, callScope, args ->
            wrapElements(cx, callScope, el.select(Context.toString(JsValues.arg(args, 0))))
        }
        defineHostFn("selectFirst", 1) { _, callScope, args ->
            val child = el.selectFirst(Context.toString(JsValues.arg(args, 0)))
            child?.let { JsHtmlElView(it).also { view -> view.attach(callScope) } }
        }
        defineHostFn("textOf", 1) { _, _, args ->
            el.textOf(Context.toString(JsValues.arg(args, 0)))
        }
        defineHostFn("attrOf", 2) { _, _, args ->
            el.attrOf(
                Context.toString(JsValues.arg(args, 0)),
                Context.toString(JsValues.arg(args, 1)),
            )
        }
        defineHostFn("absUrlOf", 2) { _, _, args ->
            el.absUrlOf(
                Context.toString(JsValues.arg(args, 0)),
                Context.toString(JsValues.arg(args, 1)),
            )
        }
        defineHostFn("bgUrl", 0) { _, _, _ ->
            el.bgUrl()
        }
    }
}

internal fun unwrapBytes(value: Any?): ByteArray {
    return when (value) {
        is JsBytes -> value.bytes
        is ByteArray -> value
        is String -> value.toByteArray(Charsets.UTF_8)
        else -> error("expected Bytes argument, got ${value?.javaClass?.simpleName ?: "null"}")
    }
}

internal fun parseFetchSpec(value: Any?): HostFetchSpec {
    val obj = value as? Scriptable ?: error("expected fetch spec")
    val url = JsValues.asString(ScriptableObject.getProperty(obj, "url"))?.takeIf { it.isNotBlank() }
        ?: error("fetch spec url")
    val method = JsValues.asString(ScriptableObject.getProperty(obj, "method")) ?: "GET"
    val headers = readStringMap(ScriptableObject.getProperty(obj, "headers"))
    return HostFetchSpec(url = url, method = method, headers = headers)
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

private fun wrapResult(cx: Context, scope: Scriptable, result: HostFetchResult): Scriptable =
    JsFetchResultView(result).also { it.attach(scope) }

private fun wrapElements(cx: Context, scope: Scriptable, elements: List<HostHtmlEl>): Scriptable {
    val views = elements.map { JsHtmlElView(it).also { view -> view.attach(scope) } }
    return cx.jsArray(scope, views)
}

internal fun Context.jsArray(scope: Scriptable, elements: List<Any?>): Scriptable {
    val arr = arrayOfNulls<Any>(elements.size)
    for (i in elements.indices) arr[i] = elements[i]
    return newArray(scope, arr)
}

internal fun ScriptableObject.defineHostFn(
    name: String,
    arity: Int,
    body: (Context, Scriptable, Array<out Any?>) -> Any?,
) {
    val fn = object : BaseFunction() {
        override fun getFunctionName(): String = name
        override fun getArity(): Int = arity
        override fun getLength(): Int = arity
        override fun call(
            cx: Context,
            scope: Scriptable,
            thisObj: Scriptable,
            args: Array<out Any>,
        ): Any? {
            @Suppress("UNCHECKED_CAST")
            val result = body(cx, scope, args as Array<out Any?>)
            return if (result === Unit) Undefined.instance else result
        }
    }
    val parent = parentScope ?: this
    fn.parentScope = parent
    fn.prototype = ScriptableObject.getFunctionPrototype(parent)
    defineProperty(name, fn, ScriptableObject.READONLY or ScriptableObject.PERMANENT)
}
