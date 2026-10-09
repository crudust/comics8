package com.comics8.desktop

import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.swing.SwingUtilities

/** Bridges macOS open-document and URI events to the Compose application lifecycle. */
internal object DesktopOpenFileEvents {
    private val lock = Any()
    private val pending = ArrayDeque<File>()
    private var listener: ((File) -> Unit)? = null
    var onBringToFront: (() -> Unit)? = null

    fun install() {
        if (!Desktop.isDesktopSupported()) return
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) {
            desktop.setOpenFileHandler { event -> accept(event.files) }
        }
        if (desktop.isSupported(Desktop.Action.APP_OPEN_URI)) {
            desktop.setOpenURIHandler { event -> acceptUri(event.uri) }
        }
    }

    fun listen(onOpenFile: (File) -> Unit): AutoCloseable {
        val queued = synchronized(lock) {
            listener = onOpenFile
            buildList {
                while (pending.isNotEmpty()) add(pending.removeFirst())
            }
        }
        if (queued.isNotEmpty()) {
            dispatch {
                onBringToFront?.invoke()
                queued.forEach(onOpenFile)
            }
        }
        return AutoCloseable {
            synchronized(lock) {
                if (listener === onOpenFile) listener = null
            }
        }
    }

    fun acceptArgs(args: List<String>) {
        if (args.isEmpty()) return
        val files = args.mapNotNull { arg ->
            val trimmed = arg.trim()
            if (trimmed.isEmpty()) null else parsePathOrUri(trimmed)
        }
        if (files.isNotEmpty()) {
            accept(files)
        }
    }

    fun acceptUri(uri: URI) {
        val file = parsePathOrUri(uri.toString())
        accept(listOf(file))
    }

    internal fun accept(files: List<File>) {
        if (files.isEmpty()) return
        dispatch {
            val (callback, toFront) = synchronized(lock) {
                val callback = listener
                if (callback == null) {
                    files.forEach(pending::addLast)
                }
                callback to onBringToFront
            }
            toFront?.invoke()
            if (callback != null) {
                files.forEach(callback)
            }
        }
    }

    fun parsePathOrUri(raw: String): File {
        val trimmed = raw.trim()
        if (trimmed.startsWith("smb://", ignoreCase = true) || trimmed.startsWith("smb:", ignoreCase = true)) {
            return parseSmbUrl(trimmed)
        }
        if (trimmed.startsWith("file://", ignoreCase = true)) {
            return try {
                File(URI(trimmed).path)
            } catch (_: Exception) {
                val pathOnly = trimmed.removePrefix("file://")
                decodeUrlPath(pathOnly)
            }
        }
        return decodeUrlPath(trimmed)
    }

    private fun parseSmbUrl(url: String): File {
        val clean = url.trim().removePrefix("smb://").removePrefix("smb:")
        // Format: [user@]host[:port]/share/subpath...
        val withoutHost = if ("/" in clean) clean.substringAfter('/') else clean
        val decoded = try {
            URLDecoder.decode(withoutHost, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            withoutHost
        }
        val share = decoded.substringBefore('/')
        val rel = decoded.substringAfter('/', "")

        // 1. Primary candidate: /Volumes/<share>/<rel>
        val directMount = File("/Volumes/$share/$rel")
        if (directMount.exists()) return directMount

        // 2. Candidate: volume mounted by file name, e.g. /Volumes/<fileName>
        val fileName = File(decoded).name
        val volumeMount = File("/Volumes/$fileName")
        if (volumeMount.exists()) {
            val inner = File(volumeMount, fileName)
            if (inner.exists()) return inner
            return volumeMount
        }

        return directMount
    }

    private fun decodeUrlPath(path: String): File {
        val decoded = if ("%" in path) {
            try {
                URLDecoder.decode(path, StandardCharsets.UTF_8.name())
            } catch (_: Exception) {
                path
            }
        } else {
            path
        }
        return File(decoded)
    }

    private fun dispatch(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            try {
                SwingUtilities.invokeAndWait(action)
            } catch (_: Exception) {
                action()
            }
        }
    }
}
