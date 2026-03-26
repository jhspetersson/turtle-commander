package io.github.jhspetersson.turtlecommander.ui

import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.util.fileErrorMessage

import com.intellij.openapi.diagnostic.thisLogger
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import javax.swing.JComponent
import javax.swing.TransferHandler

internal class FileEntryTransferHandler(private val tab: FileTab) : TransferHandler() {
    override fun getSourceActions(c: JComponent): Int = COPY

    override fun createTransferable(c: JComponent): Transferable? {
        val entries = tab.getSelectedEntries()
        if (entries.isEmpty()) return null
        return FileEntryTransferable(entries)
    }

    override fun canImport(support: TransferSupport): Boolean {
        if (!support.isDrop) return false
        if (tab.currentVfs?.isReadOnly == true) return false
        return support.isDataFlavorSupported(FileTab.FILE_ENTRY_FLAVOR)
            || support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
    }

    override fun importData(support: TransferSupport): Boolean {
        if (!canImport(support)) return false
        if (tab.currentVfs?.isReadOnly == true) {
            tab.fileErrorNotification("Cannot copy into a read-only archive")
            return false
        }
        try {
            val entries = when {
                support.isDataFlavorSupported(FileTab.FILE_ENTRY_FLAVOR) -> {
                    @Suppress("UNCHECKED_CAST")
                    support.transferable.getTransferData(FileTab.FILE_ENTRY_FLAVOR) as List<FileEntry>
                }
                support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
                    @Suppress("UNCHECKED_CAST")
                    val files = support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                    files.map { file ->
                        val path = file.toPath()
                        FileEntry(
                            name = file.name,
                            path = path,
                            isDirectory = file.isDirectory,
                            size = if (file.isFile) file.length() else 0,
                            lastModified = null,
                            permissions = "",
                        )
                    }
                }
                else -> return false
            }
            tab.performCopyEntries(entries, tab.currentPath, tab.getDisplayPath())
            return true
        } catch (e: Exception) {
            thisLogger().warn("Drop failed: ${fileErrorMessage(e)}")
            return false
        }
    }
}

internal class FileEntryTransferable(private val entries: List<FileEntry>) : Transferable {
    private val supportedFlavors = arrayOf(FileTab.FILE_ENTRY_FLAVOR, DataFlavor.javaFileListFlavor)

    override fun getTransferDataFlavors(): Array<DataFlavor> = supportedFlavors
    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in supportedFlavors
    override fun getTransferData(flavor: DataFlavor): Any {
        return when (flavor) {
            FileTab.FILE_ENTRY_FLAVOR -> entries
            DataFlavor.javaFileListFlavor -> entries.map { it.path.toFile() }
            else -> throw UnsupportedFlavorException(flavor)
        }
    }
}
