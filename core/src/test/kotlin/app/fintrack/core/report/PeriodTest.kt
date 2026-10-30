package app.fintrack.core.report

import app.fintrack.core.engine.TestKit
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Months run 1st to last day. Weeks run Monday to Sunday but are clipped so they never cross a month boundary. */
class PeriodTest {
    @Test
    fun `a month is the 1st to the last day, whatever its length`() {
        for ((ym, last) in listOf(YearMonth.of(2026, 2) to 28, YearMonth.of(2028, 2) to 29, YearMonth.of(2026, 4) to 30, YearMonth.of(2026, 10) to 31)) {
            val p = Period.month(ym)
            assertEquals(1, p.start.dayOfMonth)
            assertEquals(last, p.end.dayOfMonth)
            assertEquals(ym.lengthOfMonth().toLong(), p.lengthDays)
        }
    }

    @Test
    fun `when the 30th is a Monday it is a one-day week of the old month and the new month starts on Tuesday`() {
        val monday = LocalDate.of(2025, 6, 30)
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        val lastWeekOfJune = Period.week(monday)
        assertEquals(monday, lastWeekOfJune.start)
        assertEquals(monday, lastWeekOfJune.end)
        assertEquals("Mon 30 Jun", lastWeekOfJune.shortLabel)

        val firstWeekOfJuly = lastWeekOfJune.next()
        assertEquals(LocalDate.of(2025, 7, 1), firstWeekOfJuly.start)
        assertEquals(DayOfWeek.TUESDAY, firstWeekOfJuly.start.dayOfWeek)
        assertEquals(LocalDate.of(2025, 7, 6), firstWeekOfJuly.end)
        assertEquals(lastWeekOfJune, firstWeekOfJuly.previous())
    }

    @Test
    fun `October 2026 weeks`() {
        val first = Period.week(LocalDate.of(2026, 10, 1))
        assertEquals("Thu 1 Oct – Sun 4 Oct", first.shortLabel)
        val last = Period.week(LocalDate.of(2026, 10, 31))
        assertEquals("Mon 26 Oct – Sat 31 Oct", last.shortLabel)
        assertEquals("Sun 1 Nov", last.next().shortLabel)
        assertEquals(1L, last.next().lengthDays)
    }

    @Test
    fun `the weeks of any month partition it exactly, no gap, no overlap, no crossing`() {
        var ym = YearMonth.of(2024, 1)
        while (ym.isBefore(YearMonth.of(2030, 1))) {
            var week = Period.week(ym.atDay(1))
            var covered = 0L
            var expectedStart = ym.atDay(1)
            while (true) {
                assertEquals(expectedStart, week.start, "gap or overlap in $ym")
                assertEquals(ym, YearMonth.from(week.start))
                assertEquals(ym, YearMonth.from(week.end), "week crosses a month boundary in $ym")
                assertTrue(week.lengthDays in 1..7)
                covered += week.lengthDays
                if (week.end == ym.atEndOfMonth()) break
                expectedStart = week.end.plusDays(1)
                week = week.next()
            }
            assertEquals(ym.lengthOfMonth().toLong(), covered)
            ym = ym.plusMonths(1)
        }
    }

    @Test
    fun `previous and next are inverses`() {
        var p = Period.week(LocalDate.of(2025, 6, 18))
        repeat(60) {
            assertEquals(p, p.next().previous())
            p = p.next()
        }
    }

    @Test
    fun `Monday 30th spending appears as the previous spending of the new month's first week`() {
        val e = TestKit.engine()
        e.ingest(TestKit.sms("Sent Rs.500.00 from Kotak Bank A/c X2126 to SHOP ONE on 30-06-25. UPI Ref 1", "AX-KOTAKB-S"))
        e.ingest(TestKit.sms("Sent Rs.300.00 from Kotak Bank A/c X2126 to SHOP TWO on 01-07-25. UPI Ref 2", "AX-KOTAKB-S"))
        fun report(period: Period, asOf: LocalDate) = ReportBuilder(e.profile, e.entries, e.notices).build(period, asOf)

        val june = report(Period.month(LocalDate.of(2025, 6, 30)), LocalDate.of(2025, 7, 6))
        assertEquals(50_000, june.totals.totalSpentPaise, "Monday 30th belongs to June")

        val firstJulyWeek = report(Period.week(LocalDate.of(2025, 7, 1)), LocalDate.of(2025, 7, 6))
        assertEquals(30_000, firstJulyWeek.totals.totalSpentPaise)
        assertEquals(50_000, firstJulyWeek.previousTotals.totalSpentPaise, "previous = Monday 30th")
        val n = Notifications.weekly(firstJulyWeek)
        assertTrue("Previous (Mon 30 Jun): ₹500" in n.text, n.text)

        val julyMonth = report(Period.month(LocalDate.of(2025, 7, 1)), LocalDate.of(2025, 7, 6))
        assertEquals(30_000, julyMonth.totals.totalSpentPaise, "Monday 30th is not counted in July")
    }
}
