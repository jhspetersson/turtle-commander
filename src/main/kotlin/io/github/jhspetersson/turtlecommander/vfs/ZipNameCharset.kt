package io.github.jhspetersson.turtlecommander.vfs

import org.apache.commons.compress.archivers.zip.ZipFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal object ZipNameCharset {

    fun needsFallback(zip: ZipFile): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val entries = zip.entries
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.generalPurposeBit.usesUTF8ForNames()) continue
            val raw = entry.rawName ?: continue
            try {
                decoder.reset()
                decoder.decode(ByteBuffer.wrap(raw))
            } catch (_: CharacterCodingException) {
                return true
            }
        }
        return false
    }

    fun fallback(): Charset {
        val native = System.getProperty("native.encoding")
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
        return native?.takeIf { it != Charsets.UTF_8 } ?: Charset.forName("IBM437")
    }
}
