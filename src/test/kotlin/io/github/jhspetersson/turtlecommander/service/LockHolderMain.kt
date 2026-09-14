package io.github.jhspetersson.turtlecommander.service

import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

fun main(args: Array<String>) {
    FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
        channel.lock().use {
            println("locked")
            System.out.flush()
            while (System.`in`.read() != -1) {
            }
        }
    }
}
