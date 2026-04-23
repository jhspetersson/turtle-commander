package io.github.jhspetersson.turtlecommander.settings

import java.util.*

/**
 * A user-named bundle of color rules used purely as an exchange format for
 * exporting and importing rule sets across installs. Not persisted in settings.
 */
data class ColorRulesConfig(
    val name: String,
    val rules: List<ColorRule> = emptyList(),
) {
    override fun toString(): String = name
}

/**
 * Export / import of a [ColorRulesConfig] using the same key=value text format
 * that [ThemeManager] uses for themes, so the two share UX and serialization
 * conventions.
 *
 * Format:
 * ```
 * name=<config name>
 * rule.0.name=<rule name>
 * rule.0.priority=10
 * rule.0.active=true
 * rule.0.combinator=AND
 * rule.0.fontColor=#112233
 * ...
 * rule.0.matcher.0.type=CONTAINS
 * rule.0.matcher.0.patternKind=EXACT
 * rule.0.matcher.0.pattern=.git
 * rule.1.name=...
 * ```
 * Only non-default style/matcher fields are emitted. Unknown keys are ignored
 * on import; rules missing a name are dropped silently rather than failing the
 * whole import.
 */
object ColorRulesIO {

    fun export(config: ColorRulesConfig): String {
        val sb = StringBuilder()
        sb.appendLine("name=${config.name}")
        config.rules.forEachIndexed { idx, rule -> appendRule(sb, idx, rule) }
        return sb.toString()
    }

    fun parse(text: String): ColorRulesConfig? {
        val props = parseProps(text)
        val name = props["name"] ?: return null
        if (name.isBlank()) return null

        val ruleIndices = sortedSetOf<Int>()
        for (key in props.keys) {
            val m = RULE_KEY_REGEX.matchEntire(key) ?: continue
            m.groupValues[1].toIntOrNull()?.let { ruleIndices.add(it) }
        }
        val rules = ruleIndices.mapNotNull { idx -> parseRule(props, idx) }
        return ColorRulesConfig(name = name, rules = rules)
    }

    // --- Internals --------------------------------------------------------

    private val RULE_KEY_REGEX = Regex("^rule\\.(\\d+)\\..+$")

    private fun parseProps(text: String): Map<String, String> {
        val props = mutableMapOf<String, String>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx < 0) continue
            props[trimmed.substring(0, eqIdx).trim()] = trimmed.substring(eqIdx + 1).trim()
        }
        return props
    }

    private fun appendRule(sb: StringBuilder, idx: Int, rule: ColorRule) {
        val prefix = "rule.$idx"
        if (rule.id.isNotEmpty()) sb.appendLine("$prefix.id=${rule.id}")
        sb.appendLine("$prefix.name=${rule.name}")
        if (rule.priority != 0) sb.appendLine("$prefix.priority=${rule.priority}")
        if (!rule.active) sb.appendLine("$prefix.active=false")
        sb.appendLine("$prefix.combinator=${rule.combinator.name}")
        appendRuleStyle(sb, prefix, rule.style)
        rule.matchers.forEachIndexed { mIdx, matcher ->
            appendMatcher(sb, "$prefix.matcher.$mIdx", matcher)
        }
    }

    private fun appendRuleStyle(sb: StringBuilder, prefix: String, style: RuleStyle) {
        if (style.fontColor.isNotEmpty()) sb.appendLine("$prefix.fontColor=${style.fontColor}")
        if (style.backgroundColor.isNotEmpty()) sb.appendLine("$prefix.backgroundColor=${style.backgroundColor}")
        if (style.iconDotColor.isNotEmpty()) sb.appendLine("$prefix.iconDotColor=${style.iconDotColor}")
        style.fontStyle?.let { sb.appendLine("$prefix.fontStyle=$it") }
        style.fontSize?.let { sb.appendLine("$prefix.fontSize=$it") }
    }

    private fun appendMatcher(sb: StringBuilder, prefix: String, matcher: RuleMatcher) {
        when (matcher) {
            is RuleMatcher.Size -> {
                sb.appendLine("$prefix.type=SIZE")
                sb.appendLine("$prefix.sizeOp=${matcher.op.name}")
                sb.appendLine("$prefix.sizeBytes=${matcher.bytes}")
                if (matcher.bytesMax != 0L) sb.appendLine("$prefix.sizeBytesMax=${matcher.bytesMax}")
            }
            is RuleMatcher.Name -> {
                sb.appendLine("$prefix.type=NAME")
                sb.appendLine("$prefix.patternKind=${matcher.kind.name}")
                sb.appendLine("$prefix.pattern=${matcher.pattern}")
                if (matcher.caseSensitive) sb.appendLine("$prefix.caseSensitive=true")
                if (matcher.appliesTo != AppliesTo.BOTH) sb.appendLine("$prefix.appliesTo=${matcher.appliesTo.name}")
            }
            is RuleMatcher.Contains -> {
                sb.appendLine("$prefix.type=CONTAINS")
                sb.appendLine("$prefix.patternKind=${matcher.kind.name}")
                sb.appendLine("$prefix.pattern=${matcher.pattern}")
                if (matcher.caseSensitive) sb.appendLine("$prefix.caseSensitive=true")
            }
        }
    }

    private fun parseRule(props: Map<String, String>, idx: Int): ColorRule? {
        val prefix = "rule.$idx"
        val name = props["$prefix.name"] ?: return null
        if (name.isBlank()) return null
        val id = props["$prefix.id"]?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        val priority = props["$prefix.priority"]?.toIntOrNull() ?: 0
        val active = props["$prefix.active"]?.equals("true", ignoreCase = true) ?: true
        val combinator = runCatching {
            Combinator.valueOf(props["$prefix.combinator"] ?: Combinator.AND.name)
        }.getOrDefault(Combinator.AND)

        val matcherPrefix = "$prefix.matcher."
        val matcherIndices = sortedSetOf<Int>()
        for (key in props.keys) {
            if (!key.startsWith(matcherPrefix)) continue
            val rest = key.substring(matcherPrefix.length)
            val dotIdx = rest.indexOf('.')
            if (dotIdx < 0) continue
            rest.substring(0, dotIdx).toIntOrNull()?.let { matcherIndices.add(it) }
        }
        val matchers = matcherIndices.mapNotNull { mIdx -> parseMatcher(props, "$prefix.matcher.$mIdx") }

        return ColorRule(
            id = id,
            name = name,
            priority = priority,
            active = active,
            combinator = combinator,
            matchers = matchers,
            style = RuleStyle(
                fontColor = props["$prefix.fontColor"] ?: "",
                backgroundColor = props["$prefix.backgroundColor"] ?: "",
                iconDotColor = props["$prefix.iconDotColor"] ?: "",
                fontStyle = props["$prefix.fontStyle"]?.toIntOrNull(),
                fontSize = props["$prefix.fontSize"]?.toIntOrNull(),
            ),
        )
    }

    private fun parseMatcher(props: Map<String, String>, prefix: String): RuleMatcher? {
        val type = props["$prefix.type"] ?: return null
        return when (type) {
            "SIZE" -> {
                val op = runCatching { SizeOp.valueOf(props["$prefix.sizeOp"] ?: "") }.getOrNull() ?: return null
                val bytes = props["$prefix.sizeBytes"]?.toLongOrNull() ?: return null
                val bytesMax = props["$prefix.sizeBytesMax"]?.toLongOrNull() ?: 0L
                RuleMatcher.Size(op = op, bytes = bytes, bytesMax = bytesMax)
            }
            "NAME" -> {
                val kind = runCatching { PatternKind.valueOf(props["$prefix.patternKind"] ?: "") }.getOrNull() ?: return null
                val pattern = props["$prefix.pattern"] ?: return null
                val caseSensitive = props["$prefix.caseSensitive"]?.equals("true", ignoreCase = true) ?: false
                val appliesTo = runCatching { AppliesTo.valueOf(props["$prefix.appliesTo"] ?: "") }.getOrDefault(AppliesTo.BOTH)
                RuleMatcher.Name(kind = kind, pattern = pattern, caseSensitive = caseSensitive, appliesTo = appliesTo)
            }
            "CONTAINS" -> {
                val kind = runCatching { PatternKind.valueOf(props["$prefix.patternKind"] ?: "") }.getOrNull() ?: return null
                val pattern = props["$prefix.pattern"] ?: return null
                val caseSensitive = props["$prefix.caseSensitive"]?.equals("true", ignoreCase = true) ?: false
                RuleMatcher.Contains(kind = kind, pattern = pattern, caseSensitive = caseSensitive)
            }
            else -> null
        }
    }
}
