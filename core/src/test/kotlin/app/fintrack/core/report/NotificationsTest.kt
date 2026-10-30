package app.fintrack.core.report

import app.fintrack.core.engine.LedgerEngine
import app.fintrack.core.engine.TestKit
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationsTest {
    private val asOf = LocalDate.of(2026, 10, 30)

    private fun engineWithDay(): LedgerEngine {
        val e = TestKit.engine()
        e.ingest(TestKit.sms("ICICI Bank Credit Card XX9000 debited for INR 720.00 on 30-Oct-26 for UPI-123456789012-SWIGGY. To dispute call 1"))
        e.ingest(TestKit.sms("Sent Rs.530.00 from Kotak Bank A/c X2126 to CAFE MOCHA on 30-10-26. UPI Ref 1001. Not done by you?", "AX-KOTAKB-S"))
        e.ingest(TestKit.sms("Your a/c no. XXXXXXXX1756 is debited for Rs.2400.00 on 30-10-2026 and credited to a/c no. XXXXXXXX2126 (UPI Ref no 55)", "AD-CUBLTD-S"))
        return e
    }

    private fun report(e: LedgerEngine, period: Period) = ReportBuilder(e.profile, e.entries, e.notices).build(period, asOf)

    @Test
    fun `daily text uses the same figures as the report and says transfers are excluded`() {
        val e = engineWithDay()
        val r = report(e, Period.day(asOf))
        val n = Notifications.daily(r)
        assertEquals(125_000, r.totals.totalSpentPaise)
        assertTrue("₹1,250 across 2 transactions" in n.text, n.text)
        assertTrue("Credit card ₹720" in n.text && "Bank ₹530" in n.text, n.text)
        assertTrue("Transfers excluded" in n.text)
        assertEquals("daily:2026-10-30", n.key)
    }

    @Test
    fun `lock screen text contains no amounts`() {
        val n = Notifications.daily(report(engineWithDay(), Period.day(asOf)))
        assertFalse(n.publicText.contains("₹") || n.publicText.any { it.isDigit() }, n.publicText)
    }

    @Test
    fun `the same period always yields the same key so it can be sent only once`() {
        val e = engineWithDay()
        val a = Notifications.daily(report(e, Period.day(asOf)))
        val b = Notifications.daily(report(e, Period.day(asOf)))
        assertEquals(a.key, b.key)
        assertTrue(Notifications.daily(report(e, Period.day(asOf.plusDays(1)))).key != a.key)
    }

    @Test
    fun `an empty day says so instead of inventing a total`() {
        val n = Notifications.daily(report(TestKit.engine(), Period.day(asOf)))
        assertTrue("no spending recorded" in n.text)
    }

    @Test
    fun `weekly compares with the previous week`() {
        val e = TestKit.engine()
        e.ingest(TestKit.sms("Sent Rs.1000.00 from Kotak Bank A/c X2126 to SHOP A on 21-10-26. UPI Ref 1", "AX-KOTAKB-S"))
        e.ingest(TestKit.sms("Sent Rs.800.00 from Kotak Bank A/c X2126 to SHOP B on 28-10-26. UPI Ref 2", "AX-KOTAKB-S"))
        val n = Notifications.weekly(report(e, Period.week(asOf)))
        assertTrue("This week you spent ₹800" in n.text && "20% less than last week" in n.text, n.text)
    }

    @Test
    fun `monthly shows spending, income and net cash flow`() {
        val e = TestKit.engine()
        e.ingest(TestKit.sms("Salary Credited!\nINR 65,000.00 to HDFC Bank A/c XX7345\nBal: INR 65,100.00"))
        e.ingest(TestKit.sms("Sent Rs.32500.00 from Kotak Bank A/c X2126 to HOUSING SERVICES on 28-10-26. UPI Ref 3", "AX-KOTAKB-S"))
        val n = Notifications.monthly(report(e, Period.month(asOf)))
        assertTrue("October spending: ₹32,500. Income: ₹65,000. Net external cash flow: +₹32,500." in n.text, n.text)
    }

    @Test
    fun `threshold alert only fires when the limit is reached`() {
        val r = report(engineWithDay(), Period.day(asOf))
        assertNull(Notifications.thresholdExceeded(r, 200_000))
        assertNotNull(Notifications.thresholdExceeded(r, 100_000))
        assertNull(Notifications.thresholdExceeded(r, 0))
    }

    @Test
    fun `rupees use Indian grouping`() {
        assertEquals("₹0", formatInr(0))
        assertEquals("₹999", formatInr(99_900))
        assertEquals("₹1,250", formatInr(125_000))
        assertEquals("₹12,34,567", formatInr(123_456_700))
        assertEquals("₹1,00,000.50", formatInr(10_000_050, decimals = true))
        assertEquals("-₹5", formatInr(-500))
    }
}
