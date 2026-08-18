package io.github.jhspetersson.turtlecommander.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

class SourcesSafeToDeleteTest {

    private val docs = Path.of("C:/data/docs")
    private val photos = Path.of("C:/data/photos")
    private val notes = Path.of("C:/data/notes.txt")

    @Test
    fun `all sources are deletable when nothing failed`() {
        val sources = listOf(docs, photos, notes)
        assertEquals(sources, sourcesSafeToDelete(sources, emptySet()))
    }

    @Test
    fun `a failed file excludes only itself`() {
        val sources = listOf(docs, notes)
        assertEquals(listOf(docs), sourcesSafeToDelete(sources, setOf(notes)))
    }

    @Test
    fun `a failure inside a directory keeps the whole directory`() {
        val sources = listOf(docs, photos)
        val failed = setOf(Path.of("C:/data/docs/sub/report.txt"))
        assertEquals(listOf(photos), sourcesSafeToDelete(sources, failed))
    }

    @Test
    fun `a sibling with a shared name prefix is still deletable`() {
        val sources = listOf(Path.of("C:/data/docs"), Path.of("C:/data/docs-old"))
        val failed = setOf(Path.of("C:/data/docs/report.txt"))
        assertEquals(listOf(Path.of("C:/data/docs-old")), sourcesSafeToDelete(sources, failed))
    }

    @Test
    fun `failures in every source keep everything`() {
        val sources = listOf(docs, notes)
        val failed = setOf(Path.of("C:/data/docs/a.txt"), notes)
        assertEquals(emptyList<Path>(), sourcesSafeToDelete(sources, failed))
    }
}
