package io.github.jhspetersson.turtlecommander.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BreadcrumbSplitPathTest {

    private fun split(path: String) = BreadcrumbPathField.splitPath(path)

    @Test
    fun `UNC path splits into share root plus each folder`() {
        val segments = split("""\\server\share\projects\turtle""")

        assertEquals(
            listOf(
                """\\server\share""" to """\\server\share""",
                "projects" to """\\server\share\projects""",
                "turtle" to """\\server\share\projects\turtle""",
            ),
            segments.map { it.name to it.fullPath },
        )
    }

    @Test
    fun `UNC share root alone is a single clickable crumb`() {
        val segments = split("""\\server\share""")

        assertEquals(1, segments.size)
        assertEquals("""\\server\share""", segments[0].name)
        assertEquals("""\\server\share""", segments[0].fullPath)
    }

    @Test
    fun `UNC server without share is kept as the root crumb`() {
        val segments = split("""\\server""")

        assertEquals(1, segments.size)
        assertEquals("""\\server""", segments[0].name)
        assertEquals("""\\server""", segments[0].fullPath)
    }

    @Test
    fun `local Windows path still splits on drive and folders`() {
        val segments = split("""C:\Users\me\docs""")

        assertEquals(
            listOf(
                """C:\""" to """C:\""",
                "Users" to """C:\Users""",
                "me" to """C:\Users\me""",
                "docs" to """C:\Users\me\docs""",
            ),
            segments.map { it.name to it.fullPath },
        )
    }

    @Test
    fun `POSIX path still splits on slashes with root`() {
        val segments = split("/home/me/docs")

        assertEquals(
            listOf(
                "/" to "/",
                "home" to "/home",
                "me" to "/home/me",
                "docs" to "/home/me/docs",
            ),
            segments.map { it.name to it.fullPath },
        )
    }

    @Test
    fun `empty path yields no segments`() {
        assertEquals(emptyList<Any>(), split(""))
    }
}
