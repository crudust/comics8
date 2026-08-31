package com.comics8.desktop

import java.awt.Desktop
import java.io.File

/** Bridges macOS open-document events to the Compose application lifecycle. */
internal object DesktopOpenFileEvents {
    private val lock = Any()
    private val pending = ArrayDeque<File>()
    private var listener: ((File) -> Unit)? = null

    fun install() {
        if (!Desktop.isDesktopSupported()) return
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) return
        desktop.setOpenFileHandler { event -> accept(event.files) }
    }

    fun listen(onOpenFile: (File) -> Unit): AutoCloseable {
        val queued = synchronized(lock) {
            listener = onOpenFile
            buildList {
                while (pending.isNotEmpty()) add(pending.removeFirst())
            }
        }
        queued.forEach(onOpenFile)
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
            current?.invoke(file)
        }
    }
}
