package io.github.jhspetersson.turtlecommander.action
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class HashComputationsTest {

    private fun bytes(s: String) = ByteArrayInputStream(s.toByteArray(Charsets.UTF_8))

    @Test
    fun `toHex pads each byte to two characters`() {
        assertEquals("00010f10ff", HashComputations.toHex(byteArrayOf(0, 1, 15, 16, -1)))
    }

    // --- CRC32 vectors (java.util.zip.CRC32 is IEEE 802.3) ---

    @Test
    fun `crc32 empty input`() {
        assertEquals("00000000", HashComputations.crc32(bytes("")))
    }

    @Test
    fun `crc32 standard check vector`() {
        // ISO/IEC "check" string for CRC-32/ISO-HDLC.
        assertEquals("CBF43926", HashComputations.crc32(bytes("123456789")))
    }

    // --- CRC16-CCITT/XMODEM (seed 0xFFFF, polynomial 0x1021, no reflection) ---

    @Test
    fun `crc16 empty input returns seed value`() {
        assertEquals("FFFF", HashComputations.crc16(bytes("")))
    }

    @Test
    fun `crc16 standard check vector`() {
        // "123456789" → 0x29B1 per CRC-16/CCITT-FALSE (seed 0xFFFF).
        assertEquals("29B1", HashComputations.crc16(bytes("123456789")))
    }

    // --- MessageDigest — well-known published vectors ---

    @Test
    fun `md5 empty input`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", HashComputations.digest(bytes(""), "MD5"))
    }

    @Test
    fun `md5 of abc matches RFC 1321`() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", HashComputations.digest(bytes("abc"), "MD5"))
    }

    @Test
    fun `sha1 empty input`() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", HashComputations.digest(bytes(""), "SHA-1"))
    }

    @Test
    fun `sha1 of abc matches RFC 3174`() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", HashComputations.digest(bytes("abc"), "SHA-1"))
    }

    @Test
    fun `sha256 empty input`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            HashComputations.digest(bytes(""), "SHA-256"),
        )
    }

    @Test
    fun `sha256 of abc matches FIPS 180-4`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            HashComputations.digest(bytes("abc"), "SHA-256"),
        )
    }

    @Test
    fun `sha512 of abc matches FIPS 180-4`() {
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            HashComputations.digest(bytes("abc"), "SHA-512"),
        )
    }

    @Test
    fun `sha3_256 of empty input matches FIPS 202`() {
        assertEquals(
            "a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a",
            HashComputations.digest(bytes(""), "SHA3-256"),
        )
    }

    // --- Streaming across buffer boundaries — 64 KB buffer in the impl ---

    @Test
    fun `digest handles input larger than internal buffer`() {
        // 200 KB of deterministic data — well past the 64 KB buffer, so this exercises the
        // multi-read loop that the production code hits on real files.
        val data = ByteArray(200 * 1024) { (it % 251).toByte() }
        val md = MessageDigest.getInstance("SHA-256")
        md.update(data)
        val expected = HashComputations.toHex(md.digest())
        assertEquals(expected, HashComputations.digest(ByteArrayInputStream(data), "SHA-256"))
    }

    @Test
    fun `crc32 handles input larger than internal buffer`() {
        val data = ByteArray(200 * 1024) { (it % 251).toByte() }
        val crc = java.util.zip.CRC32().apply { update(data) }
        val expected = "%08X".format(crc.value)
        assertEquals(expected, HashComputations.crc32(ByteArrayInputStream(data)))
    }

    @Test
    fun `digest of unknown algorithm throws NoSuchAlgorithmException`() {
        try {
            HashComputations.digest(bytes("x"), "TOTALLY-NOT-A-REAL-ALGORITHM")
            org.junit.Assert.fail("expected NoSuchAlgorithmException")
        } catch (e: NoSuchAlgorithmException) {
            // expected — matches the behavior that surfaces to the action's error dialog path
            org.junit.Assert.assertTrue(e.message?.isNotEmpty() == true)
        }
    }
}
