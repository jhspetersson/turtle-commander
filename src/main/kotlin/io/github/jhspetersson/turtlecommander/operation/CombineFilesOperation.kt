package io.github.jhspetersson.turtlecommander.operation
import io.github.jhspetersson.turtlecommander.util.formatSize
import io.github.jhspetersson.turtlecommander.vfs.OpenVfsRegistry

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32

object CombineFilesOperation {

    data class CrcInfo(
        val filename: String,
        val size: Long,
        val crc32: Long,
    )

    fun parseCrcFile(crcFile: Path): CrcInfo {
        val props = mutableMapOf<String, String>()
        Files.readAllLines(crcFile).forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0) {
                props[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }
        val filename = props["filename"] ?: throw IllegalArgumentException("CRC file missing 'filename' field")
        val size = props["size"]?.toLongOrNull() ?: throw IllegalArgumentException("CRC file missing or invalid 'size' field")
        val crc32Str = props["crc32"] ?: throw IllegalArgumentException("CRC file missing 'crc32' field")
        require(crc32Str.length in 1..8) { "Invalid CRC32 value: $crc32Str" }
        val crc32 = crc32Str.toULong(16).toLong() and 0xFFFFFFFFL
        return CrcInfo(filename, size, crc32)
    }

    fun findChunkFiles(directory: Path, baseFileName: String): List<Path> {
        val chunks = mutableListOf<Path>()
        var index = 1
        while (true) {
            // Try 3-digit first, then 4, 5, 6 digits
            val chunk = findChunkFile(directory, baseFileName, index)
            if (chunk != null) {
                chunks.add(chunk)
                index++
            } else {
                break
            }
        }
        return chunks
    }

    private fun findChunkFile(directory: Path, baseFileName: String, index: Int): Path? {
        for (width in 3..6) {
            val ext = index.toString().padStart(width, '0')
            val path = directory.resolve("$baseFileName.$ext")
            if (Files.exists(path)) return path
        }
        return null
    }

    fun resolveBaseFileName(selectedFile: Path): String? {
        val name = selectedFile.fileName.toString()
        val dotIndex = name.lastIndexOf('.')
        if (dotIndex <= 0) return null
        val ext = name.substring(dotIndex + 1)
        if (ext.isEmpty()) return null
        if (ext == "crc") return name.substring(0, dotIndex)
        if (ext.all { it.isDigit() }) return name.substring(0, dotIndex)
        return null
    }

    fun combine(
        chunkFiles: List<Path>,
        targetFile: Path,
        expectedSize: Long?,
        expectedCrc32: Long?,
        onProgress: (chunkIndex: Int, totalChunks: Int, bytesWritten: Long, totalBytes: Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val totalBytes = if (expectedSize != null && expectedSize > 0) {
            expectedSize
        } else {
            chunkFiles.sumOf { Files.size(it) }
        }

        val crc32 = CRC32()
        var totalBytesWritten = 0L

        var cancelled = false
        val buffer = ByteArray(BUFFER_SIZE)
        BufferedOutputStream(Files.newOutputStream(targetFile), BUFFER_SIZE).use { output ->
            for ((idx, chunk) in chunkFiles.withIndex()) {
                if (isCancelled()) { cancelled = true; break }

                OpenVfsRegistry.materializeIfNeeded(chunk)
                BufferedInputStream(Files.newInputStream(chunk), BUFFER_SIZE).use { input ->
                    while (true) {
                        if (isCancelled()) { cancelled = true; return@use }

                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break

                        output.write(buffer, 0, bytesRead)
                        crc32.update(buffer, 0, bytesRead)
                        totalBytesWritten += bytesRead

                        onProgress(idx + 1, chunkFiles.size, totalBytesWritten, totalBytes)
                    }
                }
                if (cancelled) break
            }
        }

        if (cancelled) {
            Files.deleteIfExists(targetFile)
            return
        }

        if (expectedSize != null && totalBytesWritten != expectedSize) {
            Files.deleteIfExists(targetFile)
            throw IllegalStateException(
                "Size mismatch: expected ${formatSize(expectedSize)} but got ${formatSize(totalBytesWritten)}"
            )
        }

        if (expectedCrc32 != null && crc32.value != expectedCrc32) {
            Files.deleteIfExists(targetFile)
            throw IllegalStateException(
                "CRC mismatch: expected ${String.format("%08X", expectedCrc32)} but got ${String.format("%08X", crc32.value)}"
            )
        }
    }

    private const val BUFFER_SIZE = 64 * 1024
}
