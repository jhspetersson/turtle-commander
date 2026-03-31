package io.github.jhspetersson.turtlecommander.operation

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Minimal tar archive writer supporting POSIX ustar format.
 * Handles long file names (>100 chars) via GNU @LongLink extension.
 */
class TarOutputStream(private val out: OutputStream) : AutoCloseable {

    private val blockSize = 512

    fun putDirectoryEntry(name: String, modTimeMillis: Long) {
        writeLongNameIfNeeded(name)
        val header = createHeader(
            name = if (name.length > 100) name.substring(0, 100) else name,
            size = 0,
            modTime = modTimeMillis / 1000,
            typeFlag = '5', // directory
        )
        out.write(header)
    }

    fun putFileEntry(name: String, file: Path, size: Long, modTimeMillis: Long) {
        writeLongNameIfNeeded(name)
        val header = createHeader(
            name = if (name.length > 100) name.substring(0, 100) else name,
            size = size,
            modTime = modTimeMillis / 1000,
            typeFlag = '0', // regular file
        )
        out.write(header)
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(8192)
            var remaining = size
            while (remaining > 0) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val read = input.read(buf, 0, toRead)
                if (read <= 0) break
                out.write(buf, 0, read)
                remaining -= read
            }
        }
        // Pad to block boundary
        val remainder = (size % blockSize).toInt()
        if (remainder > 0) {
            out.write(ByteArray(blockSize - remainder))
        }
    }

    fun finish() {
        // Two zero blocks mark end of archive
        out.write(ByteArray(blockSize * 2))
        out.flush()
    }

    override fun close() {
        out.close()
    }

    private fun writeLongNameIfNeeded(name: String) {
        if (name.length <= 100) return
        // GNU @LongLink extension
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val header = createHeader(
            name = "././@LongLink",
            size = nameBytes.size.toLong(),
            modTime = 0,
            typeFlag = 'L', // GNU long name
        )
        out.write(header)
        out.write(nameBytes)
        val remainder = (nameBytes.size % blockSize)
        if (remainder > 0) {
            out.write(ByteArray(blockSize - remainder))
        }
    }

    private fun createHeader(name: String, size: Long, modTime: Long, typeFlag: Char): ByteArray {
        val header = ByteArray(blockSize)

        // Name (0-99)
        writeString(header, 0, name, 100)
        // Mode (100-107)
        writeOctal(header, 100, if (typeFlag == '5') 0b111_101_101L else 0b110_100_100L, 8)
        // UID (108-115)
        writeOctal(header, 108, 0, 8)
        // GID (116-123)
        writeOctal(header, 116, 0, 8)
        // Size (124-135)
        writeOctal(header, 124, size, 12)
        // Mod time (136-147)
        writeOctal(header, 136, modTime, 12)
        // Checksum placeholder (148-155) - spaces
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        // Type flag (156)
        header[156] = typeFlag.code.toByte()
        // Magic (257-262)
        writeString(header, 257, "ustar", 6)
        header[262] = 0
        // Version (263-264)
        header[263] = '0'.code.toByte()
        header[264] = '0'.code.toByte()

        // Compute checksum
        var checksum = 0L
        for (b in header) {
            checksum += (b.toInt() and 0xFF)
        }
        writeOctal(header, 148, checksum, 7)
        header[155] = ' '.code.toByte()

        return header
    }

    private fun writeString(buf: ByteArray, offset: Int, s: String, maxLen: Int) {
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        val len = minOf(bytes.size, maxLen)
        System.arraycopy(bytes, 0, buf, offset, len)
    }

    private fun writeOctal(buf: ByteArray, offset: Int, value: Long, fieldLen: Int) {
        val octal = String.format("%0${fieldLen - 1}o", value)
        val bytes = octal.toByteArray(StandardCharsets.UTF_8)
        val start = if (bytes.size > fieldLen - 1) bytes.size - (fieldLen - 1) else 0
        val len = minOf(bytes.size - start, fieldLen - 1)
        System.arraycopy(bytes, start, buf, offset, len)
        buf[offset + len] = 0
    }
}
