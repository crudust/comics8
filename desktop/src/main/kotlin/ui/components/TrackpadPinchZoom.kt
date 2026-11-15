package com.comics8.desktop.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import java.lang.reflect.Proxy
import javax.swing.JComponent
import javax.swing.SwingUtilities

internal val LocalTrackpadPinchDispatcher = staticCompositionLocalOf<TrackpadPinchDispatcher?> { null }

/**
 * Routes window-level trackpad magnification deltas to the currently active viewer [ZoomableBox].
 *
 * Uses a stack-ordered handler set so only the active (most recently attached) handler consumes
 * each event when pages transition inside a pager.
 */
internal class TrackpadPinchDispatcher {
    private val handlers = LinkedHashSet<(Double) -> Boolean>()

    fun register(handler: (Double) -> Boolean) {
        handlers.remove(handler)
        handlers.add(handler)
    }

    fun unregister(handler: (Double) -> Boolean) {
        handlers.remove(handler)
    }

    fun dispatch(magnificationDelta: Double): Boolean {
        if (!magnificationDelta.isFinite() || magnificationDelta == 0.0) return false
        val active = handlers.lastOrNull() ?: return false
        return active(magnificationDelta)
    }
}

/**
 * Installs a macOS `com.apple.eawt.event.MagnificationListener` via reflection onto a Swing
 * [JComponent] (typically `ComposeWindow.rootPane`), keeping Windows/Linux builds free of
 * macOS-only compile-time imports.
 */
internal object MacTrackpadPinchBridge {
    private val isMacOs: Boolean = System.getProperty("os.name")
        .orEmpty()
        .lowercase()
        .contains("mac")

    fun install(
        component: JComponent,
        onMagnify: (Double) -> Boolean,
    ): AutoCloseable {
        if (!isMacOs) return AutoCloseable {}

        return try {
            val gestureUtilitiesClass = Class.forName("com.apple.eawt.event.GestureUtilities")
            val gestureListenerClass = Class.forName("com.apple.eawt.event.GestureListener")
            val magnificationListenerClass = Class.forName("com.apple.eawt.event.MagnificationListener")
            val magnificationEventClass = Class.forName("com.apple.eawt.event.MagnificationEvent")

            val addListener = gestureUtilitiesClass.getMethod(
                "addGestureListenerTo",
                JComponent::class.java,
                gestureListenerClass,
            )
            val removeListener = gestureUtilitiesClass.getMethod(
                "removeGestureListenerFrom",
                JComponent::class.java,
                gestureListenerClass,
            )
            val getMagnification = magnificationEventClass.getMethod("getMagnification")
            val consumeEvent = magnificationEventClass.getMethod("consume")

            val proxy = Proxy.newProxyInstance(
                magnificationListenerClass.classLoader,
                arrayOf(magnificationListenerClass),
            ) { proxyInstance, method, args ->
                when (method.name) {
                    "equals" -> args?.size == 1 && proxyInstance === args[0]
                    "hashCode" -> System.identityHashCode(proxyInstance)
                    "toString" -> "MacTrackpadMagnificationListener@${System.identityHashCode(proxyInstance)}"
                    "magnify" -> {
                        val event = args?.firstOrNull() ?: return@newProxyInstance null
                        val delta = (getMagnification.invoke(event) as? Double) ?: return@newProxyInstance null
                        if (!delta.isFinite() || delta == 0.0) return@newProxyInstance null
                        val dispatch = {
                            if (onMagnify(delta)) {
                                consumeEvent.invoke(event)
                            }
                        }
                        if (SwingUtilities.isEventDispatchThread()) {
                            dispatch()
                        } else {
                            SwingUtilities.invokeLater(dispatch)
                        }
                        null
                    }
                    else -> null
                }
            }

            addListener.invoke(null, component, proxy)
            AutoCloseable {
                try {
                    removeListener.invoke(null, component, proxy)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
            AutoCloseable {}
        }
    }
}
