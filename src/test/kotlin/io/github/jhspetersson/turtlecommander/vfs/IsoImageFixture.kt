package io.github.jhspetersson.turtlecommander.vfs

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

object IsoImageFixture {

    private const val SECTOR = 2048
    private const val PVD = 16
    private const val TERMINATOR = 17
    private const val L_PATH_TABLE = 18
    private const val M_PATH_TABLE = 19
    private const val ROOT_DIR = 20
    private const val FIRST_DATA = 21

    fun build(files: Map<String, ByteArray>, minSectors: Int = 0, emptyDirs: List<String> = emptyList()): ByteArray {
        val extents = LinkedHashMap<String, Int>()
        var next = FIRST_DATA
        for ((name, bytes) in files) {
            extents[name] = next
            next += maxOf(1, (bytes.size + SECTOR - 1) / SECTOR)
        }
        val dirExtents = LinkedHashMap<String, Int>()
        for (name in emptyDirs) {
            dirExtents[name] = next
            next += 1
        }
        val total = maxOf(next, minSectors)
        val image = ByteBuffer.allocate(total * SECTOR)

        writePvd(image, total)
        writeTerminator(image)
        writePathTable(image, L_PATH_TABLE, ByteOrder.LITTLE_ENDIAN)
        writePathTable(image, M_PATH_TABLE, ByteOrder.BIG_ENDIAN)

        val dir = ByteBuffer.allocate(SECTOR)
        dir.put(record(ROOT_DIR, SECTOR, directory = true, ident = byteArrayOf(0)))
        dir.put(record(ROOT_DIR, SECTOR, directory = true, ident = byteArrayOf(1)))
        for ((name, bytes) in files) {
            dir.put(record(extents.getValue(name), bytes.size, directory = false, ident = "$name;1".toByteArray(StandardCharsets.US_ASCII)))
            image.position(extents.getValue(name) * SECTOR)
            image.put(bytes)
        }
        for ((name, extent) in dirExtents) {
            dir.put(record(extent, SECTOR, directory = true, ident = name.toByteArray(StandardCharsets.US_ASCII)))
            val sub = ByteBuffer.allocate(SECTOR)
            sub.put(record(extent, SECTOR, directory = true, ident = byteArrayOf(0)))
            sub.put(record(ROOT_DIR, SECTOR, directory = true, ident = byteArrayOf(1)))
            image.position(extent * SECTOR)
            image.put(sub.array())
        }
        image.position(ROOT_DIR * SECTOR)
        image.put(dir.array())
        return image.array()
    }

    private fun writePvd(image: ByteBuffer, totalSectors: Int) {
        val base = PVD * SECTOR
        image.put(base, 1)
        putAscii(image, base + 1, "CD001")
        image.put(base + 6, 1)
        putAscii(image, base + 8, " ".repeat(32))
        putAscii(image, base + 40, "TEST".padEnd(32))
        putBoth32(image, base + 80, totalSectors)
        putBoth16(image, base + 120, 1)
        putBoth16(image, base + 124, 1)
        putBoth16(image, base + 128, SECTOR)
        putBoth32(image, base + 132, 10)
        image.order(ByteOrder.LITTLE_ENDIAN).putInt(base + 140, L_PATH_TABLE)
        image.order(ByteOrder.BIG_ENDIAN).putInt(base + 148, M_PATH_TABLE)
        val root = record(ROOT_DIR, SECTOR, directory = true, ident = byteArrayOf(0))
        for (i in root.indices) image.put(base + 156 + i, root[i])
        putAscii(image, base + 813, "0".repeat(16))
        putAscii(image, base + 830, "0".repeat(16))
        putAscii(image, base + 847, "0".repeat(16))
        putAscii(image, base + 864, "0".repeat(16))
        image.put(base + 881, 1)
    }

    private fun writeTerminator(image: ByteBuffer) {
        val base = TERMINATOR * SECTOR
        image.put(base, 255.toByte())
        putAscii(image, base + 1, "CD001")
        image.put(base + 6, 1)
    }

    private fun writePathTable(image: ByteBuffer, sector: Int, order: ByteOrder) {
        val base = sector * SECTOR
        image.put(base, 1)
        image.put(base + 1, 0)
        image.order(order).putInt(base + 2, ROOT_DIR)
        image.order(order).putShort(base + 6, 1)
        image.put(base + 8, 0)
    }

    private fun record(extent: Int, length: Int, directory: Boolean, ident: ByteArray): ByteArray {
        val size = 33 + ident.size + (if (ident.size % 2 == 0) 1 else 0)
        val buf = ByteBuffer.allocate(size)
        buf.put(0, size.toByte())
        putBoth32(buf, 2, extent)
        putBoth32(buf, 10, length)
        buf.put(18, 124)
        buf.put(19, 1)
        buf.put(20, 1)
        buf.put(25, if (directory) 2 else 0)
        putBoth16(buf, 28, 1)
        buf.put(32, ident.size.toByte())
        for (i in ident.indices) buf.put(33 + i, ident[i])
        return buf.array()
    }

    private fun putAscii(buf: ByteBuffer, at: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.US_ASCII)
        for (i in bytes.indices) buf.put(at + i, bytes[i])
    }

    private fun putBoth16(buf: ByteBuffer, at: Int, value: Int) {
        buf.order(ByteOrder.LITTLE_ENDIAN).putShort(at, value.toShort())
        buf.order(ByteOrder.BIG_ENDIAN).putShort(at + 2, value.toShort())
    }

    private fun putBoth32(buf: ByteBuffer, at: Int, value: Int) {
        buf.order(ByteOrder.LITTLE_ENDIAN).putInt(at, value)
        buf.order(ByteOrder.BIG_ENDIAN).putInt(at + 4, value)
    }
}
