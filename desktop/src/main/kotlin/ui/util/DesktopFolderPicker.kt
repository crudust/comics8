package com.comics8.desktop.ui.util

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

object DesktopFolderPicker {
    fun pickDirectory(title: String = "Select Folder"): File? {
        if (!SwingUtilities.isEventDispatchThread()) {
            var picked: File? = null
            SwingUtilities.invokeAndWait { picked = pickDirectoryOnEdt(title) }
            return picked
        }
        return pickDirectoryOnEdt(title)
    }

    private fun pickDirectoryOnEdt(title: String): File? {
        val isMac = System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)
        return if (isMac) pickWithFileDialog(title) else pickWithChooser(title)
    }

    private fun pickWithFileDialog(title: String): File? {
        val previous = System.getProperty(MAC_DIR_PROPERTY)
        System.setProperty(MAC_DIR_PROPERTY, "true")
        try {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
            dialog.isVisible = true
            val dir = dialog.directory ?: return null
            val file = dialog.file
            val selected = if (file.isNullOrBlank()) File(dir) else File(dir, file)
            return selected.takeIf { it.isDirectory } ?: selected.parentFile?.takeIf { it.isDirectory }
        } finally {
            if (previous == null) {
                System.clearProperty(MAC_DIR_PROPERTY)
            } else {
                System.setProperty(MAC_DIR_PROPERTY, previous)
            }
        }
    }

    private fun pickWithChooser(title: String): File? {
        val chooser = JFileChooser()
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.isAcceptAllFileFilterUsed = false
        chooser.dialogTitle = title
        val result = chooser.showOpenDialog(null)
        if (result != JFileChooser.APPROVE_OPTION) return null
        return chooser.selectedFile?.takeIf { it.isDirectory }
    }

    private const val MAC_DIR_PROPERTY = "apple.awt.fileDialogForDirectories"
}
