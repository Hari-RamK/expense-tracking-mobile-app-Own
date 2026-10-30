package app.fintrack.core.report

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class PeriodKind { DAY, WEEK, MONTH }

/** A reporting window. Both ends are inclusive local dates, so a transaction belongs to exactly one day. */
data class Period(val kind: PeriodKind, val start: LocalDate, val end: LocalDate) {
    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)

    val label: String
        get() = when (kind) {
            PeriodKind.DAY -> shortLabel
            PeriodKind.WEEK -> shortLabel
            PeriodKind.MONTH -> "${start.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${start.year}"
        }

    /** "Mon 30 Jun", "Tue 1 Jul – Sun 6 Jul", "October 2026". */
    val shortLabel: String
        get() = when (kind) {
            PeriodKind.DAY -> dayText(start)
            PeriodKind.WEEK -> if (start == end) dayText(start) else "${dayText(start)} – ${dayText(end)}"
            PeriodKind.MONTH -> "${start.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${start.year}"
        }

    val lengthDays: Long get() = ChronoUnit.DAYS.between(start, end) + 1

    private fun dayText(d: LocalDate) =
        "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"

    /**
     * The period just before this one. A week's previous is the week that ends the day before it starts, which can be
     * a one-day week at the end of the previous month (e.g. Monday 30th before a month that starts on Tuesday).
     */
    fun previous(): Period = when (kind) {
        PeriodKind.DAY -> day(start.minusDays(1))
        PeriodKind.WEEK -> week(start.minusDays(1))
        PeriodKind.MONTH -> month(YearMonth.from(start).minusMonths(1))
    }

    fun next(): Period = when (kind) {
        PeriodKind.DAY -> day(start.plusDays(1))
        PeriodKind.WEEK -> week(end.plusDays(1))
        PeriodKind.MONTH -> month(YearMonth.from(start).plusMonths(1))
    }

    companion object {
        fun day(date: LocalDate) = Period(PeriodKind.DAY, date, date)

        /**
         * Monday to Sunday, but never across a month boundary: the week is clipped to the calendar month of
         * [containing]. If the 30th is a Monday and the 1st a Tuesday, Monday 30th is a one-day week of the old
         * month and the new month's first week starts on Tuesday 1st. Weeks of one month therefore partition it exactly.
         */
        fun week(containing: LocalDate, weekStart: DayOfWeek = DayOfWeek.MONDAY): Period {
            val start = containing.with(TemporalAdjusters.previousOrSame(weekStart))
            val ym = YearMonth.from(containing)
            return Period(
                PeriodKind.WEEK,
                maxOf(start, ym.atDay(1)),
                minOf(start.plusDays(6), ym.atEndOfMonth()),
            )
        }

        fun month(ym: YearMonth) = Period(PeriodKind.MONTH, ym.atDay(1), ym.atEndOfMonth())

        fun month(containing: LocalDate) = month(YearMonth.from(containing))
    }
}

/** Indian digit grouping: 1234567 paise-less rupees -> "₹12,34,567". Exact integer arithmetic only. */
fun formatInr(paise: Long, decimals: Boolean = false): String {
    val negative = paise < 0
    val abs = Math.abs(paise)
    val rupees = (abs / 100).toString()
    val grouped = if (rupees.length <= 3) rupees else {
        val head = rupees.dropLast(3)
        val tail = rupees.takeLast(3)
        head.reversed().chunked(2).joinToString(",").reversed() + "," + tail
    }
    val frac = if (decimals) "." + (abs % 100).toString().padStart(2, '0') else ""
    return (if (negative) "-" else "") + "₹" + grouped + frac
}
