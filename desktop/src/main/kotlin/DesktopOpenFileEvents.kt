package com.comics8.desktop

import java.awt.Desktop
import java.io.File

/** Bridges macOS open-document events to the Compose application lifecycle. */
internal object DesktopOpenFileEvents {
    private val lock = Any()
    private val pending = ArrayDeque<File>()
    private var listener: ((File) -> Unit)? = null

    fun install() {
        runCatching {
            if (!Desktop.isDesktopSupported()) return@runCatching
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) return@runCatching
            desktop.setOpenFileHandler { event -> accept(event.files) }
        }
    }

    fun listen(onOpenFile: (File) -> Unit): AutoCloseable {
        val queued = synchronized(lock) {
            listener = onOpenFile
            buildList {
                while (pending.isNotEmpty()) add(pending.removeFirst())
            }
        }
        queued.forEach(::dispatch)
        return AutoCloseable {
            synchronized(lock) {
                if (listener === onOpenFile) listener = null
            }
        }
    }

    internal fun accept(files: List<File>) {
        files.forEach { file ->
            val current = synchronized(lock) {
                val callback = listener
                if (callback == null) pending.addLast(file)
                callback
            }
            current?.let { callback -> runCatching { callback(file) } }
        }
    }

    private fun dispatch(file: File) {
        val current = synchronized(lock) { listener }
        current?.let { callback -> runCatching { callback(file) } }
    }
}
