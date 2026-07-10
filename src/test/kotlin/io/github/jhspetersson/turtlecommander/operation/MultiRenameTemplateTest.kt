package io.github.jhspetersson.turtlecommander.operation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.ZoneId
import java.time.ZonedDateTime

class MultiRenameTemplateTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun input(name: String, parent: String = "workdir", mtime: FileTime? = null) =
        MultiRenameTemplate.Input(name, parent, mtime)

    @Test
    fun `pass-through N E preserves name exactly`() {
        val out = MultiRenameTemplate.render(
            listOf(input("Photo.JPG"), input("Notes.txt")),
            MultiRenameTemplate.Options(zone = zone),
        )
        assertEquals(listOf("Photo.JPG", "Notes.txt"), out)
    }

    @Test
    fun `dotfile stays a dotfile under default template`() {
        val out = MultiRenameTemplate.render(
            listOf(input(".gitignore")),
            MultiRenameTemplate.Options(zone = zone),
        )
        // ".gitignore" has no extension in our splitter — the whole string is the base.
        assertEquals(listOf(".gitignore"), out)
    }

    @Test
    fun `substring extracts fixed-width prefix`() {
        val out = MultiRenameTemplate.render(
            listOf(input("Photograph.jpg")),
            MultiRenameTemplate.Options(nameTemplate = "[N1-5]", extensionTemplate = "[E]", zone = zone),
        )
        assertEquals(listOf("Photo.jpg"), out)
    }

    @Test
    fun `substring with negative index counts from end`() {
        val out = MultiRenameTemplate.render(
            listOf(input("backup_2026.sql")),
            MultiRenameTemplate.Options(nameTemplate = "[N-4--1]", extensionTemplate = "[E]", zone = zone),
        )
        // Last 4 chars of "backup_2026" are "2026".
        assertEquals(listOf("2026.sql"), out)
    }

    @Test
    fun `substring out of range yields empty`() {
        val out = MultiRenameTemplate.render(
            listOf(input("ab.txt")),
            MultiRenameTemplate.Options(nameTemplate = "[N5-10]", extensionTemplate = "[E]", zone = zone),
        )
        assertEquals(listOf(".txt"), out)
    }

    @Test
    fun `counter advances once per row regardless of references`() {
        val out = MultiRenameTemplate.render(
            listOf(input("a"), input("b"), input("c")),
            MultiRenameTemplate.Options(
                nameTemplate = "img_[C]",
                extensionTemplate = "",
                counter = MultiRenameTemplate.CounterConfig(start = 10, step = 5, width = 3),
                zone = zone,
            ),
        )
        assertEquals(listOf("img_010", "img_015", "img_020"), out)
    }

    @Test
    fun `inline counter spec overrides configured start and width`() {
        val out = MultiRenameTemplate.render(
            listOf(input("x"), input("y")),
            MultiRenameTemplate.Options(
                nameTemplate = "[C100+2:4]",
                extensionTemplate = "",
                counter = MultiRenameTemplate.CounterConfig(start = 1, step = 1, width = 1),
                zone = zone,
            ),
        )
        assertEquals(listOf("0100", "0102"), out)
    }

    @Test
    fun `date placeholders pull from lastModified in configured zone`() {
        val instant = ZonedDateTime.of(2026, 4, 20, 13, 45, 7, 0, zone).toInstant()
        val ft = FileTime.fromMillis(instant.toEpochMilli())
        val out = MultiRenameTemplate.render(
            listOf(MultiRenameTemplate.Input("doc.md", "p", ft)),
            MultiRenameTemplate.Options(
                nameTemplate = "[Y]-[M]-[D]_[h][n][s]_[N]",
                extensionTemplate = "[E]",
                zone = zone,
            ),
        )
        assertEquals(listOf("2026-04-20_134507_doc.md"), out)
    }

    @Test
    fun `month and minute placeholders are case-sensitive and do not collide`() {
        val instant = ZonedDateTime.of(2026, 7, 1, 8, 30, 0, 0, zone).toInstant()
        val ft = FileTime.fromMillis(instant.toEpochMilli())
        val out = MultiRenameTemplate.render(
            listOf(MultiRenameTemplate.Input("f.txt", "p", ft)),
            MultiRenameTemplate.Options(
                nameTemplate = "M=[M]_n=[n]",
                extensionTemplate = "[E]",
                zone = zone,
            ),
        )
        assertEquals(listOf("M=07_n=30.txt"), out)
    }

    @Test
    fun `literal search and replace is case-sensitive by default`() {
        val out = MultiRenameTemplate.render(
            listOf(input("Report_DRAFT.doc"), input("report_draft.doc")),
            MultiRenameTemplate.Options(
                nameTemplate = "[N]",
                extensionTemplate = "[E]",
                search = "DRAFT",
                replace = "FINAL",
                zone = zone,
            ),
        )
        assertEquals(listOf("Report_FINAL.doc", "report_draft.doc"), out)
    }

    @Test
    fun `case-insensitive literal replace preserves dollar-digit as literal text`() {
        // With regex mode off the replacement must be taken literally — the user's "$1"
        // is text, not a back-reference. The internal implementation uses quoteReplacement
        // for the case-insensitive path since it routes through Matcher.replaceAll.
        val out = MultiRenameTemplate.render(
            listOf(input("ZZZ_old_data")),
            MultiRenameTemplate.Options(
                nameTemplate = "[N]",
                extensionTemplate = "",
                search = "zzz",
                replace = $$"$1_prefix",
                regex = false,
                caseSensitive = false,
                zone = zone,
            ),
        )
        assertEquals(listOf($$"$1_prefix_old_data"), out)
    }

    @Test
    fun `regex replace supports back-references`() {
        // Pattern deliberately anchors to the pre-extension base (`[^.]+`) so the greedy
        // part doesn't swallow the dot + extension.
        val out = MultiRenameTemplate.render(
            listOf(input("IMG_1234_sunset.jpg")),
            MultiRenameTemplate.Options(
                nameTemplate = "[N]",
                extensionTemplate = "[E]",
                search = "IMG_(\\d+)_([^.]+)",
                replace = $$"$2-$1",
                regex = true,
                zone = zone,
            ),
        )
        assertEquals(listOf("sunset-1234.jpg"), out)
    }

    @Test
    fun `invalid regex falls back to no-op instead of throwing`() {
        val out = MultiRenameTemplate.render(
            listOf(input("a.txt")),
            MultiRenameTemplate.Options(
                nameTemplate = "[N]",
                extensionTemplate = "[E]",
                search = "(unclosed",
                replace = "x",
                regex = true,
                zone = zone,
            ),
        )
        assertEquals(listOf("a.txt"), out)
    }

    @Test
    fun `every-word-upper capitalizes after non-letter separators`() {
        val out = MultiRenameTemplate.render(
            listOf(input("hello_world-foo.bar baz.txt")),
            MultiRenameTemplate.Options(
                nameTemplate = "[N]",
                extensionTemplate = "[E]",
                caseMode = MultiRenameTemplate.CaseMode.EVERY_WORD_UPPER,
                zone = zone,
            ),
        )
        assertEquals(listOf("Hello_World-Foo.Bar Baz.Txt"), out)
    }

    @Test
    fun `unknown placeholder passes through untouched so typos are visible`() {
        val out = MultiRenameTemplate.render(
            listOf(input("a.txt")),
            MultiRenameTemplate.Options(
                nameTemplate = "[Q]-[N]",
                extensionTemplate = "[E]",
                zone = zone,
            ),
        )
        assertEquals(listOf("[Q]-a.txt"), out)
    }

    @Test
    fun `unclosed bracket is left as literal tail`() {
        val out = MultiRenameTemplate.render(
            listOf(input("a.txt")),
            MultiRenameTemplate.Options(
                nameTemplate = "[N]-[oops",
                extensionTemplate = "[E]",
                zone = zone,
            ),
        )
        assertEquals(listOf("a-[oops.txt"), out)
    }

    @Test
    fun `parent directory placeholder pulls from input`() {
        val out = MultiRenameTemplate.render(
            listOf(MultiRenameTemplate.Input("x.log", "service-A", null)),
            MultiRenameTemplate.Options(
                nameTemplate = "[P]_[N]",
                extensionTemplate = "[E]",
                zone = zone,
            ),
        )
        assertEquals(listOf("service-A_x.log"), out)
    }

    @Test
    fun `detectConflicts flags duplicate targets on both offenders`() {
        val base = Path.of("/tmp/work")
        val sources = listOf(base.resolve("a.txt"), base.resolve("b.txt"), base.resolve("c.txt"))
        val targets = listOf("out.txt", "out.txt", "d.txt")

        val conflicts = MultiRenameTemplate.detectConflicts(sources, targets, caseInsensitiveDuplicates = false) { false }

        assertTrue(0 in conflicts)
        assertTrue(1 in conflicts)
        assertFalse(2 in conflicts)
    }

    @Test
    fun `detectConflicts flags case-variant duplicates on case-insensitive FS`() {
        val base = Path.of("/tmp/work")
        val sources = listOf(base.resolve("a"), base.resolve("b"))
        val targets = listOf("OUT.TXT", "out.txt")

        val ci = MultiRenameTemplate.detectConflicts(sources, targets, caseInsensitiveDuplicates = true) { false }
        val cs = MultiRenameTemplate.detectConflicts(sources, targets, caseInsensitiveDuplicates = false) { false }

        assertEquals(setOf(0, 1), ci)
        assertEquals(emptySet<Int>(), cs)
    }

    @Test
    fun `detectConflicts flags target that already exists on disk`() {
        val tmp = Files.createTempDirectory("multi-rename-conflict-")
        try {
            val a = tmp.resolve("a").also { Files.createFile(it) }
            tmp.resolve("b").also { Files.createFile(it) } // external file, not part of the batch
            val sources = listOf(a)
            val targets = listOf("b") // renaming a→b collides with the unrelated existing b

            val conflicts = MultiRenameTemplate.detectConflicts(sources, targets, caseInsensitiveDuplicates = false) { Files.exists(it) }

            assertTrue(0 in conflicts)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `detectConflicts allows a swap since both sources are vacated`() {
        val tmp = Files.createTempDirectory("multi-rename-swap-")
        try {
            val a = tmp.resolve("a").also { Files.createFile(it) }
            val b = tmp.resolve("b").also { Files.createFile(it) }
            val sources = listOf(a, b)
            val targets = listOf("b", "a") // a↔b: executor handles this via two-phase temp rename

            val conflicts = MultiRenameTemplate.detectConflicts(sources, targets, caseInsensitiveDuplicates = false) { Files.exists(it) }

            assertEquals(emptySet<Int>(), conflicts)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `detectConflicts allows a rename chain onto a vacated source`() {
        val tmp = Files.createTempDirectory("multi-rename-chain-")
        try {
            val a = tmp.resolve("a").also { Files.createFile(it) }
            val b = tmp.resolve("b").also { Files.createFile(it) }
            val sources = listOf(a, b)
            val targets = listOf("b", "c") // a→b lands on b, but b→c vacates it

            val conflicts = MultiRenameTemplate.detectConflicts(sources, targets, caseInsensitiveDuplicates = false) { Files.exists(it) }

            assertEquals(emptySet<Int>(), conflicts)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `detectConflicts still flags a target onto a source that is not renamed`() {
        val tmp = Files.createTempDirectory("multi-rename-static-")
        try {
            val a = tmp.resolve("a").also { Files.createFile(it) }
            val b = tmp.resolve("b").also { Files.createFile(it) }
            val sources = listOf(a, b)
            val targets = listOf("b", "b") // a→b collides with b, which stays put (b→b, no rename)

            val conflicts = MultiRenameTemplate.detectConflicts(sources, targets, caseInsensitiveDuplicates = false) { Files.exists(it) }

            assertTrue(0 in conflicts)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `detectConflicts ignores a target that equals the source name (no rename)`() {
        val base = Path.of("/tmp/work")
        val sources = listOf(base.resolve("a.txt"))
        val targets = listOf("a.txt")
        // existsOnDisk always true — but since the name didn't change, it shouldn't be flagged.
        val conflicts = MultiRenameTemplate.detectConflicts(sources, targets, caseInsensitiveDuplicates = false) { true }
        assertFalse(0 in conflicts)
    }

    @Test
    fun `blank target name is a conflict`() {
        val base = Path.of("/tmp/work")
        val sources = listOf(base.resolve("a.txt"))
        val targets = listOf("   ")
        val conflicts = MultiRenameTemplate.detectConflicts(sources, targets, caseInsensitiveDuplicates = false) { false }
        assertTrue(0 in conflicts)
    }

    @Test
    fun `splitBaseExt treats tar gz as single extension gz`() {
        // Simple rule: only the final segment after the last '.' is the extension.
        // Users who want to keep ".tar" in the base get it automatically.
        val (base, ext) = MultiRenameTemplate.splitBaseExt("archive.tar.gz")
        assertEquals("archive.tar", base)
        assertEquals("gz", ext)
    }

    @Test
    fun `name template can be empty producing only extension`() {
        val out = MultiRenameTemplate.render(
            listOf(input("foo.bar")),
            MultiRenameTemplate.Options(nameTemplate = "", extensionTemplate = "[E]", zone = zone),
        )
        assertEquals(listOf(".bar"), out)
    }

    @Test
    fun `empty extension template drops the dot entirely`() {
        val out = MultiRenameTemplate.render(
            listOf(input("foo.bar")),
            MultiRenameTemplate.Options(nameTemplate = "[N]", extensionTemplate = "", zone = zone),
        )
        assertEquals(listOf("foo"), out)
    }

    @Test
    fun `substring position-length picks N chars starting at 1-based position`() {
        // "Photograph" → pos 2, 3 chars → "hot"
        val out = MultiRenameTemplate.render(
            listOf(input("Photograph.jpg")),
            MultiRenameTemplate.Options(nameTemplate = "[N2,3]", extensionTemplate = "[E]", zone = zone),
        )
        assertEquals(listOf("hot.jpg"), out)
    }

    @Test
    fun `substring position-length clamps to source length if it overruns`() {
        // "ab" → pos 1, 10 chars → clamped to "ab"
        val out = MultiRenameTemplate.render(
            listOf(input("ab.txt")),
            MultiRenameTemplate.Options(nameTemplate = "[N1,10]", extensionTemplate = "[E]", zone = zone),
        )
        assertEquals(listOf("ab.txt"), out)
    }

    @Test
    fun `substring open-ended range yields everything from position to end`() {
        // "photograph" → pos 3 onwards → "otograph"
        val out = MultiRenameTemplate.render(
            listOf(input("photograph.jpg")),
            MultiRenameTemplate.Options(nameTemplate = "[N3-]", extensionTemplate = "[E]", zone = zone),
        )
        assertEquals(listOf("otograph.jpg"), out)
    }

    @Test
    fun `substring open-ended range honours negative start`() {
        // "photograph" → from 3rd-from-end onwards → last 3 chars "aph"
        val out = MultiRenameTemplate.render(
            listOf(input("photograph.jpg")),
            MultiRenameTemplate.Options(nameTemplate = "[N-3-]", extensionTemplate = "[E]", zone = zone),
        )
        assertEquals(listOf("aph.jpg"), out)
    }

    @Test
    fun `grandparent placeholder pulls from input`() {
        val out = MultiRenameTemplate.render(
            listOf(MultiRenameTemplate.Input("x.log", "service-A", null, "prod")),
            MultiRenameTemplate.Options(
                nameTemplate = "[G]_[P]_[N]",
                extensionTemplate = "[E]",
                zone = zone,
            ),
        )
        assertEquals(listOf("prod_service-A_x.log"), out)
    }

    @Test
    fun `grandparent placeholder is empty when path has no grandparent`() {
        val out = MultiRenameTemplate.render(
            // Default grandParentName is "" via data-class default
            listOf(MultiRenameTemplate.Input("x.log", "workdir", null)),
            MultiRenameTemplate.Options(
                nameTemplate = "[G]-[N]",
                extensionTemplate = "[E]",
                zone = zone,
            ),
        )
        assertEquals(listOf("-x.log"), out)
    }

    @Test
    fun `random digits placeholder emits the requested count`() {
        val out = MultiRenameTemplate.render(
            listOf(input("a.txt"), input("b.txt")),
            MultiRenameTemplate.Options(
                nameTemplate = "[N]-[R5]",
                extensionTemplate = "[E]",
                zone = zone,
                random = kotlin.random.Random(42),
            ),
        )
        // Seeded RNG → deterministic digits. Shape check: 5 digits after the dash, different across rows.
        assertEquals(2, out.size)
        out.forEach { name ->
            val suffix = name.substringAfter('-').substringBefore('.')
            assertEquals(5, suffix.length)
            assertTrue(suffix.all { it.isDigit() })
        }
    }

    @Test
    fun `random hash-mark count parses like TC`() {
        val out = MultiRenameTemplate.render(
            listOf(input("a")),
            MultiRenameTemplate.Options(
                nameTemplate = "[R###]",
                extensionTemplate = "",
                zone = zone,
                random = kotlin.random.Random(1),
            ),
        )
        assertEquals(1, out.size)
        assertEquals(3, out[0].length)
        assertTrue(out[0].all { it.isDigit() })
    }

    @Test
    fun `bare random placeholder yields a single digit`() {
        val out = MultiRenameTemplate.render(
            listOf(input("a")),
            MultiRenameTemplate.Options(
                nameTemplate = "[R]",
                extensionTemplate = "",
                zone = zone,
                random = kotlin.random.Random(7),
            ),
        )
        assertEquals(1, out[0].length)
        assertTrue(out[0][0].isDigit())
    }

    @Test
    fun `random lowercase uppercase and mixed alphabets`() {
        val lower = MultiRenameTemplate.render(
            listOf(input("a")),
            MultiRenameTemplate.Options(nameTemplate = "[Ra8]", extensionTemplate = "", zone = zone, random = kotlin.random.Random(1)),
        )[0]
        val upper = MultiRenameTemplate.render(
            listOf(input("a")),
            MultiRenameTemplate.Options(nameTemplate = "[RA8]", extensionTemplate = "", zone = zone, random = kotlin.random.Random(1)),
        )[0]
        val mixed = MultiRenameTemplate.render(
            listOf(input("a")),
            MultiRenameTemplate.Options(nameTemplate = "[RM12]", extensionTemplate = "", zone = zone, random = kotlin.random.Random(1)),
        )[0]
        assertEquals(8, lower.length)
        assertTrue(lower.all { it in 'a'..'z' })
        assertEquals(8, upper.length)
        assertTrue(upper.all { it in 'A'..'Z' })
        assertEquals(12, mixed.length)
        assertTrue(mixed.all { it.isLetterOrDigit() })
    }

    @Test
    fun `malformed random spec falls through as unknown placeholder`() {
        // "Rz" isn't a recognized random variant and "z" isn't a digit count — preserve the
        // brackets so the user sees the typo in the preview instead of silently dropping it.
        val out = MultiRenameTemplate.render(
            listOf(input("a.txt")),
            MultiRenameTemplate.Options(nameTemplate = "[Rz]", extensionTemplate = "[E]", zone = zone),
        )
        assertEquals(listOf("[Rz].txt"), out)
    }

    @Test
    fun `useCurrentDate pulls date parts from pinned current time`() {
        // 2026-04-21 00:15:30 UTC
        val pinned = ZonedDateTime.of(2026, 4, 21, 0, 15, 30, 0, zone).toInstant().toEpochMilli()
        val out = MultiRenameTemplate.render(
            listOf(input("a.txt")),
            MultiRenameTemplate.Options(
                nameTemplate = "[Y][M][D]_[h][n][s]_[N]",
                extensionTemplate = "[E]",
                zone = zone,
                useCurrentDate = true,
                currentTimeMillis = pinned,
            ),
        )
        assertEquals(listOf("20260421_001530_a.txt"), out)
    }

    @Test
    fun `useCurrentDate ignores lastModified on each file`() {
        // File mtime is 2020; current time is pinned to 2026. With useCurrentDate=true the
        // rendered date must reflect 2026, not 2020.
        val fileTime = FileTime.fromMillis(ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli())
        val pinned = ZonedDateTime.of(2026, 4, 21, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val out = MultiRenameTemplate.render(
            listOf(MultiRenameTemplate.Input("a.txt", "p", fileTime)),
            MultiRenameTemplate.Options(
                nameTemplate = "[Y]",
                extensionTemplate = "[E]",
                zone = zone,
                useCurrentDate = true,
                currentTimeMillis = pinned,
            ),
        )
        assertEquals(listOf("2026.txt"), out)
    }
}
