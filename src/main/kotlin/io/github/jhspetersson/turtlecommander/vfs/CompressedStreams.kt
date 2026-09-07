package io.github.jhspetersson.turtlecommander.vfs

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

internal object CompressedStreams {

    private const val BUFFER_SIZE = 64 * 1024

    fun openInput(path: Path, wrap: (InputStream) -> InputStream): InputStream =
        wrapInput(Files.newInputStream(path), wrap)

    fun openOutput(path: Path, wrap: (OutputStream) -> OutputStream): OutputStream =
        wrapOutput(Files.newOutputStream(path), wrap)

    fun wrapInput(raw: InputStream, wrap: (InputStream) -> InputStream): InputStream {
        val buffered = BufferedInputStream(raw, BUFFER_SIZE)
        return try {
            wrap(buffered)
        } catch (e: Throwable) {
            runCatching { raw.close() }
            throw e
        }
    }

    fun wrapOutput(raw: OutputStream, wrap: (OutputStream) -> OutputStream): OutputStream {
        val buffered = BufferedOutputStream(raw, BUFFER_SIZE)
        return try {
            wrap(buffered)
        } catch (e: Throwable) {
            runCatching { raw.close() }
            throw e
        }
    }
}
