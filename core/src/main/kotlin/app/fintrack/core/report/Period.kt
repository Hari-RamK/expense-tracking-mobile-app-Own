package app.fintrack.core.report

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class PeriodKind { DAY, WEEK, MONTH }

/** A reporting window. Both ends are inclusive local dates, so a transaction belongs to exactly one day. */
data class Period(val kind: PeriodKind, val start: LocalDate, val end: LocalDate) {
    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)

    val label: String
        get() = when (kind) {
            PeriodKind.DAY -> start.toString()
            PeriodKind.WEEK -> "$start to $end"
            PeriodKind.MONTH -> "${start.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${start.year}"
        }

    fun previous(): Period = when (kind) {
        PeriodKind.DAY -> day(start.minusDays(1))
        PeriodKind.WEEK -> Period(PeriodKind.WEEK, start.minusDays(7), start.minusDays(1))
        PeriodKind.MONTH -> month(YearMonth.from(start).minusMonths(1))
    }

    fun next(): Period = when (kind) {
        PeriodKind.DAY -> day(start.plusDays(1))
        PeriodKind.WEEK -> Period(PeriodKind.WEEK, start.plusDays(7), end.plusDays(7))
        PeriodKind.MONTH -> month(YearMonth.from(start).plusMonths(1))
    }

    companion object {
        fun day(date: LocalDate) = Period(PeriodKind.DAY, date, date)

        fun week(containing: LocalDate, weekStart: DayOfWeek = DayOfWeek.MONDAY): Period {
            val start = containing.with(TemporalAdjusters.previousOrSame(weekStart))
            return Period(PeriodKind.WEEK, start, start.plusDays(6))
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
