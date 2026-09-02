package io.github.jhspetersson.turtlecommander.util

import com.intellij.openapi.progress.ProgressIndicator
import io.github.jhspetersson.turtlecommander.vfs.OpenVfsRegistry
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.CRC32

internal object HashComputations {

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(Character.forDigit((b.toInt() shr 4) and 0xF, 16))
            sb.append(Character.forDigit(b.toInt() and 0xF, 16))
        }
        return sb.toString()
    }

    /**
     * Read [input] into a 64 KiB buffer in a tight loop, calling [process] for each chunk
     * and ticking [indicator] (both cancel and fraction) along the way. Inline so the
     * lambda captures variables — notably crc16's running `crc` accumulator — without
     * allocating a Function object per call.
     */
    private inline fun streamWithProgress(
        input: InputStream,
        total: Long,
        indicator: ProgressIndicator?,
        process: (buffer: ByteArray, length: Int) -> Unit,
    ) {
        var processed = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            indicator?.checkCanceled()
            val n = input.read(buffer)
            if (n <= 0) break
            process(buffer, n)
            processed += n
            if (total > 0 && indicator != null) indicator.fraction = processed.toDouble() / total
        }
    }

    fun crc16(input: InputStream, total: Long = -1L, indicator: ProgressIndicator? = null): String {
        // CRC-16/CCITT-FALSE: seed 0xFFFF, polynomial 0x1021, no reflection.
        var crc = 0xFFFF
        val polynomial = 0x1021
        streamWithProgress(input, total, indicator) { buffer, n ->
            for (i in 0 until n) {
                crc = crc xor ((buffer[i].toInt() and 0xFF) shl 8)
                repeat(8) {
                    crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor polynomial) and 0xFFFF
                    else (crc shl 1) and 0xFFFF
                }
            }
        }
        return "%04X".format(crc and 0xFFFF)
    }

    fun crc32(input: InputStream, total: Long = -1L, indicator: ProgressIndicator? = null): String {
        val crc = CRC32()
        streamWithProgress(input, total, indicator) { buffer, n -> crc.update(buffer, 0, n) }
        return "%08X".format(crc.value)
    }

    fun digest(input: InputStream, algorithm: String, total: Long = -1L, indicator: ProgressIndicator? = null): String {
        val md = MessageDigest.getInstance(algorithm)
        streamWithProgress(input, total, indicator) { buffer, n -> md.update(buffer, 0, n) }
        return toHex(md.digest())
    }

    fun crc16(path: Path, indicator: ProgressIndicator): String {
        OpenVfsRegistry.materializeIfNeeded(path)
        val total = runCatching { Files.size(path) }.getOrDefault(-1L)
        return Files.newInputStream(path).use { crc16(it, total, indicator) }
    }

    fun crc32(path: Path, indicator: ProgressIndicator): String {
        OpenVfsRegistry.materializeIfNeeded(path)
        val total = runCatching { Files.size(path) }.getOrDefault(-1L)
        return Files.newInputStream(path).use { crc32(it, total, indicator) }
    }

    fun digest(path: Path, algorithm: String, indicator: ProgressIndicator): String {
        OpenVfsRegistry.materializeIfNeeded(path)
        val total = runCatching { Files.size(path) }.getOrDefault(-1L)
        return Files.newInputStream(path).use { digest(it, algorithm, total, indicator) }
    }
}
