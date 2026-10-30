package app.fintrack.core.report

import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * A notification ready to show. [key] is unique per period, so asking for the same period twice yields the same key
 * and the app can refuse to send it again. [publicText] is what the lock screen shows: no amounts.
 */
data class NotificationContent(val key: String, val title: String, val text: String, val publicText: String)

/** All figures come from [PeriodReport], the same object the dashboard shows, so the two cannot disagree. */
object Notifications {

    private fun pct(now: Long, before: Long): String? {
        if (before <= 0) return null
        val change = (now - before) * 100 / before
        return when {
            change == 0L -> "the same as"
            change < 0 -> "${abs(change)}% less than"
            else -> "$change% more than"
        }
    }

    private fun blockBreakdown(r: PeriodReport): String = listOf(
        "Credit card" to r.totals.ccPaise,
        "Bank" to r.totals.bankPaise,
        "Regular" to r.totals.regularPaise,
        "EMI" to r.totals.emiPaise,
    ).filter { it.second != 0L }.joinToString(" · ") { "${it.first} ${formatInr(it.second)}" }

    fun daily(r: PeriodReport, coverageNote: String? = null): NotificationContent {
        val t = r.totals
        val parts = mutableListOf<String>()
        if (t.totalSpentPaise == 0L && t.spendCount == 0) {
            parts += "Today: no spending recorded."
        } else {
            parts += "Today: ${formatInr(t.totalSpentPaise)} across ${t.spendCount} transaction${if (t.spendCount == 1) "" else "s"} (${blockBreakdown(r)})."
        }
        val lentToday = r.lentRows.filter { it.date in r.period }.sumOf { it.amountPaise }
        if (lentToday > 0) parts += "Lent ${formatInr(lentToday)}."
        if (r.awaitingDecisionCount > 0) parts += "${r.awaitingDecisionCount} payment(s) to people need your decision."
        if (r.needsReviewCount > 0) parts += "${r.needsReviewCount} item(s) need review."
        if (r.cardsDueTomorrow.isNotEmpty()) {
            parts += "Card due tomorrow: " + r.cardsDueTomorrow.joinToString(", ") { "${it.accountLabel} ${formatInr(it.stillDuePaise)}" } + "."
        }
        parts += "Transfers excluded."
        coverageNote?.let { parts += it }
        return NotificationContent("daily:${r.period.start}", "Today's spending", parts.joinToString(" "), "Daily spending summary is ready")
    }

    private fun topReason(r: PeriodReport): String? = r.topReasons.firstOrNull()?.let { "${it.name} (${formatInr(it.amountPaise)})" }

    fun weekly(r: PeriodReport): NotificationContent {
        val spent = r.totals.totalSpentPaise
        val cmp = pct(spent, r.previousTotals.totalSpentPaise)
        val sentence = buildString {
            append("This week you spent ${formatInr(spent)}")
            if (cmp != null) append(", $cmp last week")
            append(".")
            topReason(r)?.let { append(" Biggest: $it.") }
            append(" Transfers excluded.")
        }
        return NotificationContent("weekly:${r.period.start}", "Your week", sentence, "Weekly spending summary is ready")
    }

    fun monthly(r: PeriodReport): NotificationContent {
        val t = r.totals
        val month = r.period.start.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val net = t.netCashFlowPaise
        val netText = (if (net >= 0) "+" else "") + formatInr(net)
        val cmp = pct(t.totalSpentPaise, r.previousTotals.totalSpentPaise)
        val text = buildString {
            append("$month spending: ${formatInr(t.totalSpentPaise)}. Income: ${formatInr(t.incomePaise)}. Net external cash flow: $netText.")
            if (cmp != null) append(" That is $cmp last month.")
            topReason(r)?.let { append(" Biggest: $it.") }
        }
        return NotificationContent("monthly:${r.period.start}", "$month summary", text, "Monthly summary is ready")
    }

    fun thresholdExceeded(r: PeriodReport, thresholdPaise: Long): NotificationContent? {
        if (thresholdPaise <= 0 || r.totals.totalSpentPaise < thresholdPaise) return null
        return NotificationContent(
            "threshold:${r.period.start}:$thresholdPaise", "Spending limit reached",
            "Today's spending ${formatInr(r.totals.totalSpentPaise)} passed your limit of ${formatInr(thresholdPaise)}.",
            "Spending limit reached",
        )
    }
}
