package io.github.jhspetersson.turtlecommander.vfs

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

class MsiVirtualFileSystemTest {

    private lateinit var msiPath: Path
    private var vfs: MsiVirtualFileSystem? = null

    @After
    fun tearDown() {
        vfs?.close()
        if (::msiPath.isInitialized) Files.deleteIfExists(msiPath)
    }

    private val endOfChain = -2
    private val fatSect = -3
    private val freeSect = -1
    private val noStream = -1

    private fun dirEntry(
        name: String,
        type: Int,
        left: Int,
        right: Int,
        child: Int,
        startSector: Int,
        size: Int,
    ): ByteArray {
        val b = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        for (ch in name) b.putShort(ch.code.toShort())
        b.position(64)
        b.putShort(if (name.isEmpty()) 0 else ((name.length + 1) * 2).toShort())
        b.put(type.toByte())
        b.put(1)
        b.putInt(left)
        b.putInt(right)
        b.putInt(child)
        b.position(b.position() + 16 + 4 + 8 + 8)
        b.putInt(startSector)
        b.putInt(size)
        return b.array()
    }

    private fun writeMsi(payload: ByteArray): Path {
        require(payload.size == 4096)
        val path = Files.createTempFile("test-", ".msi")

        val header = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        header.put(
            byteArrayOf(
                0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
                0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
            ),
        )
        header.position(24)
        header.putShort(0x003E)
        header.putShort(0x0003)
        header.putShort(0xFFFE.toShort())
        header.putShort(9)
        header.putShort(6)
        header.position(header.position() + 6)
        header.putInt(0)
        header.putInt(1)
        header.putInt(1)
        header.putInt(0)
        header.putInt(0x1000)
        header.putInt(endOfChain)
        header.putInt(0)
        header.putInt(endOfChain)
        header.putInt(0)
        header.putInt(0)
        while (header.hasRemaining()) header.putInt(freeSect)

        val fat = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        fat.putInt(fatSect)
        fat.putInt(endOfChain)
        for (sector in 2..9) fat.putInt(if (sector == 9) endOfChain else sector + 1)
        while (fat.hasRemaining()) fat.putInt(freeSect)

        val directory = ByteBuffer.allocate(512)
        directory.put(dirEntry("Root Entry", 5, noStream, noStream, 1, endOfChain, 0))
        directory.put(dirEntry("Payload", 2, noStream, noStream, noStream, 2, payload.size))
        directory.put(dirEntry("", 0, noStream, noStream, noStream, 0, 0))
        directory.put(dirEntry("", 0, noStream, noStream, noStream, 0, 0))

        Files.newOutputStream(path).use { os ->
            os.write(header.array())
            os.write(fat.array())
            os.write(directory.array())
            os.write(payload)
        }
        return path
    }

    @Test
    fun `msi extracts via system 7-Zip`() = runBlocking {
        Assume.assumeTrue("system 7-Zip not installed", System7z.findBinary() != null)
        val payload = ByteArray(4096) { (it % 251).toByte() }
        msiPath = writeMsi(payload)
        val msiVfs = MsiVirtualFileSystem(msiPath)
        vfs = msiVfs

        assertTrue(msiVfs.isReadOnly)

        val rootEntries = msiVfs.listFiles(msiVfs.root).filter { !it.isParentLink }
        assertNotNull(rootEntries.find { it.name == "Payload" })
        assertArrayEquals(payload, Files.readAllBytes(msiVfs.getPath("/Payload")))
    }

    @Test
    fun `non-msi file is rejected silently`() {
        msiPath = Files.createTempFile("not-an-msi-", ".msi")
        Files.write(msiPath, "not a compound file".toByteArray())
        try {
            MsiVirtualFileSystem(msiPath)
            fail("Expected construction to fail for a non-MSI file")
        } catch (_: SilentVfsOpenException) {
        }
    }
}
