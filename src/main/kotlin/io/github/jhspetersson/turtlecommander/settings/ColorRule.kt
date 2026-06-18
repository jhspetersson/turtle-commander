package io.github.jhspetersson.turtlecommander.settings

import io.github.jhspetersson.turtlecommander.model.FileEntry
import java.util.*
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

enum class Combinator { AND, OR }

enum class ColorizationMode { WINNER, LAYERED }

enum class SizeOp { LT, LE, EQ, GE, GT, BETWEEN }

enum class PatternKind { EXACT, GLOB, REGEX }

enum class AppliesTo { FILE, DIR, BOTH }

/** Which timestamp a [RuleMatcher.Date] inspects. */
enum class DateField { CREATED, MODIFIED }

/**
 * Comparison kinds for [RuleMatcher.Date]. [BEFORE]/[AFTER]/[BETWEEN] compare against
 * absolute instants stored in the matcher; [WITHIN_LAST]/[OLDER_THAN] compare against a
 * sliding window of `now - amount * unit`, evaluated at match time.
 */
enum class DateOp { BEFORE, AFTER, BETWEEN, WITHIN_LAST, OLDER_THAN }

enum class DateUnit {
    MINUTES, HOURS, DAYS, WEEKS;

    fun toMillis(amount: Long): Long = when (this) {
        MINUTES -> amount * 60_000L
        HOURS -> amount * 3_600_000L
        DAYS -> amount * 86_400_000L
        WEEKS -> amount * 604_800_000L
    }
}

/** Which string-valued attribute a [RuleMatcher.Text] inspects. */
enum class TextProperty { OWNER, GROUP, PERMISSIONS }

/** Which symbolic-link state a [RuleMatcher.Symlink] matches. */
enum class SymlinkState { ANY, VALID, BROKEN }

/**
 * Looks up the top-level entries of a directory. Abstracted so the engine can be
 * exercised without a real filesystem and so callers can cache results.
 * Returns `null` if [entry] is not a directory or cannot be listed.
 */
fun interface ContainsEvaluator {
    fun listChildNames(entry: FileEntry): List<String>?

    companion object {
        val EMPTY = ContainsEvaluator { null }
    }
}

sealed class RuleMatcher {
    /**
     * Relative cost hint used by the engine to reorder matchers cheapest-first
     * so short-circuit `all` / `any` skips expensive work when possible.
     */
    abstract val cost: Int

    abstract fun matches(entry: FileEntry, contains: ContainsEvaluator): Boolean

    data class Size(
        val op: SizeOp,
        val bytes: Long,
        val bytesMax: Long = 0L,
    ) : RuleMatcher() {
        override val cost: Int get() = 0

        override fun matches(entry: FileEntry, contains: ContainsEvaluator): Boolean {
            if (entry.isDirectory) return false
            val s = entry.size
            return when (op) {
                SizeOp.LT -> s < bytes
                SizeOp.LE -> s <= bytes
                SizeOp.EQ -> s == bytes
                SizeOp.GE -> s >= bytes
                SizeOp.GT -> s > bytes
                SizeOp.BETWEEN -> s in bytes..bytesMax
            }
        }
    }

    data class Name(
        val kind: PatternKind,
        val pattern: String,
        val caseSensitive: Boolean = false,
        val appliesTo: AppliesTo = AppliesTo.BOTH,
    ) : RuleMatcher() {
        override val cost: Int get() = 1

        private val compiled: ((String) -> Boolean)? by lazy { compileNameMatcher(kind, pattern, caseSensitive) }

        override fun matches(entry: FileEntry, contains: ContainsEvaluator): Boolean {
            if (entry.isParentLink) return false
            val scope = appliesTo
            if (scope == AppliesTo.FILE && entry.isDirectory) return false
            if (scope == AppliesTo.DIR && !entry.isDirectory) return false
            val fn = compiled ?: return false
            return fn(entry.name)
        }
    }

    data class Contains(
        val kind: PatternKind,
        val pattern: String,
        val caseSensitive: Boolean = false,
    ) : RuleMatcher() {
        override val cost: Int get() = 2

        private val compiled: ((String) -> Boolean)? by lazy { compileNameMatcher(kind, pattern, caseSensitive) }

        override fun matches(entry: FileEntry, contains: ContainsEvaluator): Boolean {
            if (!entry.isDirectory) return false
            val children = contains.listChildNames(entry) ?: return false
            val fn = compiled ?: return false
            return children.any(fn)
        }
    }

    /**
     * Matches the creation or last-modified timestamp. Absolute operators
     * ([DateOp.BEFORE]/[DateOp.AFTER]/[DateOp.BETWEEN]) compare against [epochMillis] /
     * [epochMillisMax]; relative operators ([DateOp.WITHIN_LAST]/[DateOp.OLDER_THAN])
     * compare against `now - amount * unit`. Entries whose timestamp is unavailable
     * (e.g. some VFS entries) never match.
     */
    data class Date(
        val field: DateField,
        val op: DateOp,
        val epochMillis: Long = 0L,
        val epochMillisMax: Long = 0L,
        val amount: Long = 0L,
        val unit: DateUnit = DateUnit.DAYS,
    ) : RuleMatcher() {
        override val cost: Int get() = 0

        override fun matches(entry: FileEntry, contains: ContainsEvaluator): Boolean {
            if (entry.isParentLink) return false
            val fileTime = when (field) {
                DateField.CREATED -> entry.creationTime
                DateField.MODIFIED -> entry.lastModified
            } ?: return false
            val t = fileTime.toMillis()
            return when (op) {
                DateOp.BEFORE -> t < epochMillis
                DateOp.AFTER -> t >= epochMillis
                DateOp.BETWEEN -> t in epochMillis..epochMillisMax
                DateOp.WITHIN_LAST -> amount > 0L && t >= System.currentTimeMillis() - unit.toMillis(amount)
                DateOp.OLDER_THAN -> amount > 0L && t < System.currentTimeMillis() - unit.toMillis(amount)
            }
        }
    }

    /**
     * Matches a string-valued attribute — owner, group, or permissions string — with the
     * same EXACT / GLOB / REGEX matching used by [Name]. On Windows the permissions string
     * is DOS attribute letters (e.g. `RHSA`) and group is usually empty.
     */
    data class Text(
        val field: TextProperty,
        val kind: PatternKind,
        val pattern: String,
        val caseSensitive: Boolean = false,
    ) : RuleMatcher() {
        override val cost: Int get() = 1

        private val compiled: ((String) -> Boolean)? by lazy { compileNameMatcher(kind, pattern, caseSensitive) }

        override fun matches(entry: FileEntry, contains: ContainsEvaluator): Boolean {
            if (entry.isParentLink) return false
            val value = when (field) {
                TextProperty.OWNER -> entry.owner
                TextProperty.GROUP -> entry.group
                TextProperty.PERMISSIONS -> entry.permissions
            }
            val fn = compiled ?: return false
            return fn(value)
        }
    }

    /**
     * Matches symbolic links. [SymlinkState.ANY] matches every symlink; [SymlinkState.VALID]
     * only links whose target resolves; [SymlinkState.BROKEN] only dangling links. Non-symlink
     * entries never match.
     */
    data class Symlink(
        val state: SymlinkState = SymlinkState.ANY,
    ) : RuleMatcher() {
        override val cost: Int get() = 0

        override fun matches(entry: FileEntry, contains: ContainsEvaluator): Boolean {
            return entry.isSymbolicLink && when (state) {
                SymlinkState.ANY -> true
                SymlinkState.VALID -> !entry.isBrokenSymlink
                SymlinkState.BROKEN -> entry.isBrokenSymlink
            }
        }
    }
}

data class RuleStyle(
    val fontColor: String = "",
    val backgroundColor: String = "",
    val iconDotColor: String = "",
    val fontStyle: Int? = null,
    val fontSize: Int? = null,
    /** null = inherit, true = strike out the filename (e.g. broken links, backups). */
    val strikethrough: Boolean? = null,
) {
    fun isEmpty(): Boolean =
        fontColor.isEmpty() && backgroundColor.isEmpty() && iconDotColor.isEmpty()
            && fontStyle == null && fontSize == null && strikethrough == null

    /** Overlay [other] on this style: [other]'s set fields win. */
    fun overlay(other: RuleStyle): RuleStyle = RuleStyle(
        fontColor = other.fontColor.ifEmpty { fontColor },
        backgroundColor = other.backgroundColor.ifEmpty { backgroundColor },
        iconDotColor = other.iconDotColor.ifEmpty { iconDotColor },
        fontStyle = other.fontStyle ?: fontStyle,
        fontSize = other.fontSize ?: fontSize,
        strikethrough = other.strikethrough ?: strikethrough,
    )

    companion object {
        val EMPTY = RuleStyle()
    }
}

data class ColorRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val priority: Int = 0,
    val active: Boolean = true,
    val combinator: Combinator = Combinator.AND,
    val matchers: List<RuleMatcher> = emptyList(),
    val style: RuleStyle = RuleStyle(),
) {
    fun matches(entry: FileEntry, contains: ContainsEvaluator): Boolean {
        if (matchers.isEmpty()) return false
        val ordered = if (matchers.size == 1) matchers else matchers.sortedBy { it.cost }
        return when (combinator) {
            Combinator.AND -> ordered.all { it.matches(entry, contains) }
            Combinator.OR -> ordered.any { it.matches(entry, contains) }
        }
    }
}

private fun compileNameMatcher(kind: PatternKind, pattern: String, caseSensitive: Boolean): ((String) -> Boolean)? {
    if (pattern.isEmpty()) return null
    return when (kind) {
        PatternKind.EXACT -> {
            val target = if (caseSensitive) pattern else pattern.lowercase(Locale.ROOT)
            ({ name: String -> if (caseSensitive) name == target else name.lowercase(Locale.ROOT) == target })
        }
        PatternKind.GLOB -> {
            val regex = globToRegex(pattern)
            val flags = if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            val compiled = try { Pattern.compile(regex, flags) } catch (_: PatternSyntaxException) { return null }
            ({ name: String -> compiled.matcher(name).matches() })
        }
        PatternKind.REGEX -> {
            val flags = if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            val compiled = try { Pattern.compile(pattern, flags) } catch (_: PatternSyntaxException) { return null }
            ({ name: String -> compiled.matcher(name).matches() })
        }
    }
}

/**
 * Translates a simple glob (`*`, `?`, `[...]`) into a full-match regex.
 * Globs here apply to filenames only — path separators have no special meaning.
 */
internal fun globToRegex(glob: String): String {
    val sb = StringBuilder("^")
    var i = 0
    while (i < glob.length) {
        when (val c = glob[i]) {
            '*' -> sb.append(".*")
            '?' -> sb.append('.')
            '[' -> {
                val end = glob.indexOf(']', i + 1)
                if (end < 0) {
                    sb.append("\\[")
                } else {
                    sb.append('[')
                    var j = i + 1
                    if (j < end && glob[j] == '!') { sb.append('^'); j++ }
                    while (j < end) {
                        val ch = glob[j]
                        // `&` is regex-class metacharacter only when doubled (`&&` is
                        // intersection in Java regex). A glob that contains `&` inside
                        // `[...]` always means literal — escape every occurrence so the
                        // doubled case can't trigger intersection.
                        if (ch == '\\' || ch == ']' || ch == '&') sb.append('\\')
                        sb.append(ch)
                        j++
                    }
                    sb.append(']')
                    i = end
                }
            }
            '\\' -> {
                if (i + 1 < glob.length) {
                    sb.append(Pattern.quote(glob[i + 1].toString()))
                    i++
                } else {
                    sb.append("\\\\")
                }
            }
            '.', '(', ')', '+', '|', '^', '$', '{', '}' -> sb.append('\\').append(c)
            else -> sb.append(c)
        }
        i++
    }
    sb.append('$')
    return sb.toString()
}

/**
 * Persistent XML-friendly mirror of [ColorRule]. IntelliJ's XmlSerializer needs
 * mutable fields with a no-arg constructor, so matcher polymorphism is flattened
 * into a list of [SavedColorMatcher] with a discriminator `type` field.
 */
class SavedColorRule {
    var id: String = ""
    var name: String = ""
    var priority: Int = 0
    var active: Boolean = true
    var combinator: String = Combinator.AND.name
    var matchers: MutableList<SavedColorMatcher> = mutableListOf()
    var fontColor: String = ""
    var backgroundColor: String = ""
    var iconDotColor: String = ""
    var fontStyle: Int = -1
    var fontSize: Int = -1
    var strikethrough: Boolean = false

    fun toRule(): ColorRule = ColorRule(
        id = id.ifEmpty { UUID.randomUUID().toString() },
        name = name,
        priority = priority,
        active = active,
        combinator = runCatching { Combinator.valueOf(combinator) }.getOrDefault(Combinator.AND),
        matchers = matchers.mapNotNull { it.toMatcher() },
        style = RuleStyle(
            fontColor = fontColor,
            backgroundColor = backgroundColor,
            iconDotColor = iconDotColor,
            fontStyle = fontStyle.takeIf { it >= 0 },
            fontSize = fontSize.takeIf { it > 0 },
            // Only "on" is persisted; absent/false round-trips to inherit (null).
            strikethrough = true.takeIf { strikethrough },
        ),
    )

    companion object {
        fun fromRule(rule: ColorRule): SavedColorRule = SavedColorRule().apply {
            id = rule.id
            name = rule.name
            priority = rule.priority
            active = rule.active
            combinator = rule.combinator.name
            matchers = rule.matchers.map { SavedColorMatcher.fromMatcher(it) }.toMutableList()
            fontColor = rule.style.fontColor
            backgroundColor = rule.style.backgroundColor
            iconDotColor = rule.style.iconDotColor
            fontStyle = rule.style.fontStyle ?: -1
            fontSize = rule.style.fontSize ?: -1
            strikethrough = rule.style.strikethrough == true
        }
    }
}

class SavedColorMatcher {
    var type: String = ""
    // Size
    var sizeOp: String = ""
    var sizeBytes: Long = 0L
    var sizeBytesMax: Long = 0L
    // Name / Contains / Text
    var patternKind: String = ""
    var pattern: String = ""
    var caseSensitive: Boolean = false
    var appliesTo: String = ""
    // Date
    var dateField: String = ""
    var dateOp: String = ""
    var dateEpochMillis: Long = 0L
    var dateEpochMillisMax: Long = 0L
    var dateAmount: Long = 0L
    var dateUnit: String = ""
    // Text (owner / group / permissions)
    var textProperty: String = ""
    // Symlink
    var symlinkState: String = ""

    fun toMatcher(): RuleMatcher? = when (type) {
        "SIZE" -> runCatching {
            RuleMatcher.Size(
                op = SizeOp.valueOf(sizeOp),
                bytes = sizeBytes,
                bytesMax = sizeBytesMax,
            )
        }.getOrNull()
        "NAME" -> runCatching {
            RuleMatcher.Name(
                kind = PatternKind.valueOf(patternKind),
                pattern = pattern,
                caseSensitive = caseSensitive,
                appliesTo = runCatching { AppliesTo.valueOf(appliesTo) }.getOrDefault(AppliesTo.BOTH),
            )
        }.getOrNull()
        "CONTAINS" -> runCatching {
            RuleMatcher.Contains(
                kind = PatternKind.valueOf(patternKind),
                pattern = pattern,
                caseSensitive = caseSensitive,
            )
        }.getOrNull()
        "DATE" -> runCatching {
            RuleMatcher.Date(
                field = DateField.valueOf(dateField),
                op = DateOp.valueOf(dateOp),
                epochMillis = dateEpochMillis,
                epochMillisMax = dateEpochMillisMax,
                amount = dateAmount,
                unit = runCatching { DateUnit.valueOf(dateUnit) }.getOrDefault(DateUnit.DAYS),
            )
        }.getOrNull()
        "TEXT" -> runCatching {
            RuleMatcher.Text(
                field = TextProperty.valueOf(textProperty),
                kind = PatternKind.valueOf(patternKind),
                pattern = pattern,
                caseSensitive = caseSensitive,
            )
        }.getOrNull()
        "SYMLINK" -> runCatching {
            RuleMatcher.Symlink(
                state = runCatching { SymlinkState.valueOf(symlinkState) }.getOrDefault(SymlinkState.ANY),
            )
        }.getOrNull()
        else -> null
    }

    companion object {
        fun fromMatcher(m: RuleMatcher): SavedColorMatcher = SavedColorMatcher().apply {
            when (m) {
                is RuleMatcher.Size -> {
                    type = "SIZE"
                    sizeOp = m.op.name
                    sizeBytes = m.bytes
                    sizeBytesMax = m.bytesMax
                }
                is RuleMatcher.Name -> {
                    type = "NAME"
                    patternKind = m.kind.name
                    pattern = m.pattern
                    caseSensitive = m.caseSensitive
                    appliesTo = m.appliesTo.name
                }
                is RuleMatcher.Contains -> {
                    type = "CONTAINS"
                    patternKind = m.kind.name
                    pattern = m.pattern
                    caseSensitive = m.caseSensitive
                }
                is RuleMatcher.Date -> {
                    type = "DATE"
                    dateField = m.field.name
                    dateOp = m.op.name
                    dateEpochMillis = m.epochMillis
                    dateEpochMillisMax = m.epochMillisMax
                    dateAmount = m.amount
                    dateUnit = m.unit.name
                }
                is RuleMatcher.Text -> {
                    type = "TEXT"
                    textProperty = m.field.name
                    patternKind = m.kind.name
                    pattern = m.pattern
                    caseSensitive = m.caseSensitive
                }
                is RuleMatcher.Symlink -> {
                    type = "SYMLINK"
                    symlinkState = m.state.name
                }
            }
        }
    }
}

