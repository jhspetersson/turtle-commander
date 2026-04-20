package io.github.jhspetersson.turtlecommander.operation

import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

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
 * - `[E]`, `[E a]`, `[E a-b]`   same, applied to the extension (without the leading dot)
 * - `[C]`           counter using the supplied [CounterConfig]
 * - `[C s]`         counter with start `s`, step/width from config
 * - `[C s+step]`    counter with start `s` and step `step`
 * - `[C s+step:w]`  counter with start, step, and zero-padded width
 * - `[Y]` `[M]` `[D]`                date parts from `lastModified` (year/month/day)
 * - `[h]` `[n]` `[s]`                time parts (hour/minute/second — `[n]` for minute
 *                                    avoids the `[M]` month/minute clash TC also avoids)
 * - `[YMD]`                          shorthand for `[Y][M][D]`
 * - `[P]`                            immediate parent directory name
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
    )

    data class Input(
        val originalName: String,
        val parentName: String,
        val lastModified: FileTime?,
    ) {
        companion object {
            fun from(path: Path, lastModified: FileTime?): Input = Input(
                originalName = path.fileName?.toString() ?: "",
                parentName = path.parent?.fileName?.toString() ?: "",
                lastModified = lastModified,
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
        val renderedBase = expand(options.nameTemplate, input, base, ext, options.counter, index, options.zone)
        val renderedExt = expand(options.extensionTemplate, input, base, ext, options.counter, index, options.zone)
        val joined = if (renderedExt.isEmpty()) renderedBase else "$renderedBase.$renderedExt"
        val afterReplace = applySearchReplace(joined, options)
        return applyCase(afterReplace, options.caseMode)
    }

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

    private fun expand(
        template: String,
        input: Input,
        base: String,
        ext: String,
        counter: CounterConfig,
        index: Int,
        zone: ZoneId,
    ): String {
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
                val expanded = resolvePlaceholder(body, input, base, ext, counter, index, zone)
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

    private fun resolvePlaceholder(
        body: String,
        input: Input,
        base: String,
        ext: String,
        counter: CounterConfig,
        index: Int,
        zone: ZoneId,
    ): String? {
        if (body.isEmpty()) return null
        // Date tokens are case-sensitive: [M] is month, [n] is minute. Substring/counter
        // tokens (N/E/C/P) are treated case-insensitively for ergonomics — they don't clash.
        val head = body[0]
        val rest = body.substring(1).trim()

        if (rest.isEmpty()) {
            when (head) {
                'Y' -> return datePart(DatePart.YEAR, input.lastModified, zone)
                'M' -> return datePart(DatePart.MONTH, input.lastModified, zone)
                'D' -> return datePart(DatePart.DAY, input.lastModified, zone)
                'h' -> return datePart(DatePart.HOUR, input.lastModified, zone)
                'n' -> return datePart(DatePart.MINUTE, input.lastModified, zone)
                's' -> return datePart(DatePart.SECOND, input.lastModified, zone)
            }
        }
        when (head.uppercaseChar()) {
            'N' -> return resolveSubstring(base, rest)
            'E' -> return resolveSubstring(ext, rest)
            'P' -> return if (rest.isEmpty()) input.parentName else null
            'C' -> return resolveCounter(rest, counter, index)
        }
        if (body == "YMD") {
            return datePart(DatePart.YEAR, input.lastModified, zone) +
                datePart(DatePart.MONTH, input.lastModified, zone) +
                datePart(DatePart.DAY, input.lastModified, zone)
        }
        return null
    }

    private enum class DatePart { YEAR, MONTH, DAY, HOUR, MINUTE, SECOND }

    /**
     * Parses "a" or "a-b" (1-based, negative counts from end) and returns the substring.
     * Empty `spec` returns the whole source.
     */
    private fun resolveSubstring(source: String, spec: String): String {
        if (spec.isEmpty()) return source
        val dash = findRangeDash(spec)
        return if (dash < 0) {
            val idx = spec.toIntOrNull() ?: return ""
            val resolved = resolveIndex(idx, source.length) ?: return ""
            source.substring(resolved, resolved + 1)
        } else {
            val a = spec.substring(0, dash).toIntOrNull() ?: return ""
            val b = spec.substring(dash + 1).toIntOrNull() ?: return ""
            val start = resolveIndex(a, source.length) ?: return ""
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
        if (zero < 0 || zero >= length) return null
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

    private fun datePart(kind: DatePart, time: FileTime?, zone: ZoneId): String {
        if (time == null) return ""
        val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(time.toMillis()), zone)
        return when (kind) {
            DatePart.YEAR -> "%04d".format(ldt.year)
            DatePart.MONTH -> "%02d".format(ldt.monthValue)
            DatePart.DAY -> "%02d".format(ldt.dayOfMonth)
            DatePart.HOUR -> "%02d".format(ldt.hour)
            DatePart.MINUTE -> "%02d".format(ldt.minute)
            DatePart.SECOND -> "%02d".format(ldt.second)
        }
    }

    private fun applySearchReplace(name: String, options: Options): String {
        if (options.search.isEmpty()) return name
        return if (options.regex) {
            val flags = if (options.caseSensitive) 0 else Pattern.CASE_INSENSITIVE
            try {
                val pattern = Pattern.compile(options.search, flags)
                pattern.matcher(name).replaceAll(options.replace)
            } catch (_: PatternSyntaxException) {
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
        for (i in sources.indices) {
            if (i in conflicts) continue
            val source = sources[i]
            val newName = targetNames[i]
            if (newName == source.fileName?.toString()) continue
            val target = source.resolveSibling(newName)
            if (existsOnDisk(target)) conflicts.add(i)
        }
        return conflicts
    }
}
