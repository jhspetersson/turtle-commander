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

    private val oemByAnsi = mapOf(
        "windows-1250" to "IBM852",
        "windows-1251" to "IBM866",
        "windows-1252" to "IBM437",
        "windows-1253" to "x-IBM737",
        "windows-1254" to "IBM857",
        "windows-1255" to "IBM862",
        "windows-1257" to "IBM775",
    )

    fun fallback(nativeEncoding: String? = System.getProperty("native.encoding")): Charset {
        val default = Charset.forName("IBM437")
        val native = nativeEncoding?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: return default
        if (native == Charsets.UTF_8 || native == Charsets.US_ASCII || native == Charsets.ISO_8859_1) return default
        val oem = oemByAnsi[native.name()] ?: return native
        return runCatching { Charset.forName(oem) }.getOrNull()?.takeIf { keepsEveryByte(it) } ?: default
    }

    private fun keepsEveryByte(charset: Charset): Boolean {
        val bytes = ByteArray(256) { it.toByte() }
        return String(bytes, charset).toByteArray(charset).contentEquals(bytes)
    }
}
