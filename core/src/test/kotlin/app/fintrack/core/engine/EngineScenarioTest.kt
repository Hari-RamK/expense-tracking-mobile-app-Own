package app.fintrack.core.engine

import app.fintrack.core.model.Block
import app.fintrack.core.model.Direction
import app.fintrack.core.model.OwnAccount
import app.fintrack.core.model.AccountKind
import app.fintrack.core.model.PersonState
import app.fintrack.core.model.RegularItem
import app.fintrack.core.model.RegularKind
import app.fintrack.core.report.DueStatus
import app.fintrack.core.report.Period
import app.fintrack.core.report.RegularStatus
import app.fintrack.core.report.ReportBuilder
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Scenarios built from real SMS shapes (all numbers and names fake). */
class EngineScenarioTest {
    private val asOf = LocalDate.of(2026, 10, 30)
    private val october = Period.month(asOf)

    private fun LedgerEngine.feed(body: String, sender: String? = null) = ingest(TestKit.sms(body, sender))
    private fun LedgerEngine.report(date: LocalDate = asOf, period: Period = october) =
        ReportBuilder(profile, entries, notices).build(period, date)

    private fun kotakCreditFromOwnName(ref: String, amount: String = "5498.00") =
        "Received Rs.$amount in your Kotak Bank AC 2126 from ARUN KUMAR S on 30-10-26.UPI Ref:$ref"

    private fun tmbDebitToOwnVpa(ref: String) =
        "Your A/c No.XXXX4160 is debited with Rs.5,498.00 on 30-10-2026 10:56 AM and KKBK A/c linked to arun.kumar@example is credited (UPI Ref No.$ref).Current AVBL bal is Rs.0.50 - TMB"

    private fun federalDebit(amount: String, payee: String, ref: String, date: String = "29Oct26") =
        "Debited Rs $amount from a/c X3268 on $date 16:44 via UPI to $payee. Ref $ref.Bal Rs 15720.22. Not you?Call 18000000000 -Federal Bank"

    private fun kotakSent(amount: String, payee: String, ref: String, date: String = "30-10-26") =
        "Sent Rs.$amount from Kotak Bank A/c X2126 to $payee on $date. UPI Ref $ref. Not done by you? Tap https://example.invalid"

    private val iciciStatement =
        "ICICI Bank Credit Card XX9000 Statement is sent to ar*******@example.com. Total of Rs 11,020.40 or minimum of Rs 3,700.00 is due by 31-OCT-26."

    // ---- transfers between your own accounts --------------------------------------------------------

    @Test
    fun `credit from your own name and a debit with the same UPI reference link automatically`() {
        val e = TestKit.engine()
        e.feed(kotakCreditFromOwnName("219279920003"), "JD-KOTAKB-S")
        e.feed(tmbDebitToOwnVpa("219279920003"), "JM-TMBANK-S")
        assertEquals(1, e.groups.size)
        val legs = e.entries
        assertEquals(setOf(Block.TRANSFER), legs.map { it.block }.toSet())
        assertTrue(legs.all { it.transferGroupId == e.groups.single().id && !it.needsReview })
        val r = e.report()
        assertEquals(549_800, r.transfersPaise)
        assertEquals(0, r.totals.incomePaise)
        assertEquals(0, r.totals.totalSpentPaise)
    }

    @Test
    fun `debit to your own account with the credit leg missing is a transfer, disclosed, never an expense`() {
        val e = TestKit.engine()
        e.feed("Your a/c no. XXXXXXXX1756 is debited for Rs.2400.00 on 30-10-2026 and credited to a/c no. XXXXXXXX2126 (UPI Ref no 219279760002)", "AD-CUBLTD-S")
        val entry = e.entries.single()
        assertEquals(Block.TRANSFER, entry.block)
        assertEquals(TestKit.CUB, entry.accountId)
        assertEquals("LEG_NOT_SEEN", entry.hint)
        val r = e.report()
        assertEquals(240_000, r.transfersPaise)
        assertEquals(0, r.totals.totalSpentPaise)
        assertEquals(1, r.unmatchedTransferLegs)
        assertTrue(r.disclosures.any { "no matching other side" in it })
    }

    @Test
    fun `the missing credit leg arriving later links to the earlier debit`() {
        val e = TestKit.engine()
        e.feed("Your a/c no. XXXXXXXX1756 is debited for Rs.2400.00 on 30-10-2026 and credited to a/c no. XXXXXXXX2126 (UPI Ref no 219279760002)", "AD-CUBLTD-S")
        e.feed(kotakCreditFromOwnName("219279760002", "2400.00"), "JD-KOTAKB-S")
        assertEquals(1, e.groups.size)
        assertEquals(240_000, e.report().transfersPaise)
        assertEquals(0, e.report().unmatchedTransferLegs)
    }

    @Test
    fun `a debit to your own name with no reference match stays a lone transfer leg`() {
        val e = TestKit.engine()
        e.feed(federalDebit("1336.00", "ARUN KUMAR S", "219280170007", "30Oct26"), "VM-FEDBNK-T")
        val entry = e.entries.single()
        assertEquals(Block.TRANSFER, entry.block)
        assertEquals(133_600, e.report().transfersPaise)
        assertEquals(0, e.report().totals.totalSpentPaise)
    }

    @Test
    fun `equal amounts alone never link two transfer legs, they are only proposed and counted once`() {
        val e = TestKit.engine()
        e.feed(federalDebit("1000.00", "ARUN KUMAR S", "111", "30Oct26"), "VM-FEDBNK-T")
        e.feed(kotakCreditFromOwnName("222", "1000.00"), "JD-KOTAKB-S")
        assertEquals(0, e.groups.size)
        assertTrue(e.entries.all { it.hint?.startsWith("TRANSFER_CANDIDATE") == true && it.needsReview })
        assertEquals(100_000, e.report().transfersPaise)
    }

    @Test
    fun `owner can confirm a proposed transfer`() {
        val e = TestKit.engine()
        e.feed(federalDebit("1000.00", "ARUN KUMAR S", "111", "30Oct26"), "VM-FEDBNK-T")
        e.feed(kotakCreditFromOwnName("222", "1000.00"), "JD-KOTAKB-S")
        val ids = e.entries.map { it.id }
        e.confirmTransfer(ids[0], ids[1])
        assertEquals(1, e.groups.size)
        assertTrue(e.entries.none { it.needsReview })
        assertEquals(100_000, e.report().transfersPaise)
    }

    @Test
    fun `two unrelated credits of the same amount are never linked to a debit to a shop`() {
        val e = TestKit.engine()
        e.feed(kotakSent("500.00", "SOME SHOP", "9001"), "AX-KOTAKB-S")
        e.feed("Your a/c no. XXXXXXXX1756 is credited for Rs.500.00 on 30-10-2026 and debited from a/c no. XXXXXXXX0421 (UPI Ref no 9002) -CUB", "AX-CUBANK-S")
        assertEquals(0, e.groups.size)
        val credit = e.entries.single { it.direction == Direction.CREDIT }
        assertEquals(Block.UNCLASSIFIED_CREDIT, credit.block)
        assertEquals(0, e.report().totals.incomePaise)
        assertEquals(50_000, e.report().totals.bankPaise)
    }

    // ---- income ------------------------------------------------------------------------------------

    @Test
    fun `salary without a date in the text uses the received date, not a guess`() {
        val e = TestKit.engine()
        e.feed("Salary Credited!\nINR 45,333.00 to HDFC Bank A/c XX7345\nBal: INR 45,483.86\nGet statement on WhatsApp")
        val entry = e.entries.single()
        assertEquals(Block.INCOME, entry.block)
        assertTrue(entry.isSalary)
        assertEquals(TestKit.HDFC, entry.accountId)
        assertEquals(LocalDate.of(2026, 10, 26), entry.date)
    }

    @Test
    fun `an ordinary credit from an unknown account is not income until the owner classifies it`() {
        val e = TestKit.engine()
        e.feed("Your a/c no. XXXXXXXX1756 is credited for Rs.500.00 on 30-10-2026 and debited from a/c no. XXXXXXXX0421 (UPI Ref no 666931720004) -CUB", "AX-CUBANK-S")
        assertEquals(0, e.report().totals.incomePaise)
        assertEquals(50_000, e.report().unclassifiedCreditsPaise)
        assertTrue(e.entries.single().needsReview)
        e.setBlock(e.entries.single().id, Block.INCOME)
        assertEquals(50_000, e.report().totals.incomePaise)
    }

    @Test
    fun `a PF advance NACH credit is other income, and an unknown account suffix is disclosed not dropped`() {
        val e = TestKit.engine()
        e.feed("Savings No XXXXXXXX56 credited with INR 22695.00 towards BY ONL NACH_CR/PAYMENT FOR PF ADVAN/ACH_CR/CMP0000000:: CA 5 on 29-OCT-2026.Avl Bal 22730.79")
        val entry = e.entries.single()
        assertEquals(Block.INCOME, entry.block)
        assertNull(entry.accountId)
        assertEquals(2_269_500, e.report().totals.incomePaise)
        assertEquals(1, e.report().unresolvedAccountCount)
    }

    @Test
    fun `adding the missing account later resolves earlier messages once the sender names the bank`() {
        val e = TestKit.engine()
        e.feed("Savings No XXXXXXXX56 credited with INR 22695.00 towards BY ONL NACH_CR/PAYMENT FOR PF ADVAN/ACH_CR/CMP0000000:: CA 5 on 29-OCT-2026.Avl Bal 22730.79", "AX-AIRBNK-S")
        assertNull(e.entries.single().accountId)
        val withAccount = TestKit.profile.copy(
            accounts = TestKit.profile.accounts + OwnAccount(9, "Airtel ••0056", "AIRTEL_PAYMENTS_BANK", AccountKind.BANK, listOf("0056")),
        )
        e.updateProfile(withAccount)
        assertEquals(9L, e.entries.single().accountId)
        assertEquals(0, e.report().unresolvedAccountCount)
    }

    @Test
    fun `owner can assign an account the app could not resolve`() {
        val e = TestKit.engine()
        e.feed("Savings No XXXXXXXX56 credited with INR 22695.00 towards BY ONL NACH_CR/PAYMENT FOR PF ADVAN/ACH_CR/CMP0000000:: CA 5 on 29-OCT-2026.Avl Bal 22730.79")
        e.assignAccount(e.entries.single().id, TestKit.FEDERAL)
        assertEquals(TestKit.FEDERAL, e.entries.single().accountId)
    }

    // ---- people, lent --------------------------------------------------------------------------------

    @Test
    fun `person payments under the threshold are spend, at or above it wait for the owner`() {
        val e = TestKit.engine()
        e.feed(federalDebit("135.00", "RAVI TEJA M", "630255820006"), "VM-FEDBNK-T")
        e.feed(federalDebit("2500.00", "MEERA K", "630255820007"), "VM-FEDBNK-T")
        val small = e.entries.first { it.counterpartyName == "RAVI TEJA M" }
        val big = e.entries.first { it.counterpartyName == "MEERA K" }
        assertEquals(Block.BANK_SPEND, small.block); assertEquals(PersonState.SPEND, small.personState)
        assertEquals(Block.UNDECIDED, big.block); assertTrue(big.needsReview)
        val r = e.report()
        assertEquals(13_500, r.totals.bankPaise)
        assertEquals(250_000, r.awaitingDecisionPaise)
        assertEquals(0, r.lentOutstandingPaise)
    }

    @Test
    fun `lent is never chosen automatically and shows the sending account and an honest id`() {
        val e = TestKit.engine()
        e.feed(kotakSent("1500.00", "SURESH PRAKASH", "630382500009"), "AX-KOTAKB-S")
        val id = e.entries.single().id
        assertEquals(Block.UNDECIDED, e.entries.single().block)
        e.setPersonState(id, PersonState.LENT)
        val row = e.report().lentRows.single()
        assertEquals("Kotak ••2126", row.fromAccount)
        assertEquals(150_000, row.outstandingPaise)
        assertTrue("UPI ref 630382500009" in row.upiIdOrName, "no VPA in the SMS, so name and reference are shown: ${row.upiIdOrName}")
        assertEquals(0, e.report().totals.bankPaise)
    }

    @Test
    fun `a credit from someone you lent to is suggested as repayment and settles it once confirmed`() {
        val e = TestKit.engine()
        e.feed(federalDebit("2500.00", "MEERA K", "630255820007"), "VM-FEDBNK-T")
        e.setPersonState(e.entries.single().id, PersonState.LENT)
        val lentId = e.entries.single().id
        e.feed("Received Rs.1000.00 in your Kotak Bank AC 2126 from MEERA K on 30-10-26.UPI Ref:777001", "JD-KOTAKB-S")
        val credit = e.entries.single { it.direction == Direction.CREDIT }
        assertEquals("LENT_REPAYMENT:$lentId", credit.hint)
        assertEquals(0, e.report().totals.incomePaise)
        e.markLentRepayment(credit.id, lentId)
        assertEquals(150_000, e.report().lentOutstandingPaise)
        assertEquals(0, e.report().totals.incomePaise)
    }

    @Test
    fun `marking a payee usually spend resolves their later payments to spend`() {
        val e = TestKit.engine()
        e.feed(federalDebit("2500.00", "MEERA K", "630255820007"), "VM-FEDBNK-T")
        e.setPersonState(e.entries.single().id, PersonState.SPEND, usuallySpend = true)
        e.updateProfile(e.profile)
        e.feed(federalDebit("3000.00", "MEERA K", "630255820008"), "VM-FEDBNK-T")
        assertEquals(Block.BANK_SPEND, e.entries.last().block)
    }

    // ---- credit cards and CRED -----------------------------------------------------------------------

    @Test
    fun `card purchase via UPI is a card spend with the merchant as the reason`() {
        val e = TestKit.engine()
        e.feed("ICICI Bank Credit Card XX9000 debited for INR 290.00 on 29-Oct-26 for UPI-223643760005-SWASTIK. To dispute call 18000000000", null)
        val entry = e.entries.single()
        assertEquals(Block.CC_SPEND, entry.block)
        assertEquals(TestKit.ICICI_CARD, entry.accountId)
        assertEquals("SWASTIK", entry.reason)
        val row = e.report().ccRows.single()
        assertEquals("ICICI CC ••9000 · SWASTIK", row.cellText)
    }

    @Test
    fun `CRED payment is a card bill payment, not an expense, until it is assigned to a card`() {
        val e = TestKit.engine()
        e.feed(iciciStatement)
        e.feed(kotakSent("9261.34", "CRED", "630382500001"), "AX-KOTAKB-S")
        val payment = e.entries.single()
        assertEquals(Block.CARD_BILL, payment.block)
        assertTrue(payment.needsReview)
        assertEquals(0, e.report().totals.totalSpentPaise)
        e.assignCard(payment.id, TestKit.ICICI_CARD)
        val due = e.report().cardsDueTomorrow.single()
        assertEquals(926_134, due.paidPaise)
        assertEquals(175_906, due.stillDuePaise)
        assertEquals(DueStatus.DUE_TOMORROW, due.status)
        assertTrue(due.awaitingIssuerConfirmation)
        assertEquals(926_134, e.report().cardBillPaymentsPaise)
        assertEquals(0, e.report().totals.totalSpentPaise)
    }

    @Test
    fun `a CRED payment equal to the minimum due is assigned automatically`() {
        val e = TestKit.engine()
        e.feed(iciciStatement)
        e.feed(kotakSent("3700.00", "CRED", "5550"), "AX-KOTAKB-S")
        val payment = e.entries.single()
        assertEquals(TestKit.ICICI_CARD, payment.settlesAccountId)
        assertFalse(payment.needsReview)
        assertEquals(732_040, e.report().cardDues.single().stillDuePaise)
    }

    @Test
    fun `a statement arriving after the payment still assigns it`() {
        val e = TestKit.engine()
        e.feed(kotakSent("3700.00", "CRED", "5550"), "AX-KOTAKB-S")
        assertNull(e.entries.single().settlesAccountId)
        val result = e.feed(iciciStatement)
        assertEquals(TestKit.ICICI_CARD, result.entries.single().settlesAccountId)
    }

    @Test
    fun `a fully paid card disappears from due tomorrow but its history stays`() {
        val e = TestKit.engine()
        e.feed(iciciStatement)
        e.feed(kotakSent("11020.40", "CRED", "5551"), "AX-KOTAKB-S")
        val id = e.entries.single().id
        e.assignCard(id, TestKit.ICICI_CARD)
        assertTrue(e.report().cardsDueTomorrow.isEmpty())
        assertEquals(DueStatus.PAID, e.report().cardDues.single().status)
    }

    // ---- duplicates ------------------------------------------------------------------------------------

    @Test
    fun `the same SMS twice is ignored`() {
        val e = TestKit.engine()
        val body = federalDebit("135.00", "RAVI TEJA M", "630255820006")
        e.feed(body, "VM-FEDBNK-T")
        assertEquals(IngestStatus.DUPLICATE_MESSAGE, e.feed(body, "VM-FEDBNK-T").status)
        assertEquals(1, e.entries.size)
        assertEquals(13_500, e.report().totals.bankPaise)
    }

    @Test
    fun `the same transaction from a second notification source is merged, not counted twice`() {
        val e = TestKit.engine()
        e.feed(federalDebit("135.00", "RAVI TEJA M", "630255820006"), "VM-FEDBNK-T")
        e.feed(federalDebit("135.00", "RAVI TEJA M", "630255820006").replace("18000000000", "18001111111"), "AD-FEDBNK-S")
        assertEquals(1, e.entries.size)
        assertEquals(2, e.entries.single().sourceMessageIds.size)
        assertEquals(13_500, e.report().totals.bankPaise)
    }

    @Test
    fun `identical legitimate payments with different references are both kept`() {
        val e = TestKit.engine()
        e.feed(federalDebit("120.00", "TEA STALL", "630255820101"), "VM-FEDBNK-T")
        e.feed(federalDebit("120.00", "TEA STALL", "630255820102"), "VM-FEDBNK-T")
        assertEquals(2, e.entries.size)
        assertEquals(24_000, e.report().totals.bankPaise)
    }

    @Test
    fun `a reused reference with a different amount is kept and flagged, not silently merged`() {
        val e = TestKit.engine()
        e.feed(federalDebit("120.00", "TEA STALL", "630255820101"), "VM-FEDBNK-T")
        e.feed(federalDebit("150.00", "TEA STALL", "630255820101"), "VM-FEDBNK-T")
        assertEquals(2, e.entries.size)
        assertTrue(e.entries.last().needsReview)
        assertEquals("REF_CONFLICT", e.entries.last().hint)
    }

    // ---- non-transactions ------------------------------------------------------------------------------

    @Test
    fun `OTP promo muted and unparsed messages create no ledger entries`() {
        val e = TestKit.engine()
        assertEquals(IngestStatus.IGNORED, e.feed("482913 is your OTP for login. Do not share it.").status)
        assertEquals(IngestStatus.IGNORED, e.feed("Pre-approved loan of Rs.5,00,000 for you! Apply now").status)
        assertEquals(IngestStatus.UNPARSED, e.feed("Rs 100.00 spent via NewBank format at SHOP on 29-10-26").status)
        assertTrue(e.entries.isEmpty())
    }

    @Test
    fun `repeated auto-debit reminders collapse into one scheduled item and never a transaction`() {
        val e = TestKit.engine()
        val body = "Rs.5293.0 auto-debited from Airtel Payments Bank a/c 04-Sep-2026 towards Atal Pension Yojana\nPlease maintain sufficient balance in the A/c"
        val day = 86_400_000L
        (0..2).forEach { n -> e.ingest(TestKit.sms(body, "AX-AIRBNK-S", TestKit.CLOCK_MILLIS + n * day)) }
        assertTrue(e.entries.isEmpty())
        assertEquals(1, e.notices.size)
    }

    @Test
    fun `the same message read live and again from the inbox on the same day is one message`() {
        val e = TestKit.engine()
        val body = federalDebit("135.00", "RAVI TEJA M", "630255820006")
        e.ingest(TestKit.sms(body, "VM-FEDBNK-T", TestKit.CLOCK_MILLIS))
        assertEquals(IngestStatus.DUPLICATE_MESSAGE, e.ingest(TestKit.sms(body, "VM-FEDBNK-T", TestKit.CLOCK_MILLIS + 1_500)).status)
    }

    // ---- regular items ----------------------------------------------------------------------------------

    @Test
    fun `a premium notice gives the due date and the real debit later is matched by keyword`() {
        val profile = TestKit.profile.copy(
            regularItems = listOf(
                RegularItem(1, "Term insurance", RegularKind.REGULAR, 199_900, keywords = listOf("K0000001", "ICICI Life")),
                RegularItem(2, "Rent", RegularKind.REGULAR, 1_400_000, dueDay = 3, keywords = listOf("LANDLORD")),
                RegularItem(3, "TV EMI", RegularKind.EMI, 500_000, keywords = listOf("EXAMPLE RETAIL"), emiTotalMonths = 12, emiPaidBefore = 2),
            ),
        )
        val e = TestKit.engine(profile)
        e.feed("ICICI Life policy K0000001 is due. Premium of Rs. 1999 will be deducted on due date 13-Nov-26 as per standing instructions.", "AD-ICICIP-S")
        e.feed(kotakSent("14000.00", "LANDLORD", "8001", "03-10-26"), "AX-KOTAKB-S")
        e.feed("ICICI Bank Credit Card XX9000 debited for INR 5000.00 on 08-Oct-26 for UPI-123456789012-EXAMPLE RETAIL. To dispute call", null)
        val r = e.report()
        val term = r.regularRows.single { it.name == "Term insurance" }
        assertEquals(LocalDate.of(2026, 11, 13), term.dueDate)
        assertEquals(RegularStatus.NEXT_PERIOD, term.status)
        val rent = r.regularRows.single { it.name == "Rent" }
        assertEquals(RegularStatus.SEEN, rent.status)
        assertEquals(1_400_000, rent.seenPaise)
        assertEquals(1_400_000, r.totals.regularPaise)
        assertEquals(500_000, r.totals.emiPaise)
        assertEquals("3/12", r.emiRows.single().progress)
        assertEquals(0, r.totals.ccPaise)
    }

    // ---- period boundaries and invariants -----------------------------------------------------------------

    @Test
    fun `month end and week boundaries put each transaction in exactly one period`() {
        val e = TestKit.engine()
        e.feed(kotakSent("100.00", "SHOP ONE", "1", "31-10-26"), "AX-KOTAKB-S")
        e.feed(kotakSent("200.00", "SHOP TWO", "2", "01-11-26"), "AX-KOTAKB-S")
        val oct = e.report(LocalDate.of(2026, 10, 31), Period.month(LocalDate.of(2026, 10, 15)))
        val nov = e.report(LocalDate.of(2026, 11, 1), Period.month(LocalDate.of(2026, 11, 15)))
        assertEquals(10_000, oct.totals.totalSpentPaise)
        assertEquals(20_000, nov.totals.totalSpentPaise)
        assertEquals(10_000, nov.previousTotals.totalSpentPaise)
        val sat = Period.week(LocalDate.of(2026, 10, 31))
        assertEquals(LocalDate.of(2026, 10, 26), sat.start)
        assertEquals(LocalDate.of(2026, 11, 1), sat.end)
    }

    @Test
    fun `quiet accounts are called out because missing messages are not proof of no activity`() {
        val e = TestKit.engine()
        e.feed(kotakSent("100.00", "SHOP ONE", "1", "01-10-26"), "AX-KOTAKB-S")
        val warnings = e.report().disclosures
        assertTrue(warnings.any { "Kotak ••2126" in it && "since 2026-10-01" in it })
        assertTrue(warnings.any { "No messages seen yet for HDFC ••7345" in it })
    }

    @Test
    fun `owner edits survive profile updates and rebuilds`() {
        val e = TestKit.engine()
        e.feed(kotakSent("250.00", "SOME SHOP", "55"), "AX-KOTAKB-S")
        val id = e.entries.single().id
        e.setReason(id, "Groceries")
        e.setBlock(id, Block.EXCLUDED)
        e.updateProfile(e.profile.copy(personThresholdPaise = 1))
        val entry = assertNotNull(e.entry(id))
        assertEquals("Groceries", entry.reason)
        assertEquals(Block.EXCLUDED, entry.block)
        assertTrue(entry.userEdited)
        assertEquals(0, e.report().totals.totalSpentPaise)
    }

    @Test
    fun `learned reasons apply to the next payment to the same payee`() {
        val e = TestKit.engine()
        e.feed(kotakSent("250.00", "PAYTMQR1234", "55"), "AX-KOTAKB-S")
        e.setReason(e.entries.single().id, "Milk")
        e.feed(kotakSent("260.00", "PAYTMQR1234", "56"), "AX-KOTAKB-S")
        assertEquals("Milk", e.entries.last().reason)
    }

    @Test
    fun `audit records every decision`() {
        val e = TestKit.engine()
        val added = e.feed(kotakSent("250.00", "SOME SHOP", "55"), "AX-KOTAKB-S")
        assertTrue(added.audit.any { it.action == "ADDED" })
        val edited = e.setReason(e.entries.single().id, "Snacks")
        assertTrue(edited.audit.any { it.action == "SET_REASON" && it.detail == "Snacks" })
    }
}
