package app.fintrack.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/** Messages with no transaction reference: the fallback identity rules. */
class DedupWithoutReferenceTest {
    private fun engine() = LedgerEngine(TestKit.syntheticParser(), TestKit.syntheticProfile(), clock = { TestKit.CLOCK_MILLIS })
    private val body = "Rs 120 spent from A/c 1001 at TEA STALL on 05-10-2026"

    @Test
    fun `two identical purchases from the same sender are both kept`() {
        val e = engine()
        e.ingest(TestKit.sms(body, "AX-BANKAA-S", 1_000))
        e.ingest(TestKit.sms(body, "AX-BANKAA-S", 90_000_000))
        assertEquals(2, e.entries.size)
    }

    @Test
    fun `the same event reported by two different senders is one transaction`() {
        val e = engine()
        e.ingest(TestKit.sms(body, "AX-BANKAA-S"))
        e.ingest(TestKit.sms("$body ", "VM-PAYAPP-S").copy(body = "Rs 120 spent from A/c 1001 at TEA STALL on 05-10-2026"))
        assertEquals(1, e.entries.size)
        assertEquals(2, e.entries.single().sourceMessageIds.size)
    }

    @Test
    fun `a different amount is a different transaction even from two senders`() {
        val e = engine()
        e.ingest(TestKit.sms(body, "AX-BANKAA-S"))
        e.ingest(TestKit.sms("Rs 130 spent from A/c 1001 at TEA STALL on 05-10-2026", "VM-PAYAPP-S"))
        assertEquals(2, e.entries.size)
    }

    @Test
    fun `a different day is a different transaction`() {
        val e = engine()
        e.ingest(TestKit.sms(body, "AX-BANKAA-S"))
        e.ingest(TestKit.sms("Rs 120 spent from A/c 1001 at TEA STALL on 06-10-2026", "VM-PAYAPP-S"))
        assertEquals(2, e.entries.size)
    }
}
