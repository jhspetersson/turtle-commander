package io.github.jhspetersson.turtlecommander.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.settings.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Pure (non-IDE-platform) half of colorization resolution. Owns the child-name
 * cache used by `Contains` matchers and delegates rule matching to
 * [ColorRuleEngine]. Tests drive this class directly so they don't need the
 * IntelliJ application bootstrapped.
 */
class ColorizationResolver {

    /** Test-only clock override. */
    internal var clock: () -> Long = System::currentTimeMillis

    /**
     * Test-only override for directory listing. Production uses [Files.list].
     * Returns `null` when the path is not a directory or cannot be read.
     */
    internal var readChildNames: (Path) -> List<String>? = { path ->
        try {
            Files.list(path).use { stream ->
                stream.limit(MAX_CHILDREN_SCANNED.toLong()).map { it.name }.toList()
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class CachedChildren(val names: List<String>, val timestamp: Long)

    private val childCache = object : LinkedHashMap<Path, CachedChildren>(CACHE_MAX_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Path, CachedChildren>): Boolean =
            size > CACHE_MAX_SIZE
    }

    fun resolve(
        entry: FileEntry,
        rules: List<ColorRule>,
        mode: ColorizationMode,
        enabled: Boolean,
        anyContainsMatcher: Boolean = rules.any { r -> r.matchers.any { it is RuleMatcher.Contains } },
    ): ResolvedStyle {
        if (!enabled || rules.isEmpty()) return ResolvedStyle.EMPTY
        if (entry.isParentLink) return ResolvedStyle.EMPTY
        val evaluator = if (anyContainsMatcher && entry.isDirectory) cachingEvaluator() else ContainsEvaluator.EMPTY
        return ColorRuleEngine.resolve(rules, entry, mode, evaluator)
    }

    fun invalidate(directory: Path) {
        synchronized(childCache) { childCache.remove(directory) }
    }

    fun clearCache() {
        synchronized(childCache) { childCache.clear() }
    }

    internal fun cachedChildrenSize(): Int = synchronized(childCache) { childCache.size }

    internal fun isCached(directory: Path): Boolean = synchronized(childCache) {
        val entry = childCache[directory] ?: return@synchronized false
        clock() - entry.timestamp <= CACHE_TTL_MS
    }

    private fun cachingEvaluator(): ContainsEvaluator = ContainsEvaluator { entry ->
        if (!entry.isDirectory) return@ContainsEvaluator null
        val path = entry.path
        synchronized(childCache) {
            val cached = childCache[path]
            if (cached != null && clock() - cached.timestamp <= CACHE_TTL_MS) {
                return@ContainsEvaluator cached.names
            }
        }
        val names = readChildNames(path) ?: return@ContainsEvaluator null
        synchronized(childCache) {
            childCache[path] = CachedChildren(names, clock())
        }
        names
    }

    companion object {
        internal const val CACHE_MAX_SIZE = 500
        internal const val CACHE_TTL_MS = 10L * 60 * 1000
        /** Upper bound on how many children we read for Contains matching.
         *  Markers like `.git` or `pom.xml` live near the top; bounding avoids
         *  eating memory on pathological directories. */
        internal const val MAX_CHILDREN_SCANNED = 10_000
    }
}

/**
 * App-level service used by renderers. Keeps a resolver instance, a settings
 * snapshot refreshed on [TurtleCommanderSettings.TOPIC] changes, and exposes a
 * single [resolve] entry point for UI code.
 */
@Service(Service.Level.APP)
class ColorizationService {

    private val resolver = ColorizationResolver()

    private data class Snapshot(
        val enabled: Boolean,
        val mode: ColorizationMode,
        val activeRules: List<ColorRule>,
        val anyContainsMatcher: Boolean,
    ) {
        companion object
    }

    @Volatile
    private var snapshot: Snapshot? = null
    @Volatile
    private var subscribed = false

    fun resolve(entry: FileEntry): ResolvedStyle {
        val snap = snapshot ?: computeSnapshot().also {
            snapshot = it
            ensureSubscribed()
        }
        return resolver.resolve(entry, snap.activeRules, snap.mode, snap.enabled, snap.anyContainsMatcher)
    }

    fun refreshSnapshot() {
        snapshot = computeSnapshot()
    }

    private fun computeSnapshot(): Snapshot {
        val state = TurtleCommanderSettings.getInstance().state
        val active = ColorRuleManager.getActiveRulesForEvaluation(state)
        return Snapshot(
            enabled = state.enableFileNameHighlighting,
            mode = ColorRuleManager.getMode(state),
            activeRules = active,
            anyContainsMatcher = active.any { rule -> rule.matchers.any { it is RuleMatcher.Contains } },
        )
    }

    @Synchronized
    private fun ensureSubscribed() {
        if (subscribed) return
        subscribed = true
        ApplicationManager.getApplication().messageBus.connect().subscribe(
            TurtleCommanderSettings.TOPIC,
            object : TurtleCommanderSettingsListener {
                override fun settingsChanged() {
                    refreshSnapshot()
                    resolver.clearCache()
                }
            },
        )
    }

    companion object {
        fun getInstance(): ColorizationService =
            ApplicationManager.getApplication().getService(ColorizationService::class.java)
    }
}
