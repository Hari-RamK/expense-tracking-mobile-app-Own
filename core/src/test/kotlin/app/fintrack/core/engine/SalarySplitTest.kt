package app.fintrack.core.engine

import app.fintrack.core.model.Block
import app.fintrack.core.report.Period
import app.fintrack.core.report.ReportBuilder
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The PRD's headline scenario: salary 80,000 split to B, C, D, then a 5,000 purchase from B. */
class SalarySplitTest {
    private val month = Period.month(LocalDate.of(2026, 10, 1))
    private val asOf = LocalDate.of(2026, 10, 31)

    private fun engine() = LedgerEngine(TestKit.syntheticParser(), TestKit.syntheticProfile(), clock = { TestKit.CLOCK_MILLIS })

    private fun feedScenario(e: LedgerEngine, creditsFirst: Boolean = false) {
        val msgs = mutableListOf(TestKit.sms("SALARY Rs 80,000 credited to A/c 1001 on 01-10-2026"))
        val legs = listOf(Triple("1002", 20000, "11"), Triple("1003", 15000, "12"), Triple("1004", 10000, "13"))
        for ((dest, amt, ref) in legs) {
            val out = TestKit.sms("Rs $amt debited from A/c 1001 to A/c $dest on 02-10-2026 Ref $ref")
            val inn = TestKit.sms("Rs $amt credited to A/c $dest from A/c 1001 on 02-10-2026 Ref $ref")
            msgs += if (creditsFirst) listOf(inn, out) else listOf(out, inn)
        }
        msgs += TestKit.sms("Rs 5000 spent from A/c 1002 at SHOPKEEPER on 05-10-2026 Ref 900")
        msgs.forEach { e.ingest(it) }
    }

    private fun report(e: LedgerEngine) = ReportBuilder(e.profile, e.entries, e.notices).build(month, asOf)

    @Test
    fun `income 80000, spending 5000, transfers 45000 reported separately`() {
        val e = engine(); feedScenario(e)
        val r = report(e)
        assertEquals(8_000_000, r.totals.incomePaise)
        assertEquals(500_000, r.totals.totalSpentPaise)
        assertEquals(4_500_000, r.transfersPaise)
    }

    @Test
    fun `net external cash flow is income minus spending, transfers do not change it`() {
        val e = engine(); feedScenario(e)
        assertEquals(7_500_000, report(e).totals.netCashFlowPaise)
    }

    @Test
    fun `every leg stays in its own account history and three groups are linked`() {
        val e = engine(); feedScenario(e)
        assertEquals(3, e.groups.size)
        val transfers = e.entries.filter { it.block == Block.TRANSFER }
        assertEquals(6, transfers.size)
        assertTrue(transfers.all { it.transferGroupId != null && !it.needsReview })
        assertEquals(setOf(1L, 2L, 3L, 4L), transfers.mapNotNull { it.accountId }.toSet())
    }

    @Test
    fun `credit legs arriving before the debit legs link just the same`() {
        val e = engine(); feedScenario(e, creditsFirst = true)
        assertEquals(3, e.groups.size)
        val r = report(e)
        assertEquals(4_500_000, r.transfersPaise)
        assertEquals(0, r.unmatchedTransferLegs)
    }

    @Test
    fun `salary flow shows what was left in the salary account`() {
        val e = engine(); feedScenario(e)
        val flow = report(e).salaryFlow
        assertEquals(8_000_000, flow.salaryInPaise)
        assertEquals(4_500_000, flow.movedToOwnAccountsPaise)
        assertEquals(3_500_000, flow.leftPaise)
    }

    @Test
    fun `account net changes add up to income minus spending`() {
        val e = engine(); feedScenario(e)
        assertEquals(7_500_000, report(e).accountSummaries.sumOf { it.netChangePaise })
    }

    @Test
    fun `reprocessing every message changes nothing`() {
        val e = engine()
        val all = mutableListOf(TestKit.sms("SALARY Rs 80,000 credited to A/c 1001 on 01-10-2026"))
        all += TestKit.sms("Rs 20000 debited from A/c 1001 to A/c 1002 on 02-10-2026 Ref 11")
        all += TestKit.sms("Rs 20000 credited to A/c 1002 from A/c 1001 on 02-10-2026 Ref 11")
        all.forEach { e.ingest(it) }
        val before = report(e)
        val statuses = all.map { e.ingest(it).status }
        assertTrue(statuses.all { it == IngestStatus.DUPLICATE_MESSAGE })
        assertEquals(before.totals, report(e).totals)
        assertEquals(before.transfersPaise, report(e).transfersPaise)
        assertEquals(3, e.entries.size)
    }

    @Test
    fun `report is rebuilt identically whatever order the messages arrived in`() {
        val a = engine(); feedScenario(a)
        val b = engine(); feedScenario(b, creditsFirst = true)
        assertEquals(report(a).totals, report(b).totals)
        assertEquals(report(a).transfersPaise, report(b).transfersPaise)
        assertEquals(report(a).salaryFlow, report(b).salaryFlow)
    }
}
