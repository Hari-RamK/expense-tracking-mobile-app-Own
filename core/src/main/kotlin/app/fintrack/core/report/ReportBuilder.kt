package app.fintrack.core.report

import app.fintrack.core.model.AccountKind
import app.fintrack.core.model.Block
import app.fintrack.core.model.Direction
import app.fintrack.core.model.LedgerEntry
import app.fintrack.core.model.Notice
import app.fintrack.core.model.OwnerProfile
import app.fintrack.core.model.RegularItem
import app.fintrack.core.model.RegularKind
import app.fintrack.core.model.SPEND_BLOCKS
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

data class SpendRow(
    val entryId: Long,
    val date: LocalDate,
    val amountPaise: Long,
    val accountLabel: String,
    val reason: String?,
    /** "HDFC ••7345 · SWIGGY", or "... · needs reason" when the SMS names no merchant. */
    val cellText: String,
    val needsReview: Boolean,
)

data class LentRow(
    val entryId: Long,
    val date: LocalDate,
    val person: String,
    /** The UPI id when the SMS has one, otherwise the payee name and UPI reference. Never invented. */
    val upiIdOrName: String,
    val fromAccount: String,
    val amountPaise: Long,
    val repaidPaise: Long,
    val outstandingPaise: Long,
)

enum class DueStatus { PAID, OVERDUE, DUE_TODAY, DUE_TOMORROW, DUE_SOON, UPCOMING }

data class DueRow(
    val accountId: Long,
    val accountLabel: String,
    val totalDuePaise: Long,
    val minDuePaise: Long?,
    val dueDate: LocalDate,
    val paidPaise: Long,
    val stillDuePaise: Long,
    val daysLeft: Long,
    val status: DueStatus,
    /** Payments were assigned from a bank, but the card issuer has not confirmed them. */
    val awaitingIssuerConfirmation: Boolean,
)

enum class RegularStatus { SEEN, EXPECTED, NO_SMS_YET, NEXT_PERIOD }

data class RegularRow(
    val itemId: Long,
    val name: String,
    val kind: RegularKind,
    val expectedPaise: Long,
    val seenPaise: Long,
    val dueDate: LocalDate?,
    val status: RegularStatus,
    /** "3/12" for EMIs. */
    val progress: String?,
)

data class AccountSummary(
    val accountId: Long,
    val label: String,
    val incomePaise: Long,
    val transfersInPaise: Long,
    val transfersOutPaise: Long,
    val spentPaise: Long,
    val lentAndUndecidedPaise: Long,
    val cardBillsPaise: Long,
    val netChangePaise: Long,
)

data class SalaryFlow(
    val salaryInPaise: Long,
    val movedToOwnAccountsPaise: Long,
    val spentPaise: Long,
    val lentAndUndecidedPaise: Long,
    val cardBillsPaise: Long,
    val leftPaise: Long,
)

data class DayTotal(val date: LocalDate, val spentPaise: Long, val count: Int)

data class NamedTotal(val name: String, val amountPaise: Long, val count: Int)

data class Totals(
    val ccPaise: Long,
    val bankPaise: Long,
    val regularPaise: Long,
    val emiPaise: Long,
    val incomePaise: Long,
    val spendCount: Int,
) {
    val totalSpentPaise: Long get() = ccPaise + bankPaise + regularPaise + emiPaise
    val netCashFlowPaise: Long get() = incomePaise - totalSpentPaise
}

data class PeriodReport(
    val period: Period,
    val asOf: LocalDate,
    val totals: Totals,
    val previousTotals: Totals,
    val ccRows: List<SpendRow>,
    val bankRows: List<SpendRow>,
    val lentRows: List<LentRow>,
    val regularRows: List<RegularRow>,
    val emiRows: List<RegularRow>,
    val cardDues: List<DueRow>,
    val cardsDueTomorrow: List<DueRow>,
    val transfersPaise: Long,
    val cardBillPaymentsPaise: Long,
    val lentOutstandingPaise: Long,
    val awaitingDecisionPaise: Long,
    val awaitingDecisionCount: Int,
    val unclassifiedCreditsPaise: Long,
    val needsReviewCount: Int,
    val unmatchedTransferLegs: Int,
    val unresolvedAccountCount: Int,
    val salaryFlow: SalaryFlow,
    val accountSummaries: List<AccountSummary>,
    val daily: List<DayTotal>,
    val topReasons: List<NamedTotal>,
    val largest: List<SpendRow>,
    val disclosures: List<String>,
)

/**
 * Builds every report from the canonical ledger and nothing else (never from stored summaries), so the same
 * inputs always give the same numbers and the dashboard, reports and notifications cannot disagree.
 *
 * Rules: only CC_SPEND, BANK_SPEND, REGULAR and EMI count as spending. Transfers are counted once (the debit
 * leg, or the single seen leg). Card-bill payments, lent and undecided money, and unclassified credits never
 * count as spending or income.
 */
class ReportBuilder(
    private val profile: OwnerProfile,
    private val entries: List<LedgerEntry>,
    private val notices: List<Notice>,
) {
    private fun sign(e: LedgerEntry) = if (e.direction == Direction.DEBIT) e.amountPaise else -e.amountPaise

    private fun accountLabel(e: LedgerEntry): String =
        profile.accounts.firstOrNull { it.id == e.accountId }?.label ?: e.accountHint ?: "Unknown account"

    private fun row(e: LedgerEntry): SpendRow {
        val label = accountLabel(e)
        return SpendRow(e.id, e.date, sign(e), label, e.reason, "$label · ${e.reason ?: "needs reason"}", e.needsReview)
    }

    fun totals(period: Period): Totals {
        val inPeriod = entries.filter { it.date in period }
        fun spend(block: Block) = inPeriod.filter { it.block == block }.sumOf { sign(it) }
        return Totals(
            ccPaise = spend(Block.CC_SPEND),
            bankPaise = spend(Block.BANK_SPEND),
            regularPaise = spend(Block.REGULAR),
            emiPaise = spend(Block.EMI),
            incomePaise = inPeriod.filter { it.block == Block.INCOME }.sumOf { if (it.direction == Direction.CREDIT) it.amountPaise else -it.amountPaise },
            spendCount = inPeriod.count { it.block in SPEND_BLOCKS },
        )
    }

    /** Each transfer counted once: the debit leg of a linked pair, or a lone leg whose other side was not seen. */
    private fun transfersTotal(inPeriod: List<LedgerEntry>): Long =
        inPeriod.filter { e ->
            e.block == Block.TRANSFER && when {
                e.transferGroupId != null -> e.direction == Direction.DEBIT
                // A credit that is only a proposed match for a debit is that same movement: count it once, via the debit.
                e.direction == Direction.CREDIT && e.hint?.startsWith("TRANSFER_CANDIDATE") == true -> false
                else -> true
            }
        }.sumOf { it.amountPaise }

    fun build(period: Period, asOf: LocalDate): PeriodReport {
        val inPeriod = entries.filter { it.date in period }
        val totals = totals(period)
        val dues = cardDues(asOf)
        val lent = lentRows()
        val undecided = entries.filter { it.block == Block.UNDECIDED }
        val periodUndecided = undecided.filter { it.date in period }
        val unmatched = inPeriod.count { it.block == Block.TRANSFER && it.transferGroupId == null }
        val unresolved = inPeriod.count { it.accountId == null && it.block != Block.TRANSFER }
        val spendEntries = inPeriod.filter { it.block in SPEND_BLOCKS }
        val regular = regularRows(period, asOf)

        val disclosures = buildList {
            if (undecided.isNotEmpty()) add("${undecided.sumOf { it.amountPaise }.let(::formatInr)} in ${undecided.size} payment(s) to people awaits your decision (not in spending or lent).")
            if (unmatched > 0) add("$unmatched transfer leg(s) have no matching other side yet; shown separately, never as income or spending.")
            val unclassified = inPeriod.filter { it.block == Block.UNCLASSIFIED_CREDIT }
            if (unclassified.isNotEmpty()) add("${formatInr(unclassified.sumOf { it.amountPaise })} in ${unclassified.size} credit(s) is unclassified and not counted as income.")
            if (unresolved > 0) add("$unresolved transaction(s) are not assigned to one of your accounts.")
            dues.filter { it.awaitingIssuerConfirmation && it.stillDuePaise > 0 }.forEach {
                add("${it.accountLabel}: payment assigned, awaiting the card issuer's confirmation.")
            }
            addAll(coverageWarnings(asOf))
        }

        return PeriodReport(
            period = period,
            asOf = asOf,
            totals = totals,
            previousTotals = totals(period.previous()),
            ccRows = inPeriod.filter { it.block == Block.CC_SPEND }.sortedByDescending { it.date }.map(::row),
            bankRows = inPeriod.filter { it.block == Block.BANK_SPEND }.sortedByDescending { it.date }.map(::row),
            lentRows = lent.filter { it.date in period || it.outstandingPaise > 0 },
            regularRows = regular.filter { it.kind == RegularKind.REGULAR },
            emiRows = regular.filter { it.kind == RegularKind.EMI },
            cardDues = dues,
            cardsDueTomorrow = dues.filter { it.dueDate == asOf.plusDays(1) && it.stillDuePaise > 0 },
            transfersPaise = transfersTotal(inPeriod),
            cardBillPaymentsPaise = inPeriod.filter { it.block == Block.CARD_BILL && it.direction == Direction.DEBIT }.sumOf { it.amountPaise },
            lentOutstandingPaise = lent.sumOf { it.outstandingPaise },
            awaitingDecisionPaise = periodUndecided.sumOf { it.amountPaise },
            awaitingDecisionCount = undecided.size,
            unclassifiedCreditsPaise = inPeriod.filter { it.block == Block.UNCLASSIFIED_CREDIT }.sumOf { it.amountPaise },
            needsReviewCount = entries.count { it.needsReview },
            unmatchedTransferLegs = unmatched,
            unresolvedAccountCount = unresolved,
            salaryFlow = salaryFlow(inPeriod),
            accountSummaries = accountSummaries(inPeriod),
            daily = spendEntries.groupBy { it.date }.map { (d, list) -> DayTotal(d, list.sumOf(::sign), list.size) }.sortedBy { it.date },
            topReasons = spendEntries.groupBy { it.reason ?: "(no reason)" }
                .map { (r, list) -> NamedTotal(r, list.sumOf(::sign), list.size) }.sortedByDescending { it.amountPaise }.take(5),
            largest = spendEntries.sortedByDescending { it.amountPaise }.take(5).map(::row),
            disclosures = disclosures,
        )
    }

    // ---- lent ------------------------------------------------------------------------------------

    fun lentRows(): List<LentRow> = entries.filter { it.block == Block.LENT && it.direction == Direction.DEBIT }
        .sortedByDescending { it.date }
        .map { e ->
            val repaid = entries.filter { it.block == Block.LENT_REPAYMENT && it.settlesEntryId == e.id }.sumOf { it.amountPaise }
            val person = e.counterpartyName ?: e.reason ?: "Unknown"
            LentRow(
                entryId = e.id, date = e.date, person = person,
                upiIdOrName = e.counterpartyVpa ?: listOfNotNull(e.counterpartyName, e.ref?.let { "UPI ref $it" }).joinToString(" · ").ifBlank { "—" },
                fromAccount = accountLabel(e), amountPaise = e.amountPaise, repaidPaise = repaid,
                outstandingPaise = (e.amountPaise - repaid).coerceAtLeast(0),
            )
        }

    // ---- card dues -------------------------------------------------------------------------------

    /**
     * Statement dues per card. Payments assigned to a card are allocated to the earliest statement that still
     * owes money and is within reach (45 days before the due date to 3 days after). Nothing is hidden: a bill
     * stays until the paid amount covers it, and assigned payments are labelled unconfirmed.
     */
    fun cardDues(asOf: LocalDate): List<DueRow> {
        val statements = notices.filter { it.eventKind == "STATEMENT_DUE" && it.accountId != null && it.dueDate != null }
        return statements.groupBy { it.accountId!! }.flatMap { (cardId, list) ->
            val sorted = list.sortedBy { it.dueDate }
            val remaining = sorted.associateWith { it.amountPaise }.toMutableMap()
            val paid = sorted.associateWith { 0L }.toMutableMap()
            val payments = entries.filter { it.block == Block.CARD_BILL && it.settlesAccountId == cardId && it.direction == Direction.DEBIT }
                .sortedBy { it.date }
            for (p in payments) {
                var left = p.amountPaise
                for (s in sorted) {
                    if (left <= 0) break
                    val due = s.dueDate!!
                    if (p.date.isBefore(due.minusDays(45)) || p.date.isAfter(due.plusDays(3))) continue
                    val take = minOf(left, remaining.getValue(s))
                    if (take <= 0) continue
                    remaining[s] = remaining.getValue(s) - take
                    paid[s] = paid.getValue(s) + take
                    left -= take
                }
            }
            val label = profile.accounts.firstOrNull { it.id == cardId }?.label ?: sorted.first().accountHint ?: "Card"
            sorted.map { s ->
                val due = s.dueDate!!
                val daysLeft = ChronoUnit.DAYS.between(asOf, due)
                val still = remaining.getValue(s)
                DueRow(
                    accountId = cardId, accountLabel = label, totalDuePaise = s.amountPaise, minDuePaise = s.minDuePaise,
                    dueDate = due, paidPaise = paid.getValue(s), stillDuePaise = still, daysLeft = daysLeft,
                    status = when {
                        still <= 0 -> DueStatus.PAID
                        daysLeft < 0 -> DueStatus.OVERDUE
                        daysLeft == 0L -> DueStatus.DUE_TODAY
                        daysLeft == 1L -> DueStatus.DUE_TOMORROW
                        daysLeft <= 7 -> DueStatus.DUE_SOON
                        else -> DueStatus.UPCOMING
                    },
                    awaitingIssuerConfirmation = paid.getValue(s) > 0,
                )
            }
        }.filter { it.stillDuePaise > 0 || it.dueDate >= asOf.minusDays(30) }.sortedBy { it.dueDate }
    }

    // ---- regular & EMI ---------------------------------------------------------------------------

    private fun noticeDue(item: RegularItem, period: Period): LocalDate? {
        val keys = item.keywords.map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        if (keys.isEmpty()) return null
        return notices.filter { it.eventKind != "STATEMENT_DUE" && it.dueDate != null }
            .filter { n -> keys.any { k -> listOfNotNull(n.payee, n.policyRef).joinToString(" ").uppercase().contains(k) } }
            .map { it.dueDate!! }
            .filter { !it.isBefore(period.start) }
            .minOrNull()
    }

    fun regularRows(period: Period, asOf: LocalDate): List<RegularRow> = profile.regularItems.mapNotNull { item ->
        val matched = entries.filter { it.regularItemId == item.id }
        val seen = matched.filter { it.date in period }.sumOf { sign(it) }
        val due = noticeDue(item, period) ?: item.dueDay?.let { d ->
            val ym = YearMonth.from(if (period.kind == PeriodKind.MONTH) period.start else asOf)
            ym.atDay(d.coerceIn(1, ym.lengthOfMonth()))
        }
        val status = when {
            seen > 0 -> RegularStatus.SEEN
            due == null -> RegularStatus.EXPECTED
            due.isAfter(period.end) -> RegularStatus.NEXT_PERIOD
            due.isBefore(asOf) -> RegularStatus.NO_SMS_YET
            else -> RegularStatus.EXPECTED
        }
        if (period.kind != PeriodKind.MONTH && seen == 0L && (due == null || due !in period)) return@mapNotNull null
        val progress = if (item.kind == RegularKind.EMI && item.emiTotalMonths != null) {
            "${(item.emiPaidBefore + matched.size).coerceAtMost(item.emiTotalMonths)}/${item.emiTotalMonths}"
        } else null
        RegularRow(item.id, item.name, item.kind, item.expectedPaise, seen, due, status, progress)
    }

    // ---- salary flow and per-account ---------------------------------------------------------------

    private fun salaryFlow(inPeriod: List<LedgerEntry>): SalaryFlow {
        val salary = inPeriod.filter { it.block == Block.INCOME && it.isSalary }
        val accountIds = salary.mapNotNull { it.accountId }.toSet()
        val own = inPeriod.filter { it.accountId in accountIds }
        val moved = own.filter { it.block == Block.TRANSFER && it.direction == Direction.DEBIT }.sumOf { it.amountPaise }
        val spent = own.filter { it.block in SPEND_BLOCKS }.sumOf(::sign)
        val lent = own.filter { it.block == Block.LENT || it.block == Block.UNDECIDED }.sumOf { it.amountPaise }
        val bills = own.filter { it.block == Block.CARD_BILL }.sumOf { it.amountPaise }
        val salaryIn = salary.sumOf { it.amountPaise }
        return SalaryFlow(salaryIn, moved, spent, lent, bills, salaryIn - moved - spent - lent - bills)
    }

    private fun accountSummaries(inPeriod: List<LedgerEntry>): List<AccountSummary> =
        profile.accounts.filter { it.active && it.kind == AccountKind.BANK }.map { a ->
            val own = inPeriod.filter { it.accountId == a.id }
            val income = own.filter { it.block == Block.INCOME }.sumOf { it.amountPaise }
            val tIn = own.filter { it.block == Block.TRANSFER && it.direction == Direction.CREDIT }.sumOf { it.amountPaise }
            val tOut = own.filter { it.block == Block.TRANSFER && it.direction == Direction.DEBIT }.sumOf { it.amountPaise }
            val spent = own.filter { it.block in SPEND_BLOCKS }.sumOf(::sign)
            val repaid = own.filter { it.block == Block.LENT_REPAYMENT }.sumOf { it.amountPaise }
            val lent = own.filter { it.block == Block.LENT || it.block == Block.UNDECIDED }.sumOf { it.amountPaise } - repaid
            val bills = own.filter { it.block == Block.CARD_BILL }.sumOf { it.amountPaise }
            AccountSummary(a.id, a.label, income, tIn, tOut, spent, lent, bills, income + tIn - tOut - spent - lent - bills)
        }

    // ---- coverage ----------------------------------------------------------------------------------

    /** Missing messages are not proof that nothing happened, so quiet accounts are called out. */
    fun coverageWarnings(asOf: LocalDate, quietDays: Long = 10): List<String> =
        profile.accounts.filter { it.active }.mapNotNull { a ->
            val last = entries.filter { it.accountId == a.id }.maxOfOrNull { it.date }
            when {
                last == null -> "No messages seen yet for ${a.label}."
                ChronoUnit.DAYS.between(last, asOf) > quietDays -> "No messages from ${a.label} since $last; its totals may be incomplete."
                else -> null
            }
        }
}
