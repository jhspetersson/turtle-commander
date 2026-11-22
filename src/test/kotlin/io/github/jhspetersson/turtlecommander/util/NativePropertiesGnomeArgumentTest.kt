package io.github.jhspetersson.turtlecommander.util

import org.junit.Assert.assertEquals
import org.junit.Test

class NativePropertiesGnomeArgumentTest {

    @Test
    fun `a plain uri is wrapped as a one-element string array`() {
        assertEquals("['file:///home/me/notes.txt']", NativeProperties.gnomeUriListArgument("file:///home/me/notes.txt"))
    }

    @Test
    fun `an apostrophe is percent-encoded so it cannot end the string`() {
        assertEquals(
            "['file:///home/me/it%27s%20here.txt']",
            NativeProperties.gnomeUriListArgument("file:///home/me/it's%20here.txt"),
        )
    }
}
