package io.github.jhspetersson.turtlecommander.vfs

import com.github.junrar.Archive
import com.github.junrar.io.SeekableReadOnlyByteChannel
import com.github.junrar.volume.Volume
import com.github.junrar.volume.VolumeHelper
import com.github.junrar.volume.VolumeManager
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal class ChannelVolumeManager(private val firstVolume: Path) : VolumeManager {
    override fun nextVolume(archive: Archive, lastVolume: Volume?): Volume {
        if (lastVolume == null) return ChannelVolume(archive, firstVolume)
        val mainHeader = archive.mainHeader
        val oldNumbering = mainHeader != null && (!mainHeader.isNewNumbering || archive.isOldFormat)
        val last = (lastVolume as ChannelVolume).path.toAbsolutePath().toString()
        return ChannelVolume(archive, Path.of(VolumeHelper.nextVolumeName(last, oldNumbering)))
    }
}

internal class ChannelVolume(private val archive: Archive, val path: Path) : Volume {
    override fun getChannel(): SeekableReadOnlyByteChannel =
        FileChannelReader(FileChannel.open(path, StandardOpenOption.READ))

    override fun getLength(): Long = runCatching { Files.size(path) }.getOrDefault(0L)

    override fun getArchive(): Archive = archive
}

internal class FileChannelReader(private val channel: FileChannel) : SeekableReadOnlyByteChannel {
    private val single = ByteBuffer.allocate(1)

    override fun getPosition(): Long = channel.position()

    override fun setPosition(pos: Long) {
        channel.position(pos)
    }

    override fun read(): Int {
        single.clear()
        while (true) {
            val n = channel.read(single)
            if (n < 0) return -1
            if (n > 0) return single.get(0).toInt() and 0xFF
        }
    }

    override fun read(buffer: ByteArray, off: Int, count: Int): Int {
        if (count == 0) return 0
        return channel.read(ByteBuffer.wrap(buffer, off, count))
    }

    override fun readFully(buffer: ByteArray, count: Int): Int {
        val target = ByteBuffer.wrap(buffer, 0, count)
        while (target.hasRemaining()) {
            if (channel.read(target) < 0) throw EOFException()
        }
        return count
    }

    override fun close() {
        channel.close()
    }
}
