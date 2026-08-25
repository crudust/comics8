package com.comics8.core.source.js

import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.UniqueTag
import org.mozilla.javascript.WrapFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

internal object JsSandbox {
    const val ALLOWLIST_PREFIX = "com.comics8.core.source.js."
    const val DEFAULT_CALL_TIMEOUT_MS = 30_000L
    val DEADLINE_KEY = Any()

    private val factory = SandboxContextFactory()

    val timeoutScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "js-source-timeout").apply { isDaemon = true }
        }

    fun enter(): Context = factory.enterContext()

    fun createScope(cx: Context): ScriptableObject {
        val scope = cx.initSafeStandardObjects()
        stripLiveConnect(scope)
        return scope
    }

    fun stripLiveConnect(scope: ScriptableObject) {
        for (name in LIVECONNECT_GLOBALS) {
            scope.delete(name)
        }
    }

    fun classShutter(): ClassShutter = ClassShutter { fqn ->
        fqn.startsWith(ALLOWLIST_PREFIX)
    }

    fun checkDeadline(cx: Context) {
        if (Thread.currentThread().isInterrupted) {
            throw EvaluatorException("source call timed out")
        }
        val deadline = cx.getThreadLocal(DEADLINE_KEY) as? Long ?: return
        if (System.currentTimeMillis() > deadline) {
            throw EvaluatorException("source call timed out")
        }
    }

    private val LIVECONNECT_GLOBALS = arrayOf(
        "Packages",
        "JavaAdapter",
        "JavaImporter",
        "getClass",
        "java",
        "javax",
        "org",
        "com",
        "edu",
        "net",
        "Continuation",
    )
}

internal object JsValues {
    fun isNullish(value: Any?): Boolean =
        value == null ||
            value === Undefined.instance ||
            value === UniqueTag.NOT_FOUND ||
            value === Scriptable.NOT_FOUND

    fun arg(args: Array<out Any?>, index: Int): Any? =
        if (index in args.indices) args[index] else Undefined.instance

    fun asString(value: Any?): String? {
        if (isNullish(value)) return null
        val text = Context.toString(value)
        return text
    }

    fun asInt(value: Any?, default: Int = 0): Int {
        if (isNullish(value)) return default
        val n = Context.toNumber(value)
        if (n.isNaN()) return default
        return n.toInt()
    }

    fun asBoolean(value: Any?, default: Boolean = false): Boolean {
        if (isNullish(value)) return default
        return Context.toBoolean(value)
    }

    fun asList(value: Any?): List<Any?> {
        if (isNullish(value)) return emptyList()
        if (value is List<*>) return value
        val scriptable = value as? Scriptable ?: error("expected array")
        val length = asInt(ScriptableObject.getProperty(scriptable, "length"), 0)
        if (length <= 0) return emptyList()
        return (0 until length).map { scriptable.get(it, scriptable) }
    }
}

private class SandboxContextFactory : ContextFactory() {
    override fun makeContext(): Context {
        val cx = super.makeContext()
        cx.optimizationLevel = -1
        cx.languageVersion = Context.VERSION_ES6
        cx.instructionObserverThreshold = 10_000
        cx.setClassShutter(JsSandbox.classShutter())
        cx.wrapFactory = DenyJavaWrapFactory()
        return cx
    }

    override fun observeInstructionCount(cx: Context, instructionCount: Int) {
        JsSandbox.checkDeadline(cx)
    }

    override fun hasFeature(cx: Context, featureIndex: Int): Boolean {
        return when (featureIndex) {
            Context.FEATURE_ENHANCED_JAVA_ACCESS,
            Context.FEATURE_E4X,
            -> false
            else -> super.hasFeature(cx, featureIndex)
        }
    }
}

private class DenyJavaWrapFactory : WrapFactory() {
    init {
        isJavaPrimitiveWrap = false
    }

    override fun wrap(cx: Context, scope: Scriptable, obj: Any?, staticType: Class<*>?): Any? {
        if (obj == null || obj is Scriptable || obj is UniqueTag || obj is Undefined) return obj
        if (obj is CharSequence || obj is Number || obj is Boolean || obj is Char) {
            return super.wrap(cx, scope, obj, staticType)
        }
        throw EvaluatorException("Java objects are not visible to scripts: ${obj.javaClass.name}")
    }

    override fun wrapNewObject(cx: Context, scope: Scriptable, obj: Any): Scriptable {
        if (obj is Scriptable) return obj
        throw EvaluatorException("Java objects are not visible to scripts: ${obj.javaClass.name}")
    }

    override fun wrapAsJavaObject(
        cx: Context,
        scope: Scriptable,
        javaObject: Any,
        staticType: Class<*>?,
    ): Scriptable {
        throw EvaluatorException("Java objects are not visible to scripts: ${javaObject.javaClass.name}")
    }

    override fun wrapJavaClass(cx: Context, scope: Scriptable, javaClass: Class<*>): Scriptable {
        throw EvaluatorException("Java classes are not visible to scripts: ${javaClass.name}")
    }
}
