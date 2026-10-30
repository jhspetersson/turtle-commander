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
    val strikethrough: Boolean? = null,
    val iconId: String = "",
) {
    fun isEmpty(): Boolean =
        fontColor.isEmpty() && backgroundColor.isEmpty() && iconDotColor.isEmpty()
            && fontStyle == null && fontSize == null && strikethrough == null && iconId.isEmpty()

    companion object {
        val EMPTY = ResolvedStyle()

        fun fromRuleStyle(s: RuleStyle): ResolvedStyle = ResolvedStyle(
            fontColor = s.fontColor,
            backgroundColor = s.backgroundColor,
            iconDotColor = s.iconDotColor,
            fontStyle = s.fontStyle,
            fontSize = s.fontSize,
            strikethrough = s.strikethrough,
            iconId = s.iconId,
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
        val order = orderFor(rules)

        return when (mode) {
            ColorizationMode.WINNER -> {
                for (r in order.descending) {
                    if (r.active && r.matches(entry, contains)) return ResolvedStyle.fromRuleStyle(r.style)
                }
                ResolvedStyle.EMPTY
            }
            ColorizationMode.LAYERED -> {
                var acc = RuleStyle.EMPTY
                var matched = false
                for (r in order.ascending) {
                    if (r.active && r.matches(entry, contains)) {
                        acc = acc.overlay(r.style)
                        matched = true
                    }
                }
                if (matched) ResolvedStyle.fromRuleStyle(acc) else ResolvedStyle.EMPTY
            }
        }
    }

    private class PriorityOrder(
        val rules: List<ColorRule>,
        val ascending: List<ColorRule>,
        val descending: List<ColorRule>,
    )

    private val priorityComparator = compareBy<ColorRule> { it.priority }.thenBy { it.name.lowercase() }

    @Volatile
    private var lastOrder: PriorityOrder? = null

    private fun orderFor(rules: List<ColorRule>): PriorityOrder {
        lastOrder?.let { if (it.rules === rules) return it }
        val order = PriorityOrder(
            rules,
            rules.sortedWith(priorityComparator),
            rules.sortedWith(priorityComparator.reversed()),
        )
        lastOrder = order
        return order
    }
}
