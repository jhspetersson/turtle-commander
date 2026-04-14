package io.github.jhspetersson.turtlecommander.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ProjectBaseDeleteTest {

    @Test
    fun `returns false when base path is null`() {
        val file = Files.createTempFile("tc-del-", ".txt")
        try {
            assertFalse(allUnderProjectBase(listOf(file), null))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `returns false for empty selection`() {
        val base = Files.createTempDirectory("tc-base-")
        try {
            assertFalse(allUnderProjectBase(emptyList(), base.toString()))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `returns true when every path is under base`() {
        val base = Files.createTempDirectory("tc-base-")
        try {
            val a = Files.writeString(base.resolve("a.txt"), "x")
            val sub = Files.createDirectory(base.resolve("sub"))
            val b = Files.writeString(sub.resolve("b.txt"), "y")
            assertTrue(allUnderProjectBase(listOf(a, b, sub), base.toString()))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `returns false when any path is outside base`() {
        val base = Files.createTempDirectory("tc-base-")
        val outside = Files.createTempDirectory("tc-outside-")
        try {
            val a = Files.writeString(base.resolve("a.txt"), "x")
            val b = Files.writeString(outside.resolve("b.txt"), "y")
            assertFalse(allUnderProjectBase(listOf(a, b), base.toString()))
        } finally {
            base.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `normalizes dot-dot segments before comparison`() {
        val base = Files.createTempDirectory("tc-base-")
        try {
            val a = Files.writeString(base.resolve("a.txt"), "x")
            val tricky = base.resolve("sub").resolve("..").resolve("a.txt")
            assertTrue(allUnderProjectBase(listOf(a, tricky), base.toString()))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `returns false for malformed base path`() {
        val file = Files.createTempFile("tc-del-", ".txt")
        try {
            assertFalse(allUnderProjectBase(listOf(file), "\u0000::bogus"))
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
