package io.github.jhspetersson.turtlecommander.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecycleBinWantedTest {

    @Test
    fun `local delete honours the setting`() {
        assertTrue(recycleBinWanted(forcePermanent = false, insideArchive = false, setting = true))
        assertFalse(recycleBinWanted(forcePermanent = false, insideArchive = false, setting = false))
    }

    @Test
    fun `shift delete is always permanent`() {
        assertFalse(recycleBinWanted(forcePermanent = true, insideArchive = false, setting = true))
    }

    @Test
    fun `archive entries never go to the recycle bin`() {
        assertFalse(recycleBinWanted(forcePermanent = false, insideArchive = true, setting = true))
    }
}
