package io.github.jhspetersson.turtlecommander.util

import io.github.jhspetersson.turtlecommander.settings.DEFAULT_DATE_TIME_FORMAT
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Caches the [DateTimeFormatter] built from the user-configured pattern so that
 * per-cell rendering in the file table doesn't recompile the pattern on every paint.
 * Re-reads the setting each call so a Configurable apply() is picked up without an
 * explicit invalidation, but only rebuilds the formatter when the pattern actually
 * changed. Invalid patterns silently fall back to [DEFAULT_DATE_TIME_FORMAT].
 *
 * Headless callers (unit tests) get the default formatter when the application
 * service isn't available.
 */
object DateTimeFormatters {
    private val defaultFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern(DEFAULT_DATE_TIME_FORMAT).withZone(ZoneId.systemDefault())

    @Volatile private var cachedPattern: String = DEFAULT_DATE_TIME_FORMAT
    @Volatile private var cachedFormatter: DateTimeFormatter = defaultFormatter

    fun current(): DateTimeFormatter {
        val pattern = runCatching { TurtleCommanderSettings.getInstance().getDateTimeFormat() }
            .getOrDefault(DEFAULT_DATE_TIME_FORMAT)
        if (pattern == cachedPattern) return cachedFormatter
        val rebuilt = runCatching {
            DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault())
        }.getOrDefault(defaultFormatter)
        cachedPattern = pattern
        cachedFormatter = rebuilt
        return rebuilt
    }

    fun format(millis: Long): String = current().format(Instant.ofEpochMilli(millis))
}
