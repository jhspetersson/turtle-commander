package io.github.jhspetersson.turtlecommander.util

import java.math.BigDecimal
import java.math.RoundingMode

object SizeUnits {
    val MULTIPLIERS: List<Pair<String, Long>> = listOf(
        "TB" to 1L * 1024 * 1024 * 1024 * 1024,
        "GB" to 1L * 1024 * 1024 * 1024,
        "MB" to 1L * 1024 * 1024,
        "KB" to 1024L,
        "B" to 1L,
    )

    val COMBO_UNITS: Array<String> = arrayOf("B", "KB", "MB", "GB")

    fun multiplier(unit: String?): Long =
        MULTIPLIERS.firstOrNull { it.first.equals(unit, ignoreCase = true) }?.second ?: 1L

    fun parseBytes(text: String, unit: String?): Long? {
        val value = text.trim().toBigDecimalOrNull() ?: return null
        return value.multiply(BigDecimal.valueOf(multiplier(unit))).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    fun parseBytesWithSuffix(text: String): Long? {
        val trimmed = text.trim()
        for ((suffix, _) in MULTIPLIERS) {
            if (trimmed.endsWith(suffix, ignoreCase = true)) {
                return parseBytes(trimmed.dropLast(suffix.length), suffix)
            }
        }
        return parseBytes(trimmed, "B")
    }

    fun toWholeUnit(bytes: Long): Pair<Long, String> {
        if (bytes == 0L) return 0L to "B"
        for ((unit, multiplier) in MULTIPLIERS) {
            if (unit in COMBO_UNITS && bytes % multiplier == 0L) return (bytes / multiplier) to unit
        }
        return bytes to "B"
    }

    fun rangeToUnit(bytes1: Long, bytes2: Long?): Triple<String, String?, String> {
        val bounds = listOfNotNull(bytes1, bytes2)
        for ((unit, multiplier) in MULTIPLIERS) {
            if (unit !in COMBO_UNITS) continue
            val texts = bounds.map { BigDecimal.valueOf(it).divide(BigDecimal.valueOf(multiplier)).stripTrailingZeros() }
            if (texts.all { it.scale() <= 2 }) {
                return Triple(texts[0].toPlainString(), texts.getOrNull(1)?.toPlainString(), unit)
            }
        }
        return Triple(bytes1.toString(), bytes2?.toString(), "B")
    }
}
