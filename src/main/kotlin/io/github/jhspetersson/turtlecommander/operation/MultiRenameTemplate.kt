package io.github.jhspetersson.turtlecommander.operation

import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.regex.Pattern

/**
 * Pure template engine for the Multi-Rename tool. Mirrors the subset of Total Commander's
 * Ctrl+M placeholders we support in v1. All functions here are side-effect free so they
 * can be unit-tested without a filesystem.
 *
 * Placeholders (case-insensitive):
 *
 * - `[N]`           full base name (without extension)
 * - `[N a]`         single char at 1-based position `a` (negative counts from the end)
 * - `[N a-b]`       chars from `a` to `b` inclusive (either can be negative)
 * - `[N a-]`        chars from `a` to the end (open range)
 * - `[N a,n]`       `n` chars starting at position `a` (TC's position+length form)
 * - `[E]`, `[E a]`, `[E a-b]`, `[E a-]`, `[E a,n]`   same, applied to the extension (without the leading dot)
 * - `[C]`           counter using the supplied [CounterConfig]
 * - `[C s]`         counter with start `s`, step/width from config
 * - `[C s+step]`    counter with start `s` and step `step`
 * - `[C s+step:w]`  counter with start, step, and zero-padded width
 * - `[Y]` `[M]` `[D]`                date parts (year/month/day) — from file's `lastModified`,
 *                                    or current wall clock when `Options.useCurrentDate` is set
 * - `[h]` `[n]` `[s]`                time parts (hour/minute/second — `[n]` for minute
 *                                    avoids the `[M]` month/minute clash TC also avoids)
 * - `[YMD]`                          shorthand for `[Y][M][D]`
 * - `[P]`                            immediate parent directory name
 * - `[G]`                            grandparent directory name (parent of parent)
 * - `[R]`, `[R n]`, `[R##…]`         random digits (count from integer or `#` run; bare `[R]` → 1)
 * - `[Ra n]` / `[RA n]` / `[RM n]`   random lowercase / uppercase / mixed alphanumeric chars
 *
 * Unknown placeholders are left untouched (including their brackets) so typos are visible
 * in the preview instead of silently eaten.
 */
object MultiRenameTemplate {

    data class CounterConfig(
        val start: Int = 1,
        val step: Int = 1,
        val width: Int = 1,
    )

    enum class CaseMode {
        UNCHANGED,
        UPPER,
        LOWER,
        FIRST_UPPER,
        EVERY_WORD_UPPER,
    }

    data class Options(
        val nameTemplate: String = "[N]",
        val extensionTemplate: String = "[E]",
        val search: String = "",
        val replace: String = "",
        val regex: Boolean = false,
        val caseSensitive: Boolean = true,
        val caseMode: CaseMode = CaseMode.UNCHANGED,
        val counter: CounterConfig = CounterConfig(),
        val zone: ZoneId = ZoneId.systemDefault(),
        /**
         * When true, date/time placeholders ([Y][M][D][h][n][s][YMD]) read from the current
         * wall clock instead of each file's lastModified. Matches TC's "use current date" mode.
         */
        val useCurrentDate: Boolean = false,
        /**
         * Optional fixed epoch-millis to use when [useCurrentDate] is true. Lets tests pin time
         * deterministically; in production callers leave this null and we read the clock.
         */
        val currentTimeMillis: Long? = null,
        /**
         * RNG for random placeholders ([R]/[Ra]/[RA]/[RM]). Defaults to a fresh stream on
         * every render so the preview re-rolls as the user edits the template — mirrors TC's
         * behavior. Tests pass a seeded Random for deterministic assertions.
         */
        val random: kotlin.random.Random = kotlin.random.Random.Default,
    )

    data class Input(
        val originalName: String,
        val parentName: String,
        val lastModified: FileTime?,
        val grandParentName: String = "",
    ) {
        companion object {
            fun from(path: Path, lastModified: FileTime?): Input = Input(
                originalName = path.fileName?.toString() ?: "",
                parentName = path.parent?.fileName?.toString() ?: "",
                lastModified = lastModified,
                grandParentName = path.parent?.parent?.fileName?.toString() ?: "",
            )
        }
    }

    /**
     * Applies [options] to [inputs] and returns the generated target names in order.
     * The counter advances by one rendered row (not one input), matching TC behavior —
     * every file consumes one counter tick even if its template doesn't reference `[C]`.
     * That's the principle of least surprise: users sort their selection, expect the
     * counter to line up row-by-row.
     */
    fun render(inputs: List<Input>, options: Options): List<String> {
        val result = ArrayList<String>(inputs.size)
        inputs.forEachIndexed { idx, input ->
            val name = renderOne(input, options, idx)
            result.add(name)
        }
        return result
    }

    internal fun renderOne(input: Input, options: Options, index: Int): String {
        val (base, ext) = splitBaseExt(input.originalName)
        val ctx = RenderContext(
            input = input,
            base = base,
            ext = ext,
            counter = options.counter,
            index = index,
            zone = options.zone,
            useCurrentDate = options.useCurrentDate,
            currentTimeMillis = options.currentTimeMillis,
            random = options.random,
        )
        val renderedBase = expand(options.nameTemplate, ctx)
        val renderedExt = expand(options.extensionTemplate, ctx)
        val joined = if (renderedExt.isEmpty()) renderedBase else "$renderedBase.$renderedExt"
        val afterReplace = applySearchReplace(joined, options)
        return applyCase(afterReplace, options.caseMode)
    }

    private data class RenderContext(
        val input: Input,
        val base: String,
        val ext: String,
        val counter: CounterConfig,
        val index: Int,
        val zone: ZoneId,
        val useCurrentDate: Boolean,
        val currentTimeMillis: Long?,
        val random: kotlin.random.Random,
    )

    /**
     * Splits "foo.tar.gz" into ("foo.tar", "gz"). Leading dot is preserved in the base
     * (e.g. ".gitignore" -> (".gitignore", "")) because treating it as an extension would
     * be surprising — users think of dotfiles as having no extension.
     */
    internal fun splitBaseExt(name: String): Pair<String, String> {
        if (name.isEmpty()) return "" to ""
        val lastDot = name.lastIndexOf('.')
        if (lastDot <= 0) return name to ""
        return name.substring(0, lastDot) to name.substring(lastDot + 1)
    }

    private fun expand(template: String, ctx: RenderContext): String {
        if (template.isEmpty()) return ""
        val sb = StringBuilder(template.length + 16)
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '[') {
                val close = template.indexOf(']', i + 1)
                if (close < 0) {
                    sb.append(template, i, template.length)
                    break
                }
                val body = template.substring(i + 1, close)
                val expanded = resolvePlaceholder(body, ctx)
                if (expanded == null) {
                    // Unknown placeholder — leave it as-is so the user can see the typo.
                    sb.append('[').append(body).append(']')
                } else {
                    sb.append(expanded)
                }
                i = close + 1
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun resolvePlaceholder(body: String, ctx: RenderContext): String? {
        if (body.isEmpty()) return null
        // Date tokens are case-sensitive: [M] is month, [n] is minute. Substring/counter
        // tokens (N/E/C/P/G) are treated case-insensitively for ergonomics — they don't clash.
        val head = body[0]
        val rest = body.substring(1).trim()

        if (rest.isEmpty()) {
            when (head) {
                'Y' -> return datePart(DatePart.YEAR, ctx)
                'M' -> return datePart(DatePart.MONTH, ctx)
                'D' -> return datePart(DatePart.DAY, ctx)
                'h' -> return datePart(DatePart.HOUR, ctx)
                'n' -> return datePart(DatePart.MINUTE, ctx)
                's' -> return datePart(DatePart.SECOND, ctx)
            }
        }
        // Random placeholders. Checked before the N/E/P/C/G dispatch because [R...] and
        // [Ra...]/[RA...]/[RM...] share the leading 'R' — we need to inspect the second
        // char to route correctly.
        if (head.uppercaseChar() == 'R') {
            resolveRandom(body, ctx)?.let { return it }
        }
        when (head.uppercaseChar()) {
            'N' -> return resolveSubstring(ctx.base, rest)
            'E' -> return resolveSubstring(ctx.ext, rest)
            'P' -> return if (rest.isEmpty()) ctx.input.parentName else null
            'G' -> return if (rest.isEmpty()) ctx.input.grandParentName else null
            'C' -> return resolveCounter(rest, ctx.counter, ctx.index)
        }
        if (body == "YMD") {
            return datePart(DatePart.YEAR, ctx) +
                datePart(DatePart.MONTH, ctx) +
                datePart(DatePart.DAY, ctx)
        }
        return null
    }

    private enum class DatePart { YEAR, MONTH, DAY, HOUR, MINUTE, SECOND }

    /**
     * Parses substring specs and returns the corresponding slice of [source]. Forms accepted:
     *   - empty               → whole source
     *   - `"a"`               → single char at 1-based position `a` (negative counts from end)
     *   - `"a-b"`             → chars from `a` to `b` inclusive
     *   - `"a-"`              → chars from `a` to the end (open range; matches TC `[N3-]`)
     *   - `"a,n"`             → `n` chars starting at position `a` (TC's position+length form)
     */
    private fun resolveSubstring(source: String, spec: String): String {
        if (spec.isEmpty()) return source
        val comma = spec.indexOf(',')
        if (comma > 0) {
            val a = spec.substring(0, comma).toIntOrNull() ?: return ""
            val len = spec.substring(comma + 1).toIntOrNull() ?: return ""
            if (len <= 0) return ""
            val start = resolveIndex(a, source.length) ?: return ""
            val end = (start + len).coerceAtMost(source.length)
            return source.substring(start, end)
        }
        val dash = findRangeDash(spec)
        return if (dash < 0) {
            val idx = spec.toIntOrNull() ?: return ""
            val resolved = resolveIndex(idx, source.length) ?: return ""
            source.substring(resolved, resolved + 1)
        } else {
            val left = spec.substring(0, dash)
            val right = spec.substring(dash + 1)
            val a = left.toIntOrNull() ?: return ""
            val start = resolveIndex(a, source.length) ?: return ""
            // Open-ended "a-" means "from a to the end of the source".
            if (right.isEmpty()) return source.substring(start)
            val b = right.toIntOrNull() ?: return ""
            val end = resolveIndex(b, source.length) ?: return ""
            if (start > end) return ""
            source.substring(start, (end + 1).coerceAtMost(source.length))
        }
    }

    /**
     * Finds the range separator in a substring spec like "2-5" or "-3--1".
     * Skips a leading minus (which is a sign, not a separator).
     */
    private fun findRangeDash(spec: String): Int {
        val startAt = if (spec.startsWith('-')) 1 else 0
        return spec.indexOf('-', startAt)
    }

    /**
     * Turns a 1-based index (positive from start, negative from end) into a 0-based offset
     * clamped to the source length, or null if out of bounds.
     */
    private fun resolveIndex(oneBased: Int, length: Int): Int? {
        if (length == 0) return null
        val zero = if (oneBased > 0) oneBased - 1 else length + oneBased
        if (zero !in 0..<length) return null
        return zero
    }

    /**
     * Parses counter specs: `""`, `"10"`, `"10+2"`, `"10+2:3"`. Any missing piece falls back
     * to [cfg]. Malformed specs fall back silently.
     */
    private fun resolveCounter(spec: String, cfg: CounterConfig, index: Int): String {
        var start = cfg.start
        var step = cfg.step
        var width = cfg.width
        if (spec.isNotEmpty()) {
            val colon = spec.indexOf(':')
            val head = if (colon < 0) spec else spec.substring(0, colon)
            val tail = if (colon < 0) "" else spec.substring(colon + 1)
            // Counter step is separated from start by '+'. Start can be negative (leading '-')
            // but step is always a magnitude with a required '+' separator, so a plain indexOf
            // is correct — there's no '+' inside the start token.
            val plus = head.indexOf('+')
            if (plus < 0) {
                head.toIntOrNull()?.let { start = it }
            } else {
                head.substring(0, plus).toIntOrNull()?.let { start = it }
                head.substring(plus + 1).toIntOrNull()?.let { step = it }
            }
            if (tail.isNotEmpty()) tail.toIntOrNull()?.let { width = it }
        }
        val value = start + step * index
        val abs = kotlin.math.abs(value).toString()
        val padded = if (abs.length < width) "0".repeat(width - abs.length) + abs else abs
        return if (value < 0) "-$padded" else padded
    }

    private fun datePart(kind: DatePart, ctx: RenderContext): String {
        val millis: Long = if (ctx.useCurrentDate) {
            ctx.currentTimeMillis ?: System.currentTimeMillis()
        } else {
            ctx.input.lastModified?.toMillis() ?: return ""
        }
        val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ctx.zone)
        return when (kind) {
            DatePart.YEAR -> "%04d".format(ldt.year)
            DatePart.MONTH -> "%02d".format(ldt.monthValue)
            DatePart.DAY -> "%02d".format(ldt.dayOfMonth)
            DatePart.HOUR -> "%02d".format(ldt.hour)
            DatePart.MINUTE -> "%02d".format(ldt.minute)
            DatePart.SECOND -> "%02d".format(ldt.second)
        }
    }

    /**
     * Random placeholders. Returns null if [body] is not a random spec so the caller can fall
     * through to other `R`-prefixed dispatch (currently none, but keeps the contract uniform).
     * Forms accepted:
     *   - `[R]`            → 1 random digit
     *   - `[R5]`           → 5 random digits
     *   - `[R###]`         → 3 random digits (TC-style: count of '#' marks)
     *   - `[Ra...]`        → random lowercase letters
     *   - `[RA...]`        → random uppercase letters
     *   - `[RM...]`        → random mixed alphanumeric (upper + lower + digits)
     */
    private fun resolveRandom(body: String, ctx: RenderContext): String? {
        if (body.isEmpty() || body[0].uppercaseChar() != 'R') return null
        val alphabet: String
        val specStart: Int
        if (body.length == 1) {
            alphabet = "0123456789"
            specStart = 1
        } else {
            when (body[1]) {
                'a' -> { alphabet = "abcdefghijklmnopqrstuvwxyz"; specStart = 2 }
                'A' -> { alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"; specStart = 2 }
                'M' -> { alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"; specStart = 2 }
                else -> { alphabet = "0123456789"; specStart = 1 }
            }
        }
        val spec = body.substring(specStart)
        val count = parseRandomCount(spec) ?: return null
        if (count <= 0) return ""
        val sb = StringBuilder(count)
        repeat(count) { sb.append(alphabet[ctx.random.nextInt(alphabet.length)]) }
        return sb.toString()
    }

    /**
     * Random-count parser. Accepts plain integer (`"5"` → 5), a run of `#` marks (`"###"` → 3),
     * or empty (`""` → 1, matching TC's convention that a bare `[R]` yields a single digit).
     */
    private fun parseRandomCount(spec: String): Int? {
        if (spec.isEmpty()) return 1
        if (spec.all { it == '#' }) return spec.length
        return spec.toIntOrNull()
    }

    private fun applySearchReplace(name: String, options: Options): String {
        if (options.search.isEmpty()) return name
        return if (options.regex) {
            val flags = if (options.caseSensitive) 0 else Pattern.CASE_INSENSITIVE
            try {
                val pattern = Pattern.compile(options.search, flags)
                pattern.matcher(name).replaceAll(options.replace)
            } catch (_: IllegalArgumentException) {
                name
            } catch (_: IndexOutOfBoundsException) {
                name
            }
        } else {
            if (options.caseSensitive) {
                name.replace(options.search, options.replace)
            } else {
                // Java's String.replace is always case-sensitive; use regex with LITERAL + CASE_INSENSITIVE
                // so the Replace text is still treated as literal text (no $1 back-references when
                // the user explicitly turned regex off).
                val pattern = Pattern.compile(Pattern.quote(options.search), Pattern.CASE_INSENSITIVE)
                pattern.matcher(name).replaceAll(java.util.regex.Matcher.quoteReplacement(options.replace))
            }
        }
    }

    private fun applyCase(name: String, mode: CaseMode): String = when (mode) {
        CaseMode.UNCHANGED -> name
        CaseMode.UPPER -> name.uppercase(Locale.ROOT)
        CaseMode.LOWER -> name.lowercase(Locale.ROOT)
        CaseMode.FIRST_UPPER -> {
            if (name.isEmpty()) name
            else name.substring(0, 1).uppercase(Locale.ROOT) + name.substring(1).lowercase(Locale.ROOT)
        }
        CaseMode.EVERY_WORD_UPPER -> buildString(name.length) {
            var upNext = true
            for (c in name) {
                if (c.isLetter()) {
                    append(if (upNext) c.uppercaseChar() else c.lowercaseChar())
                    upNext = false
                } else {
                    append(c)
                    upNext = true
                }
            }
        }
    }

    /**
     * Reports name collisions in a proposed rename batch.
     * Returns the set of 0-based indices whose target name:
     *   - is empty or blank, or
     *   - duplicates another target in the batch (case-insensitive on Windows — we don't
     *     know the target FS here, so callers can intersect with a platform flag), or
     *   - differs from the source and already exists on disk according to [existsOnDisk].
     *
     * [existsOnDisk] is called with the target *path* derived by resolving the name against
     * the source's parent — the caller provides the probe function.
     */
    fun detectConflicts(
        sources: List<Path>,
        targetNames: List<String>,
        caseInsensitiveDuplicates: Boolean,
        existsOnDisk: (Path) -> Boolean,
    ): Set<Int> {
        require(sources.size == targetNames.size) { "sources/targets length mismatch" }
        val conflicts = HashSet<Int>()
        val seen = HashMap<String, Int>()
        targetNames.forEachIndexed { i, name ->
            if (name.isBlank()) {
                conflicts.add(i)
                return@forEachIndexed
            }
            val key = if (caseInsensitiveDuplicates) name.lowercase(Locale.ROOT) else name
            val prev = seen.put(key, i)
            if (prev != null) {
                conflicts.add(i)
                conflicts.add(prev)
            }
        }
        val vacated = HashSet<String>()
        sources.forEachIndexed { j, src ->
            val currentName = src.fileName?.toString() ?: return@forEachIndexed
            if (targetNames[j] != currentName) vacated.add(pathKey(src, caseInsensitiveDuplicates))
        }
        for (i in sources.indices) {
            if (i in conflicts) continue
            val source = sources[i]
            val newName = targetNames[i]
            if (newName == source.fileName?.toString()) continue
            val target = source.resolveSibling(newName)
            if (pathKey(target, caseInsensitiveDuplicates) in vacated) continue
            if (existsOnDisk(target)) conflicts.add(i)
        }
        return conflicts
    }

    private fun pathKey(path: Path, caseInsensitive: Boolean): String {
        val s = path.toString()
        return if (caseInsensitive) s.lowercase(Locale.ROOT) else s
    }
}
