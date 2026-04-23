package io.github.jhspetersson.turtlecommander.settings

import io.github.jhspetersson.turtlecommander.model.FileEntry

/**
 * Style produced by resolving a [FileEntry] against the active [ColorRule] list.
 * Empty string / null fields mean "inherit" — the renderer should fall through to
 * its existing defaults (LAF, [ComponentStyle], or column-level styling).
 */
data class ResolvedStyle(
    val fontColor: String = "",
    val backgroundColor: String = "",
    val iconDotColor: String = "",
    val fontStyle: Int? = null,
    val fontSize: Int? = null,
) {
    fun isEmpty(): Boolean =
        fontColor.isEmpty() && backgroundColor.isEmpty() && iconDotColor.isEmpty()
            && fontStyle == null && fontSize == null

    companion object {
        val EMPTY = ResolvedStyle()

        fun fromRuleStyle(s: RuleStyle): ResolvedStyle = ResolvedStyle(
            fontColor = s.fontColor,
            backgroundColor = s.backgroundColor,
            iconDotColor = s.iconDotColor,
            fontStyle = s.fontStyle,
            fontSize = s.fontSize,
        )
    }
}

object ColorRuleEngine {

    /**
     * Evaluates [rules] against [entry] and returns the effective style.
     *
     * In [ColorizationMode.WINNER] the highest-priority matching active rule wins
     * and supplies the style outright; ties broken by name (case-insensitive).
     *
     * In [ColorizationMode.LAYERED] every active matching rule contributes: rules
     * are overlaid in ascending priority so higher-priority rules override lower
     * ones on a per-field basis. A single field may come from a different rule
     * than its neighbor.
     */
    fun resolve(
        rules: List<ColorRule>,
        entry: FileEntry,
        mode: ColorizationMode = ColorizationMode.WINNER,
        contains: ContainsEvaluator = ContainsEvaluator.EMPTY,
    ): ResolvedStyle {
        if (rules.isEmpty()) return ResolvedStyle.EMPTY
        val matching = rules.filter { it.active && it.matches(entry, contains) }
        if (matching.isEmpty()) return ResolvedStyle.EMPTY

        return when (mode) {
            ColorizationMode.WINNER -> {
                val best = matching.maxWith(
                    compareBy<ColorRule> { it.priority }.thenBy { it.name.lowercase() }
                )
                ResolvedStyle.fromRuleStyle(best.style)
            }
            ColorizationMode.LAYERED -> {
                val sorted = matching.sortedWith(
                    compareBy<ColorRule> { it.priority }.thenBy { it.name.lowercase() }
                )
                var acc = RuleStyle.EMPTY
                for (r in sorted) acc = acc.overlay(r.style)
                ResolvedStyle.fromRuleStyle(acc)
            }
        }
    }
}
