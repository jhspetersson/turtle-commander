package io.github.jhspetersson.turtlecommander.settings

/**
 * CRUD + seeding for the user's color-rule list, mirroring [ThemeManager].
 *
 * On first use, the manager seeds the nine built-in project-marker rules so
 * upgraded users don't lose their previous look. If the
 * [TurtleCommanderSettings.State.enableFileNameHighlighting] master switch was
 * off at seed time, every seeded rule is written with `active=false` — the
 * user can enable individual rules later without re-typing them.
 *
 * After seeding [TurtleCommanderSettings.State.colorRulesInitialized] flips to
 * true and the seeder becomes a no-op even if the user deleted every rule.
 */
object ColorRuleManager {

    fun ensureInitialRules(state: TurtleCommanderSettings.State) {
        val defaultsActive = state.enableFileNameHighlighting
        if (!state.colorRulesInitialized) {
            for (rule in ColorRuleDefaults.builtinRules()) {
                state.colorRules.add(SavedColorRule.fromRule(rule.copy(active = defaultsActive)))
            }
            state.colorRulesInitialized = true
            // builtinRules() already includes the symlink defaults on a fresh seed.
            state.symlinkColorRulesSeeded = true
            return
        }
        // Upgrade path: an install seeded before the symlink defaults existed. Add just those,
        // skipping any the user might already have, then mark them seeded so a deleted rule
        // doesn't come back.
        if (!state.symlinkColorRulesSeeded) {
            for (rule in ColorRuleDefaults.symlinkRules()) {
                if (state.colorRules.none { it.id == rule.id }) {
                    state.colorRules.add(SavedColorRule.fromRule(rule.copy(active = defaultsActive)))
                }
            }
            state.symlinkColorRulesSeeded = true
        }
    }

    fun getAllRules(state: TurtleCommanderSettings.State): List<ColorRule> {
        ensureInitialRules(state)
        return state.colorRules.map { it.toRule() }
    }

    /** Ordered for evaluation: highest priority first, tie-breaker by name. */
    fun getActiveRulesForEvaluation(state: TurtleCommanderSettings.State): List<ColorRule> =
        getAllRules(state).filter { it.active }

    fun saveRule(state: TurtleCommanderSettings.State, rule: ColorRule): SaveResult {
        val idx = state.colorRules.indexOfFirst { it.id == rule.id }
        return if (idx >= 0) {
            state.colorRules[idx] = SavedColorRule.fromRule(rule)
            SaveResult.Replaced
        } else {
            state.colorRules.add(SavedColorRule.fromRule(rule))
            SaveResult.Saved
        }
    }

    fun deleteRule(state: TurtleCommanderSettings.State, id: String): Boolean =
        state.colorRules.removeIf { it.id == id }

    /** Overwrite the ruleset with a fresh copy of the built-in defaults. */
    fun resetToDefaults(state: TurtleCommanderSettings.State) {
        state.colorRules.clear()
        for (rule in ColorRuleDefaults.builtinRules()) {
            state.colorRules.add(SavedColorRule.fromRule(rule))
        }
        state.colorRulesInitialized = true
        state.symlinkColorRulesSeeded = true
    }

    fun getMode(state: TurtleCommanderSettings.State): ColorizationMode =
        runCatching { ColorizationMode.valueOf(state.colorizationMode) }.getOrDefault(ColorizationMode.WINNER)

    fun setMode(state: TurtleCommanderSettings.State, mode: ColorizationMode) {
        state.colorizationMode = mode.name
    }

    enum class SaveResult { Saved, Replaced }
}
