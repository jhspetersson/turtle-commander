package io.github.jhspetersson.turtlecommander.operation

import io.github.jhspetersson.turtlecommander.vfs.OpenVfsRegistry
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32

object SplitFileOperation {

    fun split(
        sourceFile: Path,
        targetDirectory: Path,
        chunkSize: Long,
        onProgress: (chunkIndex: Int, totalChunks: Int, bytesWritten: Long, totalBytes: Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val fileSize = Files.size(sourceFile)
        val fileName = sourceFile.fileName.toString()
        val totalChunks = if (fileSize == 0L) 1 else ((fileSize + chunkSize - 1) / chunkSize).toInt()
        val extensionWidth = extensionWidth(totalChunks)

        Files.createDirectories(targetDirectory)

        val crc32 = CRC32()
        var totalBytesRead = 0L
        var chunkIndex = 0
        val pendingChunks = mutableListOf<Pair<Path, Path>>()
        var cancelled = false

        try {
            OpenVfsRegistry.materializeIfNeeded(sourceFile)
            BufferedInputStream(Files.newInputStream(sourceFile), BUFFER_SIZE).use { input ->
                if (fileSize == 0L) {
                    // Create a single empty chunk file for zero-byte files
                    val chunkPath = chunkPath(targetDirectory, fileName, 1, extensionWidth)
                    val tempPath = tempPath(chunkPath)
                    Files.deleteIfExists(tempPath)
                    Files.createFile(tempPath)
                    pendingChunks.add(tempPath to chunkPath)
                    onProgress(1, 1, 0L, 0L)
                }
                while (totalBytesRead < fileSize) {
                    if (isCancelled()) { cancelled = true; break }

                    chunkIndex++
                    val chunkPath = chunkPath(targetDirectory, fileName, chunkIndex, extensionWidth)
                    val tempPath = tempPath(chunkPath)
                    var chunkBytesWritten = 0L
                    pendingChunks.add(tempPath to chunkPath)

                    BufferedOutputStream(Files.newOutputStream(tempPath), BUFFER_SIZE).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (chunkBytesWritten < chunkSize) {
                            if (isCancelled()) { cancelled = true; return@use }

                            val toRead = minOf(BUFFER_SIZE.toLong(), chunkSize - chunkBytesWritten).toInt()
                            val bytesRead = input.read(buffer, 0, toRead)
                            if (bytesRead == -1) break

                            output.write(buffer, 0, bytesRead)
                            crc32.update(buffer, 0, bytesRead)
                            chunkBytesWritten += bytesRead
                            totalBytesRead += bytesRead

                            onProgress(chunkIndex, totalChunks, totalBytesRead, fileSize)
                        }
                    }
                }
            }

            if (cancelled) {
                deleteTempFiles(pendingChunks)
                return
            }

            for ((tempPath, chunkPath) in pendingChunks) {
                Files.move(tempPath, chunkPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            deleteTempFiles(pendingChunks)
            throw e
        }

        val committed = pendingChunks.map { it.second }.toSet()
        for (stale in existingChunkFiles(targetDirectory, fileName)) {
            if (stale !in committed && !stale.fileName.toString().endsWith(".crc")) {
                Files.deleteIfExists(stale)
            }
        }

        writeCrcFile(targetDirectory, fileName, fileSize, crc32.value)
    }

    fun existingChunkFiles(targetDirectory: Path, fileName: String): List<Path> {
        if (!Files.isDirectory(targetDirectory)) return emptyList()
        val pattern = Regex("^" + Regex.escape(fileName) + "\\.(\\d{3,6}|crc)$")
        return Files.list(targetDirectory).use { stream ->
            stream.filter { pattern.matches(it.fileName.toString()) && Files.isRegularFile(it) }.toList()
        }
    }

    private fun chunkPath(targetDirectory: Path, fileName: String, index: Int, extensionWidth: Int): Path {
        val ext = index.toString().padStart(extensionWidth, '0')
        return targetDirectory.resolve("$fileName.$ext")
    }

    private fun tempPath(chunkPath: Path): Path = chunkPath.resolveSibling(chunkPath.fileName.toString() + TEMP_SUFFIX)

    private fun deleteTempFiles(pendingChunks: List<Pair<Path, Path>>) {
        for ((tempPath, _) in pendingChunks) {
            try {
                Files.deleteIfExists(tempPath)
            } catch (_: Exception) {
            }
        }
    }

    private fun writeCrcFile(targetDirectory: Path, fileName: String, fileSize: Long, crc32Value: Long) {
        val crcHex = String.format("%08X", crc32Value)
        val crcContent = "filename=$fileName\nsize=$fileSize\ncrc32=$crcHex\n"
        val crcPath = targetDirectory.resolve("$fileName.crc")
        Files.writeString(crcPath, crcContent)
    }

    private fun extensionWidth(totalChunks: Int): Int {
        return when {
            totalChunks <= 999 -> 3
            totalChunks <= 9999 -> 4
            totalChunks <= 99999 -> 5
            else -> 6
        }
    }

    private const val BUFFER_SIZE = 64 * 1024
    private const val TEMP_SUFFIX = ".part"
}
