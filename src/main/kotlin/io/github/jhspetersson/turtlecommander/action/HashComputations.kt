package io.github.jhspetersson.turtlecommander.action

import com.intellij.openapi.progress.ProgressIndicator
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

    fun crc16(input: InputStream, total: Long = -1L, indicator: ProgressIndicator? = null): String {
        var crc = 0xFFFF
        val polynomial = 0x1021
        var processed = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            indicator?.checkCanceled()
            val n = input.read(buffer)
            if (n <= 0) break
            for (i in 0 until n) {
                crc = crc xor ((buffer[i].toInt() and 0xFF) shl 8)
                repeat(8) {
                    crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor polynomial) and 0xFFFF
                    else (crc shl 1) and 0xFFFF
                }
            }
            processed += n
            if (total > 0 && indicator != null) indicator.fraction = processed.toDouble() / total
        }
        return "%04X".format(crc and 0xFFFF)
    }

    fun crc32(input: InputStream, total: Long = -1L, indicator: ProgressIndicator? = null): String {
        val crc = CRC32()
        var processed = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            indicator?.checkCanceled()
            val n = input.read(buffer)
            if (n <= 0) break
            crc.update(buffer, 0, n)
            processed += n
            if (total > 0 && indicator != null) indicator.fraction = processed.toDouble() / total
        }
        return "%08X".format(crc.value)
    }

    fun digest(input: InputStream, algorithm: String, total: Long = -1L, indicator: ProgressIndicator? = null): String {
        val md = MessageDigest.getInstance(algorithm)
        var processed = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            indicator?.checkCanceled()
            val n = input.read(buffer)
            if (n <= 0) break
            md.update(buffer, 0, n)
            processed += n
            if (total > 0 && indicator != null) indicator.fraction = processed.toDouble() / total
        }
        return toHex(md.digest())
    }

    fun crc16(path: Path, indicator: ProgressIndicator): String {
        val total = runCatching { Files.size(path) }.getOrDefault(-1L)
        return Files.newInputStream(path).use { crc16(it, total, indicator) }
    }

    fun crc32(path: Path, indicator: ProgressIndicator): String {
        val total = runCatching { Files.size(path) }.getOrDefault(-1L)
        return Files.newInputStream(path).use { crc32(it, total, indicator) }
    }

    fun digest(path: Path, algorithm: String, indicator: ProgressIndicator): String {
        val total = runCatching { Files.size(path) }.getOrDefault(-1L)
        return Files.newInputStream(path).use { digest(it, algorithm, total, indicator) }
    }
}
