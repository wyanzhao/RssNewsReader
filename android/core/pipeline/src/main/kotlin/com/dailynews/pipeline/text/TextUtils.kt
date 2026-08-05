package com.dailynews.pipeline.text

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.util.Locale
import org.jsoup.parser.Parser

object TextUtils {
    const val DEFAULT_SUMMARY_WORD_CAP = 2_000

    private val htmlTag = Regex("<[^>]+>")
    private val numericTimezone = Regex("\\s*[+-]\\d{2}:?\\d{2}$")
    private val namedTimezone = Regex("\\s*(UTC|GMT|PST|PDT|EST|EDT|CST|CDT|MST|MDT)$")
    private val rfc822 = Regex(
        "^(?:[A-Za-z]{3,9},\\s*)?(\\d{1,2})\\s+([A-Za-z]{3,9})\\s+(\\d{2}|\\d{4})\\s+" +
            "(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s+([A-Za-z]{1,5}|[+-]\\d{2}:?\\d{2})$",
        RegexOption.IGNORE_CASE,
    )
    private val trailingComment = Regex("\\s*\\([^()]*\\)$")
    private val isoDateTimeSpace = Regex("^(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2})")
    private val isoCompactOffset = Regex("([+-]\\d{2})(\\d{2})$")

    private val monthNames = mapOf(
        "jan" to Month.JANUARY, "feb" to Month.FEBRUARY, "mar" to Month.MARCH,
        "apr" to Month.APRIL, "may" to Month.MAY, "jun" to Month.JUNE,
        "jul" to Month.JULY, "aug" to Month.AUGUST, "sep" to Month.SEPTEMBER,
        "oct" to Month.OCTOBER, "nov" to Month.NOVEMBER, "dec" to Month.DECEMBER,
        "january" to Month.JANUARY, "february" to Month.FEBRUARY, "march" to Month.MARCH,
        "april" to Month.APRIL, "june" to Month.JUNE, "july" to Month.JULY,
        "august" to Month.AUGUST, "september" to Month.SEPTEMBER, "october" to Month.OCTOBER,
        "november" to Month.NOVEMBER, "december" to Month.DECEMBER,
    )

    /** Mirrors `email._parseaddr._timezones`. Anything outside this table is "unknown" to Python. */
    private val namedOffsetHours = mapOf(
        "UT" to 0, "UTC" to 0, "GMT" to 0, "Z" to 0,
        "AST" to -4, "ADT" to -3,
        "EST" to -5, "EDT" to -4, "CST" to -6, "CDT" to -5,
        "MST" to -7, "MDT" to -6, "PST" to -8, "PDT" to -7,
    )

    fun stripHtml(description: String?, maxChars: Int = 0): String {
        if (description.isNullOrEmpty()) return ""
        val text = Parser.unescapeEntities(description.replace(htmlTag, ""), false)
            .let(::collapseWhitespace)
            .trim()
        if (maxChars > 0 && text.length > maxChars) {
            val prefix = text.take(maxChars)
            val boundary = prefix.lastIndexOf(' ')
            return (if (boundary >= 0) prefix.take(boundary) else prefix) + "..."
        }
        if (maxChars == 0) {
            val words = text.split(' ').filter(String::isNotEmpty)
            if (words.size > DEFAULT_SUMMARY_WORD_CAP) return words.take(DEFAULT_SUMMARY_WORD_CAP).joinToString(" ") + "..."
        }
        return text
    }

    fun parseRssDate(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        val value = collapseWhitespace(raw).trim()
        parseRfc2822(value)?.let { return it }
        parseIso(value)?.let { return it }

        var cleaned = value.replace(numericTimezone, "").replace(namedTimezone, "")
        if (cleaned.endsWith('Z')) cleaned = cleaned.dropLast(1)
        cleaned = collapseWhitespace(cleaned).trim()
        val formatters = listOf(
            DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ENGLISH),
            DateTimeFormatterBuilder().appendPattern("uuuu-MM-dd'T'HH:mm:ss").appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND, 1, 9, true).toFormatter(Locale.ENGLISH),
        )
        for (formatter in formatters) {
            try {
                return LocalDateTime.parse(cleaned, formatter).toInstant(ZoneOffset.UTC)
            } catch (_: DateTimeParseException) {
                // Continue through the Python-compatible fallback chain.
            }
        }
        return try {
            LocalDate.parse(cleaned, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay().toInstant(ZoneOffset.UTC)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun parseRfc2822(value: String): Instant? {
        val stripped = stripTrailingComments(collapseWhitespace(value).trim())
        val match = rfc822.matchEntire(stripped)
        val fromPattern = match?.let {
            runCatching {
                val (dayText, monthText, yearText, hourText, minuteText, secondText, zoneText) = it.destructured
                val month = monthNames.getValue(monthText.lowercase())
                val parsedYear = yearText.toInt()
                val year = if (yearText.length == 2) {
                    if (parsedYear <= 68) 2_000 + parsedYear else 1_900 + parsedYear
                } else parsedYear
                LocalDateTime.of(
                    year,
                    month,
                    dayText.toInt(),
                    hourText.toInt(),
                    minuteText.toInt(),
                    secondText.ifBlank { "0" }.toInt(),
                ).toInstant(rfc822Offset(zoneText))
            }.getOrNull()
        }
        if (fromPattern != null) return fromPattern
        return try {
            ZonedDateTime.parse(stripped, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** RFC 5322 allows trailing `(comment)` groups such as `-0700 (PDT)`; Python drops them before parsing. */
    private fun stripTrailingComments(value: String): String {
        var current = value
        while (true) {
            val next = current.replace(trailingComment, "").trim()
            if (next == current) return current
            current = next
        }
    }

    /**
     * Python maps unknown zone names (including the military single letters) to a naive datetime,
     * which its own window filter then rejects outright. Treating them as UTC keeps the article
     * instead of dropping it — a deliberate, documented divergence.
     */
    private fun rfc822Offset(raw: String): ZoneOffset {
        val value = raw.uppercase()
        if (value.matches(Regex("[+-]\\d{2}:?\\d{2}"))) {
            val normalized = if (':' in value) value else value.take(3) + ":" + value.drop(3)
            return runCatching { ZoneOffset.of(normalized) }.getOrDefault(ZoneOffset.UTC)
        }
        return namedOffsetHours[value]?.let(ZoneOffset::ofHours) ?: ZoneOffset.UTC
    }

    private fun parseIso(value: String): Instant? {
        val normalized = normalizeIso(value)
        try {
            return OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (_: DateTimeParseException) {
            // Python datetime.fromisoformat also accepts naive timestamps.
        }
        return try {
            LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** `datetime.fromisoformat` accepts a space separator and a compact `+HHMM` offset; `java.time` does not. */
    private fun normalizeIso(value: String): String {
        var normalized = if (value.endsWith('Z')) value.dropLast(1) + "+00:00" else value
        normalized = normalized.replace(isoDateTimeSpace, "$1T$2")
        return normalized.replace(isoCompactOffset, "$1:$2")
    }

    fun dedupLinkKey(link: String?): String = link.orEmpty().trimEnd('/')

    fun cleanText(value: Any?): String = collapseWhitespace(value?.toString().orEmpty()).trim()

    private fun collapseWhitespace(value: String): String = buildString(value.length) {
        var previousWasWhitespace = false
        for (character in value) {
            if (character.isWhitespace()) {
                if (!previousWasWhitespace) append(' ')
                previousWasWhitespace = true
            } else {
                append(character)
                previousWasWhitespace = false
            }
        }
    }
}
