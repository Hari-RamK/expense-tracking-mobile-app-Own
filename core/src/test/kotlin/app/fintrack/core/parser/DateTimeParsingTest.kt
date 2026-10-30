package app.fintrack.core.parser

import java.time.DateTimeException
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DateTimeParsingTest {
    @Test
    fun `parses every format seen in real bank SMS`() {
        assertEquals(LocalDate.of(2026, 10, 30), parseDate("30-10-26", "dd-MM-yy"))
        assertEquals(LocalDate.of(2026, 10, 30), parseDate("30-10-2026", "dd-MM-yyyy"))
        assertEquals(LocalDate.of(2026, 10, 29), parseDate("29-Oct-26", "dd-MMM-yy"))
        assertEquals(LocalDate.of(2026, 10, 29), parseDate("29Oct26", "ddMMMyy"))
        assertEquals(LocalDate.of(2026, 10, 29), parseDate("29-OCT-2026", "dd-MMM-yyyy"))
        assertEquals(LocalDate.of(2026, 8, 13), parseDate("13/08/26", "dd/MM/yy"))
    }

    @Test
    fun `impossible dates fail instead of rolling over`() {
        assertFailsWith<DateTimeException> { parseDate("31-02-26", "dd-MM-yy") }
        assertFailsWith<DateTimeException> { parseDate("29-Xyz-26", "dd-MMM-yy") }
    }

    @Test
    fun `leap day is valid only in leap years`() {
        assertEquals(LocalDate.of(2028, 2, 29), parseDate("29-02-28", "dd-MM-yy"))
        assertFailsWith<DateTimeException> { parseDate("29-02-27", "dd-MM-yy") }
    }
}
