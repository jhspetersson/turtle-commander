package io.github.jhspetersson.turtlecommander.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

class System7zVersionTest {

    @Test
    fun `parses windows 7z banner`() {
        val lines = listOf("7-Zip 19.00 (x64) : Copyright (c) 1999-2018 Igor Pavlov : 2019-02-21")
        assertEquals(1900, System7z.parseVersionBanner(lines))
    }

    @Test
    fun `parses 7zz banner with leading blank line`() {
        val lines = listOf("", "7-Zip (z) 21.07 (x64) : Copyright (c) 1999-2021 Igor Pavlov : 2021-12-26")
        assertEquals(2107, System7z.parseVersionBanner(lines))
    }

    @Test
    fun `parses p7zip banner`() {
        val lines = listOf(
            "",
            "7-Zip [64] 16.02 : Copyright (c) 1999-2016 Igor Pavlov : 2016-05-21",
            "p7zip Version 16.02 (locale=en_US.UTF-8,Utf16=on,HugeFiles=on,64 bits,4 CPUs)",
        )
        assertEquals(1602, System7z.parseVersionBanner(lines))
    }

    @Test
    fun `unrecognizable banner yields null`() {
        assertNull(System7z.parseVersionBanner(listOf("sh: 7z: command not found")))
        assertNull(System7z.parseVersionBanner(emptyList()))
    }

    @Test
    fun `formats version`() {
        assertEquals("21.02", System7z.formatVersion(2102))
        assertEquals("19.00", System7z.formatVersion(1900))
    }

    @Test
    fun `probes installed binary`() {
        val binary = System7z.findBinary()
        Assume.assumeTrue("system 7-Zip not installed", binary != null)
        val version = System7z.binaryVersion(binary!!)
        assertNotNull(version)
        assertTrue(version!! >= 900)
    }
}
