package io.github.jhspetersson.turtlecommander.operation

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
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

        BufferedInputStream(Files.newInputStream(sourceFile), BUFFER_SIZE).use { input ->
            if (fileSize == 0L) {
                // Create a single empty chunk file for zero-byte files
                val ext = "1".padStart(extensionWidth, '0')
                val chunkPath = targetDirectory.resolve("$fileName.$ext")
                Files.createFile(chunkPath)
                onProgress(1, 1, 0L, 0L)
            }
            while (totalBytesRead < fileSize) {
                if (isCancelled()) return

                chunkIndex++
                val ext = chunkIndex.toString().padStart(extensionWidth, '0')
                val chunkPath = targetDirectory.resolve("$fileName.$ext")
                var chunkBytesWritten = 0L

                BufferedOutputStream(Files.newOutputStream(chunkPath), BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (chunkBytesWritten < chunkSize) {
                        if (isCancelled()) return

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

        writeCrcFile(targetDirectory, fileName, fileSize, crc32.value)
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
}
